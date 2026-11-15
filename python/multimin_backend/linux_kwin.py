"""Narrow, one-shot KWin scripting bridge for native Wayland windows.

Only window metadata requested by PID or exact internal UUID is returned. No
KWin journal output is read, and every temporary script is removed after use.
"""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
import threading
import uuid


KWINDOW_SERVICE = "org.kde.KWin"
SCRIPTING_PATH = "/Scripting"
SCRIPTING_INTERFACE = "org.kde.kwin.Scripting"
REPLY_PATH = "/io/github/MaDaAi/MultiMini/KWin"
REPLY_INTERFACE = "io.github.MaDaAi.MultiMini.KWin"
REPLY_XML = f"""<node>
  <interface name="{REPLY_INTERFACE}">
    <method name="Reply">
      <arg name="token" type="s" direction="in"/>
      <arg name="payload" type="s" direction="in"/>
    </method>
  </interface>
</node>"""


class KWinError(RuntimeError):
    pass


class KWin:
    """Run bounded scripts against the current KWin session.

    ``portal`` is the existing Linux Portal instance; its Gio bus and GLib
    event loop dispatch the private Reply method while callers wait.
    """

    def __init__(self, portal):
        self.portal = portal
        self._bus = portal.bus
        self._gio = portal.Gio
        self._glib = portal.GLib
        self._timeout = 3.0
        self._call_lock = threading.Lock()
        self._reply_lock = threading.Lock()
        self._waiting = {}
        self._closed = False
        self._reply_info = self._gio.DBusNodeInfo.new_for_xml(REPLY_XML).interfaces[0]
        self._registration = self._bus.register_object(
            REPLY_PATH, self._reply_info, self._on_reply, None, None)
        if not self._registration:
            raise KWinError("Could not register private KWin reply endpoint")
        data_home = os.environ.get("XDG_DATA_HOME") or os.path.join(Path.home(), ".local", "share")
        self._script_dir = Path(data_home) / "MultiMin" / "kwin"
        self._script_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            os.chmod(self._script_dir, 0o700)
        except OSError:
            pass

    def _on_reply(self, _connection, _sender, _path, _interface, method, parameters, invocation):
        if method == "Reply":
            token, payload = parameters.unpack()
            with self._reply_lock:
                waiting = self._waiting.get(token)
            if waiting is not None and _sender == waiting["expected_sender"]:
                try:
                    waiting["value"] = json.loads(payload)
                except (TypeError, ValueError):
                    waiting["value"] = {"ok": False, "error": "KWin returned invalid JSON"}
                waiting["event"].set()
        invocation.return_value(self._glib.Variant("()", ()))

    def available(self):
        """Return whether org.kde.KWin currently owns its session-bus name."""
        try:
            result = self._bus.call_sync(
                "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                "NameHasOwner", self._glib.Variant("(s)", (KWINDOW_SERVICE,)), None,
                self._gio.DBusCallFlags.NONE, 1000, None)
            return bool(result.unpack()[0])
        except Exception:
            return False

    def _kwin_owner(self):
        result = self._bus.call_sync(
            "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
            "GetNameOwner", self._glib.Variant("(s)", (KWINDOW_SERVICE,)), None,
            self._gio.DBusCallFlags.NONE, 1000, None)
        return result.unpack()[0]

    def _script(self, token, body):
        config = json.dumps({
            "token": token,
            "destination": self._bus.get_unique_name(),
            "path": REPLY_PATH,
            "interface": REPLY_INTERFACE,
        }, ensure_ascii=True)
        return f'''(() => {{
  const cfg = {config};
  const reply = value => callDBus(cfg.destination, cfg.path, cfg.interface,
      "Reply", cfg.token, JSON.stringify(value));
  try {{
    const value = (() => {{ {body} }})();
    reply({{ok: true, value: value}});
  }} catch (error) {{
    reply({{ok: false, error: String(error)}});
  }}
}})();
'''

    def _execute(self, body):
        if self._closed:
            raise KWinError("KWin adapter is closed")
        with self._call_lock:
            if not self.available():
                raise KWinError("KWin scripting service is unavailable")
            expected_sender = self._kwin_owner()
            token = uuid.uuid4().hex
            plugin = "multimini_" + token
            path = self._script_dir / (plugin + ".js")
            event = threading.Event()
            waiting = {"event": event, "value": None, "expected_sender": expected_sender}
            with self._reply_lock:
                self._waiting[token] = waiting
            script_id = None
            try:
                path.write_text(self._script(token, body), encoding="utf-8")
                os.chmod(path, 0o600)
                loaded = self._bus.call_sync(
                    KWINDOW_SERVICE, SCRIPTING_PATH, SCRIPTING_INTERFACE, "loadScript",
                    self._glib.Variant("(ss)", (str(path), plugin)), None,
                    self._gio.DBusCallFlags.NONE, 1000, None)
                script_id = int(loaded.unpack()[0])
                if script_id < 0:
                    raise KWinError("KWin rejected the temporary script")
                self._bus.call_sync(
                    KWINDOW_SERVICE, f"{SCRIPTING_PATH}/Script{script_id}",
                    "org.kde.kwin.Script", "run", self._glib.Variant("()", ()), None,
                    self._gio.DBusCallFlags.NONE, 1000, None)
                if not event.wait(self._timeout):
                    raise KWinError("KWin script response timed out")
                result = waiting["value"]
                if not isinstance(result, dict) or not result.get("ok"):
                    message = result.get("error", "KWin script failed") if isinstance(result, dict) else "KWin returned no result"
                    raise KWinError(str(message))
                return result.get("value")
            finally:
                if script_id is not None and script_id >= 0:
                    try:
                        self._bus.call_sync(
                            KWINDOW_SERVICE, SCRIPTING_PATH, SCRIPTING_INTERFACE, "unloadScript",
                            self._glib.Variant("(s)", (plugin,)), None,
                            self._gio.DBusCallFlags.NONE, 1000, None)
                    except Exception:
                        pass
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass
                with self._reply_lock:
                    self._waiting.pop(token, None)

    @staticmethod
    def _rect_value(rect):
        if not isinstance(rect, dict):
            raise ValueError("Rectangle must be an object")
        values = {}
        for key in ("x", "y", "width", "height"):
            value = rect.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ValueError("Rectangle contains an invalid coordinate")
            values[key] = value
        if values["width"] <= 0 or values["height"] <= 0:
            raise ValueError("Rectangle dimensions must be positive")
        return values

    @staticmethod
    def _find_window_js(uuid_name):
        wanted = json.dumps(str(uuid_name), ensure_ascii=True)
        return f'''const wanted = {wanted};
    const windows = workspace.stackingOrder;
    let window = null;
    for (const candidate of windows) {{
      if (candidate && String(candidate.internalId) === wanted) {{ window = candidate; break; }}
    }}
    if (!window || window.deleted) return null;
'''

    def list_windows(self, pid=None):
        """List managed windows, reading captions only after optional PID filtering."""
        if pid is not None:
            pid = int(pid)
        encoded_pid = "null" if pid is None else str(pid)
        body = f'''const wantedPid = {encoded_pid};
    const result = [];
    for (const window of workspace.stackingOrder) {{
      if (!window || window.deleted || !window.managed) continue;
      const processId = Number(window.pid);
      if (wantedPid !== null && processId !== wantedPid) continue;
      const geometry = window.frameGeometry;
      result.push({{
        uuid: String(window.internalId),
        title: String(window.caption || ""),
        process: String(window.desktopFileName || window.resourceClass || window.resourceName || ""),
        pid: processId,
        rect: {{x: geometry.x, y: geometry.y, width: geometry.width, height: geometry.height}},
        nativeWayland: typeof window.waylandClient === "boolean" ? window.waylandClient : null,
        x11WindowId: (window.waylandClient === false && Number(window.windowId) > 0) ? Number(window.windowId) : 0
      }});
    }}
    return result;
'''
        return self._execute(body)

    def get(self, window_uuid):
        """Read geometry and state for exactly the requested KWin UUID."""
        if not window_uuid:
            raise ValueError("A window UUID is required")
        body = self._find_window_js(window_uuid) + '''const geometry = window.frameGeometry;
    const active = workspace.activeWindow;
    return {
      uuid: String(window.internalId),
      rect: {x: geometry.x, y: geometry.y, width: geometry.width, height: geometry.height},
      focused: !!active && String(active.internalId) === String(window.internalId),
      activeUuid: active ? String(active.internalId) : "",
      fullscreen: !!window.fullScreen,
      keepAbove: !!window.keepAbove,
      noBorder: !!window.noBorder
    };
'''
        result = self._execute(body)
        if result is None:
            return None
        if not isinstance(result, dict) or result.get("uuid") != str(window_uuid):
            return None
        return result

    def place_focus(self, window_uuid, rect, keep_above=None, borderless=False):
        """Place/focus only the exact UUID; leave fullscreen geometry untouched."""
        if not window_uuid:
            raise ValueError("A window UUID is required")
        rect = self._rect_value(rect)
        keep_above_json = "null" if keep_above is None else ("true" if keep_above else "false")
        wanted_rect = json.dumps(rect, ensure_ascii=True, allow_nan=False)
        borderless_json = "true" if borderless else "false"
        body = self._find_window_js(window_uuid) + f'''const requested = {wanted_rect};
    const before = window.frameGeometry;
    const current = {{x: before.x, y: before.y, width: before.width, height: before.height}};
    const activeBefore = workspace.activeWindow;
    const previousActiveUuid = activeBefore ? String(activeBefore.internalId) : "";
    const sameRect = Math.abs(current.x-requested.x) < 0.5 && Math.abs(current.y-requested.y) < 0.5 &&
      Math.abs(current.width-requested.width) < 0.5 && Math.abs(current.height-requested.height) < 0.5;
    if (window.fullScreen && !sameRect) return {{ok: false, reason: "fullscreen_geometry"}};
    if ({borderless_json} && !window.fullScreen) window.noBorder = true;
    if (!window.fullScreen) window.frameGeometry = requested;
    const requestedKeepAbove = {keep_above_json};
    if (requestedKeepAbove !== null) window.keepAbove = requestedKeepAbove;
    workspace.activeWindow = window;
    workspace.raiseWindow(window);
    return {{ok: true, uuid: String(window.internalId), previousActiveUuid: previousActiveUuid, rect: requested,
      fullscreen: !!window.fullScreen, keepAbove: !!window.keepAbove}};
'''
        result = self._execute(body)
        if result is None:
            return {"ok": False, "reason": "missing_window"}
        if not result.get("ok"):
            return result
        return result

    def restore(self, window_uuid, baseline):
        """Restore one window's geometry and supported state if it still exists."""
        if not window_uuid:
            raise ValueError("A window UUID is required")
        if not isinstance(baseline, dict):
            raise ValueError("Baseline state must be an object")
        base_rect = baseline.get("rect")
        if base_rect is not None:
            base_rect = self._rect_value(base_rect)
        baseline_json = json.dumps({
            "rect": base_rect,
            "fullscreen": baseline.get("fullscreen"),
            "keepAbove": baseline.get("keepAbove"),
            "noBorder": baseline.get("noBorder"),
            "activeUuid": baseline.get("activeUuid", ""),
            "focused": bool(baseline.get("focused")),
        }, ensure_ascii=True, allow_nan=False)
        body = self._find_window_js(window_uuid) + f'''const baseline = {baseline_json};
    if (baseline.fullscreen !== null && baseline.fullscreen !== undefined &&
        !!window.fullScreen !== !!baseline.fullscreen) window.fullScreen = !!baseline.fullscreen;
    if (baseline.noBorder !== null && baseline.noBorder !== undefined) window.noBorder = !!baseline.noBorder;
    if (!baseline.fullscreen && baseline.rect) window.frameGeometry = baseline.rect;
    if (baseline.keepAbove !== null && baseline.keepAbove !== undefined)
      window.keepAbove = !!baseline.keepAbove;
    if (baseline.focused) workspace.activeWindow = window;
    else if (baseline.activeUuid) {{
      for (const candidate of workspace.stackingOrder) {{
        if (candidate && !candidate.deleted && String(candidate.internalId) === baseline.activeUuid) {{
          workspace.activeWindow = candidate; break;
        }}
      }}
    }}
    return {{ok: true, uuid: String(window.internalId)}};
'''
        result = self._execute(body)
        return result if result is not None else {"ok": False, "reason": "missing_window"}

    def close(self):
        """Unregister the private callback endpoint."""
        if self._closed:
            return
        self._closed = True
        try:
            self._bus.unregister_object(self._registration)
        except Exception:
            pass

"""Linux implementation of the JVM command protocol, sharing model/rendering.

Wayland capture is portal-only. Directed input is deliberately X11-only: a
RemoteDesktop grant cannot address an arbitrary background Wayland window.
"""
from __future__ import annotations
import json
import os
import sys
import threading
import time
from .frame_stream import FrameStream
from .linux_portal import Portal
from .snapshots import SnapshotStore


KWIN_HANDLE_BASE=1 << 48


class LinuxService:
    def __init__(self, emit, portal=None):
        self.emit = emit
        self.portal = portal or Portal()
        self.frame_stream = FrameStream(lambda: emit({"event": "frame_ready"}), coalesce_notifications=True)
        self.snapshots = SnapshotStore()
        self.closed = threading.Event()
        self.lock = threading.RLock()
        self.captures = {}
        self.generations = {}
        self.pending = set()
        self.active = None
        self.enabled = False
        self.target = 0
        self.rect = (0, 0, 0, 0)
        self.opacity = 100
        self.fps = 60
        self.source_paced = False
        self.last_frame = {}
        self.frame_deadlines = {}
        # Per-source caps applied while the real window is presented natively.
        # Capture keeps running (screenshots, loss detection, fallback) but at
        # a reduced rate so it competes less with the game for CPU/GPU time.
        self.background_limits = {}
        self.wayland = os.environ.get("XDG_SESSION_TYPE") == "wayland" or bool(os.environ.get("WAYLAND_DISPLAY"))
        self.x11 = None
        self.startup_errors = []
        if os.environ.get("DISPLAY"):
            try:
                from .linux_x11 import X11
                self.x11 = X11()
            except Exception as error:
                self.startup_errors.append(f"X11 input unavailable: {error}")
        self.kwin = None
        self.kwin_checked = False
        self.kwin_handles = {}
        self.kwin_uuids = {}
        # KWin handle -> X11 window id for XWayland clients. These are captured
        # straight from X11, so no desktop sharing picker is needed.
        self.kwin_xids = {}
        self.kwin_baseline = None
        self.kwin_active = None
        self.coordinate_scale = (1.0,1.0)
        self.native_input = None
        self.hotkeys = None
        self.app_pid = 0
        self.overlay_xid = 0
        self.control_xid = 0
        self.desktop_input = False
        self.input_path = ""
        self.selecting = False
        self.input_mode = "unavailable"
        self.permissions_pending = False
        self.overlay_options = {}
        self.last_actions = {}
        self.shortcut_session = None
        self.shortcut_sub = None
        self.shortcut_generation = 0
        self.shortcut_keys = None

    def _register(self, path, source_type, xid=0, cursor=False, restore_token=""):
        if not path or len(path.encode()) > 4096:
            raise ValueError("Invalid capture identifier")
        self._remove(path)
        with self.lock:
            generation = self.generations.get(path, 0) + 1
            self.generations[path] = generation
            self.pending.add(path)
        def publish(w, h, pixels):
            with self.lock:
                if self.closed.is_set() or self.generations.get(path) != generation:
                    return
                if self.active is not None and path not in self.active:
                    return
                now = time.monotonic()
                limit = self.background_limits.get(path, 0)
                if not self.source_paced or limit:
                    period = 1 / self._effective_fps(path)
                    deadline = self.frame_deadlines.get(path, now)
                    # Keep the intended cadence across small producer jitter.
                    # Reset after a stall rather than emitting a catch-up burst.
                    if now + period * .02 < deadline:
                        return
                    self.frame_deadlines[path] = max(deadline + period, now + period * .98)
                self.last_frame[path] = now
            self.frame_stream.publish(path, w, h, pixels)
        def error(message):
            with self.lock:
                if self.generations.get(path) != generation:
                    return
            self.frame_stream.remove(path)
            self.emit({"event": "error", "message": message})
            import base64
            self.emit({"event":"linux_capture_stopped","path64":base64.b64encode(path.encode()).decode("ascii")})
            if self.enabled and ((xid and self.target==xid) or (source_type==1 and self.desktop_input)):
                self.emit({"event":"linux_target_lost"})
        def start():
            capture = None
            try:
                if self.x11 is not None and 0<xid<KWIN_HANDLE_BASE:
                    capture = self._x11_capture(xid, publish, error, path)
                elif self.x11 is not None and self.kwin_xids.get(xid):
                    capture = self._x11_capture(self.kwin_xids[xid], publish, error, path)
                else:
                    if restore_token:
                        capture = self.portal.capture(source_type, publish, error, cursor, restore_token=restore_token)
                    else:
                        capture = self.portal.capture(source_type, publish, error, cursor)
                with self.lock:
                    stale=self.closed.is_set() or self.generations.get(path)!=generation
                    if not stale:
                        self.captures[path]=capture
                        self._apply_limit(path, capture)
                # Pipeline teardown can wait for a producer callback. Never
                # hold the same lock that the callback needs while stopping it.
                if stale:
                    capture.close()
                else:
                    granted = getattr(capture, "restore_token", "")
                    if isinstance(granted, str) and granted and len(granted) <= 512:
                        import base64
                        self.emit({"event": "linux_restore_token",
                                   "path64": base64.b64encode(path.encode()).decode("ascii"),
                                   "token64": base64.b64encode(granted.encode()).decode("ascii")})
            except Exception as exc:
                error(str(exc))
            finally:
                with self.lock:
                    if self.generations.get(path) == generation:
                        self.pending.discard(path)
        threading.Thread(target=start, name="multimini-select-source", daemon=True).start()

    def _x11_capture(self, xid, publish, error, path=""):
        from .linux_x11 import X11Capture
        return X11Capture(xid, publish, error, lambda: self._effective_fps(path))

    def _effective_fps(self, path):
        limit = self.background_limits.get(path, 0)
        return min(self.fps, limit) if limit else self.fps

    def _apply_limit(self, path, capture):
        # Drop frames before colour conversion/copy when the producer supports it.
        setter = getattr(capture, "set_rate_limit", None)
        if callable(setter):
            try:
                setter(self.background_limits.get(path, 0))
            except Exception as error:
                self.emit({"event": "error", "message": f"Capture rate limit: {error}"})

    def _remove(self, path):
        with self.lock:
            self.generations[path] = self.generations.get(path, 0)+1
            capture = self.captures.pop(path, None)
            self.pending.discard(path)
            self.last_frame.pop(path, None)
            self.frame_deadlines.pop(path, None)
            self.background_limits.pop(path, None)
        if capture:
            capture.close()
        self.frame_stream.forget(path)

    def _kwin_adapter(self):
        if not self.kwin_checked:
            self.kwin_checked=True
            try:
                from .linux_kwin import KWin
                candidate=KWin(self.portal)
                available=getattr(candidate,"available",True)
                if (available() if callable(available) else available):
                    self.kwin=candidate
                else:
                    candidate.close()
            except Exception:
                self.kwin=None
        return self.kwin

    def _kwin_windows(self):
        from .linux_x11 import encoded
        adapter=self._kwin_adapter()
        if adapter is None:
            return []
        rows=[]
        x11_rows=None
        for item in adapter.list_windows():
            if int(item.get("pid",0))==self.app_pid or not item.get("title","").strip() or item.get("title","").startswith("MultiMini"):
                continue
            token=str(item["uuid"])
            if token not in self.kwin_uuids:
                handle=KWIN_HANDLE_BASE+len(self.kwin_uuids)+1
                self.kwin_uuids[token]=handle
                self.kwin_handles[handle]=token
            handle=self.kwin_uuids[token]
            if self.x11 is not None and item.get("nativeWayland") is False:
                xid=int(item.get("x11WindowId") or 0)
                if not xid:
                    if x11_rows is None:
                        try:x11_rows=self.x11.windows()
                        except Exception:x11_rows=[]
                    xid=match_x11_window(item.get("title",""),x11_rows)
                if xid:self.kwin_xids[handle]=xid
                else:self.kwin_xids.pop(handle,None)
            x,y,w,h=(round(item["rect"][k]) for k in ("x","y","width","height"))
            rows.append(dict(title64=encoded(item["title"]),process64=encoded("kwin:"+item["process"]),
                             hwnd=self.kwin_uuids[token],left=x,top=y,width=w,height=h))
        return rows

    def _restore_kwin(self):
        if self.kwin_active is not None and self.kwin is not None:
            try:
                self.kwin.restore(self.kwin_active,self.kwin_baseline)
            except Exception as error:
                self.emit({"event":"error","message":f"KDE target restoration: {error}"})
            self.kwin_active=None
            self.kwin_baseline=None

    def _input_mode(self, mode, message=""):
        self.input_mode=mode
        self.emit({"event":"linux_input_mode", "mode":mode, "message":message})

    def _deactivate_native(self):
        # Close the input hole before restoring geometry, so no click can reach
        # a window after it has moved out from under the composition.
        if self.native_input:
            try:
                self.native_input.deactivate()
            except Exception as error:
                self.emit({"event":"error","message":f"Native input restoration: {error}"})
        self._restore_kwin()

    def _activate_input(self):
        if not self.enabled or self.selecting or self.permissions_pending:
            self._deactivate_native()
            if self.x11:
                self.x11.release_all()
            self._input_mode("unavailable")
            return
        if self.x11:
            self.x11.set_target(self.target if self.target<KWIN_HANDLE_BASE else 0,self.rect)
        try:
            if self.native_input is None:
                from .linux_native_input import NativeInput
                self.native_input=NativeInput()
            self.native_input.overlay_hint=self.overlay_xid
            self.native_input.control_hint=self.control_xid
            if self.target in self.kwin_handles:
                adapter=self._kwin_adapter()
                if adapter is None:raise RuntimeError("KDE window control is unavailable")
                token=self.kwin_handles[self.target]
                if self.kwin_active!=token:
                    self._deactivate_native()
                    self.kwin_baseline=adapter.get(token)
                    if self.kwin_baseline is None:raise RuntimeError("KDE source no longer exists; reselect it")
                    self.kwin_active=token
                sx,sy=self.coordinate_scale
                logical=tuple(round(v/(sx if i%2==0 else sy)) for i,v in enumerate(self.rect))
                requested=dict(zip(("x","y","width","height"),logical))
                result=adapter.place_focus(token,requested,keep_above=True,borderless=True)
                if not result.get("ok"):raise RuntimeError("KDE target cannot be placed: "+result.get("reason","unknown"))
                deadline=time.monotonic()+1
                while time.monotonic()<deadline:
                    actual=adapter.get(token)
                    if actual is not None and all(abs(actual["rect"][k]-v)<=1 for k,v in requested.items()) and actual.get("focused"):break
                    time.sleep(.025)
                else:raise RuntimeError("KDE target did not accept the requested geometry")
                self.native_input.activate_external(self.rect,self.app_pid)
            elif self.desktop_input:
                if self.kwin_active:self._deactivate_native()
                capture=self.captures.get(self.input_path)
                properties=getattr(capture,"properties",{})
                position=properties.get("position")
                size=properties.get("size")
                sx,sy=self.coordinate_scale
                logical=tuple(round(v/(sx if i%2==0 else sy)) for i,v in enumerate(self.rect))
                if position is None or size is None or tuple(map(round,(*position,*size)))!=logical:
                    raise RuntimeError("Display input requires a verified matching monitor; use a window source or reselect the display")
                self.native_input.activate_desktop(self.rect,self.app_pid)
            elif self.target:
                if self.kwin_active:self._deactivate_native()
                self.native_input.activate(self.target,self.rect,self.app_pid)
            else:
                self._deactivate_native()
                self._input_mode("unavailable","Native Wayland background window input is unavailable; choose an X11/XWayland target or display input")
                return
            self._input_mode("native")
        except Exception as error:
            self._deactivate_native()
            # Directed messages are a compatibility fallback, never a claim of
            # game/raw-input support. The UI keeps its focus for this path.
            mode="directed" if self.x11 and 0<self.target<KWIN_HANDLE_BASE else "unavailable"
            self._input_mode(mode,str(error))
            self.emit({"event":"error","message":f"Native input unavailable ({error}); input mode: {mode}"})

    def _check_input(self):
        if not self.enabled or self.selecting or self.permissions_pending or self.input_mode!="native":return
        try:
            if self.target in self.kwin_handles:
                state=self.kwin.get(self.kwin_handles[self.target])
                sx,sy=self.coordinate_scale
                expected=tuple(round(v/(sx if i%2==0 else sy)) for i,v in enumerate(self.rect))
                valid=state is not None and state.get("focused") and all(abs(state["rect"][k]-v)<=1 for k,v in zip(("x","y","width","height"),expected))
            elif self.desktop_input:
                valid=self.native_input._geometry(self.native_input._window(self.overlay_xid))==self.rect
            else:
                window=self.native_input._window(self.target)
                valid=self.native_input._viewable(window) and self.native_input.focused_target(self.target) and self.native_input._geometry(window)==self.rect
        except Exception:valid=False
        if not valid:
            self._deactivate_native()
            self._input_mode("unavailable","Input target focus or geometry changed")
            self.emit({"event":"linux_target_lost"})

    def _hotkey_emit(self,event):
        action=event.get("event","")
        now=time.monotonic()
        with self.lock:
            if not self.enabled or now-self.last_actions.get(action,0)<.1:
                return
            self.last_actions[action]=now
        self.emit(event)

    def _start_hotkeys(self,message):
        if self.hotkeys:
            self.hotkeys.close()
            self.hotkeys=None
        if self.x11:
            from .linux_hotkeys import X11Hotkeys
            bindings=[("toggle_multitask",message.get("stop_key","END")),
                      ("quick_swap",message.get("swap_key","RIGHT_CTRL")),
                      ("layer_swap",message.get("layer_swap_key","PAUSE")),
                      ("screenshot_full",self.full_key),("screenshot_region",self.region_key)]
            self.hotkeys=X11Hotkeys(bindings,self._hotkey_emit,lambda text:self.emit({"event":"error","message":text}))
        # Native Wayland desktop focus needs compositor-level shortcuts too.
        if self.wayland and (self.desktop_input or self.target in self.kwin_handles):
            self._shortcuts(message)

    def _shortcuts(self, message):
        # Bind only on an explicit multitask transition or applied key change.
        keys = (message.get("stop_key", "END"), message.get("swap_key", "RIGHT_CTRL"),
                message.get("layer_swap_key", "PAUSE"), self.region_key, self.full_key)
        if keys == self.shortcut_keys:
            return
        self.shortcut_keys = keys
        self.permissions_pending=True
        self._deactivate_native()
        self.emit({"event":"linux_permission_prompt","active":True})
        self.shortcut_generation += 1
        generation = self.shortcut_generation
        def bind():
            p = self.portal
            session = None
            try:
                session = p.create_session("GlobalShortcuts")
                actions = ["toggle_multitask", "quick_swap", "layer_swap", "screenshot_region", "screenshot_full"]
                shortcuts = []
                for action, key in zip(actions, keys):
                    if not key:
                        continue
                    shortcut = {"description": p.GLib.Variant("s", action.replace("_", " "))}
                    # Bare modifiers are not portable portal accelerators. Keep
                    # Right Ctrl locally and let the desktop assign a global key.
                    trigger = portal_trigger(key)
                    if trigger:
                        shortcut["preferred_trigger"] = p.GLib.Variant("s", trigger)
                    shortcuts.append((action, shortcut))
                result=p.request("GlobalShortcuts", "BindShortcuts", "(oa(sa{sv})sa{sv})", (session, shortcuts, ""))
                if self.closed.is_set() or generation != self.shortcut_generation:
                    p.close_session(session)
                    return
                if self.shortcut_session:
                    p.close_session(self.shortcut_session)
                if self.shortcut_sub is not None:
                    p.bus.signal_unsubscribe(self.shortcut_sub)
                self.shortcut_session = session
                import base64
                for action,properties in result.get("shortcuts",[]):
                    description=properties.get("trigger_description", "Unassigned") or "Unassigned"
                    self.emit({"event":"linux_shortcut","action":action,
                               "binding64":base64.b64encode(description.encode()).decode("ascii")})
                def activated(_b, _s, _p, _i, _n, params):
                    owner, action, _time, _options = params.unpack()
                    if owner == self.shortcut_session and self.enabled and (self.desktop_input or self.target in self.kwin_handles):
                        self._hotkey_emit({"event": action})
                self.shortcut_sub = p.bus.signal_subscribe("org.freedesktop.portal.Desktop",
                    "org.freedesktop.portal.GlobalShortcuts", "Activated", "/org/freedesktop/portal/desktop",
                    None, p.Gio.DBusSignalFlags.NONE, activated)
                self.emit({"event": "linux_status", "message": "Desktop shortcuts registered; desktop-assigned bindings apply globally"})
            except Exception as exc:
                if session:
                    p.close_session(session)
                self.emit({"event": "error", "message": f"Global shortcuts unavailable; use the exit control: {exc}"})
            finally:
                if generation==self.shortcut_generation:
                    self.permissions_pending=False
                    self.emit({"event":"linux_permission_prompt","active":False})
        threading.Thread(target=bind, name="multimini-shortcuts", daemon=True).start()

    region_key = "CTRL+SHIFT+F12"
    full_key = "INSERT"

    def dispatch(self, message):
        command = message.get("command")
        if command == "ping":
            return {"event": "pong"}
        if command == "capabilities":
            return {"event": "capabilities", "display": True, "window": True,
                    "input": self.x11 is not None, "native_wayland_window_input": bool(self.wayland and self.x11 and self._kwin_adapter()), "game_window": True, "game": False, "recording": False,
                    "backend": "wayland-portal" if self.wayland else "x11"}
        if command == "choose_image":
            def choose():
                import base64
                from urllib.parse import urlparse, unquote
                path = ""
                try:
                    result = self.portal.request("FileChooser", "OpenFile", "(ssa{sv})", ("", "Choose an image source"), {
                        "multiple": self.portal.GLib.Variant("b", False),
                        "filters": self.portal.GLib.Variant("a(sa(us))", [("Images", [(1, "image/*")])])})
                    uri = urlparse(result.get("uris", [""])[0])
                    if uri.scheme == "file" and uri.netloc in ("", "localhost"):
                        path = unquote(uri.path)
                except Exception as exc:
                    self.emit({"event": "error", "message": str(exc)})
                self.emit({"event": "image_file", "path64": base64.b64encode(path.encode()).decode("ascii")})
            threading.Thread(target=choose, name="multimini-file-picker", daemon=True).start()
            return {"event": "file_picker_opened"}
        if command == "list_windows":
            platform=message.get("platform","all")
            rows=self.x11.windows() if self.x11 and platform in ("all","x11") else []
            if self.wayland and platform in ("all","wayland"):
                rows+=self._kwin_windows()
            return {"event":"window_list","items":rows}
        if command in ("register_window_capture", "register_display_capture"):
            self._register(str(message.get("path", "")), 1 if command == "register_display_capture" else 2,
                           int(message.get("hwnd", 0)) if command == "register_window_capture" else 0,
                           bool(message.get("capture_cursor", False)),
                           str(message.get("restore_token", "") or "")[:512])
            return {"event": "capture_registered"}
        if command == "remove_window_capture":
            self._remove(str(message.get("path", "")))
            return {"event": "window_capture_removed"}
        if command == "active_window_captures":
            self.active = set(map(str, message.get("paths", [])))
            return {"event": "capture_activity_changed"}
        if command == "set_capture_fps":
            self.fps = max(1, min(1000, int(message.get("value", 60))))
            self.source_paced = bool(message.get("source_paced", False))
            self.frame_deadlines.clear()
            return {"event": "capture_fps_changed", "value": self.fps}
        if command == "linux_background_capture":
            path = str(message.get("path", ""))
            fps = max(0, min(1000, int(message.get("fps", 0))))
            with self.lock:
                if fps:
                    self.background_limits[path] = fps
                else:
                    self.background_limits.pop(path, None)
                self.frame_deadlines.pop(path, None)
                capture = self.captures.get(path)
            if capture is not None:
                self._apply_limit(path, capture)
            return {"event": "background_capture_changed", "fps": fps}
        if command == "set_opacity":
            self.opacity = max(0, min(100, int(message.get("value", 100))))
            return {"event": "opacity_changed", "value": self.opacity}
        if command == "linux_check_input":
            self._check_input()
            return {"event":"input_checked"}
        if command == "linux_overlay_controls":
            self.control_xid=max(0,int(message.get("xid",0)))
            return {"event":"overlay_controls_registered"}
        if command == "linux_overlay_window":
            self.overlay_xid=max(0,int(message.get("xid",0)))
            return {"event":"overlay_window_registered"}
        if command == "linux_coordinate_scale":
            self.coordinate_scale=tuple(max(.25,min(8.0,float(message.get(k,1)))) for k in ("x","y"))
            return {"event":"coordinate_scale_updated"}
        if command == "set_input_target":
            self.target = int(message.get("hwnd", 0))
            self.rect = tuple(int(message.get(k, 0)) for k in ("x", "y", "width", "height"))
            self.input_path=str(message.get("source_path", ""))
            previous_desktop=self.desktop_input
            self.desktop_input=bool(message.get("desktop_input",False))
            if self.x11:
                self.x11.set_target(self.target if self.target<KWIN_HANDLE_BASE else 0,self.rect)
            if self.enabled:
                if previous_desktop!=self.desktop_input or self.target in self.kwin_handles:
                    self._start_hotkeys(self.overlay_options)
                self._activate_input()
            return {"event": "input_target_changed", "focused": self.input_mode=="native"}
        if command == "linux_release_input":
            if self.x11:
                self.x11.release_all()
            return {"event":"input_released"}
        if command == "linux_input":
            if self.enabled and self.x11 and self.input_mode=="directed":
                self.x11.send(self.target, message, self.rect)
            return {"event": "input_processed"}
        if command == "set_multitask_overlay":
            self.enabled=bool(message.get("enabled",False))
            self.app_pid=int(message.get("app_pid",0))
            self.overlay_options=dict(message)
            if self.enabled:
                self._start_hotkeys(message)
            else:
                if self.hotkeys:
                    self.hotkeys.close();self.hotkeys=None
            self._activate_input()
            return {"event":"overlay_changed","enabled":self.enabled}
        if command == "screenshot_shortcuts":
            self.region_key, self.full_key = message.get("region", self.region_key), message.get("full", self.full_key)
        elif command == "screenshot_selection":
            self.selecting=bool(message.get("enabled",False))
            self._activate_input()
        elif command in ("set_layer_layout", "set_overlay_control_region", "refresh_overlay_hit_test"):
            pass  # Java owns composition; never reorder real application windows.
        elif command in ("start_game_capture", "game_capture_status"):
            return {"event": "game_capture_status", "status": "backend_unavailable"}
        elif command == "cleanup":
            return {"event": "cleanup_complete", "deleted": self.snapshots.cleanup()}
        elif command == "shutdown":
            self.closed.set()
            self.enabled = False
            if self.hotkeys:
                self.hotkeys.close();self.hotkeys=None
            self._deactivate_native()
            if self.native_input:
                try:
                    self.native_input.close()
                except Exception as error:
                    self.emit({"event":"error","message":f"Native input cleanup: {error}"})
                self.native_input=None
            self.shortcut_generation += 1
            for path in list(set(self.captures) | self.pending):
                self._remove(path)
            if self.kwin:
                self.kwin.close()
            self.portal.close()
            if self.x11:
                self.x11.close()
            self.frame_stream.close()
            return {"event": "shutdown"}
        else:
            return {"event": "error", "message": f"Unsupported Linux command: {command}"}
        return {"event": "command_applied"}


def match_x11_window(title, x11_rows):
    """Return the X11 id of the only X11 window with exactly this title, else 0."""
    import base64
    if not title:
        return 0
    wanted = base64.b64encode(title.encode("utf-8")).decode("ascii")
    matches = [int(row["hwnd"]) for row in x11_rows if row.get("title64") == wanted]
    return matches[0] if len(matches) == 1 else 0


def portal_trigger(key):
    names = {"CTRL": "CTRL", "SHIFT": "SHIFT", "ALT": "ALT", "WIN": "LOGO",
             "END": "End", "INSERT": "Insert", "PAUSE": "Pause", "SPACE": "space",
             "ESCAPE": "Escape", "DELETE": "Delete", "BACK_SPACE": "BackSpace",
             "PAGE_UP": "Page_Up", "PAGE_DOWN": "Page_Down", "HOME": "Home"}
    if key in ("RIGHT_CTRL", "LEFT_CTRL", "CTRL", "SHIFT", "ALT", "WIN"):
        return None
    return "+".join(names.get(part, part) for part in key.split("+"))


def run():
    lock = threading.Lock()
    def emit(message):
        with lock:
            print(json.dumps(message, separators=(",", ":")), flush=True)
    try:
        service = LinuxService(emit)
    except Exception as error:
        emit({"event": "error", "message": f"Linux backend could not start: {error}"})
        return
    emit({"event": "ready", "protocol": 1, "frame_port": service.frame_stream.port,
          "game_available": False, "frame_notifications": True, "backend": "linux"})
    for error in service.startup_errors:
        emit({"event": "error", "message": error})
    try:
        for line in sys.stdin:
            try:
                response = service.dispatch(json.loads(line))
                emit(response)
                if response.get("event") == "shutdown":
                    break
            except Exception as error:
                emit({"event": "error", "message": str(error)})
    finally:
        if not service.closed.is_set():
            service.dispatch({"command": "shutdown"})

#!/usr/bin/env python3
"""End-to-end portal input probe for one owned native GTK window."""
import json
import os
from pathlib import Path
import subprocess
import sys
import select
import signal
import tempfile
import time


FIXTURE = r'''import json, os
import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk, Gdk, GLib
Gtk.init()
display = Gdk.Display.get_default()
if display is None or "Wayland" not in type(display).__name__:
    raise RuntimeError("fixture did not connect through GDK Wayland")
monitors = display.get_monitors()
monitor = monitors.get_item(0) if monitors.get_n_items() else None
info = {"backend": type(display).__name__, "scale": monitor.get_scale_factor() if monitor else 1}
with open(os.environ["MULTIMINI_FIXTURE_INFO"], "w", encoding="utf-8") as handle:
    json.dump(info, handle)
window = Gtk.Window(title="MultiMini portal routing fixture")
window.set_decorated(False)
window.set_default_size(500, 300)
fixed = Gtk.Fixed()
entry = Gtk.Entry()
entry.set_size_request(300, 40)
entry.set_placeholder_text("fixture-ready")
fixed.put(entry, 30, 50)
window.set_child(fixed)
state={"text":"","clicked":False,"closed":False}
def record():
    target=os.environ["MULTIMINI_FIXTURE_MARKER"]
    with open(target+".tmp","w",encoding="utf-8") as handle:json.dump(state,handle)
    os.replace(target+".tmp",target)
def changed(widget):
    state["text"]=widget.get_text();record()
entry.connect("changed",changed)
gesture=Gtk.GestureClick()
gesture.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
def clicked(*args):state["clicked"]=True;record()
gesture.connect("pressed",clicked);entry.add_controller(gesture)
label=Gtk.Label(label="Click the box, then type: multimini")
fixed.put(label,30,12)
close=Gtk.Button(label="Cancel test")
def cancel(*args):state["closed"]=True;record();window.destroy()
close.connect("clicked",cancel);fixed.put(close,30,125)
record()
window.present()
GLib.MainLoop().run()
'''

class Overlay:
    def __init__(self, pid):
        self.process = subprocess.Popen(
            ["java", "--enable-native-access=ALL-UNNAMED", "-cp",
             "build/checks:build/internal/MultiMini-Linux.jar",
             "app.multimin.backend.LinuxOverlayFixture"],
            stdout=subprocess.PIPE, text=True, bufsize=1)
        self.pid = self.process.pid
        if self.pid <= 0 or self.pid == int(pid):
            raise RuntimeError("Could not start the owned Swing overlay fixture")
        ready, _, _ = select.select([self.process.stdout], [], [], 2)
        if not ready:
            raise RuntimeError("Swing overlay fixture did not report readiness")
        reported = self.process.stdout.readline().strip()
        try:
            values = [int(value) for value in reported.split()]
            if len(values) != 2 or values[0] != self.pid:
                raise RuntimeError("Swing overlay fixture PID did not match its process")
            self.xid = values[1]
        except ValueError as error:
            raise RuntimeError("Swing overlay fixture returned invalid PID/XID") from error

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)


def key(portal, session, keysym, pressed):
    portal.call("RemoteDesktop", "NotifyKeyboardKeysym", "(oa{sv}iu)",
                (session, {}, int(keysym), 1 if pressed else 0))


def pointer(portal, session, button, pressed):
    portal.call("RemoteDesktop", "NotifyPointerButton", "(oa{sv}iu)",
                (session, {}, int(button), 1 if pressed else 0))


def main():
    from multimin_backend.linux_native_input import NativeInput
    from multimin_backend.linux_portal import Portal
    from multimin_backend.linux_kwin import KWin

    with tempfile.TemporaryDirectory(prefix="multimini-kde-route-") as temp:
        def timeout(_signum, _frame):
            raise TimeoutError("KDE routing probe exceeded its 120 second bound")
        signal.signal(signal.SIGALRM, timeout)
        signal.alarm(120)
        info_path = Path(temp) / "fixture-info.json"
        marker_path = Path(temp) / "fixture-marker.json"
        env = dict(os.environ, GDK_BACKEND="wayland",
                   MULTIMINI_FIXTURE_INFO=str(info_path),
                   MULTIMINI_FIXTURE_MARKER=str(marker_path))
        child = subprocess.Popen([sys.executable, "-c", FIXTURE], env=env)
        portal = adapter = native = overlay = None
        session = None
        pressed = set()
        pointer_down = False
        saved_cursor = None
        selected_uuid = None
        baseline = None
        try:
            deadline = time.monotonic() + 6
            while time.monotonic() < deadline and child.poll() is None and not info_path.exists():
                time.sleep(.05)
            if child.poll() is not None or not info_path.exists():
                raise RuntimeError("native GTK fixture did not start")
            fixture_info = json.loads(info_path.read_text(encoding="utf-8"))
            if "Wayland" not in fixture_info.get("backend", ""):
                raise RuntimeError("GTK fixture is not a native Wayland client")
            if fixture_info.get("scale", 1) != 1:
                raise RuntimeError("This controlled routing check requires a 1x display scale")

            portal = Portal()
            if "--manual" not in sys.argv:
                session = portal.create_session("RemoteDesktop")
                portal.request("RemoteDesktop", "SelectDevices", "(oa{sv})", (session,), {
                    "types": portal.GLib.Variant("u", 3)})
                grant = portal.request("RemoteDesktop", "Start", "(osa{sv})", (session, ""))
                if int(grant.get("devices", 0)) & 3 != 3:
                    raise RuntimeError("RemoteDesktop did not grant both pointer and keyboard")
    
            overlay = Overlay(os.getpid())
            adapter = KWin(portal)
            native = NativeInput()
            native._connect()
            native.overlay_hint = overlay.xid
            if not adapter.available():
                raise RuntimeError("KWin scripting service is unavailable")
            try:
                overlay_xid, overlay_rect = native.find_overlay(overlay.pid)
            except RuntimeError as error:
                own_window = native._window(overlay.xid)
                own_attrs = own_window.get_attributes()
                own_rect = native._geometry(own_window)
                own_pid = native._property(own_window, "_NET_WM_PID", ())
                raise RuntimeError(f"Exact JAWT XID rejected: pid={list(own_pid or ())}, "
                                   f"map_state={int(own_attrs.map_state)}, "
                                   f"override_redirect={bool(own_attrs.override_redirect)}, "
                                   f"rect={own_rect}: {error}") from error
            if overlay_xid != overlay.xid:
                raise RuntimeError("Native input selected a different X11 overlay XID")
            overlay_window = native._window(overlay_xid)
            overlay_attrs = overlay_window.get_attributes()
            overlay_geometry = native._geometry(overlay_window)
            own_pid = native._property(overlay_window, "_NET_WM_PID", ())
            if not own_pid or int(own_pid[0]) != overlay.pid:
                raise RuntimeError("Exact overlay XID PID verification failed")
            from Xlib import X
            if overlay_attrs.map_state != X.IsViewable:
                raise RuntimeError("Exact JAWT overlay XID is not viewable")

            rows = []
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline and child.poll() is None:
                rows = adapter.list_windows(pid=child.pid)
                if rows:
                    break
                time.sleep(.15)
            fixture = next((row for row in rows if row.get("pid") == child.pid
                            and row.get("title") == "MultiMini portal routing fixture"), None)
            if fixture is None:
                raise RuntimeError("KWin did not return the PID-scoped GTK fixture")
            selected_uuid = fixture["uuid"]
            baseline = adapter.get(selected_uuid)
            if not baseline or baseline.get("uuid") != selected_uuid:
                raise RuntimeError("KWin exact-UUID lookup failed")

            original = baseline["rect"]
            ox, oy, ow, oh = overlay_geometry
            wanted = {"x": ox + 20, "y": oy + 20,
                      "width": 400, "height": 250}
            if wanted["x"] + wanted["width"] > ox + ow or wanted["y"] + wanted["height"] > oy + oh:
                raise RuntimeError("Requested fixture rectangle does not fit within the owned overlay")
            placed = adapter.place_focus(selected_uuid, wanted)
            if not placed.get("ok"):
                raise RuntimeError(f"KWin refused fixture placement: {placed}")
            actual = adapter.get(selected_uuid)
            if not actual or actual.get("uuid") != selected_uuid:
                raise RuntimeError("KWin lost selected UUID after placement")

            # Coordinates are logical and physical-equivalent on the verified 1x fixture display.
            physical = tuple(round(wanted[key]) for key in ("x", "y", "width", "height"))
            native.activate_external(physical, overlay.pid)
            input_overlay, input_overlay_rect = native.find_overlay(overlay.pid)
            expected_hole = NativeInput._rectangles_with_hole(input_overlay_rect, physical)
            actual_shape = native._shape_get(input_overlay)
            if sorted(actual_shape) != sorted(expected_hole):
                raise RuntimeError("Owned overlay input shape does not match the fixture hole")

            # Portal UI may take focus; reapply only the chosen KWin UUID afterward.
            placed = adapter.place_focus(selected_uuid, wanted)
            if not placed.get("ok"):
                raise RuntimeError(f"Could not refocus selected fixture: {placed}")
            rows = adapter.list_windows(pid=child.pid)
            if not any(row.get("uuid") == selected_uuid and row.get("pid") == child.pid
                       for row in rows):
                raise RuntimeError("Selected UUID/PID guard failed before input")
            current = adapter.get(selected_uuid)
            if not current or current.get("uuid") != selected_uuid or not current.get("focused"):
                raise RuntimeError("Selected fixture is not the focused KWin UUID")
            actual_shape = native._shape_get(input_overlay)
            if sorted(actual_shape) != sorted(expected_hole):
                raise RuntimeError("Fixture focus changed the owned overlay input hole")

            marker="multimini"
            if "--manual" in sys.argv:
                print("Click the box near the upper-left of the controlled overlay, then type multimini. Cancel test exits safely.",flush=True)
            else:
                cursor = adapter._execute("return {x: workspace.cursorPos.x, y: workspace.cursorPos.y};")
                saved_cursor = {"x": float(cursor["x"]), "y": float(cursor["y"])}
                target_x = wanted["x"] + 180  # Fixed entry x=30 plus center of its 300px width.
                target_y = wanted["y"] + 70   # Fixed entry y=50 plus center of its 40px height.
                portal.call("RemoteDesktop", "NotifyPointerMotion", "(oa{sv}dd)",
                            (session, {}, target_x-saved_cursor["x"], target_y-saved_cursor["y"]))
                deadline = time.monotonic() + .6
                pointer_position = None
                while time.monotonic() < deadline:
                    pointer_position = adapter._execute("return {x: workspace.cursorPos.x, y: workspace.cursorPos.y};")
                    if abs(float(pointer_position["x"])-target_x) < 3 and abs(float(pointer_position["y"])-target_y) < 3:
                        break
                    time.sleep(.05)
                else:
                    raise RuntimeError(f"Portal pointer did not reach the fixture entry center: {pointer_position}")
                pointer(portal, session, 272, True)
                pointer_down = True
                time.sleep(.08)
                pointer(portal, session, 272, False)
                pointer_down = False
    
                marker = "multimini"
                for character in marker:
                    current = adapter.get(selected_uuid)
                    if not current or current.get("uuid") != selected_uuid or not current.get("focused"):
                        raise RuntimeError("KWin focus guard failed while typing into fixture")
                    symbol = ord(character)
                    key(portal, session, symbol, True)
                    pressed.add(symbol)
                    key(portal, session, symbol, False)
                    pressed.discard(symbol)
            deadline = time.monotonic() + (40 if "--manual" in sys.argv else 3)
            actual_text = ""
            received={"clicked":False}
            while time.monotonic() < deadline:
                if overlay.process.poll() is not None:raise RuntimeError("Controlled overlay closed before verification")
                if child.poll() is not None:raise RuntimeError("Controlled input fixture closed")
                if marker_path.exists():
                    received=json.loads(marker_path.read_text(encoding="utf-8"))
                    if received.get("closed"):raise RuntimeError("Test cancelled")
                    actual_text=received.get("text", "")
                    if actual_text==marker and received.get("clicked"):break
                time.sleep(.05)
            if actual_text != marker or not received.get("clicked"):
                raise RuntimeError("Expected fixture click and marker were not received")
            print(f"PASS: PID-scoped native Wayland fixture {child.pid}; overlay hole passed pointer and keyboard")
            return 0
        finally:
            signal.alarm(0)
            if session is not None and portal is not None:
                if pointer_down:
                    try:
                        pointer(portal, session, 272, False)
                    except Exception:
                        pass
                for symbol in list(pressed):
                    try:
                        key(portal, session, symbol, False)
                    except Exception:
                        pass
            if saved_cursor is not None and session is not None and adapter is not None and portal is not None:
                try:
                    cursor = adapter._execute("return {x: workspace.cursorPos.x, y: workspace.cursorPos.y};")
                    portal.call("RemoteDesktop", "NotifyPointerMotion", "(oa{sv}dd)",
                                (session, {}, saved_cursor["x"]-float(cursor["x"]),
                                 saved_cursor["y"]-float(cursor["y"])))
                except Exception:
                    pass
            if native is not None:
                try:
                    native.close()
                except Exception:
                    pass
            if selected_uuid is not None and baseline is not None and adapter is not None:
                try:
                    adapter.restore(selected_uuid, baseline)
                except Exception:
                    pass
            if overlay is not None:
                overlay.close()
            if adapter is not None:
                adapter.close()
            if session is not None and portal is not None:
                portal.close_session(session)
            if portal is not None:
                portal.close()
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=3)


if __name__ == "__main__":
    raise SystemExit(main())

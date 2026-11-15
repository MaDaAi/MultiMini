#!/usr/bin/env python3
"""Exercise the KWin adapter against one owned native GTK window only."""
import os
import subprocess
import sys
import time


FIXTURE = r'''import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk, Gdk, GLib
Gtk.init()
display = Gdk.Display.get_default()
if display is None or "Wayland" not in type(display).__name__:
    raise RuntimeError("GTK fixture did not connect through the Wayland backend")
window = Gtk.Window(title="MultiMini KDE fixture")
window.set_default_size(500, 300)
entry = Gtk.Entry()
entry.set_text("Controlled MultiMini fixture")
window.set_child(entry)
window.present()
GLib.MainLoop().run()
'''


def near(actual, expected):
    return abs(float(actual) - float(expected)) <= 2.0


def main():
    env = dict(os.environ, GDK_BACKEND="wayland")
    child = subprocess.Popen([sys.executable, "-c", FIXTURE], env=env)
    portal = adapter = None
    try:
        # Import only after the child is started; no process/window listing is used.
        from multimin_backend.linux_portal import Portal
        from multimin_backend.linux_kwin import KWin
        portal = Portal()
        adapter = KWin(portal)
        if not adapter.available():
            raise RuntimeError("org.kde.KWin scripting service is unavailable")

        windows = []
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline and child.poll() is None:
            windows = adapter.list_windows(pid=child.pid)
            if windows:
                break
            time.sleep(.2)
        fixture = next((item for item in windows if item.get("pid") == child.pid
                        and item.get("title") == "MultiMini KDE fixture"), None)
        if fixture is None:
            raise RuntimeError("Owned GTK fixture was not found by its PID")
        window_uuid = fixture["uuid"]
        baseline = adapter.get(window_uuid)
        if not baseline or baseline.get("uuid") != window_uuid:
            raise RuntimeError("Exact UUID lookup failed")
        original = baseline["rect"]
        requested = {"x": original["x"] + 20, "y": original["y"] + 20,
                     "width": max(200, original["width"] - 40),
                     "height": max(150, original["height"] - 40)}
        placement = adapter.place_focus(window_uuid, requested, borderless=True)
        if not placement.get("ok"):
            raise RuntimeError(f"Fixture placement refused: {placement}")
        time.sleep(.25)
        moved = adapter.get(window_uuid)
        if not moved or not all(near(moved["rect"][key], requested[key])
                                for key in ("x", "y", "width", "height")):
            raise RuntimeError(f"Fixture geometry did not match placement: {moved}")

        if not moved.get("noBorder"):
            raise RuntimeError("Fixture did not enter borderless fitting")
        restored = adapter.restore(window_uuid, baseline)
        if not restored.get("ok"):
            raise RuntimeError(f"Fixture restore failed: {restored}")
        time.sleep(.25)
        after = adapter.get(window_uuid)
        if not after or not all(near(after["rect"][key], original[key])
                                for key in ("x", "y", "width", "height")):
            raise RuntimeError(f"Fixture geometry did not return to baseline: {after}")
        if after.get("noBorder") != baseline.get("noBorder"):
            raise RuntimeError("Fixture decoration state did not restore")
        print(f"PASS: scoped GTK fixture PID {child.pid}; list/get/place/restore; "
              f"KWin nativeWayland={fixture.get('nativeWayland')}")
        return 0
    finally:
        if adapter is not None:
            adapter.close()
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

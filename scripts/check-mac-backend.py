"""Smoke-test the macOS backend on a real Mac (used by CI on macos-14).

Fails on import errors or wrong PyObjC API names. Capture itself may be denied
on CI (no Screen Recording permission); that is reported, not failed.
"""
import json
import subprocess
import sys
import time

sys.path.insert(0, "python")
import Quartz  # noqa: E402,F401
import ScreenCaptureKit  # noqa: E402,F401
from multimin_backend import mac_capture, mac_input, platforms  # noqa: E402

assert platforms.service_class().__name__ == "MacService"
rows = mac_capture.list_windows()
print(f"list_windows: {len(rows)} rows")
print("accessibility trusted:", mac_input.accessibility_trusted())
for name in ("CGEventCreateMouseEvent", "CGEventPostToPid", "CGEventCreateKeyboardEvent",
             "CGEventSetIntegerValueField", "CGWindowListCopyWindowInfo", "CGGetActiveDisplayList",
             "CGBitmapContextCreate", "CGBitmapContextGetData"):
    assert hasattr(Quartz, name), name
assert hasattr(ScreenCaptureKit, "SCShareableContent")
print("SCScreenshotManager:", hasattr(ScreenCaptureKit, "SCScreenshotManager"))
try:
    grabber = mac_capture.DisplayGrabber(1)
    w, h, pixels = grabber.grab()
    assert len(pixels) == w * h * 4
    print(f"display grab: {w}x{h} via {'ScreenCaptureKit' if grabber.sck else 'CoreGraphics'}")
except Exception as error:
    print(f"display grab unavailable on this runner (expected without permission): {error!r}")

proc = subprocess.Popen([sys.executable, "-m", "multimin_backend"], cwd="python", stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, text=True)
ready = json.loads(proc.stdout.readline())
assert ready["event"] == "ready" and ready["backend"] == "macos", ready
for command in ({"command": "ping"}, {"command": "capabilities"}, {"command": "list_windows"}):
    proc.stdin.write(json.dumps(command) + "\n"); proc.stdin.flush()
    while True:
        reply = json.loads(proc.stdout.readline())
        if reply["event"] != "error":
            break
        print("backend error:", reply["message"])
    print(command["command"], "->", reply["event"])
proc.stdin.write('{"command":"shutdown"}\n'); proc.stdin.flush()
proc.wait(timeout=10)
print("PASS: macOS backend imports, PyObjC symbols exist, protocol answers")

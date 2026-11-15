#!/usr/bin/env python3
"""Sample only a synthetic window-only popup owned by this probe; never read the root."""
import subprocess
import time
from pathlib import Path
from Xlib import X, display
from Xlib.ext import shape

root = Path(__file__).resolve().parents[1]
subprocess.run(["javac", "--release", "25", "-cp", "build/internal/MultiMini-Linux.jar", "-d", "build/checks",
                "app/src/test/java/app/multimin/ui/LinuxOverlayPaintFixture.java"], cwd=root, check=True)
child = subprocess.Popen(["java", "--enable-native-access=ALL-UNNAMED", "-cp",
                          "build/checks:build/internal/MultiMini-Linux.jar",
                          "app.multimin.ui.LinuxOverlayPaintFixture"], cwd=root, stdout=subprocess.PIPE, text=True)
owner = display.Display()
try:
    pid, xid = map(int, child.stdout.readline().split())
    assert pid == child.pid
    window = owner.create_resource_object("window", xid)
    prop = window.get_full_property(owner.intern_atom("_NET_WM_PID"), X.AnyPropertyType)
    assert prop is not None and int(prop.value[0]) == pid
    # The visual fixture cannot intercept the user's clicks.
    window.shape_rectangles(shape.SO.Set, shape.SK.Input, X.Unsorted, 0, 0, [])
    owner.sync()
    time.sleep(.5)  # Allow the initial paint before sampling steady-state updates.
    colors = set()
    blank = 0
    for _ in range(180):
        pixmap = window.composite_name_window_pixmap()
        try:
            data = pixmap.get_image(240, 135, 1, 1, X.ZPixmap, 0xffffffff).data
        finally:
            pixmap.free()
        blue, green, red, alpha = data[:4]
        if max(red, green) <= 200 or blue >= 30:
            blank += 1
        else:
            colors.add("red" if red > green else "green")
        time.sleep(.016)
    print(f"Owned popup: {len(colors)} changing colors, {blank}/180 blank or invalid samples")
    assert len(colors) == 2 and blank == 0
finally:
    owner.close()
    if child.poll() is None:
        child.terminate()
        child.wait(timeout=5)

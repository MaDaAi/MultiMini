#!/usr/bin/env python3
"""Live native input check using only three windows owned by this process."""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "python"))

from Xlib import X, Xatom, XK, Xutil, display
from multimin_backend.linux_native_input import NativeInput


def xid(value):
    return int(getattr(value, "id", value) or 0)


def geometry(root, win):
    value = win.get_geometry()
    pos = root.translate_coords(win, 0, 0)
    return int(pos.x), int(pos.y), int(value.width), int(value.height)


def main():
    d = display.Display()
    root = d.screen().root
    pid = os.getpid()
    screen = d.screen()
    sw, sh = screen.width_in_pixels, screen.height_in_pixels
    ow, oh = min(800, sw - 80), min(600, sh - 80)
    if ow < 300 or oh < 240:
        raise RuntimeError("X11 screen is too small for controlled window check")
    ox, oy = 20, 20
    overlay = root.create_window(ox, oy, ow, oh, 0, X.CopyFromParent, X.InputOutput,
                                 X.CopyFromParent, background_pixel=0x335577, override_redirect=True)
    target = root.create_window(max(10, sw - 180), max(10, sh - 150), 140, 110, 0,
                                X.CopyFromParent, X.InputOutput, X.CopyFromParent,
                                event_mask=(X.KeyPressMask | X.KeyReleaseMask |
                                            X.ButtonPressMask | X.ButtonReleaseMask |
                                            X.PointerMotionMask),
                                background_pixel=0x775533)
    prior = root.create_window(max(15, sw - 100), 15, 60, 40, 0, X.CopyFromParent,
                               X.InputOutput, X.CopyFromParent, background_pixel=0x337755)
    pid_atom = d.intern_atom("_NET_WM_PID")
    overlay.change_property(d.intern_atom("_NET_WM_WINDOW_TYPE"), Xatom.ATOM, 32,
                            [d.intern_atom("_NET_WM_WINDOW_TYPE_UTILITY")])
    # Match the product POPUP overlay: unmanaged and undecorated, so no WM
    # frame intercepts clicks in the client's shaped input hole.
    overlay.change_property(d.intern_atom("_MOTIF_WM_HINTS"),
                            d.intern_atom("_MOTIF_WM_HINTS"), 32, [2, 0, 0, 0, 0])
    # Only the overlay carries the app PID. The other windows are test-owned
    # fixtures but must not look like Java controls to NativeInput.
    overlay.change_property(pid_atom, Xatom.CARDINAL, 32, [pid])
    for name, win in (("overlay",overlay),("target",target),("prior",prior)):
        win.set_wm_name("MultiMini native input fixture " + name)
        win.set_wm_class("multimini-test", "MultiMiniTest")
        win.set_wm_hints(input=True, flags=Xutil.InputHint)
        win.map()
    d.sync()
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline and any(
            win.get_attributes().map_state != X.IsViewable for win in (overlay, target, prior)):
        d.sync()
        time.sleep(.025)
    if any(win.get_attributes().map_state != X.IsViewable for win in (overlay, target, prior)):
        raise RuntimeError("Window manager did not map owned fixtures: " + str({name: win.get_attributes().map_state for name, win in (("overlay",overlay),("target",target),("prior",prior))}))
    pointer = root.query_pointer()
    saved_pointer = (int(pointer.root_x), int(pointer.root_y))
    prior.set_input_focus(X.RevertToPointerRoot, X.CurrentTime)
    d.sync()

    own_ids = {target.id, prior.id}
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline:
        prop = root.get_full_property(d.intern_atom("_NET_CLIENT_LIST"), X.AnyPropertyType)
        listed = set() if prop is None else {int(v) for v in prop.value}
        if own_ids <= listed:
            break
        time.sleep(.05)
    else:
        raise RuntimeError("Window manager did not publish owned fixture windows in _NET_CLIENT_LIST")

    original_target = geometry(root, target)
    original_overlay = geometry(root, overlay)
    router = NativeInput()
    router.overlay_hint = overlay.id
    router._connect()
    original_shape = router._shape_get(overlay.id)
    wanted = (original_overlay[0] + 90, original_overlay[1] + 70,
              min(ow - 180, 360), min(oh - 140, 260))
    try:
        router.activate(target.id, wanted, pid)
        d.sync()
        actual = geometry(root, target)
        focused = xid(d.get_input_focus().focus)
        if actual != wanted:
            raise AssertionError(f"target geometry mismatch: wanted {wanted}, got {actual}")
        if focused != target.id:
            raise AssertionError(f"focus mismatch: expected owned target {target.id}, got {focused}")
        expected_shape = NativeInput._rectangles_with_hole(original_overlay, wanted)
        actual_shape = router._shape_get(overlay.id)
        if sorted(actual_shape) != sorted(expected_shape):
            raise AssertionError(f"input shape mismatch: wanted {expected_shape}, got {actual_shape}")

        if xid(d.get_input_focus().focus) != target.id:
            raise AssertionError("Owned target lost focus before native input check")
        xtest = d.xtest_get_version(2, 2)
        if (xtest.major_version, xtest.minor_version) < (2, 1):
            raise RuntimeError("XTest 2.1 or newer is required for native input verification")
        center = (wanted[0] + wanted[2] // 2, wanted[1] + wanted[3] // 2)
        keycode = d.keysym_to_keycode(XK.string_to_keysym("a"))
        if not keycode:
            raise RuntimeError("Could not resolve fixture keyboard key")
        try:
            root.warp_pointer(center[0], center[1])
            d.sync()
            ptr = root.query_pointer()
            if (int(ptr.root_x), int(ptr.root_y)) != center:
                raise AssertionError(f"Could not place pointer at fixture point: {center} -> {(ptr.root_x, ptr.root_y)}")
            d.xtest_fake_input(X.ButtonPress, detail=1)
            d.sync()
            d.xtest_fake_input(X.ButtonRelease, detail=1)
            d.xtest_fake_input(X.KeyPress, detail=keycode)
            d.xtest_fake_input(X.KeyRelease, detail=keycode)
            d.sync()
            events = []
            event_deadline = time.monotonic() + 1.0
            while time.monotonic() < event_deadline:
                while d.pending_events():
                    event = d.next_event()
                    if event.type in (X.MotionNotify, X.ButtonPress, X.ButtonRelease,
                                      X.KeyPress, X.KeyRelease):
                        events.append(event)
                wanted_types = {X.ButtonPress, X.ButtonRelease, X.KeyPress, X.KeyRelease}
                if wanted_types <= {event.type for event in events}:
                    break
                time.sleep(.01)
            for event_type in (X.ButtonPress, X.ButtonRelease, X.KeyPress, X.KeyRelease):
                found = next((event for event in events if event.type == event_type), None)
                if found is None:
                    raise AssertionError(f"Owned target received no native event type {event_type}")
                if getattr(found, "send_event", True):
                    raise AssertionError("Input arrived as a synthetic SendEvent instead of native XTest routing")
            button = next(event for event in events if event.type == X.ButtonPress)
            if (int(button.event_x), int(button.event_y)) != (wanted[2] // 2, wanted[3] // 2):
                raise AssertionError(f"Native mouse hit test local coordinates were wrong: {(button.event_x, button.event_y)}")
        finally:
            d.xtest_fake_input(X.ButtonRelease, detail=1)
            d.xtest_fake_input(X.KeyRelease, detail=keycode)
            d.sync()

        router.deactivate()
        time.sleep(.2)
        d.sync()
        restored_target = geometry(root, target)
        restored_focus = xid(d.get_input_focus().focus)
        restored_shape = router._shape_get(overlay.id)
        if restored_target != original_target:
            raise AssertionError(f"target was not restored: {original_target} -> {restored_target}")
        if restored_focus != prior.id:
            raise AssertionError(f"prior owned focus was not restored: expected {prior.id}, got {restored_focus}")
        if restored_shape != original_shape:
            raise AssertionError(f"overlay input shape was not restored: {original_shape} -> {restored_shape}")
            root.warp_pointer(saved_pointer[0], saved_pointer[1])
        d.sync()
        print(f"PASS: owned target {target.id} fitted to {wanted}, focused, and restored to {original_target}")
        print(f"PASS: real XTest mouse and keyboard events reached target with native delivery and correct local hit coordinates")
        print(f"PASS: owned overlay {overlay.id} input hole and original shape restored; prior focus {prior.id} restored")
    finally:
        try:
            router.close()
        finally:
            try:
                d.xtest_fake_input(X.ButtonRelease, detail=1)
                d.xtest_fake_input(X.KeyRelease, detail=0)
                root.warp_pointer(saved_pointer[0], saved_pointer[1])
            except Exception:
                pass
            for win in (overlay, target, prior):
                try:
                    win.destroy()
                except Exception:
                    pass
            d.flush()
            d.close()


if __name__ == "__main__":
    main()

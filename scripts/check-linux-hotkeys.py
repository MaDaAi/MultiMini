#!/usr/bin/env python3
"""Verify X11 global shortcuts using a focused, process-owned fixture window."""
import os
import queue
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "python"))

from Xlib import X, Xatom, XK, display
from multimin_backend.linux_hotkeys import X11Hotkeys


def focus_id(value):
    return int(getattr(value, "id", value) or 0)


def wait_for(queue_item, timeout, label):
    try:
        return queue_item.get(timeout=timeout)
    except queue.Empty as error:
        raise RuntimeError(f"Timed out waiting for {label}") from error


def main():
    d = display.Display()
    root = d.screen().root
    prior_focus = focus_id(d.get_input_focus().focus)
    errors = queue.Queue()
    actions = queue.Queue()
    fixture = None
    hotkeys = None
    pressed = set()
    codes = []
    try:
        screen = d.screen()
        width, height = screen.width_in_pixels, screen.height_in_pixels
        if width < 200 or height < 120:
            raise RuntimeError("X11 screen is too small for the owned hotkey fixture")
        fixture = root.create_window(40, 40, 160, 80, 0, X.CopyFromParent,
                                     X.InputOutput, X.CopyFromParent,
                                     background_pixel=0x445566)
        fixture.change_property(d.intern_atom("_NET_WM_PID"), Xatom.CARDINAL, 32, [os.getpid()])
        fixture.map()
        d.sync()
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline:
            if fixture.get_attributes().map_state == X.IsViewable:
                break
            time.sleep(.025)
        if fixture.get_attributes().map_state != X.IsViewable:
            raise RuntimeError("Window manager did not map the owned hotkey fixture")

        def focus_fixture():
            if focus_id(d.get_input_focus().focus) != fixture.id:
                fixture.set_input_focus(X.RevertToPointerRoot, X.CurrentTime)
                d.sync()
            actual = focus_id(d.get_input_focus().focus)
            if actual != fixture.id:
                raise RuntimeError(f"Could not focus owned fixture window {fixture.id}; current focus is {actual}")

        focus_fixture()
        bindings = [
            ("quick_swap", "RIGHT_CTRL"),
            ("layer_swap", "PAUSE"),
            ("toggle_multitask", "END"),
        ]
        hotkeys = X11Hotkeys(bindings, actions.put, lambda message: errors.put(message))
        # X11Hotkeys installs grabs on its own connection before entering its
        # select loop. This bounded settle gives its startup errors time to land.
        time.sleep(.25)
        if not errors.empty():
            raise RuntimeError("X11 hotkey grabs unavailable: " + str(errors.get_nowait()))

        for action, binding in bindings:
            keysym_name = {"RIGHT_CTRL": "Control_R", "PAUSE": "Pause", "END": "End"}[binding]
            code = d.keysym_to_keycode(XK.string_to_keysym(keysym_name))
            if not code:
                raise RuntimeError(f"No X11 keycode is available for {binding}")
            codes.append(code)

            focus_fixture()
            d.xtest_fake_input(X.KeyPress, detail=code)
            pressed.add(code)
            d.sync()
            actual = wait_for(actions, .75, f"{action} from {binding}")
            if actual != {"event": action}:
                raise AssertionError(f"{binding} emitted {actual!r}, expected {action!r}")

            # Check focus again immediately before release. Release must not
            # trigger a second callback or a different action.
            focus_fixture()
            d.xtest_fake_input(X.KeyRelease, detail=code)
            pressed.discard(code)
            d.sync()
            time.sleep(.12)
            if not actions.empty():
                raise AssertionError(f"{binding} produced extra event(s): {list(actions.queue)}")
            if not errors.empty():
                raise RuntimeError("X11 hotkey grab failed: " + str(errors.get_nowait()))

        print("PASS: Right Ctrl emitted one quick_swap; Pause emitted one layer_swap; End emitted one toggle_multitask")
        print("PASS: all key releases emitted no extra actions; fixture focus stayed on the owned window")
    finally:
        if fixture is not None and d is not None:
            # Release every key on the fixture-owned test connection before
            # dropping grabs or restoring the original focus.
            for code in set(codes) | pressed:
                try:
                    d.xtest_fake_input(X.KeyRelease, detail=code)
                except Exception:
                    pass
            try:
                d.sync()
            except Exception:
                pass
        if hotkeys is not None:
            hotkeys.close()
        if fixture is not None:
            try:
                if prior_focus not in (0, 1):
                    prior = d.create_resource_object("window", prior_focus)
                    if prior.get_attributes().map_state == X.IsViewable:
                        prior.set_input_focus(X.RevertToPointerRoot, X.CurrentTime)
                d.sync()
            except Exception:
                pass
            try:
                fixture.destroy()
                d.flush()
            except Exception:
                pass
        d.close()


if __name__ == "__main__":
    main()

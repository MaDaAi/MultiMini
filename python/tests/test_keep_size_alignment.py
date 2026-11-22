import ctypes
import threading
import unittest

from multimin_backend.service import CaptureService
from multimin_backend.windows_input import _Rect, WindowsInputBridge

from test_mouse_routing import FakeUser32, bridge


class SizedUser32(FakeUser32):
    """Reports the target window as 980x580 at (100, 50), unlike the 1000x1000 default fake."""

    def __init__(self, rect=(100, 50, 1080, 630), succeed=True):
        super().__init__()
        self.window_rect = rect
        self.succeed = succeed

    def GetWindowRect(self, hwnd, pointer):
        if not self.succeed:
            return 0
        rect = ctypes.cast(pointer, ctypes.POINTER(_Rect)).contents
        rect.left, rect.top, rect.right, rect.bottom = self.window_rect
        return 1


def aligned_bridge(user32, rect=(300, 200, 640, 360)):
    instance = bridge(hwnd=77, preview=rect)
    instance.user32 = user32
    instance._mouse_target_rect = rect
    instance.events = []
    instance.emit = instance.events.append
    return instance


class KeepSizeAlignmentTests(unittest.TestCase):
    def test_keep_size_moves_window_but_keeps_its_current_size(self):
        user32 = SizedUser32()
        instance = aligned_bridge(user32, rect=(300, 200, 640, 360))
        instance._align_target_window(77, instance._mouse_target_rect, True)
        # Requested x,y are used; the window's own 980x580 replaces the rect's 640x360.
        self.assertEqual(user32.placements[0][:5], (77, 300, 200, 980, 580))
        self.assertEqual(instance._mouse_target_rect, (300, 200, 980, 580))
        self.assertEqual(instance.events, [])

    def test_without_keep_size_the_rect_size_is_used(self):
        user32 = SizedUser32()
        instance = aligned_bridge(user32, rect=(300, 200, 640, 360))
        instance._align_target_window(77, instance._mouse_target_rect, False)
        self.assertEqual(user32.placements[0][:5], (77, 300, 200, 640, 360))
        self.assertEqual(instance._mouse_target_rect, (300, 200, 640, 360))
        self.assertEqual(instance.events, [])

    def test_keep_size_with_zero_rect_moves_nothing(self):
        # An empty preview rect (preview hidden) must never move the window, keep_size or not.
        user32 = SizedUser32()
        instance = aligned_bridge(user32, rect=(300, 200, 0, 0))
        instance._align_target_window(77, (300, 200, 0, 0), True)
        self.assertEqual(user32.placements, [])
        self.assertEqual(instance.events, [{"event": "error", "message": "Window input needs visible preview bounds"}])

    def test_zero_rect_without_keep_size_emits_error_and_moves_nothing(self):
        user32 = SizedUser32()
        instance = aligned_bridge(user32, rect=(300, 200, 0, 0))
        instance._align_target_window(77, (300, 200, 0, 0), False)
        self.assertEqual(user32.placements, [])
        self.assertEqual(instance.events, [{"event": "error", "message": "Window input needs visible preview bounds"}])

    def test_keep_size_when_window_rect_unavailable_falls_back_to_rect_size_check(self):
        # If GetWindowRect fails, the rect size is kept; a zero rect then reports the error.
        user32 = SizedUser32(succeed=False)
        instance = aligned_bridge(user32, rect=(300, 200, 0, 0))
        instance._align_target_window(77, (300, 200, 0, 0), True)
        self.assertEqual(user32.placements, [])
        self.assertEqual(instance.events[0]["event"], "error")

    def test_set_mouse_target_forwards_keep_size_to_alignment(self):
        user32 = SizedUser32()
        instance = aligned_bridge(user32)
        instance._exclusive_input = True
        instance._layer_layout_active = False
        instance._desktop_input = False
        instance._mouse_replay_lock = threading.Lock()  # set by __init__ in the real bridge
        calls = []
        instance._align_target_window = lambda hwnd, rect, keep_size=False: calls.append((hwnd, rect, keep_size))
        instance.set_mouse_target(77, (300, 200, 640, 360), (0, 0, 0, 0), False, True)
        self.assertEqual(calls, [(77, (300, 200, 640, 360), True)])
        instance.set_mouse_target(77, (300, 200, 640, 360), (0, 0, 0, 0), False)
        self.assertEqual(calls[-1], (77, (300, 200, 640, 360), False))
        instance.set_mouse_target(77, (300, 200, 640, 360), (0, 0, 0, 0), True, True)
        self.assertEqual(len(calls), 2, "desktop input must not align the window")


class FakeNativeInput:
    def __init__(self):
        self.focused = []
        self.targets = []

    def focus_window(self, name, hwnd):
        self.focused.append((name, hwnd))
        return True

    def set_mouse_target(self, *args, **kwargs):
        self.targets.append((args, kwargs))


class SetInputTargetDispatchTests(unittest.TestCase):
    def make_service(self):
        # Same construction as test_capture_activity: skip __init__ (probes, disk and native work).
        service = CaptureService.__new__(CaptureService)
        service.native_input = FakeNativeInput()
        service.input_target = "desktop"
        service.input_hwnd = 0
        service.multitask_enabled = True
        return service

    def message(self, **extra):
        base = {"command": "set_input_target", "name": "Notepad", "hwnd": 77,
                "x": 300, "y": 200, "width": 640, "height": 360,
                "exclude_x": 1, "exclude_y": 2, "exclude_width": 3, "exclude_height": 4}
        base.update(extra)
        return base

    def test_keep_size_true_is_forwarded_to_set_mouse_target(self):
        service = self.make_service()
        result = service.dispatch(self.message(keep_size=True))
        self.assertEqual(result, {"event": "input_target_changed", "name": "Notepad", "focused": True})
        args, kwargs = service.native_input.targets[0]
        # service.py passes the flags positionally: (hwnd, rect, excluded, desktop_input, keep_size).
        self.assertEqual(args[0], 77)
        self.assertEqual(args[1], (300, 200, 640, 360))
        self.assertEqual(args[2], (1, 2, 3, 4))
        self.assertIs(args[3], False)
        self.assertIs(args[4], True)
        self.assertEqual(kwargs, {})

    def test_keep_size_defaults_to_false_when_absent(self):
        service = self.make_service()
        service.dispatch(self.message())
        args, _ = service.native_input.targets[0]
        self.assertIs(args[4], False)

    def test_keep_size_is_coerced_to_bool(self):
        service = self.make_service()
        service.dispatch(self.message(keep_size=0))
        service.dispatch(self.message(keep_size="yes"))
        self.assertIs(service.native_input.targets[0][0][4], False)
        self.assertIs(service.native_input.targets[1][0][4], True)


if __name__ == "__main__":
    unittest.main()

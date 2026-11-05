import ctypes
import queue
import threading
import unittest

from multimin_backend.windows_input import _MouseHookData, _Point, _Rect, WindowsInputBridge


class FakeUser32:
    def __init__(self):
        self.posted = []
        self.placements = []
        self.z_positions = []
        self.z_stack = []
        self.styles = {}
        self.alphas = []

    def IsWindow(self, hwnd):
        return hwnd in (77, 88, 99)

    def GetWindowRect(self, hwnd, pointer):
        rect = ctypes.cast(pointer, ctypes.POINTER(_Rect)).contents
        rect.left, rect.top, rect.right, rect.bottom = 1000, 500, 2000, 1500
        return 1

    def ScreenToClient(self, hwnd, pointer):
        point = ctypes.cast(pointer, ctypes.POINTER(_Point)).contents
        origin = {77: (1000, 500), 88: (1200, 700), 99: (1600, 900)}[hwnd]
        point.x -= origin[0]
        point.y -= origin[1]
        return 1

    def ChildWindowFromPointEx(self, hwnd, point, _flags):
        if hwnd == 77:
            return 88 if point.x < 600 else 99
        return hwnd

    def GetKeyState(self, _key):
        return 0

    def PostMessageW(self, hwnd, message, wparam, lparam):
        self.posted.append((hwnd, message, wparam, lparam))
        return 1

    def IsIconic(self, _hwnd):
        return 0

    def SetWindowPos(self, hwnd, after, x, y, width, height, flags):
        self.placements.append((hwnd, x, y, width, height, flags))
        self.z_positions.append((hwnd, after, flags))
        if after in (WindowsInputBridge.HWND_TOPMOST, WindowsInputBridge.HWND_TOP):
            if hwnd in self.z_stack:
                self.z_stack.remove(hwnd)
            self.z_stack.insert(0, hwnd)
        if after == WindowsInputBridge.HWND_TOPMOST:
            self.styles[hwnd] = self.styles.get(hwnd, 0) | WindowsInputBridge.WS_EX_TOPMOST
        return 1

    def GetWindowLongPtrW(self, hwnd, _index):
        return self.styles.get(hwnd, 0)

    def SetWindowLongPtrW(self, hwnd, _index, style):
        old = self.styles.get(hwnd, 0)
        self.styles[hwnd] = style
        return old

    def GetLayeredWindowAttributes(self, _hwnd, _color, _alpha, _flags):
        return 0

    def SetLayeredWindowAttributes(self, hwnd, _color, alpha, _flags):
        self.alphas.append((hwnd, alpha))
        return 1

    def BringWindowToTop(self, _hwnd):
        return 1

    def SetForegroundWindow(self, hwnd):
        if hwnd in self.z_stack:
            self.z_stack.remove(hwnd)
            self.z_stack.insert(0, hwnd)
        return 1

    def WindowFromPoint(self, _point):
        return 99

    def GetAncestor(self, hwnd, _flag):
        return hwnd


def mouse_event(x, y, mouse_data=0):
    return _MouseHookData(_Point(x, y), mouse_data, 0, 0, 0)


def bridge(*, hwnd=77, preview=(100, 100, 400, 200), desktop=False, excluded=(0, 0, 0, 0)):
    instance = object.__new__(WindowsInputBridge)
    instance.user32 = FakeUser32()
    instance._exclusive_input = True
    instance._mouse_target_hwnd = hwnd
    instance._desktop_input = desktop
    instance._mouse_target_rect = preview
    instance._mouse_excluded_rect = excluded
    instance._mouse_forwarding = False
    instance._blocked_mouse_buttons = set()
    instance._passthrough_mouse_buttons = set()
    instance._mouse_button_flags = 0
    instance._mouse_last_target_point = (0, 0)
    instance._mouse_button_targets = {}
    instance._aligned_window = None
    instance._layer_windows = {}
    instance._layer_layout_active = False
    instance._pressed_keys = set()
    instance._layer_swap_lock = threading.Lock()
    instance._overlay_hwnd = 99
    instance.emit = lambda _event: None
    instance._mouse_jobs = queue.Queue()
    return instance


class MouseRoutingTests(unittest.TestCase):
    def test_exit_visibility_changes_only_the_excluded_region(self):
        instance = bridge()
        instance._mouse_target_hwnd = 77
        instance._mouse_target_rect = (10, 20, 300, 200)
        before = dict(instance.__dict__)
        instance.set_overlay_control_region((40, 50, 38, 38))
        self.assertEqual(instance._mouse_excluded_rect, (40, 50, 38, 38))
        for key, value in before.items():
            if key != "_mouse_excluded_rect":
                self.assertEqual(instance.__dict__[key], value, key)
        instance.set_overlay_control_region((0, 0, 0, 0))
        self.assertEqual(instance._mouse_target_hwnd, 77)
        self.assertEqual(instance._mouse_target_rect, (10, 20, 300, 200))

    def test_pause_and_right_control_bindings_do_not_match_each_other(self):
        instance = bridge()
        self.assertTrue(instance._matches_binding("PAUSE", 0x13))
        self.assertTrue(instance._matches_binding("PAUSE", 0x03))
        self.assertFalse(instance._matches_binding("PAUSE", instance.VK_RCONTROL))
        self.assertTrue(instance._matches_binding("RIGHT_CTRL", instance.VK_RCONTROL))
        self.assertFalse(instance._matches_binding("RIGHT_CTRL", 0x13))

    def test_pause_hook_and_hotkey_emit_one_swap(self):
        instance = bridge()
        events = []
        instance.emit = events.append
        instance._last_layer_swap_at = 0.0
        instance._emit_layer_swap()
        instance._emit_layer_swap()
        self.assertEqual([{"event": "layer_swap"}], events)

    def test_pause_poll_emits_once_on_key_down_edge(self):
        instance = bridge()
        events = []
        instance.emit = events.append
        instance._last_layer_swap_at = 0.0
        states = iter((0, 0, 0, 0, 0x8000, 0, 0x8000, 0, 0, 0))
        instance.user32.GetAsyncKeyState = lambda _key: next(states)

        class StopAfterFourChecks:
            checks = 0
            def wait(self, _seconds):
                self.checks += 1
                return self.checks > 4

        instance._pause_poll_stop = StopAfterFourChecks()
        instance._poll_pause_key()
        self.assertEqual([{"event": "layer_swap"}], events)

    def test_overlay_lookup_uses_java_pid_not_worker_parent(self):
        instance = bridge()
        instance.set_app_pid(4242)
        instance.user32.IsWindowVisible = lambda _hwnd: 1
        instance.user32.GetWindowThreadProcessId = lambda hwnd, pointer: setattr(
            ctypes.cast(pointer, ctypes.POINTER(ctypes.c_ulong)).contents,
            "value", 4242 if hwnd == 77 else 9999)
        def enumerate_windows(callback, _lparam):
            for hwnd in (88, 77):
                callback(hwnd, 0)
        instance.user32.EnumWindows = enumerate_windows
        self.assertEqual(77, instance._find_largest_visible_app_window())

    def test_layer_input_switch_preserves_opacity_and_visual_order(self):
        instance = bridge()
        instance.set_layer_layout([
            {"hwnd": 77, "x": 100, "y": 100, "width": 400, "height": 200, "opacity": 50},
            {"hwnd": 88, "x": 100, "y": 100, "width": 400, "height": 200, "opacity": 100},
        ])
        self.assertEqual([88, 77], [placement[0] for placement in instance.user32.placements if placement[3] == 400])
        self.assertEqual([(88, 255), (77, 128)], instance.user32.alphas)
        before = list(instance.user32.alphas)
        instance._mouse_target_hwnd = 88
        instance._update_layer_input()
        self.assertEqual(before, instance.user32.alphas)
        self.assertEqual([77, 88], [placement[0] for placement in instance.user32.placements[-2:]])
        self.assertEqual(0, instance.user32.styles[88] & instance.WS_EX_TRANSPARENT)
        self.assertNotEqual(0, instance.user32.styles[77] & instance.WS_EX_TRANSPARENT)
        instance._restore_layer_windows()
        self.assertEqual(0, instance.user32.styles[77])
        self.assertEqual(0, instance.user32.styles[88])

    def test_visual_swap_reorders_windows_without_resetting_input_or_geometry(self):
        instance = bridge(hwnd=88)
        first = [
            {"hwnd": 77, "x": 100, "y": 100, "width": 400, "height": 200, "opacity": 50},
            {"hwnd": 88, "x": 100, "y": 100, "width": 400, "height": 200, "opacity": 100},
        ]
        instance.set_layer_layout(first)
        saved = dict(instance._layer_windows)
        instance.set_layer_layout(list(reversed(first)))
        self.assertEqual(88, instance._mouse_target_hwnd)
        self.assertEqual(saved, instance._layer_windows)
        self.assertEqual([88, 77], instance.user32.z_stack)
        instance._mouse_target_hwnd = 77
        instance._update_layer_input()
        self.assertEqual([77, 88], instance.user32.z_stack)
        self.assertEqual([88, 77], instance._layer_order)

    def test_visual_overlay_leaves_native_mouse_events_intact(self):
        instance = bridge(hwnd=77)
        instance._layer_layout_active = True
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEMOVE, mouse_event(200, 150)))
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(200, 150)))
        self.assertFalse(instance._forward_mouse(instance.WM_RBUTTONDOWN, mouse_event(200, 150)))
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEWHEEL, mouse_event(200, 150, 120 << 16)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_layer_input_does_not_depend_on_window_from_point(self):
        instance = bridge(hwnd=77)
        instance._layer_layout_active = True
        instance.user32.WindowFromPoint = lambda _point: 88
        self.assertFalse(instance._forward_mouse(instance.WM_RBUTTONDOWN, mouse_event(200, 150)))
        instance.user32.WindowFromPoint = lambda _point: 77
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(200, 150)))
        self.assertTrue(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(600, 400)))
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONUP, mouse_event(600, 400)))

    def test_native_click_temporarily_raises_input_then_restores_visual_order(self):
        instance = bridge(hwnd=77)
        instance._layer_layout_active = True
        instance._layer_order = [88, 77]
        instance._layer_windows = {77: (), 88: ()}
        instance.user32.z_stack = [88, 77]
        visible = {99: True}
        clicks = []
        instance.user32.ShowWindow = lambda hwnd, command: visible.__setitem__(hwnd, command != instance.SW_HIDE)
        instance.user32.WindowFromPoint = lambda _point: 99 if visible[99] else instance.user32.z_stack[0]
        instance.user32.mouse_event = lambda *args: clicks.append(args)

        instance._post_native_at_point(77, instance.WM_LBUTTONDOWN, 200, 150, 0)

        self.assertEqual(1, len(clicks))
        self.assertTrue(visible[99])
        self.assertEqual([77, 88], instance.user32.z_stack)

    def test_foreground_input_change_does_not_change_visual_top(self):
        instance = bridge(hwnd=88)
        layers = [
            {"hwnd": 77, "x": 100, "y": 100, "width": 400, "height": 200, "opacity": 65},
            {"hwnd": 88, "x": 100, "y": 100, "width": 400, "height": 200, "opacity": 80},
        ]
        instance.set_layer_layout(layers)
        self.assertEqual([88, 77], instance.user32.z_stack)
        before_alpha = list(instance.user32.alphas)
        instance.user32.SetForegroundWindow(88)
        self.assertEqual([88, 77], instance.user32.z_stack)
        instance._update_layer_input()
        self.assertEqual([88, 77], instance.user32.z_stack)
        self.assertEqual(before_alpha, instance.user32.alphas)
        instance.set_layer_layout(list(reversed(layers)))
        self.assertEqual([88, 77], instance.user32.z_stack)
        instance.user32.SetForegroundWindow(77)
        instance._mouse_target_hwnd = 77
        instance._update_layer_input()
        self.assertEqual([77, 88], instance.user32.z_stack)
    def test_aligned_window_uses_native_mouse_and_restores_original_bounds(self):
        instance = bridge()
        instance._align_target_window(77, (100, 100, 400, 200))
        self.assertEqual((77, 100, 100, 400, 200), instance.user32.placements[0][:5])
        self.assertTrue(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(300, 200)))
        retry = instance._mouse_jobs.get_nowait()
        self.assertEqual((77, instance.WM_LBUTTONDOWN, 300, 200), retry[:4])
        self.assertTrue(retry[7])
        instance._restore_aligned_window()
        self.assertEqual((77, 1000, 500, 1000, 1000), instance.user32.placements[-1][:5])
    def test_missing_mouse_hook_still_enables_click_through(self):
        instance = object.__new__(WindowsInputBridge)
        instance._exclusive_input = False
        instance._original_style = None
        instance._overlay_hwnd = 0
        instance._mouse_hook = None
        instance._find_largest_visible_app_window = lambda: 0
        instance._register_hotkeys = lambda *_args: None
        click_through_calls = []
        errors = []
        instance._set_click_through = click_through_calls.append
        instance.emit = errors.append
        instance.set_multitask(True, True)
        self.assertTrue(instance._exclusive_input)
        self.assertEqual([True], click_through_calls)
        self.assertTrue(any("native desktop mouse input remains responsive" in error["message"] for error in errors))

    def test_pointer_can_leave_preview_without_blocking_motion(self):
        instance = bridge()
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEMOVE, mouse_event(700, 500)))

    def test_pointer_moves_inside_window_source_without_hook_suppression(self):
        instance = bridge()
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEMOVE, mouse_event(300, 200)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_outside_click_and_scroll_pass_through_to_desktop(self):
        instance = bridge()
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(700, 500)))
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONUP, mouse_event(700, 500)))
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEWHEEL, mouse_event(700, 500, 120 << 16)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_click_inside_without_a_valid_target_does_not_leak(self):
        instance = bridge(hwnd=0)
        self.assertTrue(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(300, 200)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_outside_started_drag_remains_a_desktop_drag(self):
        instance = bridge()
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(700, 500)))
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEMOVE, mouse_event(300, 200)))
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONUP, mouse_event(300, 200)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_unaligned_window_does_not_warp_or_replay_click(self):
        instance = bridge()
        self.assertTrue(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(300, 200)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_unaligned_window_blocks_inside_buttons_without_replay(self):
        instance = bridge()
        self.assertTrue(instance._forward_mouse(instance.WM_RBUTTONDOWN, mouse_event(300, 200)))
        self.assertTrue(instance._forward_mouse(instance.WM_RBUTTONUP, mouse_event(300, 200)))
        self.assertTrue(instance._forward_mouse(instance.WM_MOUSEWHEEL, mouse_event(300, 200, 120 << 16)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_display_capture_uses_native_mouse_passthrough(self):
        instance = bridge(hwnd=0, preview=(100, 100, 960, 540), desktop=True)
        for message in (instance.WM_MOUSEMOVE, instance.WM_LBUTTONDOWN, instance.WM_MOUSEWHEEL):
            self.assertFalse(instance._forward_mouse(message, mouse_event(580, 370, 120 << 16)))
        self.assertTrue(instance._mouse_jobs.empty())

    def test_window_input_posts_to_child_not_screen_hit_test(self):
        instance = bridge()
        instance._post_window_mouse(77, instance.WM_LBUTTONDOWN, 1500, 1000, 0, 0, instance.MK_LBUTTON)
        child, message, wparam, lparam = instance.user32.posted[0]
        self.assertEqual((88, instance.WM_LBUTTONDOWN, instance.MK_LBUTTON), (child, message, wparam))
        self.assertEqual(instance._pack_point(300, 300), lparam)

    def test_drag_keeps_the_original_child_target(self):
        instance = bridge()
        pressed_target = instance._post_window_mouse(77, instance.WM_LBUTTONDOWN, 1500, 1000, 0, 0, instance.MK_LBUTTON)
        dragged_target = instance._post_window_mouse(77, instance.WM_MOUSEMOVE, 1700, 1100, 0, 0,
                                                     instance.MK_LBUTTON, pressed_target)
        released_target = instance._post_window_mouse(77, instance.WM_LBUTTONUP, 1700, 1100, 0, 0, 0,
                                                      pressed_target)
        self.assertEqual((88, 88, 88), (pressed_target, dragged_target, released_target))

    def test_window_hover_updates_cursor_without_warping_it(self):
        instance = bridge()
        instance._post_window_mouse(77, instance.WM_MOUSEMOVE, 1500, 1000, 0, 0, 0)
        self.assertEqual(88, instance.user32.posted[0][0])
        self.assertEqual(instance.WM_MOUSEMOVE, instance.user32.posted[0][1])
        self.assertEqual(instance.WM_SETCURSOR, instance.user32.posted[1][1])

    def test_drag_keeps_routing_when_pointer_leaves_preview(self):
        instance = bridge(hwnd=77, preview=(100, 100, 400, 200))
        self.assertTrue(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(300, 200)))
        self.assertFalse(instance._forward_mouse(instance.WM_MOUSEMOVE, mouse_event(700, 500)))
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONUP, mouse_event(700, 500)))

    def test_exit_control_region_is_left_for_overlay(self):
        instance = bridge(excluded=(480, 0, 60, 38))
        self.assertFalse(instance._forward_mouse(instance.WM_LBUTTONDOWN, mouse_event(500, 20)))
        self.assertTrue(instance._mouse_jobs.empty())


if __name__ == "__main__":
    unittest.main()

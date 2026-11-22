import threading
import time
import unittest

from multimin_backend.frame_stream import FrameStream
from multimin_backend.service import CaptureService

from test_mouse_routing import FakeUser32, bridge


class LayerPlacementTests(unittest.TestCase):
    """set_layer_layout: keep_size items and the alignment hand-off."""

    def layer_bridge(self):
        instance = bridge(hwnd=77)
        instance._exclusive_input = True
        instance._layer_windows = {}
        instance._layer_order = []
        instance._aligned_window = None
        # Input-hole and z-order reassertion are covered elsewhere; only placement matters here.
        instance._update_layer_input = lambda: None
        return instance

    def placements_for(self, instance, hwnd):
        return [p for p in instance.user32.placements if p[0] == hwnd]

    def test_keep_size_item_moves_without_resizing(self):
        instance = self.layer_bridge()
        instance.set_layer_layout([
            {"hwnd": 77, "x": 120, "y": 80, "width": 640, "height": 360, "opacity": 100, "keep_size": True},
        ])
        placements = self.placements_for(instance, 77)
        self.assertEqual(1, len(placements))
        hwnd, x, y, _width, _height, flags = placements[0]
        self.assertEqual((120, 80), (x, y))
        self.assertTrue(flags & instance.SWP_NOSIZE, f"SWP_NOSIZE missing from flags {flags:#x}")
        self.assertEqual(0x0001, instance.SWP_NOSIZE)
        self.assertTrue(flags & instance.SWP_NOZORDER)

    def test_item_without_keep_size_passes_size_and_no_nosize_flag(self):
        instance = self.layer_bridge()
        instance.set_layer_layout([
            {"hwnd": 77, "x": 120, "y": 80, "width": 640, "height": 360, "opacity": 100},
        ])
        placements = self.placements_for(instance, 77)
        self.assertEqual(1, len(placements))
        _hwnd, x, y, width, height, flags = placements[0]
        self.assertEqual((120, 80, 640, 360), (x, y, width, height))
        self.assertFalse(flags & instance.SWP_NOSIZE, f"SWP_NOSIZE unexpectedly set in {flags:#x}")

    def test_keep_size_false_is_treated_like_absent(self):
        instance = self.layer_bridge()
        instance.set_layer_layout([
            {"hwnd": 77, "x": 10, "y": 20, "width": 300, "height": 200, "opacity": 100, "keep_size": False},
        ])
        _hwnd, _x, _y, width, height, flags = self.placements_for(instance, 77)[0]
        self.assertEqual((300, 200), (width, height))
        self.assertFalse(flags & instance.SWP_NOSIZE)

    def test_aligned_window_original_bounds_become_layer_restore_rect(self):
        instance = self.layer_bridge()
        instance._aligned_window = (77, (50, 50, 980, 580))
        instance.set_layer_layout([
            {"hwnd": 77, "x": 300, "y": 200, "width": 640, "height": 360, "opacity": 100},
        ])
        self.assertEqual((50, 50, 980, 580), instance._layer_windows[77][0])
        self.assertIsNone(instance._aligned_window)

    def test_aligned_window_for_other_hwnd_is_left_alone(self):
        instance = self.layer_bridge()
        instance._aligned_window = (88, (50, 50, 980, 580))
        instance.set_layer_layout([
            {"hwnd": 77, "x": 300, "y": 200, "width": 640, "height": 360, "opacity": 100},
        ])
        self.assertEqual((88, (50, 50, 980, 580)), instance._aligned_window)
        self.assertNotEqual((50, 50, 980, 580), instance._layer_windows[77][0])


class FirstFrameWatchdogTests(unittest.TestCase):
    """service._check_first_frame: restart stalled WGC sessions with a capped allowance."""

    class FakeFrameStream:
        def __init__(self, has):
            self.has = has

        def has_frame(self, _source):
            return self.has

    class FakeNativeInput:
        def __init__(self, iconic=0):
            self.user32 = FakeUser32()
            self.user32.IsIconic = lambda _hwnd: iconic

    def make_service(self, *, has_frame=False, iconic=0, deadline=None, timeout=1.2):
        service = CaptureService.__new__(CaptureService)
        service.frame_stream = self.FakeFrameStream(has_frame)
        service.native_input = self.FakeNativeInput(iconic)
        service.capture_lock = threading.RLock()
        service.graphics_retry_after = {"win": 99.0}
        service.graphics_captures = {}
        service.graphics_starting = set()
        service.graphics_first_frame_deadline = {}
        if deadline is not None:
            service.graphics_first_frame_deadline["win"] = (deadline, timeout)
        stops = []
        service._stop_graphics_capture = stops.append
        service.stops = stops
        return service

    def test_expired_deadline_without_frame_stops_and_doubles_timeout(self):
        service = self.make_service(deadline=time.monotonic() - 1.0, timeout=1.2)
        service._check_first_frame("win", 77, "WGC")
        self.assertEqual(["win"], service.stops)
        self.assertEqual((0.0, 2.4), service.graphics_first_frame_deadline["win"])
        self.assertNotIn("win", service.graphics_retry_after)

    def test_minimized_window_is_not_stopped_and_deadline_moves_forward(self):
        service = self.make_service(iconic=1, deadline=time.monotonic() - 1.0, timeout=1.2)
        before = time.monotonic()
        service._check_first_frame("win", 77, "WGC")
        self.assertEqual([], service.stops)
        deadline, timeout = service.graphics_first_frame_deadline["win"]
        self.assertGreater(deadline, before)
        self.assertEqual(1.2, timeout)

    def test_monitor_capture_ignores_iconic_state(self):
        service = self.make_service(iconic=1, deadline=time.monotonic() - 1.0, timeout=1.2)
        service._check_first_frame("win", 0, "MONITOR")
        self.assertEqual(["win"], service.stops)
        self.assertEqual((0.0, 2.4), service.graphics_first_frame_deadline["win"])

    def test_frame_arrival_removes_watch_and_does_not_stop(self):
        service = self.make_service(has_frame=True, deadline=time.monotonic() - 1.0, timeout=1.2)
        service._check_first_frame("win", 77, "WGC")
        self.assertNotIn("win", service.graphics_first_frame_deadline)
        self.assertEqual([], service.stops)

    def test_future_deadline_does_nothing(self):
        deadline = time.monotonic() + 60.0
        service = self.make_service(deadline=deadline, timeout=1.2)
        service._check_first_frame("win", 77, "WGC")
        self.assertEqual([], service.stops)
        self.assertEqual((deadline, 1.2), service.graphics_first_frame_deadline["win"])

    def test_no_watch_entry_is_a_no_op(self):
        service = self.make_service()
        service._check_first_frame("win", 77, "WGC")
        self.assertEqual([], service.stops)
        self.assertEqual({}, service.graphics_first_frame_deadline)

    def test_restart_allowance_caps_at_ten_seconds(self):
        service = self.make_service(deadline=time.monotonic() - 1.0, timeout=8.0)
        service._check_first_frame("win", 77, "WGC")
        self.assertEqual((0.0, 10.0), service.graphics_first_frame_deadline["win"])

    def test_restart_allowance_keeps_growing_until_cap(self):
        service = self.make_service(deadline=time.monotonic() - 1.0, timeout=2.4)
        service._check_first_frame("win", 77, "WGC")
        self.assertEqual((0.0, 4.8), service.graphics_first_frame_deadline["win"])


class FrameStreamHasFrameTests(unittest.TestCase):
    def setUp(self):
        # The real constructor binds a loopback socket and starts a thread; close() releases both.
        self.stream = FrameStream()
        self.addCleanup(self.stream.close)

    def test_has_frame_reflects_publish_and_remove(self):
        self.assertFalse(self.stream.has_frame("win"))
        self.stream.publish("win", 1, 1, b"\x00\x00\x00\xff")
        self.assertTrue(self.stream.has_frame("win"))
        self.assertFalse(self.stream.has_frame("other"))
        self.stream.remove("win")
        self.assertFalse(self.stream.has_frame("win"))

    def test_rejected_frame_does_not_count_as_a_first_frame(self):
        self.stream.publish("win", 2, 2, b"\x00\x00\x00\xff")  # wrong byte count
        self.assertFalse(self.stream.has_frame("win"))


if __name__ == "__main__":
    unittest.main()

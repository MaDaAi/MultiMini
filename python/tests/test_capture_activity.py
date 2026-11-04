import threading
import unittest
from multimin_backend.service import CaptureService


class CaptureActivityTests(unittest.TestCase):
    def make_service(self):
        service = CaptureService.__new__(CaptureService)
        service.capture_lock = threading.RLock()
        service.graphics_captures = {'visible': object(), 'hidden': object()}
        service.window_captures = {
            'visible': ('Visible', 'v', 1, 'WGC', False),
            'hidden': ('Hidden', 'h', 2, 'AUTO', False),
        }
        service.active_window_paths = None
        service.graphics_retry_after = {}
        service.stopped = []
        service.started = []
        service._stop_graphics_capture = lambda path: service.stopped.append(path)
        service._start_graphics_capture = lambda path, hwnd, cursor: service.started.append(path)
        return service

    def test_hiding_a_source_stops_capture_and_rate_change_does_not_restart_it(self):
        service = self.make_service()
        service.dispatch({'command': 'active_window_captures', 'paths': ['visible']})
        self.assertEqual(service.stopped, ['hidden'])
        service.dispatch({'command': 'set_capture_fps', 'value': 144})
        self.assertEqual(service.started, ['visible'])

    def test_refresh_rate_above_240_is_not_silently_capped(self):
        service = self.make_service()
        service.dispatch({'command': 'set_capture_fps', 'value': 360})
        self.assertEqual(service.capture_fps, 360)

    def test_native_display_registration_keeps_monitor_target_separate_from_hwnd(self):
        service = self.make_service()
        service.native_input = object()
        calls = []
        service._start_graphics_capture = lambda *args, **kwargs: calls.append((args, kwargs))
        result = service.dispatch({'command': 'register_display_capture', 'path': 'display', 'monitor': 1})
        self.assertEqual(result['event'], 'display_capture_registered')
        self.assertEqual(service.window_captures['display'][3], 'MONITOR')
        self.assertEqual(calls[0][1], {'monitor_index': 1})

    def test_resuming_capture_clears_retry_delay_left_by_native_pause(self):
        service=self.make_service()
        service.active_window_paths=set()
        service.graphics_retry_after['visible']=float('inf')
        service.dispatch({'command':'active_window_captures','paths':['visible']})
        self.assertNotIn('visible',service.graphics_retry_after)


if __name__ == '__main__':
    unittest.main()

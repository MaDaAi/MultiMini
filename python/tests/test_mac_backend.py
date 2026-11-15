"""Platform-independent checks for the untested macOS backend (no PyObjC needed)."""
import tempfile
import unittest
from unittest.mock import patch

from multimin_backend import mac_input
from multimin_backend.mac_service import MacService


class MacBackendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.events = []
        with patch("multimin_backend.mac_service.SnapshotStore"):
            self.service = MacService(self.events.append)

    def tearDown(self):
        self.service.dispatch({"command": "shutdown"})
        self.temp.cleanup()

    def test_keysym_mapping(self):
        self.assertEqual(mac_input.keycode_for(ord("a")), 0x00)
        self.assertEqual(mac_input.keycode_for(ord("A")), 0x00)
        self.assertEqual(mac_input.keycode_for(ord("!")), 0x12)
        self.assertEqual(mac_input.keycode_for(0xff0d), 0x24)
        self.assertEqual(mac_input.keycode_for(0xffbe), 0x7A)
        self.assertIsNone(mac_input.keycode_for(0x1234567))

    def test_point_mapping_scales_into_window(self):
        self.assertEqual(mac_input.map_to_window(150, 100, (100, 50, 200, 100), (10, 20, 400, 200)), (110.0, 120.0))
        self.assertIsNone(mac_input.map_to_window(10, 10, (100, 50, 200, 100), (0, 0, 1, 1)))

    def test_configure_does_not_enable_input(self):
        self.service.dispatch({"command": "set_input_target", "hwnd": 42, "x": 0, "y": 0, "width": 10, "height": 10})
        self.assertFalse(self.service.enabled)
        self.assertEqual(self.service.input_mode, "unavailable")

    def test_opacity_independent_of_input_and_layers(self):
        self.service.dispatch({"command": "set_opacity", "value": 37})
        self.service.dispatch({"command": "set_input_target", "hwnd": 42})
        self.service.dispatch({"command": "set_layer_layout", "items": []})
        self.assertEqual(self.service.opacity, 37)

    def test_display_input_reports_unavailable(self):
        self.service.dispatch({"command": "set_multitask_overlay", "enabled": True})
        self.service.dispatch({"command": "set_input_target", "hwnd": 0, "desktop_input": True})
        self.assertEqual(self.service.input_mode, "unavailable")

    def test_retina_scale_converts_to_points(self):
        self.service.dispatch({"command": "linux_coordinate_scale", "x": 2, "y": 2})
        self.assertEqual(self.service._points((200, 100, 400, 300)), (100.0, 50.0, 200.0, 150.0))

    def test_background_limit_and_unknown_command(self):
        self.assertEqual(self.service.dispatch({"command": "linux_background_capture", "path": "p", "fps": 12})["fps"], 12)
        self.assertEqual(self.service._effective_fps("p"), 12)
        self.assertEqual(self.service.dispatch({"command": "nope"})["event"], "error")


if __name__ == "__main__":
    unittest.main()

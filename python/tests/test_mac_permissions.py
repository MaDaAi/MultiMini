import unittest
from unittest.mock import patch

from multimin_backend import mac_permissions
from multimin_backend.mac_service import MacService


class MacPermissionTests(unittest.TestCase):
    def test_settings_urls_point_at_exact_lists(self):
        self.assertTrue(mac_permissions.settings_url("screen").endswith("?Privacy_ScreenCapture"))
        self.assertTrue(mac_permissions.settings_url("accessibility").endswith("?Privacy_Accessibility"))
        self.assertTrue(mac_permissions.settings_url("input").endswith("?Privacy_ListenEvent"))
        with self.assertRaises(ValueError):
            mac_permissions.request("camera", opener=lambda url: None)

    def test_request_opens_settings_even_without_quartz(self):
        opened = []
        with patch("multimin_backend.mac_permissions._quartz", return_value=None), \
             patch("multimin_backend.mac_input.request_accessibility", return_value=False):
            mac_permissions.request("accessibility", opener=opened.append)
        self.assertEqual(opened, [mac_permissions.settings_url("accessibility")])

    def test_service_reports_status(self):
        with patch("multimin_backend.mac_service.SnapshotStore"):
            service = MacService(lambda e: None)
        try:
            with patch("multimin_backend.mac_permissions.status", return_value={"screen": True, "accessibility": False, "input": True}):
                reply = service.dispatch({"command": "mac_permissions"})
            self.assertEqual(reply, {"event": "mac_permissions", "screen": True, "accessibility": False, "input": True})
        finally:
            service.dispatch({"command": "shutdown"})

    def test_missing_accessibility_asks_java_to_open_window(self):
        events = []
        with patch("multimin_backend.mac_service.SnapshotStore"):
            service = MacService(events.append)
        try:
            with patch("multimin_backend.mac_input.accessibility_trusted", return_value=False), \
                 patch("multimin_backend.mac_input.request_accessibility", return_value=False):
                service.dispatch({"command": "set_multitask_overlay", "enabled": True, "app_pid": 5})
            self.assertIn({"event": "mac_permissions_needed"}, events)
        finally:
            service.dispatch({"command": "shutdown"})


if __name__ == "__main__":
    unittest.main()

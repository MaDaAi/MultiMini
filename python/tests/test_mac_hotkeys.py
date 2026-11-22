import unittest
from unittest.mock import patch

from multimin_backend import mac_hotkeys as hk
from multimin_backend.mac_service import MacService


class MacHotkeyTests(unittest.TestCase):
    def test_parse_combinations_and_modifier_keys(self):
        b = hk.parse_binding("toggle_multitask", "CTRL+ALT+E")
        self.assertEqual((b.keycode, b.flags, b.modifier_only), (0x0E, hk.CONTROL | hk.ALTERNATE, False))
        r = hk.parse_binding("quick_swap", "RIGHT_ALT")
        self.assertEqual((r.keycode, r.flags, r.modifier_only), (0x3D, hk.ALTERNATE, True))
        self.assertEqual(hk.parse_binding("x", "CMD+SHIFT+F12").flags, hk.COMMAND | hk.SHIFT)
        self.assertEqual(hk.parse_binding("x", "END").keycode, 0x77)
        self.assertEqual(hk.parse_binding("x", "PAUSE").keycode, 0x71)   # F15 stand-in
        self.assertIsNone(hk.parse_binding("x", "WEIRD+K"))
        self.assertIsNone(hk.parse_binding("x", "⌘"))
        self.assertIsNone(hk.parse_binding("x", ""))

    def test_match_exact_modifiers_no_repeat(self):
        bindings = [hk.parse_binding("toggle_multitask", "CTRL+ALT+E"), hk.parse_binding("quick_swap", "RIGHT_ALT")]
        flags = hk.CONTROL | hk.ALTERNATE | 0x100  # device bits are ignored
        self.assertEqual(hk.match(bindings, hk.KEY_DOWN, 0x0E, flags), "toggle_multitask")
        self.assertIsNone(hk.match(bindings, hk.KEY_DOWN, 0x0E, hk.CONTROL))          # missing Option
        self.assertIsNone(hk.match(bindings, hk.KEY_DOWN, 0x0E, flags, autorepeat=True))
        self.assertIsNone(hk.match(bindings, hk.KEY_DOWN, 0x0E, flags | hk.SHIFT))    # extra modifier
        self.assertEqual(hk.match(bindings, hk.FLAGS_CHANGED, 0x3D, hk.ALTERNATE), "quick_swap")
        self.assertIsNone(hk.match(bindings, hk.FLAGS_CHANGED, 0x3D, 0))              # Right Option released
        self.assertIsNone(hk.match(bindings, hk.FLAGS_CHANGED, 0x3A, hk.ALTERNATE))   # Left Option
        self.assertTrue(hk.swallows_release(bindings, hk.KEY_UP, 0x0E))
        self.assertFalse(hk.swallows_release(bindings, hk.KEY_UP, 0x00))

    def test_service_tracks_bindings_and_needs_accessibility(self):
        events = []
        with patch("multimin_backend.mac_service.SnapshotStore"):
            service = MacService(events.append)
        try:
            with patch("multimin_backend.mac_input.accessibility_trusted", return_value=False), \
                 patch("multimin_backend.mac_input.request_accessibility", return_value=False):
                service.dispatch({"command": "set_multitask_overlay", "enabled": True, "app_pid": 5,
                                  "stop_key": "CTRL+ALT+X", "swap_key": "RIGHT_ALT", "layer_swap_key": "F5"})
            self.assertEqual(service.keys["toggle_multitask"], "CTRL+ALT+X")
            self.assertEqual(service.keys["layer_swap"], "F5")
            self.assertIsNone(service.key_tap)
            self.assertTrue(any("Accessibility" in e.get("message", "") for e in events))
            service.dispatch({"command": "screenshot_shortcuts", "full": "F6", "region": "CTRL+ALT+R"})
            self.assertEqual(service.keys["screenshot_full"], "F6")
        finally:
            service.dispatch({"command": "shutdown"})

    def test_hotkeys_only_emit_while_multitasking_and_debounced(self):
        events = []
        with patch("multimin_backend.mac_service.SnapshotStore"):
            service = MacService(events.append)
        try:
            service._hotkey_emit("quick_swap")
            self.assertEqual(events, [])
            service.enabled = True
            service._hotkey_emit("quick_swap"); service._hotkey_emit("quick_swap")
            self.assertEqual([e for e in events if e.get("event") == "quick_swap"], [{"event": "quick_swap"}])
        finally:
            service.dispatch({"command": "shutdown"})


if __name__ == "__main__":
    unittest.main()

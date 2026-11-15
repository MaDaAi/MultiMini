import sys
import types
import unittest
from unittest.mock import patch

from multimin_backend.linux_hotkeys import accelerator, is_autorepeat_release, normalized_state


class LinuxHotkeyTranslationTests(unittest.TestCase):
    def setUp(self):
        self.X = types.SimpleNamespace(
            ControlMask=4, ShiftMask=1, Mod1Mask=8, Mod4Mask=64,
            LockMask=2, Mod2Mask=16, KeyPress=2, KeyRelease=3,
            Button1Mask=1 << 8, Button2Mask=1 << 9, Button3Mask=1 << 10,
            Button4Mask=1 << 11, Button5Mask=1 << 12)
        names = {
            "Control_R": 0xffe4, "Control_L": 0xffe3, "Alt_R": 0xffea,
            "Alt_L": 0xffe9, "Shift_R": 0xffe2, "Shift_L": 0xffe1,
            "End": 0xff57, "Pause": 0xff13, "Insert": 0xff63,
            "Escape": 0xff1b, "space": 0x20, "Prior": 0xff55,
            "Next": 0xff56, "BackSpace": 0xff08, "Delete": 0xffff,
            "F12": 0xffc9, "a": ord('a')}
        XK = types.SimpleNamespace(string_to_keysym=lambda name: names.get(name, 0))
        xlib = types.ModuleType("Xlib")
        xlib.X, xlib.XK = self.X, XK
        self.modules = patch.dict(sys.modules, {"Xlib": xlib})
        self.modules.start()

    def tearDown(self):
        self.modules.stop()

    def test_modifier_and_alias_translation(self):
        self.assertEqual(accelerator("CTRL+SHIFT+F12"), (0xffc9, 4 | 1))
        self.assertEqual(accelerator("RIGHT_CTRL"), (0xffe4, 0))
        self.assertEqual(accelerator("ALT+PAUSE"), (0xff13, 8))
        self.assertEqual(accelerator("CTRL+a"), (ord('a'), 4))

    def test_empty_and_unsupported_shortcuts(self):
        self.assertIsNone(accelerator(""))
        with self.assertRaisesRegex(ValueError, "Unsupported shortcut modifier"):
            accelerator("CTRL+META+F12")
        with self.assertRaisesRegex(ValueError, "Unsupported shortcut key"):
            accelerator("CTRL+NOT_A_KEY")

    def test_normalized_state_ignores_lock_and_button_bits(self):
        state = 4 | 2 | 16 | (1 << 8) | (1 << 9)
        self.assertEqual(normalized_state(state, self.X), 4)

    def test_autorepeat_pair_does_not_look_like_a_real_release(self):
        release = types.SimpleNamespace(type=self.X.KeyRelease, detail=38, time=123)
        repeated_press = types.SimpleNamespace(type=self.X.KeyPress, detail=38, time=123)
        actual_press = types.SimpleNamespace(type=self.X.KeyPress, detail=38, time=124)
        self.assertTrue(is_autorepeat_release(release, repeated_press, self.X))
        self.assertFalse(is_autorepeat_release(release, actual_press, self.X))
        self.assertFalse(is_autorepeat_release(release, None, self.X))


if __name__ == "__main__":
    unittest.main()

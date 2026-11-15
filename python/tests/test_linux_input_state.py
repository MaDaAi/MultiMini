import sys
import types
import unittest
from unittest.mock import patch

from multimin_backend.linux_x11 import X11, map_point


class FakeEvent:
    def __init__(self, **fields):
        self.fields = fields


class FakeWindow:
    def __init__(self, xid):
        self.xid = xid
        self.sent = []
    def get_geometry(self):
        return types.SimpleNamespace(width=200, height=100)
    def send_event(self, item, **kwargs):
        self.sent.append((item, kwargs))


class FakeDisplay:
    def __init__(self):
        self.windows = {}
        self.flushed = 0
    def create_resource_object(self, kind, xid):
        return self.windows.setdefault(int(xid), FakeWindow(int(xid)))
    def keysym_to_keycode(self, keysym):
        return {ord('a'): 38, ord('b'): 56}.get(keysym, 0)
    def flush(self):
        self.flushed += 1
    def close(self):
        pass


def fake_event_module():
    names = ("KeyPress", "KeyRelease", "ButtonPress", "ButtonRelease", "MotionNotify")
    return types.SimpleNamespace(**{name: type(name, (FakeEvent,), {}) for name in names})


class LinuxInputStateTests(unittest.TestCase):
    def setUp(self):
        self.display = FakeDisplay()
        self.x11 = X11.__new__(X11)
        self.x11.X = types.SimpleNamespace(
            CurrentTime=0, NONE=0, KeyPressMask=1, KeyReleaseMask=2,
            ButtonPressMask=4, ButtonReleaseMask=8, PointerMotionMask=16)
        self.x11.display = self.display
        self.x11.root = object()
        self.x11.target = 0
        self.x11.rect = (0, 0, 0, 0)
        self.x11._held_keys = {}
        self.x11._held_buttons = {}
        self.events = types.ModuleType("Xlib.protocol.event")
        for name in ("KeyPress", "KeyRelease", "ButtonPress", "ButtonRelease", "MotionNotify"):
            setattr(self.events, name, getattr(fake_event_module(), name))
        self.modules = patch.dict(sys.modules, {
            "Xlib": types.ModuleType("Xlib"),
            "Xlib.protocol": types.ModuleType("Xlib.protocol"),
            "Xlib.protocol.event": self.events,
        })
        self.modules.start()

    def tearDown(self):
        self.modules.stop()

    def test_map_point_rejects_outside_and_maps_inside(self):
        rect = (100, 50, 400, 200)
        self.assertEqual(map_point(300, 150, rect, (200, 100)), (100, 50))
        self.assertIsNone(map_point(99, 150, rect, (200, 100)))
        self.assertIsNone(map_point(100, 250, rect, (200, 100)))
        self.assertIsNone(map_point(500, 100, rect, (200, 100)))

    def test_press_outside_rejected_but_held_release_outside_sent(self):
        rect = (10, 20, 400, 200)
        self.assertFalse(self.x11.send(7, {"kind": "press", "button": 1, "x": 9, "y": 30}, rect))
        self.assertFalse(self.x11.send(7, {"kind": "motion", "x": 500, "y": 30}, rect))
        self.assertTrue(self.x11.send(7, {"kind": "press", "button": 1, "x": 20, "y": 30}, rect))
        self.assertTrue(self.x11.send(7, {"kind": "release", "button": 1, "x": 900, "y": 900}, rect))
        sent = self.display.windows[7].sent
        self.assertEqual([entry[0].__class__.__name__ for entry in sent], ["ButtonPress", "ButtonRelease"])
        self.assertEqual(sent[0][0].fields["root_x"], 20)
        self.assertEqual(sent[0][0].fields["event_x"], 5)
        self.assertEqual(sent[1][0].fields["root_x"], 900)
        self.assertEqual(sent[1][0].fields["root_y"], 900)

    def test_release_mask_is_state_before_release_including_all_held_buttons(self):
        rect = (0, 0, 100, 100)
        self.x11.send(7, {"kind": "press", "button": 1, "x": 10, "y": 10}, rect)
        self.x11.send(7, {"kind": "press", "button": 2, "x": 10, "y": 10}, rect)
        self.x11.send(7, {"kind": "release", "button": 1, "x": 20, "y": 20}, rect)
        release = self.display.windows[7].sent[-1][0]
        self.assertEqual(release.__class__.__name__, "ButtonRelease")
        self.assertEqual(release.fields["state"], (1 << 8) | (1 << 9))

    def test_state_masks_include_modifiers_and_already_held_button(self):
        self.x11.send(7, {"kind": "press", "button": 1, "x": 20, "y": 30, "state": 1 | 4 | 8 | 64}, (0, 0, 100, 100))
        event = self.display.windows[7].sent[-1][0]
        self.assertEqual(event.fields["state"], 1 | 4 | 8 | 64)
        self.x11.send(7, {"kind": "press", "button": 3, "x": 20, "y": 30}, (0, 0, 100, 100))
        self.assertEqual(self.display.windows[7].sent[-1][0].fields["state"], 1 << 8)

    def test_target_change_releases_held_keys_and_buttons_to_old_target(self):
        self.x11.set_target(7, (0, 0, 100, 100))
        self.x11.send(7, {"kind": "key_press", "keysym": ord('a')}, (0, 0, 100, 100))
        self.x11.send(7, {"kind": "press", "button": 2, "x": 20, "y": 30}, (0, 0, 100, 100))
        self.x11.set_target(8, (100, 100, 100, 100))
        old = self.display.windows[7].sent
        self.assertEqual([entry[0].__class__.__name__ for entry in old],
                         ["KeyPress", "ButtonPress", "KeyRelease", "ButtonRelease"])
        self.assertEqual(self.display.windows.get(8, FakeWindow(8)).sent, [])
        self.assertFalse(self.x11._held_keys)
        self.assertFalse(self.x11._held_buttons)

    def test_close_releases_held_inputs_before_closing_display(self):
        self.x11.send(7, {"kind": "key_press", "keysym": ord('a')}, (0, 0, 100, 100))
        self.x11.close()
        self.assertEqual(self.display.windows[7].sent[-1][0].__class__.__name__, "KeyRelease")


if __name__ == "__main__":
    unittest.main()

import math
import json
import threading
import types
import unittest

from multimin_backend.linux_kwin import KWin


class KWinInputValidationTests(unittest.TestCase):
    def test_rect_accepts_finite_coordinates_and_positive_size(self):
        rect = {"x": -10, "y": 20.5, "width": 640, "height": 480}
        self.assertEqual(KWin._rect_value(rect), rect)

    def test_rect_rejects_missing_or_non_numeric_coordinates(self):
        with self.assertRaises(ValueError):
            KWin._rect_value({"x": 0, "y": 0, "width": 10})
        with self.assertRaises(ValueError):
            KWin._rect_value({"x": "0", "y": 0, "width": 10, "height": 10})
        with self.assertRaises(ValueError):
            KWin._rect_value({"x": True, "y": 0, "width": 10, "height": 10})

    def test_rect_rejects_non_finite_coordinates_and_non_positive_size(self):
        for invalid in (math.nan, math.inf, -math.inf):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    KWin._rect_value({"x": invalid, "y": 0, "width": 10, "height": 10})
        for width, height in ((0, 10), (-1, 10), (10, 0), (10, -1)):
            with self.subTest(width=width, height=height):
                with self.assertRaises(ValueError):
                    KWin._rect_value({"x": 0, "y": 0, "width": width, "height": height})

    def test_uuid_is_escaped_before_embedding_in_script(self):
        js = KWin._find_window_js('uuid"; malicious() //')
        self.assertIn('"uuid\\\"; malicious() //"', js)
        self.assertIn("String(candidate.internalId) === wanted", js)

    def test_reply_ignores_unverified_bus_sender(self):
        adapter = KWin.__new__(KWin)
        adapter._reply_lock = threading.Lock()
        adapter._waiting = {"token": {
            "expected_sender": ":1.20", "value": None, "event": threading.Event()}}
        adapter._glib = types.SimpleNamespace(Variant=lambda signature, value: (signature, value))
        invocation = types.SimpleNamespace(return_value=lambda _value: None)
        params = types.SimpleNamespace(unpack=lambda: ("token", json.dumps({"ok": True})))

        adapter._on_reply(None, ":1.21", "", "", "Reply", params, invocation)
        self.assertFalse(adapter._waiting["token"]["event"].is_set())
        self.assertIsNone(adapter._waiting["token"]["value"])

        adapter._on_reply(None, ":1.20", "", "", "Reply", params, invocation)
        self.assertTrue(adapter._waiting["token"]["event"].is_set())
        self.assertEqual(adapter._waiting["token"]["value"], {"ok": True})


if __name__ == "__main__":
    unittest.main()

import unittest

from multimin_backend.linux_portal import STANDARD_RATES, framerate_caps

try:
    import gi
    gi.require_version("Gst", "1.0")
    from gi.repository import Gst
    Gst.init(None)
except (ImportError, ValueError):
    Gst = None


class FrameRateCapsStringTest(unittest.TestCase):
    def test_one_structure_per_standard_rate_in_descending_order_then_plain_raw(self):
        parts = framerate_caps().split("; ")
        self.assertEqual(len(parts), len(STANDARD_RATES) + 1)
        self.assertEqual(
            parts[:-1],
            [f"video/x-raw,max-framerate=(fraction)[{rate}/1,1000/1]" for rate in STANDARD_RATES],
        )
        self.assertEqual(list(STANDARD_RATES), sorted(STANDARD_RATES, reverse=True))
        self.assertEqual(parts[-1], "video/x-raw")


@unittest.skipIf(Gst is None, "GStreamer 1.0 (gi) is not available")
class FrameRateCapsGstTest(unittest.TestCase):
    def test_caps_parse_to_expected_structure_count(self):
        caps = Gst.Caps.from_string(framerate_caps())
        self.assertEqual(caps.get_size(), len(STANDARD_RATES) + 1)

    def _fixated_rate(self, limit):
        caps = Gst.Caps.from_string(framerate_caps())
        wanted = Gst.Caps.from_string(f"video/x-raw,max-framerate=(fraction)[1/1,{limit}/1]")
        fixed = caps.intersect(wanted)
        fixed.fixate()
        ok, num, den = fixed.get_structure(0).get_fraction("max-framerate")
        self.assertTrue(ok)
        return num, den

    def test_144_hz_limit_negotiates_144(self):
        self.assertEqual(self._fixated_rate(144), (144, 1))

    def test_60_hz_limit_negotiates_60(self):
        self.assertEqual(self._fixated_rate(60), (60, 1))


if __name__ == "__main__":
    unittest.main()

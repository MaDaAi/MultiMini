import struct
import unittest
import zlib

from multimin_backend import thumbnails


class ThumbnailTests(unittest.TestCase):
    def test_png_is_valid_rgb(self):
        png = thumbnails.encode_png(2, 1, bytes([10, 20, 30, 255, 40, 50, 60, 255]))
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        width, height, depth, colour = struct.unpack(">IIBB", png[16:26])
        self.assertEqual((width, height, depth, colour), (2, 1, 8, 2))
        idat = png.index(b"IDAT")
        length = struct.unpack(">I", png[idat - 4:idat])[0]
        self.assertEqual(zlib.decompress(png[idat + 4:idat + 4 + length]), bytes([0, 30, 20, 10, 60, 50, 40]))

    def test_downscale_keeps_aspect(self):
        w, h, data = thumbnails.downscale_bgra(100, 50, bytes(100 * 50 * 4), 20)
        self.assertEqual((w, h, len(data)), (20, 10, 20 * 10 * 4))

    def test_request_limits_and_worker_emits_once_per_handle(self):
        handles, width = thumbnails.thumbnail_request({"hwnds": list(range(100)), "width": 5000})
        self.assertEqual((len(handles), width), (64, 640))
        events, done = [], __import__("threading").Event()
        def emit(event):
            events.append(event)
            if len(events) == 2: done.set()
        thumbnails.thumbnail_worker(emit, lambda h, w: b"png" if h == 1 else None, [1, 2], 320)
        self.assertTrue(done.wait(2))
        self.assertEqual([e["hwnd"] for e in events], [1, 2])
        self.assertTrue(events[0]["png64"]); self.assertEqual(events[1]["png64"], "")


if __name__ == "__main__":
    unittest.main()

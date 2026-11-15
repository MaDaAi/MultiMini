import unittest
from multimin_backend.linux_portal import packed_bgra


class PixelPackingTests(unittest.TestCase):
    def test_packed_bytes_reused_without_copy(self):
        pixels = bytes(range(24))
        self.assertIs(packed_bgra(pixels, 3, 2, 12), pixels)

    def test_padded_rows_and_offset_preserve_pixels(self):
        self.assertEqual(packed_bgra(b'xxabcdefghPADijklmnopPAD', 2, 2, 11, 2), b'abcdefghijklmnop')

    def test_mutable_input_is_owned_copy(self):
        pixels = bytearray(range(8))
        result = packed_bgra(memoryview(pixels), 2, 1, 8)
        pixels[0] = 99
        self.assertEqual(result, bytes(range(8)))

    def test_invalid_layout_rejected(self):
        for width,height,stride,offset in ((0,1,4,0),(2,1,4,0),(1,2,4,-1),(1,3,4,0),(20000,1,80000,0)):
            with self.subTest(layout=(width,height,stride,offset)), self.assertRaises(ValueError):
                packed_bgra(bytes(8), width,height,stride,offset)

import socket
import struct
import unittest

from multimin_backend.frame_stream import FrameStream


class FrameStreamTests(unittest.TestCase):
    def test_latest_frame_replaces_old_frame_and_skips_unchanged_pixels(self):
        stream = FrameStream()
        try:
            stream.publish("source", 1, 1, bytes((1, 2, 3, 255)))
            stream.publish("source", 1, 1, bytes((4, 5, 6, 255)))
            with socket.create_connection(("127.0.0.1", stream.port), timeout=2) as client:
                client.settimeout(2)
                for last_seen, expected_length in ((0, 4), (2, 0)):
                    client.sendall(struct.pack("!H", 6) + b"source" + struct.pack("!Q", last_seen))
                    header = self._read(client, 28)
                    width, height, sequence, timestamp, length = struct.unpack("!IIQQI", header)
                    self.assertEqual((width, height, sequence, length), (1, 1, 2, expected_length))
                    self.assertGreater(timestamp, 0)
                    if length:
                        self.assertEqual(self._read(client, length), bytes((4, 5, 6, 255)))
                stream.remove("source")
                client.sendall(struct.pack("!H", 6) + b"source" + struct.pack("!Q", 2))
                self.assertEqual(struct.unpack("!IIQQI", self._read(client, 28)), (0, 0, 0, 0, 0))
        finally:
            stream.close()

    @staticmethod
    def _read(client, count):
        data = bytearray()
        while len(data) < count:
            data.extend(client.recv(count - len(data)))
        return bytes(data)


if __name__ == "__main__":
    unittest.main()

import socket
import struct
import unittest

from multimin_backend.frame_stream import FrameStream


class FrameStreamTests(unittest.TestCase):
    def test_ready_notification_observes_the_published_frame_without_holding_frame_lock(self):
        seen=[]
        stream=None
        def published():
            with stream._lock:
                seen.append(stream._frames['source'][2])
        stream=FrameStream(published)
        try:
            stream.publish('source',1,1,b'\x00\x01\x02\xff')
            stream.publish('source',1,1,b'\x03\x04\x05\xff')
            self.assertEqual(seen,[1,2])
        finally:
            stream.close()

    def test_coalesced_notifications_rearm_on_read_and_keep_latest_pixels(self):
        notices = []
        stream = FrameStream(lambda: notices.append(True), coalesce_notifications=True)
        try:
            for value in range(100):
                stream.publish("source", 1, 1, bytes((value, 0, 0, 255)))
            self.assertEqual(len(notices), 1)
            with socket.create_connection(("127.0.0.1", stream.port), timeout=2) as client:
                client.settimeout(2)
                client.sendall(struct.pack("!H", 6) + b"source" + struct.pack("!Q", 0))
                width, height, sequence, timestamp, length = struct.unpack("!IIQQI", self._read(client, 28))
                self.assertEqual(sequence, 100)
                self.assertEqual(self._read(client, length), bytes((99, 0, 0, 255)))
                stream.publish("source", 1, 1, bytes((101, 0, 0, 255)))
                self.assertEqual(len(notices), 2)
        finally:
            stream.close()

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

import socket
import struct
import time
import unittest

from multimin_backend.game_ingest import GameIngest, HEADER


class GameIngestTests(unittest.TestCase):
    def test_only_registered_source_can_publish_dx11_pixels(self):
        received = []
        ingest = GameIngest(lambda source, width, height, pixels: received.append((source, width, height, pixels)))
        try:
            source = "controlled-dx11-test"
            token = "0123456789abcdef0123456789abcdef"
            ingest.allow(source, token)
            pixels = bytes([1, 2, 3, 255, 4, 5, 6, 255])
            with socket.create_connection(("127.0.0.1", ingest.port), timeout=2) as client:
                client.sendall(HEADER.pack(b"MMGC", len(source), len(token), 2, 1, len(pixels))
                               + source.encode() + token.encode() + pixels)
                for _ in range(50):
                    if received:
                        break
                    time.sleep(0.01)
            self.assertEqual(received, [(source, 2, 1, pixels)])
            ingest.revoke(source)
            with socket.create_connection(("127.0.0.1", ingest.port), timeout=2) as client:
                client.sendall(HEADER.pack(b"MMGC", len(source), len(token), 2, 1, len(pixels))
                               + source.encode() + token.encode() + pixels)
                time.sleep(0.02)
            self.assertEqual(len(received), 1)
        finally:
            ingest.close()

    def test_rejects_invalid_frame_size_before_reading_pixels(self):
        received = []
        ingest = GameIngest(lambda *frame: received.append(frame))
        try:
            with socket.create_connection(("127.0.0.1", ingest.port), timeout=2) as client:
                client.sendall(HEADER.pack(b"MMGC", 1, 32, 8192, 8192, 0))
                time.sleep(0.02)
            self.assertEqual(received, [])
        finally:
            ingest.close()

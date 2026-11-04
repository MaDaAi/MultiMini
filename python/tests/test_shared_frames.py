import mmap
import socket
import struct
import sys
import unittest
from multimin_backend.frame_stream import FrameStream


@unittest.skipUnless(sys.platform == 'win32', 'Windows named shared memory')
class SharedFrameTests(unittest.TestCase):
    def test_shared_pixels_resize_unchanged_and_socket_fallback(self):
        stream = FrameStream()
        name = b'source'
        def exact(client, count):
            data = bytearray()
            while len(data) < count:
                part = client.recv(count - len(data))
                self.assertTrue(part)
                data.extend(part)
            return data
        try:
            with socket.create_connection(('127.0.0.1', stream.port), timeout=2) as client:
                client.settimeout(2)
                for width, height in ((1, 1), (127, 71), (1, 1)):
                    pixels = bytes((23, 45, 67, 128)) * (width * height)
                    stream.publish('source', width, height, pixels)
                    client.sendall(struct.pack('!H', 0x8000 | len(name)) + name + struct.pack('!Q', 0))
                    w, h, seq, timestamp, length = struct.unpack('!IIQQI', exact(client, 28))
                    self.assertEqual((w, h, length), (width, height, 0xffffffff))
                    size = struct.unpack('!H', exact(client, 2))[0]
                    mapping_name = exact(client, size).decode('ascii')
                    with mmap.mmap(-1, len(pixels), tagname=mapping_name, access=mmap.ACCESS_READ) as mapping:
                        self.assertEqual(mapping[:len(pixels)], pixels)
                    client.sendall(struct.pack('!H', 0x8000 | len(name)) + name + struct.pack('!Q', seq))
                    self.assertEqual(struct.unpack('!IIQQI', exact(client, 28))[-1], 0)
                client.sendall(struct.pack('!H', len(name)) + name + struct.pack('!Q', 0))
                self.assertEqual(struct.unpack('!IIQQI', exact(client, 28))[-1], 4)
                self.assertEqual(exact(client, 4), pixels)
        finally:
            stream.close()

"""Local, latest-frame transport for Windows Graphics Capture BGRA frames."""

from __future__ import annotations

import socket
import struct
import threading
import time
import mmap
import sys
import secrets


class SharedReply:
    """One mapping per serialized connection; not rewritten until its next request."""
    def __init__(self):
        self.mapping = None
        self.capacity = 0
        self.name = ''

    def write(self, pixels):
        if self.capacity < len(pixels):
            self.close()
            self.name = 'Local\\MultiMiniFrame-' + secrets.token_hex(16)
            self.mapping = mmap.mmap(-1, len(pixels), tagname=self.name)
            self.capacity = len(pixels)
        self.mapping.seek(0)
        self.mapping.write(pixels)
        return self.name.encode('ascii')

    def close(self):
        if self.mapping is not None:
            self.mapping.close()
        self.mapping = None
        self.capacity = 0


class FrameStream:
    def __init__(self, on_publish=None, coalesce_notifications=False) -> None:
        self._on_publish = on_publish
        self._coalesce_notifications = coalesce_notifications
        self._pending_notifications: set[str] = set()
        self._lock = threading.Lock()
        self._frames: dict[str, tuple[int, int, int, int, bytes]] = {}
        self._sequence: dict[str, int] = {}
        self._stop = threading.Event()
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.bind(("127.0.0.1", 0))
        self._server.listen(2)
        self._server.settimeout(0.5)
        self.port = self._server.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, name="multimin-frame-stream", daemon=True)
        self._thread.start()

    def publish(self, source: str, width: int, height: int, pixels: bytes) -> None:
        if width <= 0 or height <= 0 or len(pixels) != width * height * 4:
            return
        with self._lock:
            sequence = self._sequence.get(source, 0) + 1
            self._sequence[source] = sequence
            self._frames[source] = (width, height, sequence, time.monotonic_ns(), pixels)
            notify = not self._coalesce_notifications or source not in self._pending_notifications
            if self._coalesce_notifications:
                self._pending_notifications.add(source)
        if notify and self._on_publish is not None:
            self._on_publish()

    def remove(self, source: str) -> None:
        with self._lock:
            self._pending_notifications.discard(source)
            self._frames.pop(source, None)

    def forget(self, source: str) -> None:
        with self._lock:
            self._pending_notifications.discard(source)
            self._frames.pop(source, None)
            self._sequence.pop(source, None)

    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                client, _ = self._server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self._client, args=(client,), daemon=True).start()

    def _client(self, client: socket.socket) -> None:
        shared = SharedReply()
        with client:
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            client.settimeout(5.0)
            try:
                while not self._stop.is_set():
                    size = self._read_exact(client, 2)
                    if not size:
                        return
                    request = struct.unpack("!H", size)[0]
                    shared_requested = bool(request & 0x8000) and sys.platform == 'win32'
                    name_length = request & 0x7fff
                    if name_length > 4096:
                        return
                    name = self._read_exact(client, name_length)
                    if name is None:
                        return
                    last_seen = self._read_exact(client, 8)
                    if last_seen is None:
                        return
                    previous_sequence = struct.unpack("!Q", last_seen)[0]
                    with self._lock:
                        source = name.decode("utf-8")
                        frame = self._frames.get(source)
                        # Re-arm atomically with selecting the newest complete frame.
                        # Notifications never represent a queue of pixel buffers.
                        self._pending_notifications.discard(source)
                    if frame is None:
                        client.sendall(struct.pack("!IIQQI", 0, 0, 0, 0, 0))
                    else:
                        width, height, sequence, timestamp, pixels = frame
                        changed = sequence != previous_sequence
                        if changed and shared_requested:
                            try:
                                mapping_name = shared.write(pixels)
                            except (OSError, ValueError):
                                shared_requested = False
                            else:
                                client.sendall(struct.pack("!IIQQIH", width, height, sequence, timestamp,
                                                           0xffffffff, len(mapping_name)) + mapping_name)
                                continue
                        client.sendall(struct.pack("!IIQQI", width, height, sequence, timestamp, len(pixels) if changed else 0))
                        if changed:
                            client.sendall(pixels)
            except (OSError, UnicodeDecodeError):
                return
            finally:
                shared.close()

    @staticmethod
    def _read_exact(client: socket.socket, count: int) -> bytes | None:
        data = bytearray()
        while len(data) < count:
            chunk = client.recv(count - len(data))
            if not chunk:
                return None
            data.extend(chunk)
        return bytes(data)

    def close(self) -> None:
        self._stop.set()
        self._server.close()
        self._thread.join(timeout=1.0)

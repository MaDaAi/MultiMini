"""Local, latest-frame transport for Windows Graphics Capture BGRA frames."""

from __future__ import annotations

import socket
import struct
import threading
import time


class FrameStream:
    def __init__(self) -> None:
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

    def remove(self, source: str) -> None:
        with self._lock:
            self._frames.pop(source, None)

    def forget(self, source: str) -> None:
        with self._lock:
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
        with client:
            client.settimeout(5.0)
            try:
                while not self._stop.is_set():
                    size = self._read_exact(client, 2)
                    if not size:
                        return
                    name_length = struct.unpack("!H", size)[0]
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
                        frame = self._frames.get(name.decode("utf-8"))
                    if frame is None:
                        client.sendall(struct.pack("!IIQQI", 0, 0, 0, 0, 0))
                    else:
                        width, height, sequence, timestamp, pixels = frame
                        changed = sequence != previous_sequence
                        client.sendall(struct.pack("!IIQQI", width, height, sequence, timestamp, len(pixels) if changed else 0))
                        if changed:
                            client.sendall(pixels)
            except (OSError, UnicodeDecodeError):
                return

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

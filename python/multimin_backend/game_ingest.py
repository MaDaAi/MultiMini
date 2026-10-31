"""Local frame receiver for the opt-in DirectX 11 test capture executable.

This channel is separate from WGC window capture. A randomly generated token
authorizes one registered source; unregistered processes cannot publish frames.
"""

from __future__ import annotations

import socket
import struct
import threading


HEADER = struct.Struct("!4sHHIII")
MAX_FRAME_BYTES = 64 * 1024 * 1024


class GameIngest:
    def __init__(self, publish) -> None:
        self._publish = publish
        self._allowed: dict[str, str] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.bind(("127.0.0.1", 0))
        self._server.listen(2)
        self._server.settimeout(0.5)
        self.port = self._server.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, name="multimin-game-ingest", daemon=True)
        self._thread.start()

    def allow(self, source: str, token: str) -> None:
        with self._lock:
            self._allowed[source] = token

    def revoke(self, source: str) -> None:
        with self._lock:
            self._allowed.pop(source, None)

    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                client, _ = self._server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self._safe_client, args=(client,), daemon=True).start()

    def _safe_client(self, client: socket.socket) -> None:
        try:
            self._client(client)
        except OSError:
            # The renderer may close while a frame is being read.
            pass

    def _client(self, client: socket.socket) -> None:
        with client:
            client.settimeout(3)
            while not self._stop.is_set():
                header = self._read_exact(client, HEADER.size)
                if header is None:
                    return
                magic, source_size, token_size, width, height, length = HEADER.unpack(header)
                if (magic != b"MMGC" or not 1 <= source_size <= 4096 or token_size != 32
                        or not 1 <= width <= 8192 or not 1 <= height <= 8192
                        or length != width * height * 4 or length > MAX_FRAME_BYTES):
                    return
                identity = self._read_exact(client, source_size + token_size)
                if identity is None:
                    return
                try:
                    source = identity[:source_size].decode("utf-8")
                    token = identity[source_size:].decode("ascii")
                except UnicodeError:
                    return
                with self._lock:
                    valid = self._allowed.get(source) == token
                if not valid:
                    return
                pixels = self._read_exact(client, length)
                if pixels is None:
                    return
                self._publish(source, width, height, pixels)

    @staticmethod
    def _read_exact(client: socket.socket, length: int) -> bytes | None:
        data = bytearray(length)
        view = memoryview(data)
        offset = 0
        while offset < length:
            count = client.recv_into(view[offset:])
            if count == 0:
                return None
            offset += count
        return bytes(data)

    def close(self) -> None:
        self._stop.set()
        self._server.close()
        self._thread.join(timeout=1)

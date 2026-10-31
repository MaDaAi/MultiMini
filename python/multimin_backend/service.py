from __future__ import annotations

import json
import os
import secrets
import subprocess
import sys
import threading
import time
from pathlib import Path

from .snapshots import SnapshotStore
from .windows_input import WindowsInputBridge
from .frame_stream import FrameStream
from .game_ingest import GameIngest


class CaptureService:
    """Owns capture state. Pixel data never crosses the command channel."""

    def __init__(self, emit) -> None:
        self.emit = emit
        self.snapshots = SnapshotStore()
        self.frame_stream = FrameStream()
        self.game_ingest = GameIngest(self._publish_game_frame)
        self.game_processes: dict[str, subprocess.Popen] = {}
        self.game_active: set[str] = set()
        self.native_game_executable = Path(__file__).resolve().parents[2] / "native" / "build" / "multimin-dx11-test.exe"
        self.game_available = self._probe_native_game()
        self.opacity = 100
        self.input_target = "desktop"
        self.input_hwnd = 0
        self.multitask_enabled = False
        self.capture_fps = 60
        self.native_input = WindowsInputBridge(emit) if sys.platform == "win32" else None
        self.window_captures: dict[str, tuple[str, str, int, str, bool]] = {}
        self.graphics_captures: dict[str, tuple[object, object]] = {}
        self.graphics_starting: set[str] = set()
        self.frame_locks: dict[str, threading.Lock] = {}
        self.graphics_retry_after: dict[str, float] = {}
        self.capture_lock = threading.RLock()
        self.capture_stop = threading.Event()
        self.capture_thread = None
        if self.native_input:
            self.native_input.enable_capture_exclusion()
            self.capture_thread = threading.Thread(target=self._capture_windows, name="multimin-window-capture", daemon=True)
            self.capture_thread.start()

    def _capture_windows(self) -> None:
        while not self.capture_stop.is_set():
            with self.capture_lock:
                captures = list(self.window_captures.items())
            for path, (title, _source_id, hwnd, method, capture_cursor) in captures:
                if not self.native_input.user32.IsWindow(hwnd):
                    with self.capture_lock:
                        self.graphics_retry_after[path] = time.monotonic() + 1.0
                    continue
                with self.capture_lock:
                    graphics_active = path in self.graphics_captures or path in self.graphics_starting
                if graphics_active:
                    continue
                if method == "AUTO" and time.monotonic() >= self.graphics_retry_after.get(path, 0.0):
                    if self._start_graphics_capture(path, hwnd, capture_cursor):
                        continue
                try:
                    captured = self.native_input.capture_window(title, path, hwnd, method)
                    if not captured and method == "AUTO":
                        with self.capture_lock:
                            self.graphics_retry_after[path] = min(self.graphics_retry_after.get(path, 0.0), time.monotonic())
                except Exception as error:
                    self.emit({"event": "error", "message": f"Window capture failed: {error}"})
            self.capture_stop.wait(1.0 / self.capture_fps)

    def _stop_graphics_capture(self, path: str) -> None:
        with self.capture_lock:
            session = self.graphics_captures.pop(path, None)
        if session:
            session[1].stop()

    def _start_graphics_capture(self, path: str, hwnd: int, capture_cursor: bool = False) -> bool:
        with self.capture_lock:
            if path in self.graphics_captures or path in self.graphics_starting:
                return True
            self.graphics_starting.add(path)
        if not hwnd or not self.native_input.user32.IsWindow(hwnd):
            self.graphics_retry_after[path] = time.monotonic() + 5.0
            with self.capture_lock:
                self.graphics_starting.discard(path)
            return False
        try:
            from windows_capture import WindowsCapture
        except ImportError:
            self.emit({"event": "error", "message": "Install the Windows capture dependency to capture covered windows"})
            self.graphics_retry_after[path] = time.monotonic() + 30.0
            with self.capture_lock:
                self.graphics_starting.discard(path)
            return False
        try:
            with self.capture_lock:
                frame_lock = self.frame_locks.setdefault(path, threading.Lock())
            capture = WindowsCapture(window_hwnd=hwnd, cursor_capture=capture_cursor,
                                     draw_border=False, minimum_update_interval=max(1, round(1000 / self.capture_fps)))

            @capture.event
            def on_frame_arrived(frame, _control):
                try:
                    pixels = frame.frame_buffer.tobytes()
                    self.frame_stream.publish(path, frame.width, frame.height, pixels)
                except Exception as error:
                    self.emit({"event": "error", "message": f"Window frame update failed: {error}"})

            @capture.event
            def on_closed():
                self.frame_stream.remove(path)
                with self.capture_lock:
                    self.graphics_captures.pop(path, None)
                self.graphics_retry_after[path] = time.monotonic() + 1.0

            control = capture.start_free_threaded()
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                pass
            with self.capture_lock:
                self.graphics_captures[path] = (capture, control)
            self.graphics_retry_after.pop(path, None)
            return True
        except Exception as error:
            self.emit({"event": "error", "message": f"Isolated window capture failed: {error}"})
            self.graphics_retry_after[path] = time.monotonic() + 5.0
            return False
        finally:
            with self.capture_lock:
                self.graphics_starting.discard(path)

    def dispatch(self, message: dict) -> dict:
        command = message.get("command")
        if command == "ping":
            return {"event": "pong"}
        if command == "capabilities":
            return {
                "event": "capabilities",
                "display": True,
                "window": sys.platform == "win32",
                "game": self.game_available,
                "recording": False,
            }
        if command == "set_opacity":
            self.opacity = max(0, min(100, int(message.get("value", 100))))
            return {"event": "opacity_changed", "value": self.opacity}
        if command == "set_capture_fps":
            self.capture_fps = max(1, min(240, int(message.get("value", 60))))
            with self.capture_lock:
                active = list(self.graphics_captures)
                auto_captures = [(path, title, hwnd, cursor) for path, (title, _sid, hwnd, method, cursor) in self.window_captures.items() if method == "AUTO"]
            for path in active:
                self._stop_graphics_capture(path)
            for path, _title, hwnd, cursor in auto_captures:
                self._start_graphics_capture(path, hwnd, cursor)
            return {"event": "capture_fps_changed", "value": self.capture_fps}
        if command == "set_input_target":
            self.input_target = str(message.get("name", "desktop"))
            hwnd = int(message.get("hwnd", 0))
            self.input_hwnd = hwnd
            focused = self.native_input.focus_window(self.input_target, hwnd) if self.native_input and self.multitask_enabled else False
            if self.native_input:
                self.native_input.set_mouse_target(hwnd,
                    (int(message.get("x", 0)), int(message.get("y", 0)), int(message.get("width", 0)), int(message.get("height", 0))),
                    (int(message.get("exclude_x", 0)), int(message.get("exclude_y", 0)), int(message.get("exclude_width", 0)), int(message.get("exclude_height", 0))),
                    bool(message.get("desktop_input", False)))
            return {"event": "input_target_changed", "name": self.input_target, "focused": focused}
        if command == "set_layer_layout":
            if self.native_input:
                self.native_input.set_layer_layout(message.get("items", []))
            return {"event": "layer_layout_changed"}
        if command == "refresh_overlay_hit_test":
            if self.native_input and self.multitask_enabled:
                self.native_input.refresh_overlay_hit_test()
            return {"event": "overlay_hit_test_refreshed"}
        if command == "list_windows":
            windows = self.native_input.list_windows() if self.native_input else []
            return {"event": "window_list", "items": windows}
        if command == "set_multitask_overlay":
            enabled = bool(message.get("enabled", False))
            self.multitask_enabled = enabled
            click_through = bool(message.get("click_through", False))
            stop_key = str(message.get("stop_key", "END"))
            if self.native_input:
                self.native_input.set_app_pid(int(message.get("app_pid", 0)))
                self.native_input.set_multitask(enabled, click_through, stop_key,
                    str(message.get("swap_key", "RIGHT_CTRL")), str(message.get("layer_swap_key", "")))
                if enabled:
                    self.native_input.focus_window(self.input_target, self.input_hwnd)
            return {"event": "overlay_changed", "enabled": enabled, "click_through": click_through, "stop_key": stop_key}
        if command == "register_window_capture":
            if not self.native_input:
                return {"event": "error", "message": "Window capture is currently supported on Windows only"}
            path = str(message.get("path", ""))
            title = str(message.get("title", ""))
            if path and title:
                self._stop_graphics_capture(path)
                method = str(message.get("method", "AUTO")).upper()
                if method not in {"AUTO", "WINDOW", "SCREEN"}:
                    method = "AUTO"
                with self.capture_lock:
                    capture_cursor = bool(message.get("capture_cursor", False))
                    hwnd = int(message.get("hwnd", 0))
                    self.window_captures[path] = (title, str(message.get("id", "")), hwnd, method, capture_cursor)
                    self.frame_locks.setdefault(path, threading.Lock())
                if method == "AUTO":
                    self._start_graphics_capture(path, hwnd, capture_cursor)
            return {"event": "window_capture_registered", "path": path}
        if command == "remove_window_capture":
            path = str(message.get("path", ""))
            self._stop_graphics_capture(path)
            self.frame_stream.forget(path)
            with self.capture_lock:
                self.window_captures.pop(path, None)
                self.graphics_retry_after.pop(path, None)
                self.frame_locks.pop(path, None)
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                pass
            return {"event": "window_capture_removed"}
        if command == "start_game_capture":
            source = str(message.get("path", ""))
            if not self.game_available:
                return {"event": "game_capture_status", "path": source, "status": "backend_unavailable"}
            if not source or len(source.encode("utf-8")) > 4096:
                return {"event": "game_capture_status", "path": source, "status": "unsupported"}
            self._stop_game(source)
            token = secrets.token_hex(16)
            self.game_ingest.allow(source, token)
            try:
                process = subprocess.Popen([str(self.native_game_executable), str(self.game_ingest.port), source, token],
                                           cwd=str(self.native_game_executable.parent))
                self.game_processes[source] = process
                threading.Thread(target=self._watch_game, args=(source, process), daemon=True).start()
                return {"event": "game_capture_status", "path": source, "status": "game_detected"}
            except OSError as error:
                self.game_ingest.revoke(source)
                return {"event": "game_capture_status", "path": source, "status": "unsupported", "message": str(error)}
        if command == "stop_game_capture":
            source = str(message.get("path", ""))
            self._stop_game(source)
            self.frame_stream.forget(source)
            return {"event": "game_capture_status", "path": source, "status": "stopped"}
        if command == "game_capture_status":
            source = str(message.get("path", ""))
            process = self.game_processes.get(source)
            status = ("backend_unavailable" if not self.game_available else
                      "waiting_for_game" if process is None else
                      "unsupported" if process.poll() is not None else
                      "active" if source in self.game_active else "connecting")
            return {"event": "game_capture_status", "path": source, "status": status}
        if command == "snapshot":
            path = self.snapshots.capture_temporary()
            return {"event": "snapshot_created", "path": str(path), "retention_days": 7}
        if command == "cleanup":
            return {"event": "cleanup_complete", "deleted": self.snapshots.cleanup()}
        if command == "shutdown":
            self.capture_stop.set()
            for source in list(self.game_processes):
                self._stop_game(source)
            self.game_ingest.close()
            for path in list(self.graphics_captures):
                self._stop_graphics_capture(path)
            if self.native_input:
                self.native_input.close()
            self.frame_stream.close()
            return {"event": "shutdown"}
        return {"event": "error", "message": f"Unknown command: {command}"}

    def _stop_game(self, source: str) -> None:
        self.game_ingest.revoke(source)
        self.game_active.discard(source)
        process = self.game_processes.pop(source, None)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()

    def _probe_native_game(self) -> bool:
        if sys.platform != "win32" or not self.native_game_executable.is_file():
            return False
        try:
            return subprocess.run([str(self.native_game_executable), "--probe"], timeout=5,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            return False

    def _publish_game_frame(self, source: str, width: int, height: int, pixels: bytes) -> None:
        self.frame_stream.publish(source, width, height, pixels)
        if source not in self.game_active:
            self.game_active.add(source)
            self.emit({"event": "game_capture_status", "path": source, "status": "active"})

    def _watch_game(self, source: str, process: subprocess.Popen) -> None:
        code = process.wait()
        if self.game_processes.get(source) is process:
            self.game_processes.pop(source, None)
            self.game_active.discard(source)
            self.game_ingest.revoke(source)
            self.frame_stream.remove(source)
            self.emit({"event": "game_capture_status", "path": source,
                       "status": "waiting_for_game" if code == 0 else "unsupported"})


_output_lock = threading.Lock()


def emit(message: dict) -> None:
    with _output_lock:
        print(json.dumps(message, separators=(",", ":")), flush=True)


def run() -> None:
    service = CaptureService(emit)
    service.snapshots.cleanup()
    emit({"event": "ready", "protocol": 1, "local_only": True, "frame_port": service.frame_stream.port,
          "game_available": service.game_available})
    for line in sys.stdin:
        try:
            message = json.loads(line)
            response = service.dispatch(message)
            emit(response)
            if response.get("event") == "shutdown":
                break
        except Exception as error:
            emit({"event": "error", "message": str(error)})

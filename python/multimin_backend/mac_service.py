"""macOS backend speaking the same JSON protocol as linux_service.

UNTESTED PREVIEW: written without access to a Mac. Capture uses
ScreenCaptureKit (fallback CGWindowList); input is "directed": Java forwards
events from the MultiMini overlay and mac_input re-posts them to the selected
app. Configure never focuses or moves captured apps; real windows are never
reordered (Java owns composition).
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time

from .frame_stream import FrameStream
from .snapshots import SnapshotStore


class MacService:
    def __init__(self, emit):
        self.emit = emit
        self.frame_stream = FrameStream(lambda: emit({"event": "frame_ready"}), coalesce_notifications=True)
        self.snapshots = SnapshotStore()
        self.closed = threading.Event()
        self.lock = threading.RLock()
        self.captures = {}
        self.generations = {}
        self.active = None
        self.fps = 60
        self.source_paced = False
        self.frame_deadlines = {}
        self.background_limits = {}
        self.enabled = False
        self.target = 0
        self.rect = (0, 0, 0, 0)
        self.opacity = 100
        self.app_pid = 0
        self.coordinate_scale = (1.0, 1.0)
        self.desktop_input = False
        self.selecting = False
        self.input_mode = "unavailable"
        self.input = None
        self.target_info = None
        self.prompted_accessibility = False
        self.keys = {"toggle_multitask": "CTRL+ALT+E", "quick_swap": "RIGHT_ALT", "layer_swap": "CTRL+ALT+L",
                     "screenshot_full": "CTRL+ALT+S", "screenshot_region": "CTRL+ALT+R"}
        self.key_tap = None
        self.last_actions = {}

    # ---- capture -------------------------------------------------------
    def _effective_fps(self, path):
        limit = self.background_limits.get(path, 0)
        return min(self.fps, limit) if limit else self.fps

    def _register(self, path, make_grabber, window_id=0):
        if not path or len(path.encode()) > 4096:
            raise ValueError("Invalid capture identifier")
        self._remove(path)
        with self.lock:
            generation = self.generations.get(path, 0) + 1
            self.generations[path] = generation

        def publish(w, h, pixels):
            with self.lock:
                if self.closed.is_set() or self.generations.get(path) != generation:
                    return
                if self.active is not None and path not in self.active:
                    return
                now = time.monotonic()
                period = 1 / self._effective_fps(path)
                deadline = self.frame_deadlines.get(path, now)
                if now + period * .02 < deadline:
                    return
                self.frame_deadlines[path] = max(deadline + period, now + period * .98)
            self.frame_stream.publish(path, w, h, pixels)

        def error(message):
            import base64
            with self.lock:
                if self.generations.get(path) != generation:
                    return
            self.frame_stream.remove(path)
            self.emit({"event": "error", "message": message})
            self.emit({"event": "linux_capture_stopped", "path64": base64.b64encode(path.encode()).decode("ascii")})
            if self.enabled and window_id and self.target == window_id:
                self.emit({"event": "linux_target_lost"})

        def start():
            try:
                from .mac_capture import MacCapture
                capture = MacCapture(make_grabber(), publish, error, lambda: self._effective_fps(path))
                with self.lock:
                    stale = self.closed.is_set() or self.generations.get(path) != generation
                    if not stale:
                        self.captures[path] = capture
                        capture.set_rate_limit(self.background_limits.get(path, 0))
                if stale:
                    capture.close()
            except Exception as exc:
                error(f"macOS capture could not start: {exc}")

        threading.Thread(target=start, name="multimini-mac-source", daemon=True).start()

    def _remove(self, path):
        with self.lock:
            self.generations[path] = self.generations.get(path, 0) + 1
            capture = self.captures.pop(path, None)
            self.frame_deadlines.pop(path, None)
            self.background_limits.pop(path, None)
        if capture:
            capture.close()
        self.frame_stream.forget(path)

    # ---- hotkeys -------------------------------------------------------
    def _hotkey_emit(self, action):
        now = time.monotonic()
        with self.lock:
            if not self.enabled or now - self.last_actions.get(action, 0) < .1:
                return
            self.last_actions[action] = now
        self.emit({"event": action})

    def _forward_pid(self):
        # Forward typing only while MultiMini itself is the active app; otherwise
        # keys already go to whatever app the user switched to.
        info = self.target_info
        if not (self.enabled and info and self.input_mode == "directed"):
            return 0
        from .mac_hotkeys import frontmost_pid
        return info["pid"] if self.app_pid and frontmost_pid() == self.app_pid else 0

    def _start_keys(self):
        self._stop_keys()
        from . import mac_hotkeys, mac_input
        if not mac_input.accessibility_trusted():
            if not self.prompted_accessibility:
                self.prompted_accessibility = True
                mac_input.request_accessibility()
            self.emit({"event": "error", "message": "Hotkeys need Accessibility: System Settings → Privacy & Security → "
                                                     "Accessibility → turn on MultiMini, then restart MultiMini."})
            return
        bindings = [b for b in (mac_hotkeys.parse_binding(action, key) for action, key in self.keys.items()) if b]
        try:
            self.key_tap = mac_hotkeys.MacKeyTap(bindings, self._hotkey_emit, self._forward_pid)
        except Exception as error:
            self.key_tap = None
            self.emit({"event": "error", "message": str(error)})
            return
        unsupported = [action for action, key in self.keys.items() if key and not mac_hotkeys.parse_binding(action, key)]
        if unsupported:
            self.emit({"event": "error", "message": "These hotkeys have no Mac key; set new ones in Settings → Controls: "
                                                     + ", ".join(a.replace("_", " ") for a in unsupported)})

    def _stop_keys(self):
        if self.key_tap is not None:
            self.key_tap.close()
            self.key_tap = None

    # ---- input ---------------------------------------------------------
    def _input_mode(self, mode, message=""):
        self.input_mode = mode
        self.emit({"event": "linux_input_mode", "mode": mode, "message": message})

    def _activate_input(self):
        if not self.enabled or self.selecting or not self.target or self.desktop_input:
            if self.input:
                self.input.release_all()
            reason = "Display input is not available on macOS yet; use a window source" if self.desktop_input else ""
            self._input_mode("unavailable", reason)
            return
        from . import mac_input
        if not mac_input.accessibility_trusted():
            if not self.prompted_accessibility:
                self.prompted_accessibility = True
                mac_input.request_accessibility()
            self._input_mode("unavailable", "Allow MultiMini in System Settings → Privacy & Security → Accessibility, then re-enter multitasking")
            return
        try:
            from .mac_capture import window_info
            info = window_info(self.target)
            if not info:
                raise RuntimeError("selected window closed")
            bounds = info.get("kCGWindowBounds", {}) or {}
            self.target_info = dict(pid=int(info.get("kCGWindowOwnerPID", 0)), window_id=int(self.target),
                                    bounds=(float(bounds.get("X", 0)), float(bounds.get("Y", 0)),
                                            float(bounds.get("Width", 0)), float(bounds.get("Height", 0))))
            if self.input is None:
                self.input = mac_input.MacInput()
            self._input_mode("directed")
        except Exception as error:
            self._input_mode("unavailable", str(error))

    def _points(self, values):
        sx, sy = self.coordinate_scale
        return tuple(v / (sx if i % 2 == 0 else sy) for i, v in enumerate(values))

    # ---- protocol ------------------------------------------------------
    def dispatch(self, message):
        command = message.get("command")
        if command == "ping":
            return {"event": "pong"}
        if command == "capabilities":
            return {"event": "capabilities", "display": True, "window": True, "input": True,
                    "native_wayland_window_input": False, "game_window": True, "game": False,
                    "recording": False, "backend": "macos"}
        if command == "list_windows":
            from .mac_capture import list_windows
            try:
                rows = list_windows(self.app_pid or os.getppid())
            except Exception as error:
                self.emit({"event": "error", "message": f"Window list unavailable: {error}"})
                rows = []
            return {"event": "window_list", "items": rows}
        if command == "register_window_capture":
            window_id = int(message.get("hwnd", 0))
            from .mac_capture import WindowGrabber
            self._register(str(message.get("path", "")), lambda: WindowGrabber(window_id), window_id)
            return {"event": "capture_registered"}
        if command == "register_display_capture":
            monitor = int(message.get("monitor", 1))
            from .mac_capture import DisplayGrabber
            self._register(str(message.get("path", "")), lambda: DisplayGrabber(monitor, self.app_pid or os.getppid()))
            return {"event": "capture_registered"}
        if command == "remove_window_capture":
            self._remove(str(message.get("path", "")))
            return {"event": "window_capture_removed"}
        if command == "active_window_captures":
            self.active = set(map(str, message.get("paths", [])))
            return {"event": "capture_activity_changed"}
        if command == "set_capture_fps":
            self.fps = max(1, min(240, int(message.get("value", 60))))
            self.source_paced = bool(message.get("source_paced", False))
            self.frame_deadlines.clear()
            return {"event": "capture_fps_changed", "value": self.fps}
        if command == "linux_background_capture":
            path = str(message.get("path", ""))
            fps = max(0, min(1000, int(message.get("fps", 0))))
            with self.lock:
                if fps:
                    self.background_limits[path] = fps
                else:
                    self.background_limits.pop(path, None)
                capture = self.captures.get(path)
            if capture is not None:
                capture.set_rate_limit(fps)
            return {"event": "background_capture_changed", "fps": fps}
        if command == "set_opacity":
            self.opacity = max(0, min(100, int(message.get("value", 100))))
            return {"event": "opacity_changed", "value": self.opacity}
        if command == "linux_coordinate_scale":
            self.coordinate_scale = tuple(max(.25, min(8.0, float(message.get(k, 1)))) for k in ("x", "y"))
            return {"event": "coordinate_scale_updated"}
        if command == "set_input_target":
            self.target = int(message.get("hwnd", 0))
            self.rect = tuple(int(message.get(k, 0)) for k in ("x", "y", "width", "height"))
            self.desktop_input = bool(message.get("desktop_input", False))
            if self.input:
                self.input.release_all()
            if self.enabled:
                self._activate_input()
            return {"event": "input_target_changed", "focused": False}
        if command == "set_multitask_overlay":
            self.enabled = bool(message.get("enabled", False))
            self.app_pid = int(message.get("app_pid", 0)) or self.app_pid
            for action, field in (("toggle_multitask", "stop_key"), ("quick_swap", "swap_key"), ("layer_swap", "layer_swap_key")):
                if field in message:
                    self.keys[action] = str(message.get(field) or "")
            self._activate_input()
            if self.enabled:
                self._start_keys()
            else:
                self._stop_keys()
            return {"event": "overlay_changed", "enabled": self.enabled}
        if command == "linux_input":
            if self.enabled and self.input and self.input_mode == "directed" and self.target_info:
                scaled = dict(message)
                if "x" in message:
                    scaled["x"], scaled["y"] = self._points((float(message.get("x", 0)), float(message.get("y", 0))))
                self.input.send(self.target_info, scaled, self._points(self.rect))
            return {"event": "input_processed"}
        if command == "linux_release_input":
            if self.input:
                self.input.release_all()
            return {"event": "input_released"}
        if command == "linux_check_input":
            return {"event": "input_checked"}
        if command == "choose_image":
            threading.Thread(target=self._choose_image, name="multimini-file-picker", daemon=True).start()
            return {"event": "file_picker_opened"}
        if command == "screenshot_selection":
            self.selecting = bool(message.get("enabled", False))
            self._activate_input()
            return {"event": "command_applied"}
        if command == "screenshot_shortcuts":
            self.keys["screenshot_region"] = str(message.get("region", self.keys["screenshot_region"]) or "")
            self.keys["screenshot_full"] = str(message.get("full", self.keys["screenshot_full"]) or "")
            if self.enabled:
                self._start_keys()
            return {"event": "command_applied"}
        if command in ("linux_overlay_controls", "linux_overlay_window",
                       "set_layer_layout", "set_overlay_control_region", "refresh_overlay_hit_test"):
            return {"event": "command_applied"}  # Java owns composition; never reorder real windows.
        if command in ("start_game_capture", "game_capture_status"):
            return {"event": "game_capture_status", "status": "backend_unavailable"}
        if command == "cleanup":
            return {"event": "cleanup_complete", "deleted": self.snapshots.cleanup()}
        if command == "shutdown":
            self.closed.set()
            self.enabled = False
            self._stop_keys()
            if self.input:
                self.input.release_all()
            for path in list(self.captures):
                self._remove(path)
            self.frame_stream.close()
            return {"event": "shutdown"}
        return {"event": "error", "message": f"Unsupported macOS command: {command}"}

    def _choose_image(self):
        import base64
        import subprocess
        path = ""
        try:
            script = 'POSIX path of (choose file with prompt "Choose an image source" of type {"public.image"})'
            result = subprocess.run(["/usr/bin/osascript", "-e", script], capture_output=True, text=True, timeout=600)
            if result.returncode == 0:
                path = result.stdout.strip()
        except Exception as exc:
            self.emit({"event": "error", "message": str(exc)})
        self.emit({"event": "image_file", "path64": base64.b64encode(path.encode()).decode("ascii")})


def run():
    lock = threading.Lock()

    def emit(message):
        with lock:
            print(json.dumps(message, separators=(",", ":")), flush=True)

    try:
        service = MacService(emit)
    except Exception as error:
        emit({"event": "error", "message": f"macOS backend could not start: {error}"})
        return
    emit({"event": "ready", "protocol": 1, "frame_port": service.frame_stream.port,
          "game_available": False, "frame_notifications": True, "backend": "macos"})
    try:
        import Quartz  # noqa: F401
    except Exception as error:
        emit({"event": "error", "message": f"PyObjC is missing; capture and input are unavailable: {error}"})
    try:
        for line in sys.stdin:
            try:
                response = service.dispatch(json.loads(line))
                emit(response)
                if response.get("event") == "shutdown":
                    break
            except Exception as error:
                emit({"event": "error", "message": str(error)})
    finally:
        if not service.closed.is_set():
            service.dispatch({"command": "shutdown"})

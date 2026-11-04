from __future__ import annotations

import ctypes
import base64
import os
import queue
import struct
import threading
import time
from ctypes import wintypes
from typing import Callable


class _Rect(ctypes.Structure):
    _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG), ("right", wintypes.LONG), ("bottom", wintypes.LONG)]


class _BitmapInfoHeader(ctypes.Structure):
    _fields_ = [
        ("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
        ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
        ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
        ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD),
    ]


class _BitmapInfo(ctypes.Structure):
    _fields_ = [("bmiHeader", _BitmapInfoHeader), ("bmiColors", wintypes.DWORD * 3)]


class _KeyboardHookData(ctypes.Structure):
    _fields_ = [("vkCode", wintypes.DWORD), ("scanCode", wintypes.DWORD), ("flags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]


class _Point(ctypes.Structure):
    _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]


class _MouseHookData(ctypes.Structure):
    _fields_ = [("pt", _Point), ("mouseData", wintypes.DWORD), ("flags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]


class WindowsInputBridge:
    """Small Win32 adapter for a click-through top-level MultiMin window."""

    WM_HOTKEY = 0x0312
    WM_QUIT = 0x0012
    VK_RCONTROL = 0xA3
    VK_CONTROL = 0x11
    VK_SHIFT = 0x10
    VK_MENU = 0x12
    VK_SPACE = 0x20
    MOD_CONTROL = 0x0002
    MOD_SHIFT = 0x0004
    MOD_ALT = 0x0001
    MOD_WIN = 0x0008
    MOD_NOREPEAT = 0x4000
    GWL_EXSTYLE = -20
    WS_EX_LAYERED = 0x00080000
    WS_EX_TRANSPARENT = 0x00000020
    WS_EX_TOPMOST = 0x00000008
    HWND_TOP = 0
    HWND_TOPMOST = -1
    HWND_NOTOPMOST = -2
    LWA_ALPHA = 0x00000002
    # Keep click-through overlays fully opaque per-pixel; a lower global alpha
    # dims the entire desktop, including content beneath transparent regions.
    OVERLAY_ALPHA = 255
    WDA_EXCLUDEFROMCAPTURE = 0x00000011
    SW_RESTORE = 9
    SW_HIDE = 0
    SW_SHOWNA = 8
    RIGHT_CTRL_ID = 0x4D31
    EXIT_ID = 0x4D32
    LAYER_SWAP_ID = 0x4D33
    WH_KEYBOARD_LL = 13
    WH_MOUSE_LL = 14
    WM_KEYDOWN = 0x0100
    WM_KEYUP = 0x0101
    WM_SYSKEYDOWN = 0x0104
    WM_SYSKEYUP = 0x0105
    WM_MOUSEMOVE = 0x0200
    WM_SETCURSOR = 0x0020
    WM_LBUTTONDOWN = 0x0201
    WM_LBUTTONUP = 0x0202
    WM_RBUTTONDOWN = 0x0204
    WM_RBUTTONUP = 0x0205
    WM_MBUTTONDOWN = 0x0207
    WM_MBUTTONUP = 0x0208
    WM_MOUSEWHEEL = 0x020A
    WM_XBUTTONDOWN = 0x020B
    WM_XBUTTONUP = 0x020C
    WM_MOUSEHWHEEL = 0x020E
    LLMHF_INJECTED = 0x00000001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010
    MOUSEEVENTF_MIDDLEDOWN = 0x0020
    MOUSEEVENTF_MIDDLEUP = 0x0040
    MOUSEEVENTF_WHEEL = 0x0800
    MOUSEEVENTF_HWHEEL = 0x1000
    SWP_NOSIZE = 0x0001
    SWP_NOMOVE = 0x0002
    SWP_NOZORDER = 0x0004
    SWP_NOACTIVATE = 0x0010
    SWP_FRAMECHANGED = 0x0020
    GA_ROOT = 2
    MK_LBUTTON = 0x0001
    MK_RBUTTON = 0x0002
    MK_SHIFT = 0x0004
    MK_CONTROL = 0x0008
    MK_MBUTTON = 0x0010
    MK_XBUTTON1 = 0x0020
    MK_XBUTTON2 = 0x0040
    HTCLIENT = 1
    CWP_SKIPINVISIBLE = 0x0001
    CWP_SKIPDISABLED = 0x0002
    CWP_SKIPTRANSPARENT = 0x0004
    LLKHF_EXTENDED = 0x01

    def __init__(self, emit: Callable[[dict], None]) -> None:
        self.emit = emit
        self.user32 = ctypes.WinDLL("user32", use_last_error=True)
        self.kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._enable_per_monitor_dpi_awareness()
        self.kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel32.OpenProcess.restype = wintypes.HANDLE
        self.kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        self.kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        self.user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
        self.user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
        self.user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
        self.user32.FindWindowW.restype = wintypes.HWND
        self.user32.IsWindowVisible.argtypes = [wintypes.HWND]
        self.user32.IsWindow.argtypes = [wintypes.HWND]
        self.user32.SetWindowDisplayAffinity.argtypes = [wintypes.HWND, wintypes.DWORD]
        self.user32.GetWindowDisplayAffinity.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
        self.user32.GetWindowDisplayAffinity.restype = wintypes.BOOL
        self.user32.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
        self.user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
        self.user32.SetForegroundWindow.argtypes = [wintypes.HWND]
        self.user32.IsIconic.argtypes = [wintypes.HWND]
        self.user32.BringWindowToTop.argtypes = [wintypes.HWND]
        self.user32.SetActiveWindow.argtypes = [wintypes.HWND]
        self.user32.GetForegroundWindow.restype = wintypes.HWND
        self.user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
        self.user32.AttachThreadInput.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.BOOL]
        self.user32.RegisterHotKey.argtypes = [wintypes.HWND, ctypes.c_int, wintypes.UINT, wintypes.UINT]
        self.user32.UnregisterHotKey.argtypes = [wintypes.HWND, ctypes.c_int]
        self.user32.SetWindowsHookExW.argtypes = [ctypes.c_int, ctypes.c_void_p, wintypes.HANDLE, wintypes.DWORD]
        self.user32.SetWindowsHookExW.restype = wintypes.HANDLE
        self.user32.CallNextHookEx.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM]
        self.user32.CallNextHookEx.restype = ctypes.c_ssize_t
        self.user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
        self.user32.GetCursorPos.argtypes = [ctypes.POINTER(_Point)]
        self.user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
        self.user32.mouse_event.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ctypes.c_size_t]
        self.user32.GetKeyState.argtypes = [ctypes.c_int]
        self.user32.GetKeyState.restype = ctypes.c_short
        self.user32.GetAsyncKeyState.argtypes = [ctypes.c_int]
        self.user32.GetAsyncKeyState.restype = ctypes.c_short
        self.user32.GetClientRect.argtypes = [wintypes.HWND, ctypes.POINTER(_Rect)]
        self.user32.ScreenToClient.argtypes = [wintypes.HWND, ctypes.POINTER(_Point)]
        self.user32.WindowFromPoint.argtypes = [_Point]
        self.user32.WindowFromPoint.restype = wintypes.HWND
        self.user32.GetAncestor.argtypes = [wintypes.HWND, wintypes.UINT]
        self.user32.GetAncestor.restype = wintypes.HWND
        self.user32.ChildWindowFromPointEx.argtypes = [wintypes.HWND, _Point, wintypes.UINT]
        self.user32.ChildWindowFromPointEx.restype = wintypes.HWND
        self.user32.MapWindowPoints.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.POINTER(_Point), wintypes.UINT]
        self.user32.MapWindowPoints.restype = ctypes.c_int
        self.user32.ClientToScreen.argtypes = [wintypes.HWND, ctypes.POINTER(_Point)]
        self.user32.ScreenToClient.argtypes = [wintypes.HWND, ctypes.POINTER(_Point)]
        self.user32.SetFocus.argtypes = [wintypes.HWND]
        self.user32.UnhookWindowsHookEx.argtypes = [wintypes.HANDLE]
        self.user32.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT]
        self.user32.PeekMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT, wintypes.UINT]
        self.user32.PostThreadMessageW.argtypes = [wintypes.DWORD, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
        self.user32.SetLayeredWindowAttributes.argtypes = [wintypes.HWND, wintypes.COLORREF, wintypes.BYTE, wintypes.DWORD]
        self.user32.GetLayeredWindowAttributes.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.COLORREF), ctypes.POINTER(wintypes.BYTE), ctypes.POINTER(wintypes.DWORD)]
        self.user32.GetLayeredWindowAttributes.restype = wintypes.BOOL
        self.user32.GetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int]
        self.user32.GetWindowLongPtrW.restype = ctypes.c_ssize_t
        self.user32.SetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_ssize_t]
        self.user32.SetWindowLongPtrW.restype = ctypes.c_ssize_t
        self.user32.GetWindowDC.argtypes = [wintypes.HWND]
        self.user32.GetWindowDC.restype = wintypes.HDC
        self.user32.GetDC.argtypes = [wintypes.HWND]
        self.user32.GetDC.restype = wintypes.HDC
        self.user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
        self.user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(_Rect)]
        self.user32.PrintWindow.argtypes = [wintypes.HWND, wintypes.HDC, wintypes.UINT]
        self.gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
        self.gdi32.CreateCompatibleDC.argtypes = [wintypes.HDC]
        self.gdi32.CreateCompatibleDC.restype = wintypes.HDC
        self.gdi32.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
        self.gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
        self.gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
        self.gdi32.SelectObject.restype = wintypes.HGDIOBJ
        self.gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
        self.gdi32.DeleteDC.argtypes = [wintypes.HDC]
        self.gdi32.GetDIBits.argtypes = [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT, ctypes.c_void_p, ctypes.POINTER(_BitmapInfo), wintypes.UINT]
        self.gdi32.BitBlt.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.HDC, ctypes.c_int, ctypes.c_int, wintypes.DWORD]
        self._overlay_hwnd = 0
        self._overlay_style_hwnd = 0
        self._app_pid = 0
        self._hotkey_thread: threading.Thread | None = None
        self._hotkey_thread_id = 0
        self._keyboard_hook = None
        self._keyboard_hook_proc = None
        self._mouse_hook = None
        self._mouse_hook_proc = None
        self._mouse_target_hwnd = 0
        self._desktop_input = False
        self._exclusive_input = False
        self._mouse_target_rect = (0, 0, 0, 0)
        self._mouse_excluded_rect = (0, 0, 0, 0)
        self._mouse_forwarding = False
        self._blocked_mouse_buttons: set[int] = set()
        self._passthrough_mouse_buttons: set[int] = set()
        self._mouse_button_flags = 0
        self._mouse_last_target_point = (0, 0)
        self._mouse_button_targets: dict[int, int] = {}
        self._aligned_window: tuple[int, tuple[int, int, int, int]] | None = None
        self._layer_windows: dict[int, tuple[tuple[int, int, int, int], int, int, int, int]] = {}
        self._layer_order: list[int] = []
        self._layer_layout_active = False
        self._mouse_replay_lock = threading.Lock()
        self._mouse_jobs: queue.Queue[tuple | None] = queue.Queue()
        self._mouse_worker = threading.Thread(target=self._mouse_loop, name="multimin-mouse-input", daemon=True)
        self._replaying_mouse = False
        self._mouse_worker.start()
        self._right_control_down = False
        self._pressed_keys: set[int] = set()
        self._swap_key = "RIGHT_CTRL"
        self._layer_swap_key = "PAUSE"
        self._swap_key_down = False
        self._layer_swap_key_down = False
        self._layer_hotkey_registered = False
        self._last_layer_swap_at = 0.0
        self._layer_swap_lock = threading.Lock()
        self._pause_poll_stop = threading.Event()
        self._pause_poll_thread: threading.Thread | None = None
        self._stop_key_down = False
        self._emergency_key_down = False
        self._hwnd = self._find_app_window()
        self._original_style: int | None = None

    def _enable_per_monitor_dpi_awareness(self) -> None:
        try:
            self.user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
            self.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2
            return
        except (AttributeError, OSError):
            pass
        try:
            shcore = ctypes.WinDLL("shcore", use_last_error=True)
            shcore.SetProcessDpiAwareness(2)  # PROCESS_PER_MONITOR_DPI_AWARE
        except (AttributeError, OSError):
            pass

    def enable_capture_exclusion(self) -> None:
        if self._hwnd:
            self.user32.SetWindowDisplayAffinity(self._hwnd, self.WDA_EXCLUDEFROMCAPTURE)

    def set_app_pid(self, pid: int) -> None:
        # A Python launcher can insert an extra process between Java and this
        # worker. The Java PID is authoritative for finding its overlay HWND.
        self._app_pid = max(0, int(pid))

    def set_multitask(self, enabled: bool, click_through: bool, stop_key: str = "END", swap_key: str = "RIGHT_CTRL",
                      layer_swap_key: str = "PAUSE") -> None:
        if enabled:
            self._exclusive_input = True
            if self._original_style is not None:
                self._set_click_through(False)
            self._overlay_hwnd = self._find_largest_visible_app_window()
            if self._overlay_hwnd:
                self.user32.SetWindowDisplayAffinity(self._overlay_hwnd, self.WDA_EXCLUDEFROMCAPTURE)
            self._register_hotkeys(stop_key, swap_key, layer_swap_key)
            # Always pass native mouse events through the preview window.
            # Window sources are routed by the hook; display sources keep the
            # OS path for games, raw input, and taskbar interaction.
            self._set_click_through(True)
            if not self._mouse_hook:
                self.emit({"event": "error", "message": "Window-target mouse forwarding is unavailable; native desktop mouse input remains responsive"})
        else:
            self._restore_layer_windows()
            self._restore_aligned_window()
            self._release_mouse_buttons()
            self._exclusive_input = False
            self._mouse_target_hwnd = 0
            self._desktop_input = False
            self._blocked_mouse_buttons.clear()
            self._passthrough_mouse_buttons.clear()
            self._mouse_forwarding = False
            hwnd = self._overlay_hwnd
            self._set_click_through(False)
            if hwnd and self.user32.IsWindow(hwnd):
                self.user32.SetWindowDisplayAffinity(hwnd, 0)
            self._unregister_hotkeys()

    def close(self) -> None:
        self._restore_layer_windows()
        self._restore_aligned_window()
        self._release_mouse_buttons()
        self._exclusive_input = False
        self._desktop_input = False
        self._set_click_through(False)
        self._unregister_hotkeys()
        self._mouse_jobs.put(None)
        if self._hwnd:
            self.user32.SetWindowDisplayAffinity(self._hwnd, 0)

    def list_windows(self) -> list[dict]:
        current_pid = os.getppid()
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        windows: list[dict] = []

        def visit(hwnd: int, _lparam: int) -> bool:
            process_id = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(process_id))
            if process_id.value == current_pid or not self.user32.IsWindowVisible(hwnd):
                return True
            length = self.user32.GetWindowTextLengthW(hwnd)
            if length <= 0:
                return True
            buffer = ctypes.create_unicode_buffer(length + 1)
            self.user32.GetWindowTextW(hwnd, buffer, length + 1)
            title = buffer.value.strip()
            if not title:
                return True
            path = self._process_image_path(process_id.value)
            rect = _Rect()
            if not self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
                return True
            windows.append({
                "title64": base64.b64encode(title.encode("utf-8")).decode("ascii"),
                "process64": base64.b64encode(os.path.basename(path).encode("utf-8")).decode("ascii"),
                "hwnd": int(hwnd),
                "left": int(rect.left), "top": int(rect.top),
                "width": max(1, int(rect.right - rect.left)), "height": max(1, int(rect.bottom - rect.top)),
            })
            return True

        callback = callback_type(visit)
        self.user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
        self.user32.EnumWindows(callback, 0)
        windows.sort(key=lambda item: (base64.b64decode(item["process64"]).decode("utf-8").casefold(),
                                       base64.b64decode(item["title64"]).decode("utf-8").casefold()))
        return windows

    def _process_image_path(self, process_id: int) -> str:
        process = self.kernel32.OpenProcess(0x1000, False, process_id)
        if not process:
            return "Application"
        try:
            size = wintypes.DWORD(32768)
            buffer = ctypes.create_unicode_buffer(size.value)
            if self.kernel32.QueryFullProcessImageNameW(process, 0, buffer, ctypes.byref(size)):
                return buffer.value
            return "Application"
        finally:
            self.kernel32.CloseHandle(process)

    def focus_window(self, title: str, hwnd: int = 0) -> bool:
        if not title or title.lower() in {"desktop", "work desktop", "media desktop", "game desktop"}:
            return False
        hwnd = hwnd if hwnd and self.user32.IsWindow(hwnd) else self.user32.FindWindowW(None, title)
        if not hwnd:
            return False
        if self.user32.IsIconic(hwnd):
            self.user32.ShowWindow(hwnd, self.SW_RESTORE)
        foreground = self.user32.GetForegroundWindow()
        foreground_thread = self.user32.GetWindowThreadProcessId(foreground, None) if foreground else 0
        target_thread = self.user32.GetWindowThreadProcessId(hwnd, None)
        current_thread = self.kernel32.GetCurrentThreadId()
        attached = False
        if foreground_thread and foreground_thread != current_thread:
            attached = bool(self.user32.AttachThreadInput(current_thread, foreground_thread, True))
        try:
            if not self._layer_layout_active:
                self.user32.BringWindowToTop(hwnd)
            focused = bool(self.user32.SetForegroundWindow(hwnd))
            if not focused and target_thread and target_thread != current_thread:
                target_attached = bool(self.user32.AttachThreadInput(current_thread, target_thread, True))
                focused = bool(self.user32.SetForegroundWindow(hwnd))
                if target_attached:
                    self.user32.AttachThreadInput(current_thread, target_thread, False)
            if not self._layer_layout_active:
                self.user32.SetActiveWindow(hwnd)
            return focused
        finally:
            if attached:
                self.user32.AttachThreadInput(current_thread, foreground_thread, False)

    def set_mouse_target(self, hwnd: int, rect: tuple[int, int, int, int], excluded: tuple[int, int, int, int] = (0, 0, 0, 0),
                         desktop_input: bool = False) -> None:
        if not self._layer_layout_active:
            self._restore_aligned_window()
        self._release_mouse_buttons()
        self._mouse_target_hwnd = int(hwnd) if hwnd and self.user32.IsWindow(hwnd) else 0
        self._desktop_input = bool(desktop_input)
        self._mouse_target_rect = tuple(int(value) for value in rect)
        self._mouse_excluded_rect = tuple(int(value) for value in excluded)
        self._mouse_forwarding = False
        self._mouse_last_target_point = (0, 0)
        self._blocked_mouse_buttons.clear()
        self._passthrough_mouse_buttons.clear()
        self._mouse_button_flags = 0
        self._mouse_button_targets.clear()
        if self._layer_layout_active:
            self._update_layer_input()
        elif self._exclusive_input and self._mouse_target_hwnd and not self._desktop_input:
            self._align_target_window(self._mouse_target_hwnd, self._mouse_target_rect)

    def set_layer_layout(self, items: list[dict]) -> None:
        if not self._exclusive_input:
            return
        self._layer_layout_active = True
        valid = [item for item in items if int(item.get("hwnd", 0)) and
                 self.user32.IsWindow(int(item.get("hwnd", 0))) and
                 int(item.get("width", 0)) > 0 and int(item.get("height", 0)) > 0]
        self._layer_order = [int(item["hwnd"]) for item in valid]
        get_style = self.user32.GetWindowLongPtrW
        set_style = self.user32.SetWindowLongPtrW
        for hwnd in list(self._layer_windows):
            if hwnd not in self._layer_order:
                self._restore_layer_window(hwnd)
        for item in reversed(valid):
            hwnd = int(item.get("hwnd", 0))
            x, y = int(item.get("x", 0)), int(item.get("y", 0))
            width, height = int(item.get("width", 0)), int(item.get("height", 0))
            if hwnd not in self._layer_windows:
                old = _Rect()
                if not self.user32.GetWindowRect(hwnd, ctypes.byref(old)):
                    continue
                style = int(get_style(hwnd, self.GWL_EXSTYLE))
                color = wintypes.COLORREF()
                alpha = wintypes.BYTE(255)
                flags = wintypes.DWORD()
                had_alpha = bool(style & self.WS_EX_LAYERED and self.user32.GetLayeredWindowAttributes(
                    hwnd, ctypes.byref(color), ctypes.byref(alpha), ctypes.byref(flags)))
                self._layer_windows[hwnd] = ((old.left, old.top, old.right - old.left, old.bottom - old.top),
                                             style, int(color.value), int(alpha.value), int(flags.value) if had_alpha else 0)
                if self.user32.IsIconic(hwnd):
                    self.user32.ShowWindow(hwnd, self.SW_RESTORE)
                set_style(hwnd, self.GWL_EXSTYLE, style | self.WS_EX_LAYERED)
            opacity = max(1, min(255, round(int(item.get("opacity", 100)) * 255 / 100)))
            self.user32.SetLayeredWindowAttributes(hwnd, 0, opacity, self.LWA_ALPHA)
            self.user32.SetWindowPos(hwnd, None, x, y, width, height, self.SWP_NOZORDER)
        self._update_layer_input()

    def _update_layer_input(self) -> None:
        get_style = self.user32.GetWindowLongPtrW
        set_style = self.user32.SetWindowLongPtrW
        for hwnd in self._layer_windows:
            style = int(get_style(hwnd, self.GWL_EXSTYLE))
            desired = style & ~self.WS_EX_TRANSPARENT if hwnd == self._mouse_target_hwnd else style | self.WS_EX_TRANSPARENT
            if desired != style:
                set_style(hwnd, self.GWL_EXSTYLE, desired)
                self.user32.SetWindowPos(hwnd, None, 0, 0, 0, 0,
                    self.SWP_NOMOVE | self.SWP_NOSIZE | self.SWP_NOZORDER | self.SWP_NOACTIVATE | self.SWP_FRAMECHANGED)
        # Focus can raise the input target. Reapply the independent visual order.
        self._reassert_visual_stack()
        retry = threading.Timer(0.05, self._reassert_visual_stack)
        retry.daemon = True
        retry.start()

    def _reassert_visual_stack(self) -> None:
        if not self._layer_layout_active:
            return
        flags = self.SWP_NOMOVE | self.SWP_NOSIZE | self.SWP_NOACTIVATE
        # The Java preview paints visual order independently. Native hit
        # testing must keep the input target above the other source windows.
        native_order = [hwnd for hwnd in self._layer_order if hwnd != self._mouse_target_hwnd]
        if self._mouse_target_hwnd in self._layer_order:
            native_order.insert(0, self._mouse_target_hwnd)
        for hwnd in reversed(native_order):
            if hwnd in self._layer_windows and self.user32.IsWindow(hwnd):
                if not int(self.user32.GetWindowLongPtrW(hwnd, self.GWL_EXSTYLE)) & self.WS_EX_TOPMOST:
                    self.user32.SetWindowPos(hwnd, self.HWND_TOPMOST, 0, 0, 0, 0, flags)
                self.user32.SetWindowPos(hwnd, self.HWND_TOP, 0, 0, 0, 0, flags)

    def _emit_layer_swap(self) -> None:
        with self._layer_swap_lock:
            now = time.monotonic()
            if now - self._last_layer_swap_at < 0.25:
                return
            self._last_layer_swap_at = now
        self.emit({"event": "layer_swap"})

    def _poll_pause_key(self) -> None:
        def pressed() -> bool:
            return bool((self.user32.GetAsyncKeyState(0x13) | self.user32.GetAsyncKeyState(0x03)) & 0x8000)
        was_down = pressed()
        while not self._pause_poll_stop.wait(0.01):
            down = pressed()
            if down and not was_down:
                self._emit_layer_swap()
            was_down = down

    def _restore_layer_windows(self) -> None:
        for hwnd in list(self._layer_windows):
            self._restore_layer_window(hwnd)
        self._layer_order = []
        self._layer_layout_active = False

    def _restore_layer_window(self, hwnd: int) -> None:
        rect, style, color, alpha, flags = self._layer_windows.pop(hwnd)
        try:
            if not self.user32.IsWindow(hwnd):
                return
            if flags:
                self.user32.SetLayeredWindowAttributes(hwnd, color, alpha, flags)
            self.user32.SetWindowLongPtrW(hwnd, self.GWL_EXSTYLE, style)
            x, y, width, height = rect
            self.user32.SetWindowPos(hwnd, self.HWND_TOPMOST if style & self.WS_EX_TOPMOST else self.HWND_NOTOPMOST,
                x, y, width, height, self.SWP_NOACTIVATE | self.SWP_FRAMECHANGED)
        except OSError:
            pass

    def _align_target_window(self, hwnd: int, rect: tuple[int, int, int, int]) -> None:
        x, y, width, height = rect
        if width <= 0 or height <= 0:
            self.emit({"event": "error", "message": "Window input needs visible preview bounds"})
            return
        old = _Rect()
        if not self.user32.GetWindowRect(hwnd, ctypes.byref(old)):
            return
        original = (old.left, old.top, old.right - old.left, old.bottom - old.top)
        if self.user32.IsIconic(hwnd):
            self.user32.ShowWindow(hwnd, self.SW_RESTORE)
        self._aligned_window = (hwnd, original)
        if not self.user32.SetWindowPos(hwnd, None, x, y, width, height, self.SWP_NOZORDER):
            self._aligned_window = None
            self.emit({"event": "error", "message": "Could not align selected window for mouse input"})
            return
        self.user32.BringWindowToTop(hwnd)
        self.user32.SetForegroundWindow(hwnd)

    def _restore_aligned_window(self) -> None:
        aligned = self._aligned_window
        self._aligned_window = None
        if aligned is None:
            return
        hwnd, (x, y, width, height) = aligned
        if self.user32.IsWindow(hwnd):
            self.user32.SetWindowPos(hwnd, None, x, y, width, height, self.SWP_NOZORDER | self.SWP_NOACTIVATE)

    def _forward_mouse(self, message: int, data: _MouseHookData) -> bool:
        if not self._exclusive_input:
            return False
        ex, ey, ew, eh = self._mouse_excluded_rect
        x, y = int(data.pt.x), int(data.pt.y)
        if ex <= x < ex + ew and ey <= y < ey + eh:
            return False
        # Display capture is a live view of the desktop itself. Its overlay is
        # WS_EX_TRANSPARENT, so preserve native pointer events for apps, games,
        # the taskbar, and raw-input consumers instead of swallowing/replaying.
        if self._desktop_input:
            return False
        if self._layer_layout_active:
            # The selected native window is positioned below the click-through
            # preview. Windows must dispatch the physical mouse event itself:
            # consuming it here and replaying it later loses clicks in apps
            # that use raw input, pointer capture, or Chromium's input stack.
            left, top, width, height = self._mouse_target_rect
            inside = left <= x < left + width and top <= y < top + height
            if message == self.WM_MOUSEMOVE:
                return False
            # Keep button releases native even after a drag leaves the source;
            # the target may own mouse capture until the release arrives.
            if message in (self.WM_LBUTTONUP, self.WM_RBUTTONUP, self.WM_MBUTTONUP):
                return False
            if inside and self._mouse_target_hwnd and self.user32.IsWindow(self._mouse_target_hwnd):
                return False
            # Clicking outside a window source must not activate the desktop.
            return message in (self.WM_LBUTTONDOWN, self.WM_RBUTTONDOWN, self.WM_MBUTTONDOWN,
                               self.WM_XBUTTONDOWN, self.WM_MOUSEWHEEL, self.WM_MOUSEHWHEEL)
        # The selected window is aligned under its preview. Normally the
        # preview is click-through. Some Java layered windows still win hit
        # testing; replay only those button events after briefly hiding it.
        if self._aligned_window and self._aligned_window[0] == self._mouse_target_hwnd:
            if message == self.WM_MOUSEMOVE:
                return False
            x, y = int(data.pt.x), int(data.pt.y)
            left, top, width, height = self._mouse_target_rect
            if not (left <= x < left + width and top <= y < top + height):
                return False
            hit = self.user32.WindowFromPoint(_Point(x, y))
            root = self.user32.GetAncestor(hit, self.GA_ROOT) if hit else 0
            if root == self._overlay_hwnd and self._overlay_hwnd:
                wheel = ctypes.c_short((data.mouseData >> 16) & 0xFFFF).value
                self._mouse_jobs.put((self._mouse_target_hwnd, message, x, y, wheel, 0, 0, True))
                return True
            if root == self._mouse_target_hwnd:
                return False
            self.emit({"event": "error", "message": "Selected window is not at its preview position"})
            return True
        # Alignment failed or has not completed. Never revive the old cursor
        # warping route: it clicked at unrelated screen coordinates.
        if self._mouse_target_hwnd:
            left, top, width, height = self._mouse_target_rect
            x, y = int(data.pt.x), int(data.pt.y)
            inside = left <= x < left + width and top <= y < top + height
            button = {self.WM_LBUTTONDOWN: 1, self.WM_LBUTTONUP: 1,
                      self.WM_RBUTTONDOWN: 2, self.WM_RBUTTONUP: 2,
                      self.WM_MBUTTONDOWN: 3, self.WM_MBUTTONUP: 3}.get(message)
            if message in (self.WM_LBUTTONDOWN, self.WM_RBUTTONDOWN, self.WM_MBUTTONDOWN) and not inside:
                self._passthrough_mouse_buttons.add(button)
            if message in (self.WM_LBUTTONUP, self.WM_RBUTTONUP, self.WM_MBUTTONUP) and button in self._passthrough_mouse_buttons:
                self._passthrough_mouse_buttons.discard(button)
                return False
            return message != self.WM_MOUSEMOVE and inside
        # A low-level mouse hook must never consume movement. Windows updates
        # the visible pointer only after the hook chain lets this event pass.
        # Consuming it here was the cause of the cursor freezing over a source.
        if message == self.WM_MOUSEMOVE:
            return False
        hwnd = self._mouse_target_hwnd
        left, top, width, height = self._mouse_target_rect
        inside = left <= x < left + width and top <= y < top + height
        if not hwnd or not self.user32.IsWindow(hwnd):
            # An invalid source must not receive clicks, but normal desktop
            # events outside its preview remain untouched.
            return bool(inside and message != self.WM_MOUSEMOVE)
        button_pairs = {
            self.WM_LBUTTONDOWN: (self.WM_LBUTTONUP, 1, self.MK_LBUTTON),
            self.WM_RBUTTONDOWN: (self.WM_RBUTTONUP, 2, self.MK_RBUTTON),
            self.WM_MBUTTONDOWN: (self.WM_MBUTTONUP, 3, self.MK_MBUTTON),
        }
        up_buttons = {
            self.WM_LBUTTONUP: (1, self.MK_LBUTTON),
            self.WM_RBUTTONUP: (2, self.MK_RBUTTON),
            self.WM_MBUTTONUP: (3, self.MK_MBUTTON),
        }
        button = None
        mask = 0
        if message in button_pairs:
            _up_message, button, mask = button_pairs[message]
            if not inside and not self._mouse_forwarding:
                self._passthrough_mouse_buttons.add(button)
                return False
            if button in self._passthrough_mouse_buttons:
                return True
            self._mouse_forwarding = True
            self._mouse_button_flags |= mask
        elif message == self.WM_XBUTTONDOWN:
            button = 4 if ((data.mouseData >> 16) & 0xFFFF) == 1 else 5
            mask = self.MK_XBUTTON1 if button == 4 else self.MK_XBUTTON2
            if not inside and not self._mouse_forwarding:
                self._passthrough_mouse_buttons.add(button)
                return False
            if button in self._passthrough_mouse_buttons:
                return True
            self._mouse_forwarding = True
            self._mouse_button_flags |= mask
        elif message in up_buttons:
            button, mask = up_buttons[message]
            if button in self._passthrough_mouse_buttons:
                self._passthrough_mouse_buttons.discard(button)
                return False
            if button in self._blocked_mouse_buttons:
                self._blocked_mouse_buttons.discard(button)
                return True
            if not self._mouse_forwarding:
                return False
            self._mouse_button_flags &= ~mask
            self._mouse_forwarding = bool(self._mouse_button_flags)
        elif message == self.WM_XBUTTONUP:
            button = 4 if ((data.mouseData >> 16) & 0xFFFF) == 1 else 5
            mask = self.MK_XBUTTON1 if button == 4 else self.MK_XBUTTON2
            if button in self._passthrough_mouse_buttons:
                self._passthrough_mouse_buttons.discard(button)
                return False
            if button in self._blocked_mouse_buttons:
                self._blocked_mouse_buttons.discard(button)
                return True
            if not self._mouse_forwarding:
                return False
            self._mouse_button_flags &= ~mask
            self._mouse_forwarding = bool(self._mouse_button_flags)
        elif message == self.WM_MOUSEMOVE:
            # Let the cursor roam anywhere. Route hover into the source only
            # while over its visible bounds; outside them, preserve motion.
            if self._passthrough_mouse_buttons or (not inside and not self._mouse_forwarding):
                return False
        elif message in (self.WM_MOUSEWHEEL, self.WM_MOUSEHWHEEL):
            if self._passthrough_mouse_buttons or (not inside and not self._mouse_forwarding):
                return False
        else:
            return False
        nx = min(1.0, max(0.0, (x - left) / max(1, width)))
        ny = min(1.0, max(0.0, (y - top) / max(1, height)))
        window_rect = _Rect()
        if not self.user32.GetWindowRect(hwnd, ctypes.byref(window_rect)):
            return False
        window_width = max(1, window_rect.right - window_rect.left)
        window_height = max(1, window_rect.bottom - window_rect.top)
        target_x = window_rect.left + min(window_width - 1, int(nx * window_width))
        target_y = window_rect.top + min(window_height - 1, int(ny * window_height))
        self._mouse_last_target_point = (target_x, target_y)
        wheel = ctypes.c_short((data.mouseData >> 16) & 0xFFFF).value if message in (self.WM_MOUSEWHEEL, self.WM_MOUSEHWHEEL) else 0
        x_button = (data.mouseData >> 16) & 0xFFFF if message in (self.WM_XBUTTONDOWN, self.WM_XBUTTONUP) else 0
        job = (hwnd, message, target_x, target_y, wheel, x_button, self._mouse_button_flags)
        if message == self.WM_MOUSEMOVE:
            # Keep at most the newest hover position after the latest button or
            # wheel event. Replaying a backlog of obsolete moves makes input
            # visibly lag behind the physical pointer.
            with self._mouse_jobs.mutex:
                if self._mouse_jobs.queue and self._mouse_jobs.queue[-1][1] == self.WM_MOUSEMOVE:
                    self._mouse_jobs.queue[-1] = job
                    return True
                self._mouse_jobs.queue.append(job)
                self._mouse_jobs.unfinished_tasks += 1
                self._mouse_jobs.not_empty.notify()
        else:
            self._mouse_jobs.put(job)
        return True

    def _mouse_loop(self) -> None:
        while True:
            job = self._mouse_jobs.get()
            if job is None:
                return
            hwnd, message, target_x, target_y, wheel, x_button, buttons = job[:7]
            with self._mouse_replay_lock:
                if not self._exclusive_input or not self.user32.IsWindow(hwnd):
                    continue
                try:
                    if len(job) > 7 and job[7] == "hover":
                        self._post_window_mouse(hwnd, message, target_x, target_y, 0, 0, 0)
                        continue
                    if len(job) > 7 and job[7]:
                        self._post_native_at_point(hwnd, message, target_x, target_y, wheel)
                        continue
                    down_masks = {
                        self.WM_LBUTTONDOWN: self.MK_LBUTTON,
                        self.WM_RBUTTONDOWN: self.MK_RBUTTON,
                        self.WM_MBUTTONDOWN: self.MK_MBUTTON,
                    }
                    up_masks = {
                        self.WM_LBUTTONUP: self.MK_LBUTTON,
                        self.WM_RBUTTONUP: self.MK_RBUTTON,
                        self.WM_MBUTTONUP: self.MK_MBUTTON,
                    }
                    if message in (self.WM_XBUTTONDOWN, self.WM_XBUTTONUP):
                        x_mask = self.MK_XBUTTON1 if x_button == 1 else self.MK_XBUTTON2
                        (down_masks if message == self.WM_XBUTTONDOWN else up_masks)[message] = x_mask
                    forced_target = 0
                    if message in up_masks:
                        forced_target = self._mouse_button_targets.get(up_masks[message], 0)
                    elif message == self.WM_MOUSEMOVE and self._mouse_button_targets:
                        forced_target = next(reversed(self._mouse_button_targets.values()))
                    posted_target = self._post_target_mouse(hwnd, message, target_x, target_y, wheel, x_button, buttons, forced_target)
                    if message in down_masks and posted_target:
                        self._mouse_button_targets[down_masks[message]] = posted_target
                    elif message in up_masks:
                        self._mouse_button_targets.pop(up_masks[message], None)
                except Exception as error:
                    self.emit({"event": "error", "message": f"Window input failed: {error}"})

    def _post_native_at_point(self, hwnd: int, message: int, x: int, y: int, wheel: int) -> None:
        flags = {
            self.WM_LBUTTONDOWN: self.MOUSEEVENTF_LEFTDOWN,
            self.WM_LBUTTONUP: self.MOUSEEVENTF_LEFTUP,
            self.WM_RBUTTONDOWN: self.MOUSEEVENTF_RIGHTDOWN,
            self.WM_RBUTTONUP: self.MOUSEEVENTF_RIGHTUP,
            self.WM_MBUTTONDOWN: self.MOUSEEVENTF_MIDDLEDOWN,
            self.WM_MBUTTONUP: self.MOUSEEVENTF_MIDDLEUP,
            self.WM_MOUSEWHEEL: self.MOUSEEVENTF_WHEEL,
            self.WM_MOUSEHWHEEL: self.MOUSEEVENTF_HWHEEL,
        }
        flag = flags.get(message)
        if flag is None:
            return
        overlay = self._overlay_hwnd if self._overlay_hwnd and self.user32.IsWindow(self._overlay_hwnd) else 0
        if overlay:
            self.user32.ShowWindow(overlay, self.SW_HIDE)
        try:
            hit = self.user32.WindowFromPoint(_Point(x, y))
            root = self.user32.GetAncestor(hit, self.GA_ROOT) if hit else 0
            if root != hwnd:
                # A different visual layer may be above the input layer. Put
                # the input window on top only for native dispatch, then put
                # the visual stack back in the finally block below.
                self.user32.SetWindowPos(hwnd, self.HWND_TOPMOST, 0, 0, 0, 0,
                    self.SWP_NOMOVE | self.SWP_NOSIZE | self.SWP_NOACTIVATE)
                self.user32.SetWindowPos(hwnd, self.HWND_TOP, 0, 0, 0, 0,
                    self.SWP_NOMOVE | self.SWP_NOSIZE | self.SWP_NOACTIVATE)
                hit = self.user32.WindowFromPoint(_Point(x, y))
                root = self.user32.GetAncestor(hit, self.GA_ROOT) if hit else 0
                if root != hwnd:
                    self.emit({"event": "error", "message": "Selected window is not beneath the preview at the click point"})
                    return
            self.user32.mouse_event(flag, 0, 0, wheel & 0xFFFFFFFF, 0)
            time.sleep(0.02)
        finally:
            if self._layer_layout_active:
                self._reassert_visual_stack()
            if overlay:
                self.user32.ShowWindow(overlay, self.SW_SHOWNA)

    def _post_target_mouse(self, hwnd: int, message: int, screen_x: int, screen_y: int,
                           wheel: int, x_button: int, buttons: int, forced_target: int = 0) -> int:
        """Use real Windows mouse input for applications that ignore posted messages."""
        flags = {
            self.WM_LBUTTONDOWN: self.MOUSEEVENTF_LEFTDOWN,
            self.WM_LBUTTONUP: self.MOUSEEVENTF_LEFTUP,
            self.WM_RBUTTONDOWN: self.MOUSEEVENTF_RIGHTDOWN,
            self.WM_RBUTTONUP: self.MOUSEEVENTF_RIGHTUP,
            self.WM_MBUTTONDOWN: self.MOUSEEVENTF_MIDDLEDOWN,
            self.WM_MBUTTONUP: self.MOUSEEVENTF_MIDDLEUP,
            self.WM_MOUSEWHEEL: self.MOUSEEVENTF_WHEEL,
            self.WM_MOUSEHWHEEL: self.MOUSEEVENTF_HWHEEL,
        }
        flag = flags.get(message)
        if flag is None:
            return hwnd
        physical = _Point()
        if not self.user32.GetCursorPos(ctypes.byref(physical)):
            return 0
        if not self.user32.SetCursorPos(screen_x, screen_y):
            return 0
        hit = self.user32.WindowFromPoint(_Point(screen_x, screen_y))
        hit_root = self.user32.GetAncestor(hit, self.GA_ROOT) if hit else 0
        if hit_root != hwnd:
            self.user32.BringWindowToTop(hwnd)
            self.user32.SetForegroundWindow(hwnd)
            hit = self.user32.WindowFromPoint(_Point(screen_x, screen_y))
            hit_root = self.user32.GetAncestor(hit, self.GA_ROOT) if hit else 0
        overlay_covered = hit_root == self._overlay_hwnd and self._overlay_hwnd != hwnd
        if overlay_covered:
            # A Java transparent window can still win native hit testing on
            # some systems. Remove it briefly while Windows dispatches input.
            self.user32.ShowWindow(self._overlay_hwnd, self.SW_HIDE)
            try:
                hit = self.user32.WindowFromPoint(_Point(screen_x, screen_y))
                hit_root = self.user32.GetAncestor(hit, self.GA_ROOT) if hit else 0
                if hit_root != hwnd:
                    self.emit({"event": "error", "message": "Mouse target is covered by another window"})
                    return 0
                self.user32.mouse_event(flag, 0, 0, wheel & 0xFFFFFFFF, 0)
                time.sleep(0.02)
            finally:
                self.user32.ShowWindow(self._overlay_hwnd, self.SW_SHOWNA)
        elif hit_root == hwnd:
            self.user32.mouse_event(flag, 0, 0, wheel & 0xFFFFFFFF, 0)
        else:
            self.emit({"event": "error", "message": "Mouse target is covered by another window"})
            return 0
        return hwnd

    @staticmethod
    def _pack_point(x: int, y: int) -> int:
        return ((int(y) & 0xFFFF) << 16) | (int(x) & 0xFFFF)

    def _child_at_screen_point(self, hwnd: int, screen_x: int, screen_y: int) -> tuple[int, _Point]:
        target = int(hwnd)
        point = _Point(screen_x, screen_y)
        skip_flags = self.CWP_SKIPINVISIBLE | self.CWP_SKIPDISABLED | self.CWP_SKIPTRANSPARENT
        for _ in range(32):
            point = _Point(screen_x, screen_y)
            if not self.user32.ScreenToClient(target, ctypes.byref(point)):
                break
            child = int(self.user32.ChildWindowFromPointEx(target, point, skip_flags) or 0)
            if not child or child == target:
                return target, point
            target = child
        point = _Point(screen_x, screen_y)
        self.user32.ScreenToClient(target, ctypes.byref(point))
        return target, point

    def _post_window_mouse(self, hwnd: int, message: int, screen_x: int, screen_y: int,
                           wheel: int, x_button: int, buttons: int, forced_target: int = 0) -> int:
        if forced_target:
            target = forced_target
            client_point = _Point(screen_x, screen_y)
            self.user32.ScreenToClient(target, ctypes.byref(client_point))
        else:
            target, client_point = self._child_at_screen_point(hwnd, screen_x, screen_y)
        key_state = int(buttons)
        for key, mask in ((self.VK_SHIFT, self.MK_SHIFT), (self.VK_CONTROL, self.MK_CONTROL)):
            if self.user32.GetKeyState(key) & 0x8000:
                key_state |= mask
        if message in (self.WM_MOUSEWHEEL, self.WM_MOUSEHWHEEL):
            wparam = (key_state & 0xFFFF) | ((wheel & 0xFFFF) << 16)
            lparam = self._pack_point(screen_x, screen_y)
        else:
            wparam = key_state & 0xFFFF
            if message in (self.WM_XBUTTONDOWN, self.WM_XBUTTONUP):
                wparam |= (x_button & 0xFFFF) << 16
            lparam = self._pack_point(client_point.x, client_point.y)
        if not self.user32.PostMessageW(target, message, wparam, lparam):
            if message != self.WM_MOUSEMOVE:
                self.emit({"event": "error", "message": "Windows rejected mouse input for the selected window"})
        if message == self.WM_MOUSEMOVE:
            cursor_param = (self.WM_MOUSEMOVE << 16) | self.HTCLIENT
            self.user32.PostMessageW(target, self.WM_SETCURSOR, target, cursor_param)
        return target

    def _release_mouse_buttons(self) -> None:
        """Release any synthetic button held by a stop or teardown during a drag."""
        with self._mouse_jobs.mutex:
            queued = len(self._mouse_jobs.queue)
            self._mouse_jobs.queue.clear()
            self._mouse_jobs.unfinished_tasks = max(0, self._mouse_jobs.unfinished_tasks - queued)
        with self._mouse_replay_lock:
            buttons = self._mouse_button_flags
            target = self._mouse_target_hwnd
            if buttons and target and self.user32.IsWindow(target):
                x, y = self._mouse_last_target_point
                for mask, message, x_button in (
                    (self.MK_LBUTTON, self.WM_LBUTTONUP, 0),
                    (self.MK_RBUTTON, self.WM_RBUTTONUP, 0),
                    (self.MK_MBUTTON, self.WM_MBUTTONUP, 0),
                    (self.MK_XBUTTON1, self.WM_XBUTTONUP, 1),
                    (self.MK_XBUTTON2, self.WM_XBUTTONUP, 2),
                ):
                    if buttons & mask:
                        self._post_window_mouse(target, message, x, y, 0, x_button, buttons & ~mask,
                                                self._mouse_button_targets.get(mask, 0))
            self._mouse_button_flags = 0
            self._mouse_forwarding = False
            self._blocked_mouse_buttons.clear()
            self._passthrough_mouse_buttons.clear()
            self._mouse_button_targets.clear()

    def capture_window(self, title: str, output_path: str, hwnd: int = 0, method: str = "AUTO") -> bool:
        """Capture a target without silently replacing its pixels with the desktop."""
        if not title:
            return False
        hwnd = hwnd if hwnd and self.user32.IsWindow(hwnd) else self.user32.FindWindowW(None, title)
        if not hwnd or not self.user32.IsWindow(hwnd):
            return False
        rect = _Rect()
        if not self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
            return False
        try:
            dwmapi = ctypes.WinDLL("dwmapi", use_last_error=True)
            dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]
            visible_rect = _Rect()
            if dwmapi.DwmGetWindowAttribute(hwnd, 9, ctypes.byref(visible_rect), ctypes.sizeof(visible_rect)) == 0:
                rect = visible_rect
        except (OSError, AttributeError):
            pass
        width, height = rect.right - rect.left, rect.bottom - rect.top
        if width <= 0 or height <= 0 or width * height > 30_000_000:
            return False
        source_dc = self.user32.GetWindowDC(hwnd)
        memory_dc = self.gdi32.CreateCompatibleDC(source_dc)
        bitmap = self.gdi32.CreateCompatibleBitmap(source_dc, width, height)
        previous = self.gdi32.SelectObject(memory_dc, bitmap)
        try:
            method = method.upper()
            printed = False
            if method != "SCREEN":
                printed = bool(self.user32.PrintWindow(hwnd, memory_dc, 2))
                if not printed:
                    printed = bool(self.user32.PrintWindow(hwnd, memory_dc, 0))
            self.gdi32.SelectObject(memory_dc, previous)
            info = _BitmapInfo()
            info.bmiHeader = _BitmapInfoHeader(
                ctypes.sizeof(_BitmapInfoHeader), width, -height, 1, 32, 0, width * height * 4, 0, 0, 0, 0
            )
            pixels = ctypes.create_string_buffer(width * height * 4)
            if not self.gdi32.GetDIBits(source_dc, bitmap, 0, height, pixels, ctypes.byref(info), 0):
                return False
            payload = bytearray(pixels.raw)
            if method != "SCREEN" and (not printed or self._looks_blank(payload)):
                return False
            if method == "SCREEN":
                if self._is_multimin_covering(rect):
                    return False
                desktop_dc = self.user32.GetDC(None)
                if not desktop_dc:
                    return False
                try:
                    self.gdi32.SelectObject(memory_dc, bitmap)
                    copied = bool(self.gdi32.BitBlt(memory_dc, 0, 0, width, height, desktop_dc, rect.left, rect.top, 0x00CC0020))
                    self.gdi32.SelectObject(memory_dc, previous)
                finally:
                    self.user32.ReleaseDC(None, desktop_dc)
                if not copied or not self.gdi32.GetDIBits(source_dc, bitmap, 0, height, pixels, ctypes.byref(info), 0):
                    return False
                payload = bytearray(pixels.raw)
            payload[3::4] = b"\xff" * (width * height)
            header = struct.pack("<2sIHHI", b"BM", 54 + len(payload), 0, 0, 54)
            dib = struct.pack("<IiiHHIIiiII", 40, width, -height, 1, 32, 0, len(payload), 0, 0, 0, 0)
            temporary = output_path + ".tmp"
            with open(temporary, "wb") as stream:
                stream.write(header)
                stream.write(dib)
                stream.write(payload)
            os.replace(temporary, output_path)
            return True
        finally:
            self.gdi32.SelectObject(memory_dc, previous)
            self.gdi32.DeleteObject(bitmap)
            self.gdi32.DeleteDC(memory_dc)
            self.user32.ReleaseDC(hwnd, source_dc)

    @staticmethod
    def _looks_blank(payload: bytearray) -> bool:
        sample_count = 0
        black_samples = 0
        first = None
        uniform_samples = 0
        for offset in range(0, len(payload) - 2, 64):
            color = (payload[offset], payload[offset + 1], payload[offset + 2])
            if color[0] < 5 and color[1] < 5 and color[2] < 5:
                black_samples += 1
            if first is None:
                first = color
            elif color == first:
                uniform_samples += 1
            sample_count += 1
        return sample_count > 0 and (black_samples / sample_count > 0.92 or uniform_samples / sample_count > 0.98)

    def _is_multimin_covering(self, target: _Rect) -> bool:
        current_pid = os.getppid()
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        covered = False

        def visit(hwnd: int, _lparam: int) -> bool:
            nonlocal covered
            process_id = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(process_id))
            if process_id.value != current_pid or not self.user32.IsWindowVisible(hwnd):
                return True
            rect = _Rect()
            if self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
                affinity = wintypes.DWORD()
                if self.user32.GetWindowDisplayAffinity(hwnd, ctypes.byref(affinity)) and affinity.value == self.WDA_EXCLUDEFROMCAPTURE:
                    return True
                overlap_width = max(0, min(target.right, rect.right) - max(target.left, rect.left))
                overlap_height = max(0, min(target.bottom, rect.bottom) - max(target.top, rect.top))
                overlap_area = overlap_width * overlap_height
                target_area = max(1, (target.right - target.left) * (target.bottom - target.top))
                # Ignore small app-owned controls such as the floating exit X;
                # they do not meaningfully obscure a captured source.
                if overlap_area >= target_area * 0.5:
                    covered = True
                    return False
            return True

        callback = callback_type(visit)
        self.user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
        self.user32.EnumWindows(callback, 0)
        return covered

    def _find_app_window(self) -> int:
        hwnd_result = 0
        current_pid = os.getppid()
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _lparam: int) -> bool:
            nonlocal hwnd_result
            process_id = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(process_id))
            if process_id.value != current_pid or not self.user32.IsWindowVisible(hwnd):
                return True
            length = self.user32.GetWindowTextLengthW(hwnd)
            if length:
                title = ctypes.create_unicode_buffer(length + 1)
                self.user32.GetWindowTextW(hwnd, title, length + 1)
                if title.value in ("MultiMin", "MultiMini"):
                    hwnd_result = hwnd
                    return False
            return True

        callback = callback_type(visit)
        self.user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
        self.user32.EnumWindows(callback, 0)
        return hwnd_result

    def _set_click_through(self, enabled: bool) -> None:
        if enabled:
            candidate = self._find_largest_visible_app_window()
            previous = getattr(self, "_overlay_style_hwnd", 0)
            if previous and previous != candidate and self._original_style is not None:
                if self.user32.IsWindow(previous):
                    self.user32.SetWindowLongPtrW(previous, self.GWL_EXSTYLE, self._original_style)
                self._original_style = None
            self._overlay_hwnd = candidate
            self._overlay_style_hwnd = candidate
            if not candidate:
                self.emit({"event": "error", "message": "Could not find MultiMin overlay for mouse click-through"})
        hwnd = self._overlay_hwnd
        if not hwnd or not self.user32.IsWindow(hwnd):
            return
        get_style = self.user32.GetWindowLongPtrW
        set_style = self.user32.SetWindowLongPtrW
        get_style.argtypes = [wintypes.HWND, ctypes.c_int]
        get_style.restype = ctypes.c_ssize_t
        set_style.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_ssize_t]
        set_style.restype = ctypes.c_ssize_t
        style = get_style(hwnd, self.GWL_EXSTYLE)
        if enabled:
            if self._original_style is None:
                self._original_style = style
            set_style(hwnd, self.GWL_EXSTYLE, style | self.WS_EX_LAYERED | self.WS_EX_TRANSPARENT)
            # Swing owns this window's per-pixel alpha. SetLayeredWindowAttributes
            # switches it to constant-alpha mode and can stop later paints from
            # updating the visual composition.
            self.user32.SetWindowPos(hwnd, None, 0, 0, 0, 0,
                self.SWP_NOMOVE | self.SWP_NOSIZE | self.SWP_NOZORDER |
                self.SWP_NOACTIVATE | self.SWP_FRAMECHANGED)
        elif self._original_style is not None:
            set_style(hwnd, self.GWL_EXSTYLE, self._original_style)
            self._original_style = None
            self._overlay_hwnd = 0
            self._overlay_style_hwnd = 0

    def refresh_overlay_hit_test(self) -> None:
        if self._exclusive_input:
            self._set_click_through(True)

    def _find_largest_visible_app_window(self) -> int:
        result = 0
        largest_area = 0
        current_pid = self._app_pid or os.getppid()
        callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def visit(hwnd: int, _lparam: int) -> bool:
            nonlocal result, largest_area
            process_id = wintypes.DWORD()
            self.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(process_id))
            if process_id.value == current_pid and self.user32.IsWindowVisible(hwnd):
                rect = _Rect()
                if self.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
                    area = max(0, rect.right - rect.left) * max(0, rect.bottom - rect.top)
                    if area > largest_area:
                        largest_area, result = area, hwnd
            return True

        callback = callback_type(visit)
        self.user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
        self.user32.EnumWindows(callback, 0)
        return result

    def _register_hotkeys(self, stop_key: str = "END", swap_key: str = "RIGHT_CTRL", layer_swap_key: str = "") -> None:
        if self._hotkey_thread and self._hotkey_thread.is_alive():
            self._unregister_hotkeys()
        stop_key = (stop_key or "END").upper()
        if stop_key == "CTRL_SHIFT_SPACE":
            stop_key = "CTRL+SHIFT+SPACE"
        self._stop_binding = stop_key
        self._swap_key = (swap_key or "RIGHT_CTRL").upper()
        self._layer_swap_key = (layer_swap_key or "").upper()
        self._pressed_keys.clear()
        self._swap_key_down = False
        self._layer_swap_key_down = False
        self._stop_key_down = False
        self._emergency_key_down = False
        stop_parts = stop_key.split("+")
        stop_vk = self._binding_key_code(stop_parts[-1]) or self.VK_END
        stop_modifiers = self.MOD_NOREPEAT
        for modifier in stop_parts[:-1]:
            stop_modifiers |= {"CTRL": self.MOD_CONTROL, "CONTROL": self.MOD_CONTROL,
                               "SHIFT": self.MOD_SHIFT, "ALT": self.MOD_ALT, "WIN": self.MOD_WIN}.get(modifier, 0)
        ready = threading.Event()

        def message_loop() -> None:
            self._hotkey_thread_id = self.kernel32.GetCurrentThreadId()
            queue_message = wintypes.MSG()
            self.user32.PeekMessageW(ctypes.byref(queue_message), None, 0, 0, 0)
            self._layer_hotkey_registered = (self._layer_swap_key == "PAUSE" and
                bool(self.user32.RegisterHotKey(None, self.LAYER_SWAP_ID, self.MOD_NOREPEAT, 0x13)))
            hook_type = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)

            def handle_key(code, message, data_pointer):
                if code >= 0:
                    data = ctypes.cast(data_pointer, ctypes.POINTER(_KeyboardHookData)).contents
                    key_down = message in (self.WM_KEYDOWN, self.WM_SYSKEYDOWN)
                    key_up = message in (self.WM_KEYUP, self.WM_SYSKEYUP)
                    vk = int(data.vkCode)
                    right_control = vk == self.VK_RCONTROL or (vk == self.VK_CONTROL and bool(data.flags & self.LLKHF_EXTENDED))
                    normalized = self.VK_RCONTROL if right_control else vk
                    emergency_release = key_up and normalized == self.VK_SPACE and self._emergency_key_down
                    stop_release = key_up and normalized == self._binding_key_code(self._stop_binding.split("+")[-1]) and self._stop_key_down
                    swap_key = self._matches_swap_key(normalized)
                    layer_swap_key = bool(self._layer_swap_key) and self._matches_binding(self._layer_swap_key, normalized)
                    if key_down:
                        self._pressed_keys.add(normalized)
                        swap_key = self._matches_swap_key(normalized)
                        layer_swap_key = bool(self._layer_swap_key) and self._matches_binding(self._layer_swap_key, normalized)
                        if swap_key:
                            if not self._swap_key_down:
                                self.emit({"event": "quick_swap"})
                            self._swap_key_down = True
                        if layer_swap_key:
                            if normalized in (0x13, 0x03) or not self._layer_swap_key_down:
                                self._emit_layer_swap()
                            self._layer_swap_key_down = normalized not in (0x13, 0x03)
                    elif key_up:
                        self._pressed_keys.discard(normalized)
                        if swap_key:
                            self._swap_key_down = False
                        if normalized == self._binding_key_code(self._layer_swap_key.split("+")[-1]):
                            self._layer_swap_key_down = False
                    if key_down and self._exclusive_input:
                        emergency = normalized == self.VK_SPACE and self._modifier_down("CTRL") and self._modifier_down("SHIFT")
                        stop = self._matches_binding(self._stop_binding, normalized)
                        if emergency and not self._emergency_key_down:
                            self._emergency_key_down = True
                            self.emit({"event": "toggle_multitask"})
                            return 1
                        if stop and not self._stop_key_down:
                            self._stop_key_down = True
                            self.emit({"event": "toggle_multitask"})
                            return 1
                    if key_up:
                        if normalized == self.VK_SPACE:
                            self._emergency_key_down = False
                        if normalized == self._binding_key_code(self._stop_binding.split("+")[-1]):
                            self._stop_key_down = False
                    if emergency_release or stop_release:
                        return 1
                    if swap_key or (layer_swap_key and not self._layer_hotkey_registered):
                        return 1
                    if self._exclusive_input:
                        target = self._mouse_target_hwnd
                        target_valid = self._desktop_input or bool(target and self.user32.IsWindow(target))
                        windows_key = normalized in (0x5B, 0x5C) or 0x5B in self._pressed_keys or 0x5C in self._pressed_keys
                        task_switch = normalized == 0x09 and self.VK_MENU in self._pressed_keys
                        start_menu = normalized == 0x1B and self.VK_CONTROL in self._pressed_keys
                        target_shortcut = ((normalized == self.VK_MENU or self.VK_MENU in self._pressed_keys)
                                           or (normalized in (0x09, 0x1B) and (self.VK_CONTROL in self._pressed_keys or self.VK_RCONTROL in self._pressed_keys)))
                        if windows_key or task_switch or start_menu or (target_shortcut and target_valid) or not target_valid:
                            return 1
                        if target_valid and message in (self.WM_KEYDOWN, self.WM_SYSKEYDOWN, self.WM_KEYUP, self.WM_SYSKEYUP):
                            # Normal keystrokes are delivered by Windows to the
                            # selected app after it is focused. Consume system
                            # keys here too so they cannot act on another app.
                            if message in (self.WM_SYSKEYDOWN, self.WM_SYSKEYUP):
                                return 1
                return self.user32.CallNextHookEx(self._keyboard_hook, code, message, data_pointer)

            try:
                self._keyboard_hook_proc = hook_type(handle_key)
                self._keyboard_hook = self.user32.SetWindowsHookExW(self.WH_KEYBOARD_LL, self._keyboard_hook_proc, None, 0)
                mouse_hook_type = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)

                def handle_mouse(code, message, data_pointer):
                    if code >= 0:
                        try:
                            data = ctypes.cast(data_pointer, ctypes.POINTER(_MouseHookData)).contents
                            if data.flags & self.LLMHF_INJECTED:
                                return self.user32.CallNextHookEx(self._mouse_hook, code, message, data_pointer)
                            if self._forward_mouse(int(message), data):
                                return 1
                        except Exception:
                            pass
                    return self.user32.CallNextHookEx(self._mouse_hook, code, message, data_pointer)

                self._mouse_hook_proc = mouse_hook_type(handle_mouse)
                self._mouse_hook = self.user32.SetWindowsHookExW(self.WH_MOUSE_LL, self._mouse_hook_proc, None, 0)
                ready.set()
                if not self._keyboard_hook:
                    self.emit({"event": "error", "message": "Keyboard input hook could not be installed"})
                if not self._mouse_hook:
                    self.emit({"event": "error", "message": "Window input mouse hook could not be installed"})
                message = wintypes.MSG()
                while self.user32.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
                    if message.message == self.WM_HOTKEY and message.wParam == self.LAYER_SWAP_ID:
                        self._emit_layer_swap()
                    if message.message == self.WM_QUIT:
                        break
            except Exception as error:
                self.emit({"event": "error", "message": f"Input hook stopped: {error}"})
            finally:
                ready.set()
                if self._layer_hotkey_registered:
                    self.user32.UnregisterHotKey(None, self.LAYER_SWAP_ID)
                    self._layer_hotkey_registered = False
                if self._keyboard_hook:
                    self.user32.UnhookWindowsHookEx(self._keyboard_hook)
                    self._keyboard_hook = None
                self._keyboard_hook_proc = None
                if self._mouse_hook:
                    self.user32.UnhookWindowsHookEx(self._mouse_hook)
                    self._mouse_hook = None
                self._mouse_hook_proc = None
                self._hotkey_thread_id = 0

        self._hotkey_thread = threading.Thread(target=message_loop, name="multimin-global-hotkeys", daemon=True)
        self._hotkey_thread.start()
        ready.wait(timeout=2)
        if self._layer_swap_key == "PAUSE":
            self._pause_poll_stop.clear()
            self._pause_poll_thread = threading.Thread(target=self._poll_pause_key,
                name="multimin-pause-key", daemon=True)
            self._pause_poll_thread.start()

    def _matches_swap_key(self, key: int) -> bool:
        parts = self._swap_key.split("+")
        target = self._binding_key_code(parts[-1])
        if target is None or key != target:
            return False
        pressed = self._pressed_keys
        for modifier in parts[:-1]:
            expected = {"CTRL": self.VK_CONTROL, "CONTROL": self.VK_CONTROL, "SHIFT": self.VK_SHIFT, "ALT": self.VK_MENU}.get(modifier)
            if expected is None or (expected not in pressed and not (expected == self.VK_CONTROL and self.VK_RCONTROL in pressed)):
                return False
        return True

    def _modifier_down(self, modifier: str) -> bool:
        aliases = {
            "CTRL": (self.VK_CONTROL, 0xA2, self.VK_RCONTROL),
            "CONTROL": (self.VK_CONTROL, 0xA2, self.VK_RCONTROL),
            "SHIFT": (self.VK_SHIFT, 0xA0, 0xA1),
            "ALT": (self.VK_MENU, 0xA4, 0xA5),
            "WIN": (0x5B, 0x5C),
        }.get(modifier.upper(), ())
        return any(code in self._pressed_keys for code in aliases)

    def _matches_binding(self, binding: str, key: int) -> bool:
        parts = binding.upper().split("+")
        target = self._binding_key_code(parts[-1])
        same_key = target == key or (target == 0x13 and key == 0x03)
        return same_key and all(self._modifier_down(part) for part in parts[:-1])

    def _binding_key_code(self, token: str) -> int | None:
        token = token.upper()
        key_codes = {"RIGHT_CTRL": self.VK_RCONTROL, "CTRL": self.VK_CONTROL, "CONTROL": self.VK_CONTROL,
                     "SHIFT": self.VK_SHIFT, "ALT": self.VK_MENU, "SPACE": self.VK_SPACE,
                     "ESCAPE": 0x1B, "ENTER": 0x0D, "TAB": 0x09, "END": 0x23, "PAUSE": 0x13,
                     "HOME": 0x24, "INSERT": 0x2D, "DELETE": 0x2E,
                     "UP": 0x26, "DOWN": 0x28, "LEFT": 0x25, "RIGHT": 0x27,
                     "PAGE_UP": 0x21, "PAGE_DOWN": 0x22}
        if token.startswith("F") and token[1:].isdigit() and 1 <= int(token[1:]) <= 12:
            return 0x70 + int(token[1:]) - 1
        if len(token) == 1 and token.isalnum():
            return ord(token)
        return key_codes.get(token)

    def _unregister_hotkeys(self) -> None:
        self._pause_poll_stop.set()
        poll_thread = self._pause_poll_thread
        if poll_thread and poll_thread.is_alive() and poll_thread is not threading.current_thread():
            poll_thread.join(timeout=0.2)
        self._pause_poll_thread = None
        thread = self._hotkey_thread
        if self._hotkey_thread_id:
            self.user32.PostThreadMessageW(self._hotkey_thread_id, self.WM_QUIT, 0, 0)
            self._hotkey_thread_id = 0
        if thread and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=1.0)
        self._hotkey_thread = None

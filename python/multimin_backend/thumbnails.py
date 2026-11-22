"""Small window previews for the window picker (standard library only).

Each platform grabs one still image of a window, shrinks it to picker size and
encodes it as PNG. These previews are only shown in the picker; they never
replace live capture and are not stored anywhere.
"""
from __future__ import annotations

import base64
import struct
import sys
import threading
import zlib


def encode_png(width, height, bgra, stride=None):
    """Encode packed BGRA rows as an opaque RGB PNG."""
    stride = stride or width * 4
    raw = bytearray()
    for y in range(height):
        row = bgra[y * stride:y * stride + width * 4]
        rgb = bytearray(width * 3)
        rgb[0::3] = row[2::4]
        rgb[1::3] = row[1::4]
        rgb[2::3] = row[0::4]
        raw.append(0)  # filter: none
        raw += rgb
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")


def downscale_bgra(width, height, bgra, max_width):
    """Nearest-neighbour shrink for platforms without a native scaler."""
    if width <= max_width:
        return width, height, bytes(bgra)
    target_w = max(1, max_width)
    target_h = max(1, round(height * target_w / width))
    pixels = memoryview(bytes(bgra)).cast("I")
    out = bytearray()
    for y in range(target_h):
        source_row = (y * height // target_h) * width
        row = [pixels[source_row + (x * width // target_w)] for x in range(target_w)]
        out += struct.pack(f"<{target_w}I", *row)
    return target_w, target_h, bytes(out)


def to_event(handle, png, path=""):
    event = {"event": "window_thumbnail", "hwnd": int(handle),
             "png64": base64.b64encode(png).decode("ascii") if png else ""}
    if path:
        event["path64"] = base64.b64encode(path.encode("utf-8")).decode("ascii")
    return event


def thumbnail_worker(emit, grab, handles, width, describe=None):
    """Emit one window_thumbnail event per handle, off the command thread.

    `describe(handle)` may return the window's program path (used for its icon).
    """
    def work():
        for handle in handles:
            try:
                png = grab(handle, width)
            except Exception:
                png = None
            path = ""
            if describe is not None:
                try:
                    path = describe(handle) or ""
                except Exception:
                    path = ""
            emit(to_event(handle, png, path))
    threading.Thread(target=work, name="multimini-thumbnails", daemon=True).start()


def thumbnail_request(message):
    handles = [int(h) for h in message.get("hwnds", [])][:64]
    width = max(64, min(640, int(message.get("width", 320))))
    return handles, width


# ---- Windows ---------------------------------------------------------------
def windows_thumbnail(hwnd, max_width=320):
    """PNG of one window via PrintWindow + HALFTONE StretchBlt, or None."""
    if sys.platform != "win32":
        return None
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
    for function, args, result in (
            (user32.IsWindow, [wintypes.HWND], wintypes.BOOL), (user32.IsIconic, [wintypes.HWND], wintypes.BOOL),
            (user32.GetWindowRect, [wintypes.HWND, ctypes.POINTER(wintypes.RECT)], wintypes.BOOL),
            (user32.GetDC, [wintypes.HWND], wintypes.HDC), (user32.ReleaseDC, [wintypes.HWND, wintypes.HDC], ctypes.c_int),
            (user32.PrintWindow, [wintypes.HWND, wintypes.HDC, wintypes.UINT], wintypes.BOOL),
            (gdi32.CreateCompatibleDC, [wintypes.HDC], wintypes.HDC), (gdi32.DeleteDC, [wintypes.HDC], wintypes.BOOL),
            (gdi32.CreateCompatibleBitmap, [wintypes.HDC, ctypes.c_int, ctypes.c_int], wintypes.HBITMAP),
            (gdi32.SelectObject, [wintypes.HDC, wintypes.HGDIOBJ], wintypes.HGDIOBJ),
            (gdi32.DeleteObject, [wintypes.HGDIOBJ], wintypes.BOOL),
            (gdi32.SetStretchBltMode, [wintypes.HDC, ctypes.c_int], ctypes.c_int),
            (gdi32.SetBrushOrgEx, [wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_void_p], wintypes.BOOL),
            (gdi32.StretchBlt, [wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.HDC,
                                ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.DWORD], wintypes.BOOL),
            (gdi32.GetDIBits, [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT, ctypes.c_void_p,
                               ctypes.c_void_p, wintypes.UINT], ctypes.c_int)):
        function.argtypes, function.restype = args, result
    hwnd = wintypes.HWND(int(hwnd))
    if not user32.IsWindow(hwnd) or user32.IsIconic(hwnd):
        return None  # minimized windows have no current pixels
    rect = wintypes.RECT()
    if not user32.GetWindowRect(hwnd, ctypes.byref(rect)):
        return None
    width, height = rect.right - rect.left, rect.bottom - rect.top
    if width <= 0 or height <= 0 or width > 16384 or height > 16384:
        return None
    target_w = max(1, min(max_width, width))
    target_h = max(1, round(height * target_w / width))
    screen = user32.GetDC(None)
    full_dc = gdi32.CreateCompatibleDC(screen)
    thumb_dc = gdi32.CreateCompatibleDC(screen)
    full = gdi32.CreateCompatibleBitmap(screen, width, height)
    thumb = gdi32.CreateCompatibleBitmap(screen, target_w, target_h)
    try:
        old_full = gdi32.SelectObject(full_dc, full)
        old_thumb = gdi32.SelectObject(thumb_dc, thumb)
        if not user32.PrintWindow(hwnd, full_dc, 2):  # PW_RENDERFULLCONTENT
            user32.PrintWindow(hwnd, full_dc, 0)
        gdi32.SetStretchBltMode(thumb_dc, 4)  # HALFTONE
        gdi32.SetBrushOrgEx(thumb_dc, 0, 0, None)
        gdi32.StretchBlt(thumb_dc, 0, 0, target_w, target_h, full_dc, 0, 0, width, height, 0x00CC0020)
        gdi32.SelectObject(full_dc, old_full)
        gdi32.SelectObject(thumb_dc, old_thumb)  # GetDIBits needs the bitmap deselected

        class BITMAPINFOHEADER(ctypes.Structure):
            _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
                        ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                        ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                        ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD)]
        info = BITMAPINFOHEADER(ctypes.sizeof(BITMAPINFOHEADER), target_w, -target_h, 1, 32, 0, 0, 0, 0, 0, 0)
        buffer = (ctypes.c_ubyte * (target_w * target_h * 4))()
        if not gdi32.GetDIBits(thumb_dc, thumb, 0, target_h, buffer, ctypes.byref(info), 0):
            return None
        return encode_png(target_w, target_h, bytes(buffer))
    finally:
        gdi32.DeleteObject(full); gdi32.DeleteObject(thumb)
        gdi32.DeleteDC(full_dc); gdi32.DeleteDC(thumb_dc)
        user32.ReleaseDC(None, screen)


def windows_process_path(hwnd):
    """Full path of the program that owns a window, or "" (Windows only)."""
    if sys.platform != "win32":
        return ""
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(wintypes.HWND(int(hwnd)), ctypes.byref(pid))
    if not pid.value:
        return ""
    process = kernel32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not process:
        return ""
    try:
        size = wintypes.DWORD(32768)
        buffer = ctypes.create_unicode_buffer(size.value)
        if kernel32.QueryFullProcessImageNameW(process, 0, buffer, ctypes.byref(size)):
            return buffer.value
        return ""
    finally:
        kernel32.CloseHandle(process)


# ---- macOS -----------------------------------------------------------------
def mac_thumbnail(window_id, max_width=320):
    """Quartz still image when available; ScreenCaptureKit on macOS 15+ where it is not."""
    try:
        import Quartz as Q
    except Exception:
        return None
    image = None
    try:
        image = Q.CGWindowListCreateImage(Q.CGRectNull, Q.kCGWindowListOptionIncludingWindow, int(window_id),
                                          Q.kCGWindowImageBoundsIgnoreFraming | Q.kCGWindowImageNominalResolution)
    except Exception:
        image = None
    if image is None or int(Q.CGImageGetWidth(image)) <= 0:
        try:
            from .mac_capture import WindowGrabber
            width, height, pixels = WindowGrabber(int(window_id)).grab()
            return encode_png(*downscale_bgra(width, height, pixels, max_width))
        except Exception:
            return None
    width, height = int(Q.CGImageGetWidth(image)), int(Q.CGImageGetHeight(image))
    if width <= 0 or height <= 0:
        return None
    target_w = max(1, min(max_width, width)); target_h = max(1, round(height * target_w / width))
    context = Q.CGBitmapContextCreate(None, target_w, target_h, 8, target_w * 4, Q.CGColorSpaceCreateDeviceRGB(),
                                      Q.kCGImageAlphaPremultipliedFirst | Q.kCGBitmapByteOrder32Little)
    Q.CGContextSetInterpolationQuality(context, Q.kCGInterpolationHigh)
    Q.CGContextDrawImage(context, Q.CGRectMake(0, 0, target_w, target_h), image)
    data = Q.CGBitmapContextGetData(context)
    size = target_w * target_h * 4
    try:
        pixels = bytes(data.as_buffer(size))
    except AttributeError:
        pixels = bytes(data[:size])
    return encode_png(target_w, target_h, pixels)

"""Fast X11 window reads through MIT-SHM (shared memory) and XComposite.

The pure-Python Xlib path decodes every frame in Python, which costs tens of
milliseconds for a 1080p game. This module asks the X server to copy the
window's composite backing pixmap straight into a shared-memory segment, so a
frame costs one server-side copy plus one memcpy into Python bytes.

Only the selected window is read; never the root window. Any failure raises
XShmUnavailable and callers fall back to the Python Xlib path. Set
MULTIMIN_XSHM=0 to disable this module entirely.
"""
from __future__ import annotations

import ctypes
import ctypes.util
import os
import threading

ZPixmap = 2
IPC_PRIVATE = 0
IPC_CREAT = 0o1000
IPC_RMID = 0
ALL_PLANES = ctypes.c_ulong(~0 & 0xFFFFFFFFFFFFFFFF)


class XShmUnavailable(RuntimeError):
    pass


class XShmSegmentInfo(ctypes.Structure):
    _fields_ = [("shmseg", ctypes.c_ulong), ("shmid", ctypes.c_int),
                ("shmaddr", ctypes.c_void_p), ("readOnly", ctypes.c_int)]


class XImagePrefix(ctypes.Structure):
    """Leading fields of Xlib's XImage; only read, never allocated by us."""
    _fields_ = [("width", ctypes.c_int), ("height", ctypes.c_int), ("xoffset", ctypes.c_int),
                ("format", ctypes.c_int), ("data", ctypes.c_void_p), ("byte_order", ctypes.c_int),
                ("bitmap_unit", ctypes.c_int), ("bitmap_bit_order", ctypes.c_int),
                ("bitmap_pad", ctypes.c_int), ("depth", ctypes.c_int),
                ("bytes_per_line", ctypes.c_int), ("bits_per_pixel", ctypes.c_int)]


class XWindowAttributes(ctypes.Structure):
    _fields_ = [("x", ctypes.c_int), ("y", ctypes.c_int), ("width", ctypes.c_int), ("height", ctypes.c_int),
                ("border_width", ctypes.c_int), ("depth", ctypes.c_int), ("visual", ctypes.c_void_p),
                ("root", ctypes.c_ulong), ("class_", ctypes.c_int), ("bit_gravity", ctypes.c_int),
                ("win_gravity", ctypes.c_int), ("backing_store", ctypes.c_int),
                ("backing_planes", ctypes.c_ulong), ("backing_pixel", ctypes.c_ulong),
                ("save_under", ctypes.c_int), ("colormap", ctypes.c_ulong), ("map_installed", ctypes.c_int),
                ("map_state", ctypes.c_int), ("all_event_masks", ctypes.c_long),
                ("your_event_mask", ctypes.c_long), ("do_not_propagate_mask", ctypes.c_long),
                ("override_redirect", ctypes.c_int), ("screen", ctypes.c_void_p)]


class XErrorEvent(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("display", ctypes.c_void_p), ("resourceid", ctypes.c_ulong),
                ("serial", ctypes.c_ulong), ("error_code", ctypes.c_ubyte),
                ("request_code", ctypes.c_ubyte), ("minor_code", ctypes.c_ubyte)]


_ERROR_HANDLER = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.POINTER(XErrorEvent))
_libs = None
_libs_lock = threading.Lock()
_errors = threading.local()
_error_counter = [0]


def _on_x_error(_display, event):
    # The default Xlib handler exits the whole process. Record and continue.
    _error_counter[0] += 1
    return 0


_handler_ref = _ERROR_HANDLER(_on_x_error)


def _load():
    global _libs
    with _libs_lock:
        if _libs is not None:
            return _libs
        if os.environ.get("MULTIMIN_XSHM", "1") == "0":
            raise XShmUnavailable("disabled by MULTIMIN_XSHM=0")
        def lib(name, soname):
            path = ctypes.util.find_library(name) or soname
            try:
                return ctypes.CDLL(path)
            except OSError as error:
                raise XShmUnavailable(f"{soname} unavailable: {error}") from error
        x11 = lib("X11", "libX11.so.6")
        xext = lib("Xext", "libXext.so.6")
        comp = lib("Xcomposite", "libXcomposite.so.1")
        libc = lib("c", "libc.so.6")
        x11.XInitThreads.restype = ctypes.c_int
        x11.XInitThreads()
        x11.XOpenDisplay.restype = ctypes.c_void_p
        x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
        x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
        x11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
        x11.XFree.argtypes = [ctypes.c_void_p]
        x11.XFreePixmap.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        x11.XGetWindowAttributes.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(XWindowAttributes)]
        x11.XGetWindowAttributes.restype = ctypes.c_int
        x11.XGetGeometry.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong),
                                     ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                                     ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
                                     ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
        x11.XGetGeometry.restype = ctypes.c_int
        x11.XSetErrorHandler.argtypes = [_ERROR_HANDLER]
        x11.XSetErrorHandler.restype = ctypes.c_void_p
        x11.XSetErrorHandler(_handler_ref)
        xext.XShmQueryExtension.argtypes = [ctypes.c_void_p]
        xext.XShmQueryExtension.restype = ctypes.c_int
        xext.XShmCreateImage.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint, ctypes.c_int,
                                         ctypes.c_void_p, ctypes.POINTER(XShmSegmentInfo),
                                         ctypes.c_uint, ctypes.c_uint]
        xext.XShmCreateImage.restype = ctypes.c_void_p
        xext.XShmAttach.argtypes = [ctypes.c_void_p, ctypes.POINTER(XShmSegmentInfo)]
        xext.XShmAttach.restype = ctypes.c_int
        xext.XShmDetach.argtypes = [ctypes.c_void_p, ctypes.POINTER(XShmSegmentInfo)]
        xext.XShmGetImage.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p,
                                      ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
        xext.XShmGetImage.restype = ctypes.c_int
        comp.XCompositeNameWindowPixmap.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        comp.XCompositeNameWindowPixmap.restype = ctypes.c_ulong
        comp.XCompositeQueryExtension.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int)]
        comp.XCompositeQueryExtension.restype = ctypes.c_int
        libc.shmget.argtypes = [ctypes.c_int, ctypes.c_size_t, ctypes.c_int]
        libc.shmget.restype = ctypes.c_int
        libc.shmat.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
        libc.shmat.restype = ctypes.c_void_p
        libc.shmdt.argtypes = [ctypes.c_void_p]
        libc.shmctl.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        _libs = (x11, xext, comp, libc)
        return _libs


class XShmGrabber:
    """Reads one X11 window's pixels into Python bytes (BGRA, 4 bytes/pixel)."""

    def __init__(self, xid: int):
        self.x11, self.xext, self.comp, self.libc = _load()
        self.xid = int(xid)
        self.display = self.x11.XOpenDisplay(None)
        if not self.display:
            raise XShmUnavailable("cannot open X display")
        self.image = None
        self.info = XShmSegmentInfo()
        self.size = (0, 0)
        try:
            if not self.xext.XShmQueryExtension(self.display):
                raise XShmUnavailable("MIT-SHM extension unavailable")
            a, b = ctypes.c_int(), ctypes.c_int()
            self.composite = bool(self.comp.XCompositeQueryExtension(self.display, ctypes.byref(a), ctypes.byref(b)))
            attributes = XWindowAttributes()
            before = _error_counter[0]
            if not self.x11.XGetWindowAttributes(self.display, self.xid, ctypes.byref(attributes)) or _error_counter[0] != before:
                raise XShmUnavailable("window attributes unavailable")
            self.visual, self.depth = attributes.visual, attributes.depth
        except Exception:
            self.close()
            raise

    def _ensure(self, width, height):
        if self.image and self.size == (width, height):
            return
        self._release_image()
        image = self.xext.XShmCreateImage(self.display, self.visual, self.depth, ZPixmap, None,
                                          ctypes.byref(self.info), width, height)
        if not image:
            raise XShmUnavailable("XShmCreateImage failed")
        prefix = XImagePrefix.from_address(image)
        if prefix.bits_per_pixel != 32 or prefix.byte_order != 0:
            self.x11.XFree(image)
            raise XShmUnavailable("only 32-bit little-endian pixels are supported")
        size = prefix.bytes_per_line * height
        shmid = self.libc.shmget(IPC_PRIVATE, size, IPC_CREAT | 0o600)
        if shmid < 0:
            self.x11.XFree(image)
            raise XShmUnavailable("shmget failed")
        address = self.libc.shmat(shmid, None, 0)
        if address in (None, ctypes.c_void_p(-1).value):
            self.libc.shmctl(shmid, IPC_RMID, None)
            self.x11.XFree(image)
            raise XShmUnavailable("shmat failed")
        self.info.shmid, self.info.shmaddr, self.info.readOnly = shmid, address, 0
        prefix.data = address
        before = _error_counter[0]
        attached = self.xext.XShmAttach(self.display, ctypes.byref(self.info))
        self.x11.XSync(self.display, 0)
        # Mark for removal now; the segment lives until both sides detach.
        self.libc.shmctl(shmid, IPC_RMID, None)
        if not attached or _error_counter[0] != before:
            self.libc.shmdt(address)
            self.info.shmaddr = None
            self.x11.XFree(image)
            raise XShmUnavailable("XShmAttach failed (sandbox without shared IPC?)")
        self.image, self.size = image, (width, height)

    def grab(self):
        """Return (width, height, bytes) or raise XShmUnavailable/OSError."""
        root, x, y = ctypes.c_ulong(), ctypes.c_int(), ctypes.c_int()
        w, h, border, depth = ctypes.c_uint(), ctypes.c_uint(), ctypes.c_uint(), ctypes.c_uint()
        before = _error_counter[0]
        if not self.x11.XGetGeometry(self.display, self.xid, ctypes.byref(root), ctypes.byref(x), ctypes.byref(y),
                                     ctypes.byref(w), ctypes.byref(h), ctypes.byref(border), ctypes.byref(depth)) \
                or _error_counter[0] != before:
            raise XShmUnavailable("window geometry unavailable")
        width, height = w.value, h.value
        if width <= 0 or height <= 0 or width > 16384 or height > 16384:
            raise XShmUnavailable("invalid window size")
        self._ensure(width, height)
        pixmap = 0
        drawable = self.xid
        offset = 0
        if self.composite:
            # A fresh name each frame: DRI3/Present clients swap backing pixmaps.
            pixmap = self.comp.XCompositeNameWindowPixmap(self.display, self.xid)
            if pixmap:
                drawable = pixmap
                offset = border.value
        try:
            before = _error_counter[0]
            ok = self.xext.XShmGetImage(self.display, drawable, self.image, offset, offset, ALL_PLANES)
            self.x11.XSync(self.display, 0)
            if (not ok or _error_counter[0] != before) and pixmap:
                # The backing pixmap may not include a border offset; retry at origin.
                before = _error_counter[0]
                ok = self.xext.XShmGetImage(self.display, drawable, self.image, 0, 0, ALL_PLANES)
                self.x11.XSync(self.display, 0)
            if not ok or _error_counter[0] != before:
                raise XShmUnavailable("XShmGetImage failed")
        finally:
            if pixmap:
                self.x11.XFreePixmap(self.display, pixmap)
        prefix = XImagePrefix.from_address(self.image)
        row = width * 4
        if prefix.bytes_per_line == row:
            return width, height, ctypes.string_at(self.info.shmaddr, row * height)
        raw = ctypes.string_at(self.info.shmaddr, prefix.bytes_per_line * height)
        stride = prefix.bytes_per_line
        return width, height, b"".join(raw[y * stride:y * stride + row] for y in range(height))

    def _release_image(self):
        if self.image:
            try:
                self.xext.XShmDetach(self.display, ctypes.byref(self.info))
                self.x11.XSync(self.display, 0)
            except Exception:
                pass
            if self.info.shmaddr:
                self.libc.shmdt(self.info.shmaddr)
            self.info.shmaddr = None
            self.x11.XFree(self.image)
            self.image, self.size = None, (0, 0)

    def close(self):
        if getattr(self, "display", None):
            self._release_image()
            self.x11.XCloseDisplay(self.display)
            self.display = None

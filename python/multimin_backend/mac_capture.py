"""macOS window/display capture. UNTESTED: written without access to a Mac.

Preferred path: ScreenCaptureKit's SCScreenshotManager (macOS 14+), one image per
frame. Fallback: CGWindowListCreateImage / CGDisplayCreateImage (older macOS).
Every image is redrawn into a bitmap context with a known BGRA layout, so the
Java side always receives 4-byte little-endian BGRA rows with no padding.

Both paths need the Screen Recording permission (System Settings → Privacy &
Security → Screen & System Audio Recording). Without it macOS returns blank
images or no window titles.
"""
from __future__ import annotations

import threading

BGRA = 0x42475241  # kCVPixelFormatType_32BGRA ('BGRA')


class MacCaptureError(RuntimeError):
    pass


def _quartz():
    try:
        import Quartz
        return Quartz
    except Exception as error:  # pragma: no cover - macOS only
        raise MacCaptureError(f"PyObjC Quartz is unavailable: {error}") from error


def cgimage_to_bgra(image):
    """Return (width, height, bytes) in packed BGRA for any CGImage."""
    Q = _quartz()
    if image is None:
        raise MacCaptureError("macOS returned no image (Screen Recording permission?)")
    width, height = int(Q.CGImageGetWidth(image)), int(Q.CGImageGetHeight(image))
    if width <= 0 or height <= 0 or width > 16384 or height > 16384:
        raise MacCaptureError("invalid capture size")
    space = Q.CGColorSpaceCreateDeviceRGB()
    info = Q.kCGImageAlphaPremultipliedFirst | Q.kCGBitmapByteOrder32Little  # BGRA in memory
    context = Q.CGBitmapContextCreate(None, width, height, 8, width * 4, space, info)
    if context is None:
        raise MacCaptureError("could not create bitmap context")
    Q.CGContextDrawImage(context, Q.CGRectMake(0, 0, width, height), image)
    data = Q.CGBitmapContextGetData(context)
    size = width * height * 4
    try:
        pixels = bytes(data.as_buffer(size))
    except AttributeError:
        pixels = bytes(data[:size])
    return width, height, pixels


def _wait(start, timeout=3.0):
    """Run an Objective-C call that takes a completion block; return its arguments."""
    done = threading.Event()
    result = []
    def handler(*args):
        result.extend(args)
        done.set()
    start(handler)
    if not done.wait(timeout):
        raise MacCaptureError("ScreenCaptureKit did not answer")
    return result


class _ScreenCaptureKit:
    """SCScreenshotManager wrapper; raises MacCaptureError when unavailable."""

    def __init__(self):
        try:
            import ScreenCaptureKit as SCK
        except Exception as error:
            raise MacCaptureError(f"ScreenCaptureKit unavailable: {error}") from error
        if not hasattr(SCK, "SCScreenshotManager"):
            raise MacCaptureError("SCScreenshotManager requires macOS 14 or later")
        self.SCK = SCK

    def content(self):
        args = _wait(lambda h: self.SCK.SCShareableContent.getShareableContentExcludingDesktopWindows_onScreenWindowsOnly_completionHandler_(True, False, h))
        content, error = (args + [None, None])[:2]
        if content is None:
            raise MacCaptureError(f"no shareable content ({error}); grant Screen Recording permission")
        return content

    def shot(self, content_filter, width, height):
        config = self.SCK.SCStreamConfiguration.alloc().init()
        config.setWidth_(int(width))
        config.setHeight_(int(height))
        config.setPixelFormat_(BGRA)
        config.setShowsCursor_(False)
        args = _wait(lambda h: self.SCK.SCScreenshotManager.captureImageWithFilter_configuration_completionHandler_(content_filter, config, h))
        image, error = (args + [None, None])[:2]
        if image is None:
            raise MacCaptureError(f"screenshot failed: {error}")
        return cgimage_to_bgra(image)


class WindowGrabber:
    def __init__(self, window_id: int):
        self.window_id = int(window_id)
        self.sck = None
        self.filter = None
        self.size = (0, 0)
        try:
            self.sck = _ScreenCaptureKit()
            self._refresh()
        except MacCaptureError:
            self.sck = None

    def _refresh(self):
        content = self.sck.content()
        for window in content.windows():
            if int(window.windowID()) == self.window_id:
                frame = window.frame()
                self.filter = self.sck.SCK.SCContentFilter.alloc().initWithDesktopIndependentWindow_(window)
                scale = 2.0
                try:
                    scale = float(self.filter.pointPixelScale())
                except Exception:
                    pass
                self.size = (max(1, int(frame.size.width * scale)), max(1, int(frame.size.height * scale)))
                return
        raise WindowGone("selected window closed; reselect source")

    def grab(self):
        if self.sck is not None:
            try:
                return self.sck.shot(self.filter, *self.size)
            except MacCaptureError:
                self._refresh()  # window resized or moved between displays
                return self.sck.shot(self.filter, *self.size)
        Q = _quartz()
        if not window_exists(self.window_id):
            raise WindowGone("selected window closed; reselect source")
        image = Q.CGWindowListCreateImage(Q.CGRectNull, Q.kCGWindowListOptionIncludingWindow, self.window_id,
                                          Q.kCGWindowImageBoundsIgnoreFraming | Q.kCGWindowImageBestResolution)
        return cgimage_to_bgra(image)


class DisplayGrabber:
    def __init__(self, monitor: int, exclude_pid: int = 0):
        Q = _quartz()
        error, displays, count = Q.CGGetActiveDisplayList(16, None, None)
        displays = list(displays or [])[:count]
        if not displays:
            raise MacCaptureError("no displays found")
        self.display_id = displays[max(0, min(len(displays) - 1, int(monitor) - 1))]
        bounds = Q.CGDisplayBounds(self.display_id)
        self.bounds = (bounds.origin.x, bounds.origin.y, bounds.size.width, bounds.size.height)
        self.sck = None
        try:
            self.sck = _ScreenCaptureKit()
            content = self.sck.content()
            target = next(d for d in content.displays() if int(d.displayID()) == int(self.display_id))
            own = [w for w in content.windows() if exclude_pid and w.owningApplication() is not None
                   and int(w.owningApplication().processID()) == int(exclude_pid)]
            self.filter = self.sck.SCK.SCContentFilter.alloc().initWithDisplay_excludingWindows_(target, own)
            self.size = (int(Q.CGDisplayPixelsWide(self.display_id)), int(Q.CGDisplayPixelsHigh(self.display_id)))
            mode = Q.CGDisplayCopyDisplayMode(self.display_id)
            if mode is not None:
                self.size = (int(Q.CGDisplayModeGetPixelWidth(mode)), int(Q.CGDisplayModeGetPixelHeight(mode)))
        except (MacCaptureError, StopIteration):
            self.sck = None

    def grab(self):
        if self.sck is not None:
            return self.sck.shot(self.filter, *self.size)
        Q = _quartz()
        return cgimage_to_bgra(Q.CGDisplayCreateImage(self.display_id))


class WindowGone(MacCaptureError):
    pass


def window_exists(window_id):
    Q = _quartz()
    info = Q.CGWindowListCopyWindowInfo(Q.kCGWindowListOptionIncludingWindow, int(window_id)) or []
    return len(info) > 0


def window_info(window_id):
    Q = _quartz()
    info = Q.CGWindowListCopyWindowInfo(Q.kCGWindowListOptionIncludingWindow, int(window_id)) or []
    return dict(info[0]) if info else None


def list_windows(own_pid=0):
    """Visible normal-layer windows as rows the Java chooser understands."""
    import base64
    Q = _quartz()
    options = Q.kCGWindowListOptionOnScreenOnly | Q.kCGWindowListExcludeDesktopElements
    rows = []
    for item in Q.CGWindowListCopyWindowInfo(options, Q.kCGNullWindowID) or []:
        if int(item.get("kCGWindowLayer", 1)) != 0:
            continue
        pid = int(item.get("kCGWindowOwnerPID", 0))
        if own_pid and pid == own_pid:
            continue
        owner = str(item.get("kCGWindowOwnerName", "") or "")
        title = str(item.get("kCGWindowName", "") or "") or owner
        if not title or title.startswith("MultiMini") or owner == "MultiMini":
            continue
        bounds = item.get("kCGWindowBounds", {}) or {}
        width, height = int(bounds.get("Width", 0)), int(bounds.get("Height", 0))
        if width < 40 or height < 40:
            continue
        enc = lambda value: base64.b64encode(value.encode("utf-8")).decode("ascii")
        rows.append(dict(title64=enc(title), process64=enc(owner), hwnd=int(item.get("kCGWindowNumber", 0)),
                         left=int(bounds.get("X", 0)), top=int(bounds.get("Y", 0)), width=width, height=height))
    return rows


class MacCapture:
    """Background thread publishing frames from a grabber at the requested rate."""

    def __init__(self, grabber, publish, error, fps):
        self.stop = threading.Event()
        self.limit = 0
        self.thread = threading.Thread(target=self._run, args=(grabber, publish, error, fps),
                                       name="multimini-mac-capture", daemon=True)
        self.thread.start()

    def set_rate_limit(self, fps):
        self.limit = max(0, int(fps))

    def _run(self, grabber, publish, error, fps):
        import time
        last_error = 0.0
        while not self.stop.is_set():
            started = time.monotonic()
            try:
                width, height, pixels = grabber.grab()
                publish(width, height, pixels)
            except WindowGone as gone:
                error(str(gone))
                return
            except Exception as exc:
                if time.monotonic() - last_error > 5:
                    error(f"macOS capture failed: {exc}")
                    last_error = time.monotonic()
            rate = fps()
            if self.limit:
                rate = min(rate, self.limit)
            self.stop.wait(max(.001, 1 / max(1, min(240, rate)) - (time.monotonic() - started)))

    def close(self):
        self.stop.set()
        self.thread.join(timeout=2)

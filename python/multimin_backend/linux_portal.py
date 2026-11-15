"""User-mediated capture and shortcuts; never enumerate native Wayland windows.

All Gio signal delivery is owned by one GLib thread. Request subscriptions are
installed BEFORE calling the portal, including its predicted request path.
"""
from __future__ import annotations

import os
import threading
import uuid

DEST = "org.freedesktop.portal.Desktop"
ROOT = "/org/freedesktop/portal/desktop"
PREFIX = "org.freedesktop.portal."


def packed_bgra(data, width, height, stride, offset=0):
    """Keep packed immutable buffers intact; copy only padded/offset layouts."""
    row = width * 4
    if width <= 0 or height <= 0 or width > 16384 or height > 16384 or row * height > 256 * 1024 * 1024:
        raise ValueError("Invalid PipeWire frame dimensions")
    if stride < row or offset < 0 or offset + (height - 1) * stride + row > len(data):
        raise ValueError("Invalid PipeWire frame layout")
    if stride == row:
        if isinstance(data, bytes) and offset == 0 and len(data) == row * height:
            return data
        return bytes(data[offset:offset + row * height])
    return b"".join(data[offset + y * stride:offset + y * stride + row] for y in range(height))


class RateLimiter:
    """Admit at most `fps` frames per second; 0 admits every frame."""
    def __init__(self, clock=None):
        import time
        self.clock = clock or time.monotonic
        self.fps = 0
        self.deadline = 0.0
        self.lock = threading.Lock()

    def set(self, fps):
        with self.lock:
            self.fps = max(0, int(fps))
            self.deadline = 0.0

    def admit(self):
        with self.lock:
            if not self.fps:
                return True
            now = self.clock()
            period = 1 / self.fps
            if now + period * .02 < self.deadline:
                return False
            self.deadline = max(self.deadline + period, now + period * .98)
            return True


class PortalError(RuntimeError):
    pass


class Portal:
    def __init__(self):
        import gi
        gi.require_version("Gst", "1.0")
        from gi.repository import Gio, GLib, Gst
        self.Gio, self.GLib, self.Gst = Gio, GLib, Gst
        Gst.init(None)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self.loop = GLib.MainLoop()
        self.thread = threading.Thread(target=self.loop.run, name="multimini-portals", daemon=True)
        self.thread.start()
        self.cancelled = threading.Event()
        self.requests = set()
        self.sessions = set()

    def call(self, interface, method, signature, args, path=ROOT):
        return self.bus.call_sync(DEST, path, PREFIX + interface, method,
                                  self.GLib.Variant(signature, args), None,
                                  self.Gio.DBusCallFlags.NONE, 10000, None).unpack()

    def request(self, interface, method, signature, args, options=None):
        token = "multimini_" + uuid.uuid4().hex
        sender = self.bus.get_unique_name()[1:].replace(".", "_")
        path = ROOT + "/request/" + sender + "/" + token
        done = threading.Event()
        response = []
        def receive(_bus, _sender, _path, _iface, _signal, params):
            response.extend(params.unpack())
            done.set()
        sub = self.bus.signal_subscribe(DEST, PREFIX + "Request", "Response", path,
                                       None, self.Gio.DBusSignalFlags.NONE, receive)
        self.requests.add(path)
        opts = dict(options or {})
        opts["handle_token"] = self.GLib.Variant("s", token)
        try:
            returned = self.call(interface, method, signature, (*args, opts))[0]
            if returned != path:
                raise PortalError("Portal returned an unexpected request handle")
            for _ in range(600):
                if done.wait(.5):
                    break
                if self.cancelled.is_set():
                    raise PortalError("Capture request cancelled during shutdown")
            else:
                self.call("Request", "Close", "()", (), path=path)
                raise PortalError("Desktop permission request timed out")
            if response[0] != 0:
                raise PortalError("Desktop selection cancelled or permission denied")
            return response[1]
        finally:
            if not done.is_set():
                try:
                    self.call("Request", "Close", "()", (), path=path)
                except Exception:
                    pass
            self.requests.discard(path)
            self.bus.signal_unsubscribe(sub)

    def create_session(self, interface):
        result = self.request(interface, "CreateSession", "(a{sv})", (), {
            "session_handle_token": self.GLib.Variant("s", "multimini_" + uuid.uuid4().hex)})
        session = result["session_handle"]
        self.sessions.add(session)
        return session

    def close_session(self, session):
        if session in self.sessions:
            self.sessions.discard(session)
            try:
                self.call("Session", "Close", "()", (), path=session)
            except Exception:
                pass

    def capture(self, source_type, on_frame, on_error, cursor=False, restore_token=""):
        session = self.create_session("ScreenCast")
        try:
            # persist_mode 2 asks the desktop to remember this grant until the
            # user revokes it. A valid token restores it without a picker; an
            # invalid or revoked token makes the desktop show the picker again.
            options = {
                "types": self.GLib.Variant("u", source_type),
                "multiple": self.GLib.Variant("b", False),
                "cursor_mode": self.GLib.Variant("u", 2 if cursor else 1),
                "persist_mode": self.GLib.Variant("u", 2)}
            if restore_token:
                options["restore_token"] = self.GLib.Variant("s", restore_token)
            self.request("ScreenCast", "SelectSources", "(oa{sv})", (session,), options)
            result = self.request("ScreenCast", "Start", "(osa{sv})", (session, ""))
            streams = result.get("streams", [])
            if len(streams) != 1:
                raise PortalError("Choose exactly one window or display")
            node, properties = streams[0]
            reply, fds = self.bus.call_with_unix_fd_list_sync(
                DEST, ROOT, PREFIX + "ScreenCast", "OpenPipeWireRemote",
                self.GLib.Variant("(oa{sv})", (session, {})), None,
                self.Gio.DBusCallFlags.NONE, 10000, None, None)
            fd = fds.get(reply.unpack()[0])
            try:
                capture = PipeWireCapture(self, session, fd, node, properties, on_frame, on_error)
                capture.restore_token = str(result.get("restore_token", "") or "")
                return capture
            except Exception:
                os.close(fd)
                raise
        except Exception:
            self.close_session(session)
            raise

    def close(self):
        self.cancelled.set()
        for path in list(self.requests):
            try:
                self.call("Request", "Close", "()", (), path=path)
            except Exception:
                pass
        for session in list(self.sessions):
            self.close_session(session)
        self.loop.quit()
        self.thread.join(timeout=2)


class PipeWireCapture:
    def __init__(self, portal, session, fd, node, properties, on_frame, on_error):
        self.portal, self.session, self.fd = portal, session, fd
        self.closed = False
        self.properties = properties
        self.limiter = RateLimiter()
        Gst = portal.Gst
        threads = max(1, min(4, os.cpu_count() or 1))
        self.pipeline = Gst.parse_launch(
            f"pipewiresrc fd={fd} path={int(node)} do-timestamp=true ! "
            f"videoconvert name=convert n-threads={threads} ! video/x-raw,format=BGRA ! "
            "appsink name=frames emit-signals=true max-buffers=1 drop=true sync=false")
        self.sink = self.pipeline.get_by_name("frames")
        # Drop surplus frames before colour conversion, mapping and copying.
        convert_pad = self.pipeline.get_by_name("convert").get_static_pad("sink")
        def probe(_pad, _info):
            return Gst.PadProbeReturn.OK if self.limiter.admit() else Gst.PadProbeReturn.DROP
        convert_pad.add_probe(Gst.PadProbeType.BUFFER, probe)
        def sample(sink):
            frame = sink.emit("pull-sample")
            if frame is None:
                return Gst.FlowReturn.EOS
            caps = frame.get_caps().get_structure(0)
            w, h = caps.get_value("width"), caps.get_value("height")
            buffer = frame.get_buffer()
            # BGRA rows have a multiple-of-four stride. Inspect actual video
            # metadata as PipeWire can still provide wider padded rows.
            import gi
            gi.require_version("GstVideo", "1.0")
            from gi.repository import GstVideo
            info = GstVideo.VideoInfo.new_from_caps(frame.get_caps())
            meta = GstVideo.buffer_get_video_meta(buffer)
            stride = meta.stride[0] if meta else info.stride[0]
            offset = meta.offset[0] if meta else info.offset[0]
            ok, mapping = buffer.map(Gst.MapFlags.READ)
            if ok:
                try:
                    data = mapping.data
                    pixels = packed_bgra(data, w, h, stride, offset)
                    on_frame(w, h, pixels)
                finally:
                    buffer.unmap(mapping)
            return Gst.FlowReturn.OK
        self.sink.connect("new-sample", sample)
        self.bus = self.pipeline.get_bus()
        self.bus.add_signal_watch()
        def message(_bus, msg):
            if msg.type == Gst.MessageType.ERROR:
                error, _debug = msg.parse_error()
                on_error(str(error))
            elif msg.type == Gst.MessageType.EOS:
                on_error("Desktop sharing ended; reselect this source")
        self.bus.connect("message", message)
        self.closed_sub = portal.bus.signal_subscribe(DEST, PREFIX+"Session", "Closed", session,
            None, portal.Gio.DBusSignalFlags.NONE,
            lambda *_: on_error("Desktop sharing permission ended; reselect this source"))
        if self.pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            self.close(close_fd=False)
            raise PortalError("PipeWire capture could not start")

    def set_rate_limit(self, fps):
        self.limiter.set(fps)

    def close(self, close_fd=True):
        if self.closed:
            return
        self.closed = True
        self.pipeline.set_state(self.portal.Gst.State.NULL)
        self.bus.remove_signal_watch()
        self.portal.bus.signal_unsubscribe(self.closed_sub)
        self.portal.close_session(self.session)
        if self.fd >= 0 and close_fd:
            os.close(self.fd)
            self.fd = -1

"""X11 window enumeration and directed events (not a Wayland escape hatch).

XSendEvent works with cooperative X11 clients; games and clients that reject
synthetic events need separate testing. No focus or real-window reordering.
"""
import base64


def encoded(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def map_point(x, y, rect, size):
    left, top, width, height = rect
    if width <= 0 or height <= 0 or size[0] <= 0 or size[1] <= 0:
        return None
    if x < left or y < top or x >= left + width or y >= top + height:
        return None
    return (int((x-left)*size[0]/width), int((y-top)*size[1]/height))


class X11:
    def __init__(self):
        from Xlib import display, X
        self.X = X
        self.display = display.Display()
        self.root = self.display.screen().root
        self.target = 0
        self.rect = (0, 0, 0, 0)
        self._held_keys = {}
        self._held_buttons = {}

    @staticmethod
    def _state(held_buttons=()):
        """Return X11 modifier/button masks for events sent by XSendEvent."""
        state = 0
        for button in held_buttons:
            if 1 <= button <= 5:
                state |= 1 << (7 + button)  # Button1Mask is bit 8.
        return state

    @staticmethod
    def _modifier_state(java_state):
        """Keep the X11 modifier masks supplied on the input wire."""
        state = 0
        if java_state & 1:
            state |= 1  # ShiftMask
        if java_state & 4:
            state |= 4  # ControlMask
        if java_state & 8:
            state |= 8  # Mod1Mask (Alt)
        if java_state & 64:
            state |= 64  # Mod4Mask (Meta)
        return state

    def set_target(self, target, rect):
        """Switch directed input target, releasing every held input on its owner."""
        target = int(target or 0)
        rect = tuple(rect)
        if target == self.target and rect == self.rect:
            return
        self.release_all()
        self.target, self.rect = target, rect

    def _send_key(self, target, code, pressed, state=0):
        from Xlib.protocol import event
        win = self.display.create_resource_object("window", target)
        cls = event.KeyPress if pressed else event.KeyRelease
        mask = self.X.KeyPressMask if pressed else self.X.KeyReleaseMask
        item = cls(time=self.X.CurrentTime, root=self.root, window=win,
                   child=self.X.NONE, root_x=0, root_y=0, event_x=0, event_y=0,
                   state=state, same_screen=1, detail=code)
        win.send_event(item, event_mask=mask, propagate=True)

    def _send_button(self, target, button, pressed, rect, point=None, state=0):
        from Xlib.protocol import event
        win = self.display.create_resource_object("window", target)
        if point is None:
            point = (0, 0)
        cls = event.ButtonPress if pressed else event.ButtonRelease
        mask = self.X.ButtonPressMask if pressed else self.X.ButtonReleaseMask
        root_point = point[2:4] if point is not None and len(point) > 3 else (0, 0)
        local_point = point[:2] if point is not None else (0, 0)
        item = cls(time=self.X.CurrentTime, root=self.root, window=win,
                   child=self.X.NONE, root_x=root_point[0], root_y=root_point[1], event_x=local_point[0], event_y=local_point[1],
                   state=state, same_screen=1, detail=button)
        win.send_event(item, event_mask=mask, propagate=True)

    def release_all(self):
        """Release held keys and buttons to the exact windows that received presses."""
        for (target, code), state in list(self._held_keys.items()):
            try:
                self._send_key(target, code, False, state)
            except Exception:
                pass
        for (target, button), (rect, point, state) in list(self._held_buttons.items()):
            try:
                modifiers = state & ~self._state(range(1, 6))
                others = self._state(other for owner, other in self._held_buttons
                                     if owner == target)
                self._send_button(target, button, False, rect, point, modifiers | others)
            except Exception:
                pass
        self._held_keys.clear()
        self._held_buttons.clear()
        self.display.flush()

    def windows(self):
        X, d = self.X, self.display
        prop = self.root.get_full_property(d.intern_atom("_NET_CLIENT_LIST"), X.AnyPropertyType)
        result = []
        for xid in ([] if prop is None else prop.value):
            try:
                win = d.create_resource_object("window", int(xid))
                title_prop = win.get_full_property(d.intern_atom("_NET_WM_NAME"), X.AnyPropertyType)
                title = (title_prop.value.decode("utf-8", "replace") if title_prop else win.get_wm_name()) or ""
                if not title or title.startswith("MultiMini"):
                    continue
                geometry = win.get_geometry()
                point = self.root.translate_coords(win, 0, 0)
                wm_class = win.get_wm_class() or ("X11",)
                result.append(dict(title64=encoded(title), process64=encoded(str(wm_class[-1])),
                    hwnd=int(xid), left=point.x, top=point.y, width=geometry.width, height=geometry.height))
            except Exception:
                continue
        return result

    def send(self, target, message, rect):
        from Xlib.protocol import event
        if not target:
            return False
        target = int(target)
        rect = tuple(rect)
        win = self.display.create_resource_object("window", target)
        geometry = win.get_geometry()
        pos = map_point(message.get("x", 0), message.get("y", 0), rect, (geometry.width, geometry.height))
        kind = message.get("kind")
        key = int(message.get("keysym", 0))
        if kind == "motion":
            if pos is None:
                return False
            state = self._modifier_state(int(message.get("state", 0))) | self._state(button for (owner, button) in self._held_buttons if owner == target)
            item = event.MotionNotify(time=self.X.CurrentTime, root=self.root, window=win,
                child=self.X.NONE, root_x=int(message.get("x", 0)), root_y=int(message.get("y", 0)),
                event_x=pos[0], event_y=pos[1], state=state, same_screen=1, detail=0)
            mask = self.X.PointerMotionMask
        elif kind == "press":
            if pos is None:
                return False
            button = int(message.get("button", 1))
            state = self._modifier_state(int(message.get("state", 0))) | self._state(button for (owner, button) in self._held_buttons if owner == target)
            item = event.ButtonPress(time=self.X.CurrentTime, root=self.root, window=win,
                child=self.X.NONE, root_x=int(message.get("x", 0)), root_y=int(message.get("y", 0)),
                event_x=pos[0], event_y=pos[1], state=state, same_screen=1, detail=button)
            mask = self.X.ButtonPressMask
            self._held_buttons.setdefault((target, button), (rect, (pos[0], pos[1], int(message.get("x", 0)), int(message.get("y", 0))), state))
        elif kind == "release":
            button = int(message.get("button", 1))
            held_key = (target, button)
            held = self._held_buttons.get(held_key)
            # A release follows its original press and is delivered even after leaving the source.
            if held is not None:
                rect, point, state = held
                try:
                    geometry = win.get_geometry()
                    release_local = (int((int(message.get("x", point[2]))-rect[0])*geometry.width/rect[2]),
                                     int((int(message.get("y", point[3]))-rect[1])*geometry.height/rect[3]))
                except Exception:
                    release_local = point[:2]
                win = self.display.create_resource_object("window", target)
                held_modifiers = state & ~self._state(range(1, 6))
                other_buttons = self._state(other for owner, other in self._held_buttons
                                            if owner == target)
                release_state = self._modifier_state(int(message.get("state", 0))) | held_modifiers | other_buttons
                item = event.ButtonRelease(time=self.X.CurrentTime, root=self.root, window=win,
                    child=self.X.NONE, root_x=int(message.get("x", point[2])), root_y=int(message.get("y", point[3])),
                    event_x=release_local[0], event_y=release_local[1],
                    state=release_state, same_screen=1, detail=button)
                mask = self.X.ButtonReleaseMask
                del self._held_buttons[held_key]
            elif pos is not None:
                item = event.ButtonRelease(time=self.X.CurrentTime, root=self.root, window=win,
                    child=self.X.NONE, root_x=int(message.get("x", 0)), root_y=int(message.get("y", 0)),
                    event_x=pos[0], event_y=pos[1], state=self._modifier_state(int(message.get("state", 0))),
                    same_screen=1, detail=button)
                mask = self.X.ButtonReleaseMask
            else:
                return False
        elif kind in ("key_press", "key_release"):
            code = self.display.keysym_to_keycode(key)
            if not code:
                return False
            held_key = (target, code)
            if kind == "key_press":
                state = self._modifier_state(int(message.get("state", 0)))
                self._send_key(target, code, True, state)
                self._held_keys.setdefault(held_key, state)
                self.display.flush()
                return True
            owner = target if held_key in self._held_keys else None
            if owner is None:
                # Unmatched releases are harmless, but still forward to current target.
                state = self._modifier_state(int(message.get("state", 0)))
            else:
                state = self._held_keys.pop(held_key)
            self._send_key(target, code, False, state)
            self.display.flush()
            return True
        else:
            return False
        win.send_event(item, event_mask=mask, propagate=True)
        self.display.flush()
        return True

    def close(self):
        self.release_all()
        self.display.close()


class X11Capture:
    """Read the selected X11 drawable; prefer its compositor backing pixmap.

    No root/display capture or window focus changes. A private connection owns
    both its structure notifications and image reads.
    """
    def __init__(self, xid, publish, error, fps):
        import threading
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._run, args=(xid,publish,error,fps), daemon=True,
                                       name="multimini-x11-capture")
        self.thread.start()

    def _run(self, xid, publish, error, fps):
        import time
        from Xlib import display, X
        d = display.Display()
        win = d.create_resource_object('window', int(xid))
        last_error = 0
        try:
            win.change_attributes(event_mask=X.StructureNotifyMask)
            d.sync()
            while not self.stop.is_set():
                started = time.monotonic()
                try:
                    while d.pending_events():
                        item = d.next_event()
                        if item.type == X.DestroyNotify:
                            error('Selected X11 window closed; reselect source')
                            return
                    if win.get_attributes().map_state != X.IsViewable:
                        self.stop.wait(.1)
                        continue
                    geom = win.get_geometry()
                    w,h=geom.width,geom.height
                    if w<=0 or h<=0 or w>16384 or h>16384 or w*h*4>256*1024*1024:
                        raise RuntimeError('Invalid X11 source dimensions')
                    # A DRI3/Present client can replace its backing pixmap on
                    # every presentation without a ConfigureNotify. Name a
                    # fresh pixmap for each read, then release it immediately.
                    candidate = None
                    image = None
                    if d.has_extension('Composite'):
                        try:
                            candidate = win.composite_name_window_pixmap()
                            backing = candidate.get_geometry()  # Deliver async errors now.
                            border = geom.border_width if backing.width == w+2*geom.border_width else 0
                            image = candidate.get_image(border, border, w, h, X.ZPixmap, 0xffffffff)
                        except Exception:
                            if candidate:
                                try: candidate.free()
                                except Exception: pass
                            candidate = None
                    # Some XWayland/Vulkan combinations do not expose a useful
                    # Composite backing pixmap. The selected window drawable is
                    # an exact-source fallback; it still never reads the root.
                    if image is None:
                        image = win.get_image(0, 0, w, h, X.ZPixmap, 0xffffffff)
                    if candidate:
                        candidate.free()
                    fmt=next(f for f in d.display.info.pixmap_formats if f.depth==image.depth)
                    if fmt.bits_per_pixel!=32 or d.display.info.image_byte_order!=X.LSBFirst:
                        raise RuntimeError('X11 preview currently requires a 32-bit little-endian pixel format')
                    data=bytearray(image.data)
                    if len(data)!=w*h*4:
                        raise RuntimeError('Unexpected X11 image stride')
                    # Most X11 windows are depth24 in a 32-bit storage format.
                    # Their unused alpha byte is zero, not transparent content.
                    if image.depth==24:
                        data[3::4]=b'\xff'*(w*h)
                    publish(w,h,bytes(data))
                except Exception as exc:
                    if time.monotonic()-last_error>5:
                        error(f'X11 capture failed: {exc}')
                        last_error=time.monotonic()
                self.stop.wait(max(.001,1/max(1,min(1000,fps()))-(time.monotonic()-started)))
        finally:
            d.close()

    def close(self):
        self.stop.set()
        self.thread.join(timeout=2)

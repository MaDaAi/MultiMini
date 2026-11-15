"""Real X11/XWayland input routing for a temporarily fitted target window.

This module does not connect to an X server at import time. It deliberately
uses X11 focus and pointer delivery instead of synthesizing input events.
"""
from __future__ import annotations

import time


class NativeInput:
    """Activate a real X11 client beneath an input-shaped overlay.

    ``rect`` is the desired physical client rectangle in root coordinates.
    Configure/Wayland compositors may reject moving a client; activation then
    rolls back and raises RuntimeError. Fullscreen and maximized clients are
    not modified.
    """

    def __init__(self, display_factory=None):
        self._display_factory = display_factory
        self.display = None
        self.root = None
        self.overlay_hint = 0
        self.control_hint = 0
        self.active_target = 0
        self.overlay = 0
        self._baseline = None
        self._shape_saved = None
        self._focus_before = 0
        self._atoms = {}
        self._desktop_mode = False

    def _connect(self):
        if self.display is None:
            if self._display_factory:
                self.display = self._display_factory()
            else:
                from Xlib import display
                self.display = display.Display()
            self.root = self.display.screen().root
        return self.display

    def _atom(self, name):
        if name not in self._atoms:
            self._atoms[name] = self.display.intern_atom(name)
        return self._atoms[name]

    def _window(self, xid):
        return self.display.create_resource_object("window", int(xid))

    def _property(self, window, name, default=None):
        prop = window.get_full_property(self._atom(name), 0)
        return default if prop is None else prop.value

    def find_overlay(self, app_pid):
        """Find this process's largest visible client without reading titles."""
        if self.overlay_hint:
            win=self._window(self.overlay_hint)
            pid=self._property(win,"_NET_WM_PID",())
            if not pid or int(pid[0])!=int(app_pid):
                raise RuntimeError("Reported overlay is not owned by MultiMini")
            if not self._viewable(win):
                raise RuntimeError("Reported MultiMini overlay is not viewable")
            return int(self.overlay_hint),self._geometry(win)
        clients = self._property(self.root, "_NET_CLIENT_LIST", ()) or ()
        matches = []
        for xid in clients:
            try:
                win = self._window(int(xid))
                pid = self._property(win, "_NET_WM_PID", ())
                if not pid or int(pid[0]) != int(app_pid):
                    continue
                attrs = win.get_attributes()
                if attrs.map_state == 0:
                    continue
                geom = win.get_geometry()
                point = self.root.translate_coords(win, 0, 0)
                area = max(0, geom.width) * max(0, geom.height)
                matches.append((area, int(xid), (point.x, point.y, geom.width, geom.height)))
            except Exception:
                continue
        if not matches:
            raise RuntimeError("Could not find a visible MultiMini overlay window")
        _area, xid, geom = max(matches)
        return xid, geom

    def _overlay_window(self, app_pid):
        return self.find_overlay(app_pid)

    def _raise_own_controls(self, app_pid, overlay, target):
        if self.control_hint:
            win=self._window(self.control_hint)
            pid=self._property(win,"_NET_WM_PID",())
            if pid and int(pid[0])==int(app_pid) and self._viewable(win):
                win.configure(stack_mode=0)
        # Exact Java window identities supersede PID scanning. Flatpak PIDs
        # are namespaced and may collide with another sandbox's JVM PID.
        if self.overlay_hint or self.control_hint:
            return
        clients = self._property(self.root, "_NET_CLIENT_LIST", ()) or ()
        overlay_geom = self._geometry(self._window(overlay))
        overlay_area = overlay_geom[2] * overlay_geom[3]
        for xid in clients:
            xid = int(xid)
            if xid in (overlay, target):
                continue
            try:
                win = self._window(xid)
                pid = self._property(win, "_NET_WM_PID", ())
                if not pid or int(pid[0]) != int(app_pid):
                    continue
                if win.get_attributes().map_state == 0:
                    continue
                geom = win.get_geometry()
                if int(geom.width) * int(geom.height) < overlay_area:
                    win.configure(stack_mode=0)  # Keep exit/control JWindows above preview.
            except Exception:
                continue

    def _viewable(self, win):
        try:
            from Xlib import X
            return win.get_attributes().map_state == X.IsViewable
        except Exception:
            return False

    def focused_target(self, target):
        """Read focus ancestry only; never inspect another client's contents."""
        cursor=self._focus_id(self.display.get_input_focus().focus)
        for _ in range(32):
            if cursor==int(target):return True
            if cursor in (0,1,int(self.root.id)):return False
            try:cursor=int(self._window(cursor).query_tree().parent.id)
            except Exception:return False
        return False

    def _restore_focus(self, xid):
        if int(xid or 0) in (0, 1):
            return
        win = self._window(xid)
        if self._viewable(win):
            win.set_input_focus(1, 0)

    def _shape_api(self):
        # Xlib's extension object exposes shape_get_rectangles/set_mask in
        # supported versions; keep imports lazy for non-X11 environments.
        from Xlib.ext import shape
        return shape

    def _shape_get(self, overlay):
        shape = self._shape_api()
        win = self._window(overlay)
        reply = win.shape_get_rectangles(shape.SK.Input)
        return [(int(r.x), int(r.y), int(r.width), int(r.height)) for r in reply.rectangles]

    def _shape_set(self, overlay, rectangles):
        from Xlib import X
        from Xlib.ext import shape
        # XFixes region helpers are not consistently exposed by Python-Xlib.
        # SetRectangles accepts an array of CARD16 rectangles directly.
        self._window(overlay).shape_rectangles(shape.SO.Set, shape.SK.Input,
            X.Unsorted, 0, 0,
            [(x, y, width, height) for x, y, width, height in rectangles])

    def set_input_hole(self, overlay, overlay_rect, target_rect):
        """Set overlay input to its visible area outside a physical target rect."""
        self._shape_set(int(overlay), self._rectangles_with_hole(overlay_rect, target_rect))

    @staticmethod
    def _rectangles_with_hole(overlay_rect, target_rect):
        ox, oy, ow, oh = overlay_rect
        tx, ty, tw, th = target_rect
        left, top = max(0, tx-ox), max(0, ty-oy)
        right, bottom = min(ow, tx+tw-ox), min(oh, ty+th-oy)
        if right <= left or bottom <= top:
            return [(0, 0, ow, oh)]
        result = []
        if top > 0: result.append((0, 0, ow, top))
        if bottom < oh: result.append((0, bottom, ow, oh-bottom))
        if left > 0: result.append((0, top, left, bottom-top))
        if right < ow: result.append((right, top, ow-right, bottom-top))
        return result

    def _geometry(self, win):
        geom = win.get_geometry()
        point = self.root.translate_coords(win, 0, 0)
        return (int(point.x), int(point.y), int(geom.width), int(geom.height))

    def _frame_extents(self, win):
        values = self._property(win, "_NET_FRAME_EXTENTS", (0, 0, 0, 0))
        return tuple(map(int, values[:4])) if len(values) >= 4 else (0, 0, 0, 0)

    def _state(self, win):
        return tuple(int(v) for v in (self._property(win, "_NET_WM_STATE", ()) or ()))

    def _check_window(self, target):
        win = self._window(target)
        if win.get_attributes().map_state == 0:
            raise RuntimeError("Selected X11 target is not visible")
        return win

    def _has_special_geometry_state(self, win):
        states = self._state(win)
        forbidden = (self._atom("_NET_WM_STATE_FULLSCREEN"),
                     self._atom("_NET_WM_STATE_MAXIMIZED_VERT"),
                     self._atom("_NET_WM_STATE_MAXIMIZED_HORZ"))
        return any(atom in states for atom in forbidden)

    def _fit(self, target, rect, baseline=None):
        win = self._check_window(target)
        x, y, width, height = map(int, rect)
        if width <= 0 or height <= 0:
            raise ValueError("Target rectangle must have positive dimensions")
        current = self._geometry(win)
        if self._has_special_geometry_state(win):
            if current == (x, y, width, height):
                return win
            raise RuntimeError("Fullscreen or maximized game cannot be resized to the requested overlay rectangle")
        left, right, top, bottom = self._frame_extents(win)
        win.configure(x=x-left, y=y-top, width=max(1, width), height=max(1, height))
        self.display.sync()
        deadline = time.monotonic() + .5
        while time.monotonic() < deadline:
            actual = self._geometry(win)
            # Translate_coords reports client origin; frame extents only affect
            # outer position, so check the visible client rectangle itself.
            if actual == (x, y, width, height):
                return win
            time.sleep(.01)
        raise RuntimeError("Window manager did not place the game at the requested overlay rectangle")

    def _restore_target(self, target, baseline):
        win = self._window(target)
        x, y, width, height = baseline[0]
        left, _right, top, _bottom = baseline[1]
        if self._geometry(win) == (x, y, width, height):
            return
        win.configure(x=x-left, y=y-top, width=width, height=height)
        self.display.sync()

    def activate(self, target, rect, app_pid):
        """Route real pointer/keyboard input to ``target`` under the overlay."""
        d = self._connect()
        target = int(target)
        rect = tuple(map(int, rect))
        if target <= 0 or len(rect) != 4:
            raise ValueError("A valid target XID and (x, y, width, height) are required")
        if self.active_target == target and self._baseline is not None:
            win = self._check_window(target)
            current = self._geometry(win)
            try:
                self._fit(target, rect, self._baseline)
                self._apply_hole(rect)
                win.set_input_focus(1,0)
                self.display.sync()
                return True
            except Exception:
                try:
                    self._restore_target(target, (current, self._frame_extents(win), self._state(win)))
                    self.display.sync()
                except Exception:
                    pass
                raise
        self.deactivate()
        overlay, overlay_rect = self._overlay_window(app_pid)
        target_win = self._check_window(target)
        original = self._geometry(target_win)
        frame = self._frame_extents(target_win)
        state = self._state(target_win)
        old_focus = self._focus_id(d.get_input_focus().focus)
        old_shape = self._shape_get(overlay)
        baseline = (original, frame, state, old_focus, overlay, overlay_rect, old_shape)
        try:
            self._fit(target, rect, baseline)
            self._shape_set(overlay, self._rectangles_with_hole(overlay_rect, rect))
            target_win.configure(stack_mode=0)  # Above; real X stacking, no synthetic input.
            overlay_win = self._window(overlay)
            overlay_win.configure(stack_mode=0)
            self._raise_own_controls(app_pid, overlay, target)
            from Xlib import X
            from Xlib.protocol import event
            # Ask the WM to activate this user-selected target, then focus it
            # AFTER raising the non-focusable overlay/control windows.
            request=event.ClientMessage(window=target_win,client_type=self._atom("_NET_ACTIVE_WINDOW"),
                                        data=(32,[2,X.CurrentTime,0,0,0]))
            self.root.send_event(request,event_mask=X.SubstructureRedirectMask|X.SubstructureNotifyMask)
            target_win.set_input_focus(X.RevertToPointerRoot,X.CurrentTime)
            d.sync()
            accepted=False
            deadline=time.monotonic()+.5
            while time.monotonic()<deadline:
                cursor=self._focus_id(d.get_input_focus().focus)
                accepted=cursor==target
                while not accepted and cursor not in (0,1,int(self.root.id)):
                    try:
                        cursor=int(self._window(cursor).query_tree().parent.id)
                        accepted=cursor==target
                    except Exception:
                        break
                if accepted:break
                time.sleep(.01)
            if not accepted:
                raise RuntimeError("Window manager denied focus of the selected game")
            self.active_target, self.overlay, self._baseline = target, overlay, baseline
            self._focus_before = old_focus
            self._shape_saved = (overlay, old_shape)
            self.display.flush()
            return True
        except Exception:
            try:
                self._shape_set(overlay, old_shape)
            except Exception:
                pass
            try:
                self._restore_target(target, baseline)
                self._restore_focus(old_focus)
                d.sync()
            except Exception:
                pass
            raise

    def activate_desktop(self, rect, app_pid):
        """Unscaled desktop passthrough; transformed displays must not misroute clicks."""
        return self._activate_passthrough(rect,app_pid,True)

    def activate_external(self, rect, app_pid):
        """Hole for an externally positioned/focused native Wayland target."""
        return self._activate_passthrough(rect,app_pid,False)

    def _activate_passthrough(self, rect, app_pid, require_fullscreen):
        d = self._connect()
        rect = tuple(map(int, rect))
        if len(rect) != 4 or rect[2] <= 0 or rect[3] <= 0:
            raise ValueError("Desktop input rectangle must have positive dimensions")
        self.deactivate()
        overlay, overlay_rect = self.find_overlay(app_pid)
        if require_fullscreen and rect != tuple(overlay_rect):
            raise RuntimeError("Desktop input requires a source rectangle matching the overlay's physical screen bounds")
        old_shape = self._shape_get(overlay)
        baseline_focus = self._focus_id(d.get_input_focus().focus)
        try:
            self.set_input_hole(overlay, overlay_rect, rect)
            self.display.flush()
            self.overlay = overlay
            self._baseline = (None, None, (), baseline_focus, overlay, overlay_rect, old_shape)
            self._shape_saved = (overlay, old_shape)
            self._desktop_mode = True
            return True
        except Exception:
            try:
                self._shape_set(overlay, old_shape)
                d.sync()
            except Exception:
                pass
            raise
    def _apply_hole(self, rect):
        overlay, old_shape = self._shape_saved
        _x, _y, _w, _h, _f, _s, _old_rects = self._baseline
        # Refresh overlay origin/size in case the UI was resized since activation.
        _xid, current = self._overlay_window_for_id(overlay)
        self._shape_set(overlay, self._rectangles_with_hole(current, rect))

    def _overlay_window_for_id(self, xid):
        win = self._window(xid)
        return xid, self._geometry(win)

    @staticmethod
    def _focus_id(focus):
        return int(getattr(focus, "id", focus) or 0)

    def deactivate(self):
        if self.display is None or self._baseline is None:
            return
        target, overlay = self.active_target, self.overlay
        original, frame, state, old_focus, _overlay, _overlay_rect, old_shape = self._baseline
        failures = []
        try:
            try:
                self._shape_set(overlay, old_shape)
                # Java may expose a sole native source with a bounding-shape
                # hole. Close that visual hole before moving/restoring targets,
                # including backend-detected focus/geometry loss.
                from Xlib.ext import shape
                self._window(overlay).shape_mask(shape.SO.Set, shape.SK.Bounding, 0, 0, 0)
                self.display.sync()
            except Exception as error:
                failures.append(error)
            if not self._desktop_mode:
                try:
                    win = self._window(target)
                    if win.get_attributes().map_state != 0:
                        self._restore_target(target, (original, frame, state))
                except Exception as error:
                    failures.append(error)
                if old_focus not in (0, 1):
                    try:
                        self._restore_focus(old_focus)
                    except Exception as error:
                        failures.append(error)
            try:
                self.display.sync()
            except Exception as error:
                failures.append(error)
        finally:
            self.active_target = self.overlay = 0
            self._baseline = self._shape_saved = None
            self._desktop_mode = False
        if failures:
            raise RuntimeError("Could not fully restore native input state: " + "; ".join(str(e) for e in failures))

    def close(self):
        try:
            self.deactivate()
        finally:
            if self.display is not None:
                self.display.close()
                self.display = self.root = None


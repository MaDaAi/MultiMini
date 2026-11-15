"""Live test against ONLY a window created here. Works on X11 or XWayland.

Does not enumerate, focus, capture or send input to existing applications.
Not evidence of native Wayland capture or compatibility with ordinary clients.
"""
import os
import tempfile
import threading
import time
from Xlib import X, display
from multimin_backend.linux_portal import Portal
from multimin_backend.linux_service import LinuxService
from multimin_backend.linux_x11 import X11

owner = display.Display()
root = owner.screen().root
window = root.create_window(60, 60, 320, 180, 0, owner.screen().root_depth,
    X.InputOutput, X.CopyFromParent, background_pixel=0x112233,
    event_mask=X.ExposureMask | X.KeyPressMask | X.ButtonPressMask | X.PointerMotionMask)
window.set_wm_name('MultiMini Linux capture test')
window.map()
owner.sync()
# Mapping is asynchronous when a window manager owns the root redirect.
for _ in range(100):
    if window.get_attributes().map_state == X.IsViewable:
        break
    time.sleep(.02)
assert window.get_attributes().map_state == X.IsViewable, 'Fixture was not mapped'
portal = Portal()
service = None
capture = None
try:
    with tempfile.TemporaryDirectory(prefix='multimini-x11-') as profile:
        # Only this child process chooses the X11 backend for its own fixture.
        os.environ['XDG_SESSION_TYPE'] = 'x11'
        os.environ.pop('WAYLAND_DISPLAY', None)
        os.environ['XDG_DATA_HOME'] = profile
        os.environ['LOCALAPPDATA'] = profile
        service = LinuxService(lambda _event: None, portal)
        assert service.x11 is not None
        # No SelectSources call: target is the XID this process just created.
        ready = threading.Event()
        frames, errors = [], []
        def frame(w, h, pixels):
            frames.append((w, h, pixels[:4]))
            ready.set()
        capture = service._x11_capture(window.id, frame, errors.append)
        assert ready.wait(5), f'No frame: {errors}'
        assert frames[0][0:2] == (320, 180), frames[0]
        assert frames[0][2][:3] == b'\x33\x22\x11', frames[0]
        service.dispatch(dict(command='set_input_target', hwnd=window.id, x=10, y=20, width=640, height=360))
        # Configure must not forward any input.
        service.dispatch(dict(command='linux_input', kind='press', x=330, y=200, button=1))
        owner.sync()
        while owner.pending_events():
            assert owner.next_event().type != X.ButtonPress, 'Configure forwarded input'
        service.enabled = True  # Isolate directed delivery from native routing and shortcuts.
        service.input_mode = "directed"
        service.dispatch(dict(command='linux_input', kind='press', x=330, y=200, button=1))
        service.dispatch(dict(command='linux_input', kind='key_press', keysym=ord('a')))
        deadline = time.monotonic()+3
        received = []
        while time.monotonic()<deadline:
            while owner.pending_events():
                received.append(owner.next_event())
            if any(e.type == X.ButtonPress for e in received) and any(e.type == X.KeyPress for e in received):
                break
            time.sleep(.01)
        button = next(e for e in received if e.type == X.ButtonPress)
        key = next(e for e in received if e.type == X.KeyPress)
        assert (button.event_x, button.event_y) == (160, 90), (button.event_x,button.event_y)
        assert owner.keycode_to_keysym(key.detail, 0) == ord('a')
        assert button.send_event and key.send_event
        print(f'PASS: controlled X11 drawable capture {frames[0][0]}x{frames[0][1]}, BGRA pixels; Configure blocked input; mapped mouse and keyboard delivered only to fixture')
finally:
    if capture:
        capture.close()
    if service:
        service.dispatch({'command':'shutdown'})
    else:
        portal.close()
    window.destroy()
    owner.sync()
    owner.close()

"""Interactive portal probe; choose ONLY the controlled test window.

Run after creating a test window. The desktop picker requires the user's action.
No frames are written to disk; report dimensions and frame change only.
"""
import hashlib
import threading
from multimin_backend.linux_portal import Portal

print('Select the controlled MultiMini test window in the desktop sharing picker.', flush=True)
portal=Portal()
ready=threading.Event()
frames=[]
errors=[]
capture=None
try:
    def frame(w,h,pixels):
        frames.append((w,h,hashlib.sha256(pixels).digest()))
        if len(frames)>=5:
            ready.set()
    capture=portal.capture(2,frame,errors.append)
    if not ready.wait(15):
        raise RuntimeError(f'No sustained frames: {errors}')
    print(f'PASS: portal delivered {len(frames)} frames at {frames[-1][0]}x{frames[-1][1]}; pixel contents were not saved')
finally:
    if capture:
        capture.close()
    portal.close()

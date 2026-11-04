"""Isolate native WGC callbacks from Python pixel packing on a controlled window."""
import base64
import subprocess
import threading
import time
from windows_capture import WindowsCapture
from multimin_backend.windows_input import WindowsInputBridge

fixture=subprocess.Popen(['java','-cp','build/internal','MovingCaptureFixture'],stdout=subprocess.PIPE,text=True)
threading.Thread(target=lambda:[print(line.strip(),flush=True) for line in fixture.stdout],daemon=True).start()
try:
    bridge=WindowsInputBridge(lambda _:None)
    for _ in range(30):
        target=next((w for w in bridge.list_windows() if base64.b64decode(w['title64']).decode()=='MultiMini moving capture fixture'),None)
        if target:break
        time.sleep(.2)
    if not target:raise RuntimeError('Fixture missing')
    for copy_pixels in (False,True):
        counters=[0,0]
        capture=WindowsCapture(window_hwnd=target['hwnd'],cursor_capture=False,draw_border=False,minimum_update_interval=1)
        @capture.event
        def on_frame_arrived(frame,_control):
            counters[0]+=1
            if copy_pixels:
                started=time.perf_counter_ns();frame.frame_buffer.tobytes();counters[1]+=time.perf_counter_ns()-started
        @capture.event
        def on_closed():pass
        control=capture.start_free_threaded();time.sleep(1)
        counters[:]=[0,0];start=time.monotonic();time.sleep(5);elapsed=time.monotonic()-start
        count,packing=counters;control.stop()
        print(f'copy={copy_pixels} callbacks_fps={count/elapsed:.1f} packing_ms_per_frame={packing/max(1,count)/1e6:.3f}',flush=True)
    bridge.close()
finally:
    fixture.terminate();fixture.wait(timeout=5)

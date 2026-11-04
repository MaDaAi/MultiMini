"""Run the actual Java UI against one controlled moving WGC window; no game interaction."""
import base64
import os
import subprocess
import sys
import time
import threading
from multimin_backend.windows_input import WindowsInputBridge

fixture=subprocess.Popen(['java','-cp','build/internal','MovingCaptureFixture'],stdout=subprocess.PIPE,text=True)
threading.Thread(target=lambda: [print(line.strip(),flush=True) for line in fixture.stdout],daemon=True).start()
try:
    bridge=WindowsInputBridge(lambda event:None)
    target=None
    for _ in range(30):
        target=next((w for w in bridge.list_windows() if base64.b64decode(w['title64']).decode()=='MultiMini moving capture fixture'),None)
        if target:break
        time.sleep(.2)
    if not target:raise RuntimeError('Fixture window not found')
    native='--native-preview' in sys.argv
    dwm='--dwm' in sys.argv
    for jar in [arg for arg in sys.argv[1:] if arg not in ('--native-preview','--dwm')]:
        print('BUILD:',jar,flush=True)
        subprocess.run(['java','--enable-native-access=ALL-UNNAMED','-Xmx2g','-XX:G1HeapRegionSize=32m',
                        '-cp','build/internal;'+jar,'app.multimin.ui.CapturePipelineCheck',str(target['hwnd'])] + (['dwm'] if dwm else ['native'] if native else []),check=True)
    bridge.close()
finally:
    fixture.terminate()
    fixture.wait(timeout=5)

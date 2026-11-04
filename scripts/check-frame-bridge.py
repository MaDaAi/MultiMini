"""Run after compiling native/tests/FrameBridgeCheck.java into build/."""
import subprocess
import sys
from multimin_backend.frame_stream import FrameStream

jar = sys.argv[1]
classes = sys.argv[2] if len(sys.argv) > 2 else 'build/internal'
stream = FrameStream()
try:
    stream.publish('small', 1, 1, bytes((23, 45, 67, 128)))
    stream.publish('large', 127, 71, bytes((23, 45, 67, 128)) * (127 * 71))
    for shared in (True, False):
        subprocess.run(['java', '--enable-native-access=ALL-UNNAMED',
                        '-Dmultimin.disableSharedFrames=' + str(not shared).lower(),
                        '-cp', f'{classes};{jar}', 'FrameBridgeCheck', str(stream.port), str(shared).lower()], check=True)
finally:
    stream.close()

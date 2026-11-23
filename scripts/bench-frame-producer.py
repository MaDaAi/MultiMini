#!/usr/bin/env python3
"""Publishes moving BGRA frames through the real FrameStream at a fixed rate.

Used by native/tests/LinuxPaintBench.java. Prints one JSON line with the loopback
port, then a frame_ready event per published frame (like the backend does).
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'python'))
from multimin_backend.frame_stream import FrameStream  # noqa: E402

p = argparse.ArgumentParser()
p.add_argument('--width', type=int, required=True)
p.add_argument('--height', type=int, required=True)
p.add_argument('--fps', type=float, required=True)
p.add_argument('--seconds', type=float, required=True)
p.add_argument('--id', default='bench')
a = p.parse_args()

w, h = a.width, a.height
row_bytes = w * 4
base_row = b''.join(bytes((x * 255 // w, 90, 255 - x * 255 // w, 255)) for x in range(w))
background = bytearray(base_row * h)
bar = bytes((255, 255, 255, 255)) * 120
pool = []
for k in range(16):
    frame = bytearray(background)
    left = (k * 97) % (w - 120)
    for y in range(h):
        frame[y * row_bytes + left * 4: y * row_bytes + (left + 120) * 4] = bar
    pool.append(bytes(frame))


def notify():
    sys.stdout.write('{"event":"frame_ready"}\n')
    sys.stdout.flush()


stream = FrameStream(notify, coalesce_notifications=True)
print(json.dumps({'event': 'ready', 'port': stream.port}), flush=True)
stream.publish(a.id, w, h, pool[0])

period = 1.0 / a.fps
deadline = time.monotonic()
end = deadline + a.seconds
n = 0
try:
    while time.monotonic() < end:
        deadline += period
        stream.publish(a.id, w, h, pool[n % len(pool)])
        n += 1
        delay = deadline - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        elif delay < -0.25:
            deadline = time.monotonic()
finally:
    stream.close()

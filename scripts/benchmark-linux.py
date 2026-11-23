#!/usr/bin/env python3
"""Linux X11 capture pipeline benchmark (Xvfb with/without a compositor, and Xwayland).

    dbus-run-session -- python3 scripts/benchmark-linux.py run --jar build/internal/MultiMini-Linux.jar --out build/bench

Modes
  run      orchestrate all scenarios and write results.json / results.md
  window   controlled moving test window (internal)
  backend  the real backend with timing wrappers around it (internal; the
           application code itself is not modified)
  probe    micro-benchmark of individual X read calls against a live window
           (internal; run per display by `run`)

The test window draws a 34-cell strip (two fixed marker cells + a 32-bit frame
counter) and a moving bar at a fixed rate. The strip is composed off-screen and
copied in one request, so a capture never sees a half-drawn counter. Java
decodes the counter from the frame it receives; both sides log CLOCK_MONOTONIC
nanoseconds (Java System.nanoTime() and Python time.monotonic_ns() share it on
Linux), which gives end-to-end latency directly.
"""
from __future__ import annotations

import argparse
import bisect
import json
import os
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time

CELL_W, CELL_H, CELLS = 16, 24, 34
BAR_W = 120


# --------------------------------------------------------------------------
# Test window
# --------------------------------------------------------------------------
def window_main(args):
    from Xlib import X, display
    d = display.Display()
    screen = d.screen()
    win = screen.root.create_window(0, 0, args.width, args.height, 0, screen.root_depth, X.InputOutput,
                                    X.CopyFromParent, background_pixel=0x202020, event_mask=0)
    win.set_wm_name("MultiMini Benchmark Source")
    win.map()
    d.sync()
    pix = win.create_pixmap(args.width, args.height, screen.root_depth)
    gc_bg = win.create_gc(foreground=0x202020)
    gc_black = win.create_gc(foreground=0x000000)
    gc_white = win.create_gc(foreground=0xFFFFFF)
    gc_bar = win.create_gc(foreground=0x3080FF)
    pix.fill_rectangle(gc_bg, 0, 0, args.width, args.height)
    win.copy_area(gc_bg, pix, 0, 0, args.width, args.height, 0, 0)
    d.sync()
    print("XID", win.id, flush=True)

    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    period = 1.0 / args.hz
    submit, done = [], []
    mono = time.monotonic_ns
    span = args.width - BAR_W
    speed = 1200.0  # px/s
    old_x = 0
    counter = 0
    begin = time.perf_counter()
    next_t = begin
    pc = time.perf_counter
    while not stop.is_set():
        now = pc()
        if now < next_t:
            gap = next_t - now
            if gap > 0.002:
                time.sleep(gap - 0.001)
            while pc() < next_t:
                pass
        elif now - next_t > period:
            next_t = now  # stalled: resume cadence, no catch-up burst
        next_t += period
        counter += 1
        t_submit = mono()
        x = int((pc() - begin) * speed) % span
        cells = [(0, 0, CELL_W, CELL_H)]
        for i in range(32):
            if (counter >> i) & 1:
                cells.append(((i + 2) * CELL_W, 0, CELL_W, CELL_H))
        pix.fill_rectangle(gc_black, 0, 0, CELLS * CELL_W, CELL_H)
        pix.poly_fill_rectangle(gc_white, [(0, 0, CELL_W, CELL_H)] + cells[1:])
        pix.fill_rectangle(gc_bg, old_x, CELL_H, BAR_W, args.height - CELL_H)
        pix.fill_rectangle(gc_bar, x, CELL_H, BAR_W, args.height - CELL_H)
        win.copy_area(gc_bg, pix, 0, 0, CELLS * CELL_W, CELL_H, 0, 0)  # atomic counter strip
        left = min(old_x, x)
        win.copy_area(gc_bg, pix, left, CELL_H, abs(x - old_x) + BAR_W, args.height - CELL_H, left, CELL_H)
        d.flush()
        d.sync()
        submit.append(t_submit)
        done.append(mono())
        old_x = x
        if args.seconds and pc() - begin > args.seconds:
            break
    with open(args.log, "w") as handle:
        json.dump({"width": args.width, "height": args.height, "hz": args.hz, "submit": submit, "done": done}, handle)
    d.close()


# --------------------------------------------------------------------------
# Backend with timing wrappers
# --------------------------------------------------------------------------
def backend_main(_args):
    import threading as th
    stats = {"pub_ns": [], "pub_dur_ns": [], "capture_total_ns": [], "grab": [], "shm_write": [],
             "xlib_get_image": [], "xshm_init_failures": [], "xshm_grab_failures": [], "xshm_inits": 0}
    local = th.local()
    mono = time.monotonic_ns
    pc = time.perf_counter_ns

    from multimin_backend import frame_stream, linux_xshm
    FrameStream, SharedReply = frame_stream.FrameStream, frame_stream.SharedReply
    Grabber = linux_xshm.XShmGrabber

    original_init, original_grab = Grabber.__init__, Grabber.grab

    def init(self, xid):
        try:
            original_init(self, xid)
            stats["xshm_inits"] += 1
        except Exception as error:
            stats["xshm_init_failures"].append(str(error)[:200])
            raise

    def grab(self):
        start = pc()
        local.start = start
        try:
            result = original_grab(self)
        except Exception as error:
            if len(stats["xshm_grab_failures"]) < 20:
                stats["xshm_grab_failures"].append(str(error)[:200])
            raise
        stats["grab"].append((mono(), pc() - start))
        return result

    Grabber.__init__, Grabber.grab = init, grab

    original_publish = FrameStream.publish

    def publish(self, source, width, height, pixels):
        start = pc()
        began = getattr(local, "start", None)
        local.start = None
        original_publish(self, source, width, height, pixels)
        end = pc()
        stats["pub_ns"].append(mono())
        stats["pub_dur_ns"].append(end - start)
        if began is not None:
            stats["capture_total_ns"].append((mono(), end - began))

    FrameStream.publish = publish

    original_write = SharedReply.write

    def write(self, pixels):
        start = pc()
        try:
            return original_write(self, pixels)
        finally:
            stats["shm_write"].append(pc() - start)

    SharedReply.write = write

    try:  # Pure-Python fallback path: time python-xlib's own read.
        from Xlib.xobject import drawable
        original_get_image = drawable.Drawable.get_image

        def get_image(self, *a, **k):
            start = pc()
            if getattr(local, "start", None) is None:
                local.start = start
            try:
                return original_get_image(self, *a, **k)
            finally:
                stats["xlib_get_image"].append((mono(), pc() - start))
        drawable.Drawable.get_image = get_image
    except Exception:
        pass

    out = os.environ.get("MM_BENCH_STATS")
    try:
        from multimin_backend.platforms import runtime_module
        runtime_module().run()
    finally:
        if out:
            with open(out, "w") as handle:
                json.dump(stats, handle)


# --------------------------------------------------------------------------
# Probe: where does one grab spend its time?
# --------------------------------------------------------------------------
def decode_counter(pixels, width):
    """Frame counter drawn by the test window (see window_main); -1 when absent."""
    bits = []
    for i in range(CELLS):
        offset = ((CELL_H // 2) * width + i * CELL_W + CELL_W // 2) * 4
        bits.append(1 if pixels[offset] > 127 else 0)
    if bits[0] != 1 or bits[1] != 0:
        return -1
    return sum(bit << i for i, bit in enumerate(bits[2:]))


def find_window(display, name):
    from Xlib import X
    def walk(win):
        try:
            if (win.get_wm_name() or "") == name:
                if win.get_attributes().map_state == X.IsViewable:
                    return win.id
            for child in win.query_tree().children:
                found = walk(child)
                if found:
                    return found
        except Exception:
            pass
        return 0
    return walk(display.screen().root)


def probe_main(args):
    import zlib
    from Xlib import X, display
    from multimin_backend import linux_xshm as xs
    xid = args.xid
    if args.name:
        d = display.Display()
        deadline = time.time() + 15
        xid = 0
        while time.time() < deadline and not xid:
            xid = find_window(d, args.name)
            time.sleep(0.3)
        d.close()
        if not xid:
            raise SystemExit("window not found: " + args.name)
    g = xs.XShmGrabber(xid)
    width, height, _ = g.grab()
    x11, xext, comp, display_ptr = g.x11, g.xext, g.comp, g.display
    planes = xs.ALL_PLANES
    pc = time.perf_counter_ns
    out = {"xid": xid, "size": [width, height], "composite": g.composite, "event_driven": g.event_driven, "variants": {}}

    def timed(label, fn, seconds=2.5):
        times, fails, end = [], 0, time.perf_counter() + seconds
        while time.perf_counter() < end:
            start = pc()
            try:
                fn()
            except Exception:
                fails += 1
                continue
            times.append((pc() - start) / 1e6)
        entry = summary(times) or {}
        entry["fails"] = fails
        out["variants"][label] = entry

    def need(ok):
        if not ok:
            raise RuntimeError("read failed")

    def direct(sync):
        def run():
            need(xext.XShmGetImage(display_ptr, g.xid, g.image, 0, 0, planes))
            if sync:
                x11.XSync(display_ptr, 0)
        return run

    def named(sync):
        def run():
            pixmap = comp.XCompositeNameWindowPixmap(display_ptr, g.xid)
            try:
                need(xext.XShmGetImage(display_ptr, pixmap, g.image, 0, 0, planes))
                if sync:
                    x11.XSync(display_ptr, 0)
            finally:
                x11.XFreePixmap(display_ptr, pixmap)
        return run

    cached = comp.XCompositeNameWindowPixmap(display_ptr, g.xid)
    x11.XSync(display_ptr, 0)

    def cached_pixmap():
        need(xext.XShmGetImage(display_ptr, cached, g.image, 0, 0, planes))

    ex = display.Display()
    ewin = ex.create_resource_object("window", xid)

    def xlib_get_image():
        ewin.get_image(0, 0, width, height, X.ZPixmap, 0xFFFFFFFF).data

    timed("grab() as shipped", g.grab)
    timed("window XShmGetImage + XSync", direct(True))
    timed("window XShmGetImage", direct(False))
    timed("NameWindowPixmap + XShmGetImage + XSync", named(True))
    timed("NameWindowPixmap + XShmGetImage", named(False))
    timed("cached pixmap XShmGetImage", cached_pixmap)
    timed("python-xlib get_image (fallback)", xlib_get_image)
    timed("copy out of shm (memmove)", lambda: g._copy_out(width * height * 4))
    timed("XSync round trip", lambda: x11.XSync(display_ptr, 0), 1.0)
    # Damage-driven loop as the capture thread runs it, against polling at full speed.
    seconds = 4.0
    for label, driven in (("damage-driven", True), ("polled", False)):
        frames, wakeups, timeouts, end = [], 0, 0, time.perf_counter() + seconds
        while time.perf_counter() < end:
            if driven:
                if not g.wait_for_change(0.25):
                    timeouts += 1
                    continue
                wakeups += 1
            _, _, pixels = g.grab()
            counter = decode_counter(pixels, width) if not args.name else -1
            frames.append(counter if counter >= 0 else zlib.crc32(pixels))
        out[label] = {"grabs_per_s": round(len(frames) / seconds, 1), "unique_per_s": round(len(set(frames)) / seconds, 1),
                      "wakeups_per_s": round(wakeups / seconds, 1), "timeouts": timeouts,
                      "counter_gaps": sum(1 for a, b in zip(frames, frames[1:]) if not args.name and b - a > 1)}
    g.close()
    ex.close()
    print("PROBE " + json.dumps(out), flush=True)

# --------------------------------------------------------------------------
# Orchestrator
# --------------------------------------------------------------------------
def pct(values, q):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(q / 100 * (len(ordered) - 1)))))
    return ordered[index]


def summary(values):
    if not values:
        return None
    return {"n": len(values), "mean": round(statistics.fmean(values), 3), "p50": round(pct(values, 50), 3),
            "p95": round(pct(values, 95), 3), "max": round(max(values), 3)}


def read_ticks(pid):
    try:
        with open(f"/proc/{pid}/stat") as handle:
            data = handle.read()
        fields = data[data.rindex(")") + 2:].split()
        return int(fields[11]) + int(fields[12])
    except (OSError, ValueError):
        return -1


class Sampler(threading.Thread):
    """Samples CPU ticks of helper processes once per 500 ms."""
    def __init__(self, pids):
        super().__init__(daemon=True)
        self.pids, self.rows, self.stop = pids, [], threading.Event()

    def run(self):
        while not self.stop.is_set():
            self.rows.append((time.monotonic_ns(), {name: read_ticks(pid) for name, pid in self.pids.items()}))
            self.stop.wait(0.5)

    def percent(self, name, t0, t1, clk):
        rows = [r for r in self.rows if t0 <= r[0] <= t1 and r[1][name] >= 0]
        if len(rows) < 2:
            return None
        return round((rows[-1][1][name] - rows[0][1][name]) / clk / ((rows[-1][0] - rows[0][0]) / 1e9) * 100, 1)


def cpu_percent(samples, column, clk):
    rows = [r for r in samples if r[column] >= 0]
    if len(rows) < 2:
        return None, None
    wall = (rows[-1][0] - rows[0][0]) / 1e9
    avg = (rows[-1][column] - rows[0][column]) / clk / wall * 100
    peak = 0.0
    for a, b in zip(rows, rows[1:]):
        dt = (b[0] - a[0]) / 1e9
        if dt > 0.2:
            peak = max(peak, (b[column] - a[column]) / clk / dt * 100)
    return round(avg, 1), round(peak, 1)


def analyse(java, stats, window, sampler, clk):
    t0, t1 = java["t_start_ns"], java["t_end_ns"]
    duration = (t1 - t0) / 1e9
    frames = [f for f in java["frames"] if t0 <= f[2] <= t1]
    valid = [f for f in frames if f[1] >= 0]
    submit = window["submit"]
    result = {"duration_s": round(duration, 2), "startup_ms": round(java["startup_ms"], 1),
              "first_frame_ms": round(java["first_frame_ms"], 1), "frames_read": len(frames),
              "frames_undecodable": len(frames) - len(valid), "events": java["events"], "errors": java["errors"]}
    result["java_read_fps"] = round(len(frames) / duration, 1)
    result["frame_size"] = [frames[0][5], frames[0][6]] if frames else None
    pubs = [t for t in stats["pub_ns"] if t0 <= t <= t1]
    result["publish_fps"] = round(len(pubs) / duration, 1)
    grabs = [(t, ns) for t, ns in stats["grab"] if t0 <= t <= t1]
    xlib = [(t, ns) for t, ns in stats["xlib_get_image"] if t0 <= t <= t1]
    result["xshm_grabs"] = len(grabs)
    result["xlib_reads"] = len(xlib)
    result["capture_path"] = "xshm" if grabs and not xlib else "xlib-fallback" if xlib and not grabs else "mixed" if grabs else "none"
    result["xshm_init_failures"] = stats["xshm_init_failures"][:3]
    result["xshm_grab_failures"] = stats["xshm_grab_failures"][:3]
    result["xshm_grab_ms"] = summary([ns / 1e6 for _, ns in grabs])
    result["xlib_get_image_ms"] = summary([ns / 1e6 for _, ns in xlib])
    result["capture_to_publish_ms"] = summary([ns / 1e6 for t, ns in stats["capture_total_ns"] if t0 <= t <= t1])
    result["publish_call_ms"] = summary([ns / 1e6 for t, ns in zip(stats["pub_ns"], stats["pub_dur_ns"]) if t0 <= t <= t1])
    result["shm_write_ms"] = summary([ns / 1e6 for ns in stats["shm_write"]])
    result["java_read_call_ms"] = summary([f[4] / 1e6 for f in frames])
    result["age_at_read_ms"] = summary([(f[2] - f[3]) / 1e6 for f in valid])
    e2e, lag = [], []
    for f in valid:
        index = int(f[1]) - 1
        if 0 <= index < len(submit):
            e2e.append((f[2] - submit[index]) / 1e6)
            lag.append(bisect.bisect_right(submit, f[2]) - int(f[1]))
    result["e2e_ms"] = summary(e2e)
    result["lag_frames"] = summary(lag)
    counters = [int(f[1]) for f in valid]
    result["duplicate_counters"] = len(counters) - len(set(counters))
    skipped = sum(max(0, b[0] - a[0] - 1) for a, b in zip(frames, frames[1:]))
    result["sequences_skipped"] = skipped
    result["window_fps_actual"] = round(len(submit) / max(1e-9, (submit[-1] - submit[0]) / 1e9), 1) if len(submit) > 1 else None
    jc, jp = cpu_percent(java["cpu"], 1, clk)
    pc_, pp = cpu_percent(java["cpu"], 2, clk)
    result["cpu_java_pct"], result["cpu_java_peak_pct"] = jc, jp
    result["cpu_python_pct"], result["cpu_python_peak_pct"] = pc_, pp
    result["cpu_xvfb_pct"] = sampler.percent("xvfb", t0, t1, clk)
    result["cpu_window_pct"] = sampler.percent("window", t0, t1, clk)
    if "compositor" in sampler.pids:
        result["cpu_compositor_pct"] = sampler.percent("compositor", t0, t1, clk)
    return result


def command_output(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        return ""


SCREEN = (2688, 1500)


def wait_for_display(env, seconds=30):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if subprocess.run(["xdpyinfo"], env=env, capture_output=True).returncode == 0:
            return True
        time.sleep(0.25)
    return False


def terminate(proc):
    if proc:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


def start_xwayland(number, out):
    """Headless weston with its Xwayland module: windows are redirected by Xwayland itself."""
    import re
    import tempfile
    runtime = tempfile.mkdtemp(prefix="mm-xdg-")
    os.chmod(runtime, 0o700)
    base = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "WAYLAND_DISPLAY", "XDG_SESSION_TYPE")}
    base["XDG_RUNTIME_DIR"] = runtime
    size = [f"--width={SCREEN[0]}", f"--height={SCREEN[1]}"]
    attempts = [("gl", ["--renderer=gl"]), ("pixman", ["--renderer=pixman"]), ("default", [])]
    notes = []
    for label, extra in attempts:
        log_path = os.path.join(out, f"weston-{label}.log")
        log = open(log_path, "w")
        socket = f"mm-wl-{number}-{label}"
        weston = subprocess.Popen(["weston", "--backend=headless", *extra, "--xwayland", f"--socket={socket}",
                                   "--idle-time=0", *size], env=base, stdout=log, stderr=subprocess.STDOUT)
        display_name = None
        deadline = time.time() + 15
        while time.time() < deadline and weston.poll() is None and not display_name:
            time.sleep(0.3)
            match = re.search(r"listening on display (:\d+)", open(log_path, errors="replace").read())
            display_name = match.group(1) if match else None
        env = dict(base, DISPLAY=display_name or "")
        if display_name and weston.poll() is None and wait_for_display(env):
            pid = command_output("pgrep", "-o", "Xwayland")
            pids = {"xvfb": int(pid) if pid.isdigit() else weston.pid, "compositor": weston.pid}
            text = open(log_path, errors="replace").read()
            glamor = [l.strip() for l in text.splitlines() if "glamor" in l.lower() or "egl" in l.lower()][:6]
            note = {"weston_renderer": label, "xwayland_display": display_name, "log_hints": glamor}
            ext = subprocess.run(["xdpyinfo"], env=env, capture_output=True, text=True).stdout
            note["extensions"] = [w for w in ("GLX", "DRI3", "Present", "COMPOSITE", "DAMAGE", "MIT-SHM") if w in ext]
            print("xwayland up:", json.dumps(note), flush=True)
            return env, [weston], pids, note
        terminate(weston)
        notes.append(f"{label}: display={display_name!r} exit={weston.poll()}")
        print("weston attempt failed:", label, open(log_path, errors="replace").read()[-1500:], flush=True)
    raise RuntimeError("could not start weston + Xwayland: " + "; ".join(notes))


def start_display(number, compositor, out):
    """Returns (env, processes to stop, pids to sample, note)."""
    if compositor == "xwayland":
        return start_xwayland(number, out)
    env = dict(os.environ, DISPLAY=f":{number}")
    xvfb = subprocess.Popen(["Xvfb", f":{number}", "-screen", "0", f"{SCREEN[0]}x{SCREEN[1]}x24", "-nolisten", "tcp"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not wait_for_display(env, 15):
        raise RuntimeError("Xvfb did not start")
    procs, pids = [xvfb], {"xvfb": xvfb.pid}
    if compositor == "xcompmgr":
        comp = subprocess.Popen(["xcompmgr", "-n"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.0)
        if comp.poll() is not None:
            raise RuntimeError("xcompmgr exited")
        procs.insert(0, comp)
        pids["compositor"] = comp.pid
    return env, procs, pids, {}


def run_scenario(args, env, pids, spec, index):
    width, height = spec["size"]
    work = os.path.join(args.out, "raw")
    os.makedirs(work, exist_ok=True)
    tag = f"{index:02d}"
    wlog, jout, bstats = (os.path.join(work, f"{tag}-{n}.json") for n in ("window", "java", "backend"))
    window = subprocess.Popen([sys.executable, os.path.abspath(__file__), "window", "--width", str(width), "--height", str(height),
                               "--hz", str(spec["hz"]), "--log", wlog], env=env, stdout=subprocess.PIPE, text=True)
    line = window.stdout.readline().split()
    if not line or line[0] != "XID":
        raise RuntimeError("test window failed to start")
    xid = int(line[1])
    time.sleep(0.5)
    sampler = Sampler(dict(pids, window=window.pid))
    sampler.start()
    benv = dict(env)
    benv["PYTHONPATH"] = os.path.abspath("python")
    benv.pop("WAYLAND_DISPLAY", None)  # the backend must take the X11 path
    benv.pop("XDG_SESSION_TYPE", None)
    cmd = ["java", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED", "-cp",
           os.pathsep.join((args.bench_classes, args.jar)), "LinuxPipelineBench",
           f"python={sys.executable}", f"wrapper={os.path.abspath(__file__)}", f"pythonpath={benv['PYTHONPATH']}",
           f"stats={bstats}", f"xid={xid}", f"path=bench-{tag}", f"fps={spec['fps']}", f"paced={str(spec['paced']).lower()}",
           f"warmup={args.warmup}", f"seconds={args.seconds}", f"out={jout}", f"clk={os.sysconf('SC_CLK_TCK')}"]
    try:
        subprocess.run(cmd, env=benv, check=True, timeout=args.seconds + args.warmup + 90)
    finally:
        sampler.stop.set()
        window.send_signal(signal.SIGTERM)
        try:
            window.wait(timeout=20)
        except subprocess.TimeoutExpired:
            window.kill()
    with open(jout) as h:
        java = json.load(h)
    with open(bstats) as h:
        stats = json.load(h)
    with open(wlog) as h:
        wdata = json.load(h)
    result = analyse(java, stats, wdata, sampler, os.sysconf("SC_CLK_TCK"))
    if not args.keep_raw:
        for f in (wlog, jout, bstats):
            os.remove(f)
    return result


def markdown(meta, results):
    def f(v, key=None):
        if isinstance(v, dict):
            v = v.get(key)
        return "-" if v is None else v
    lines = ["# Linux X11 pipeline benchmark", "",
             f"Runner: {meta['cpu']} x{meta['cores']}, {meta['kernel']}; Java {meta['java']}; {meta['python']}; {meta['xvfb']}", "",
             "Latency columns: age = frame age when Java finished reading it; e2e = test window submit to Java read; "
             "lag = frames the window had already drawn beyond the one Java received.", "",
             "| compositor | size | window Hz | capture fps | paced | path | publish fps | read fps | age p50/p95 ms | e2e p50/p95 ms | lag p50 fr | grab ms mean/p95 | capture->publish ms p50 | java cpu % | python cpu % | x server cpu % | compositor cpu % | window cpu % |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        s = r["spec"]
        res = r["result"]
        grab = res["xshm_grab_ms"] or res["xlib_get_image_ms"]
        lines.append("| {} | {}x{} | {} | {} | {} | {} | {} | {} | {}/{} | {}/{} | {} | {}/{} | {} | {} | {} | {} | {} | {} |".format(
            s["compositor"], s["size"][0], s["size"][1], s["hz"], s["fps"], str(s["paced"]).lower(), res["capture_path"],
            res["publish_fps"], res["java_read_fps"], f(res["age_at_read_ms"], "p50"), f(res["age_at_read_ms"], "p95"),
            f(res["e2e_ms"], "p50"), f(res["e2e_ms"], "p95"), f(res["lag_frames"], "p50"), f(grab, "mean"), f(grab, "p95"),
            f(res["capture_to_publish_ms"], "p50"), f(res["cpu_java_pct"]), f(res["cpu_python_pct"]),
            f(res["cpu_xvfb_pct"]), f(res.get("cpu_compositor_pct")), f(res["cpu_window_pct"])))
    startups = [r["result"]["startup_ms"] for r in results]
    firsts = [r["result"]["first_frame_ms"] for r in results if r["result"]["first_frame_ms"] >= 0]
    lines += ["", f"Backend startup (process start to ready line): median {statistics.median(startups):.0f} ms, "
              f"min {min(startups):.0f}, max {max(startups):.0f} over {len(startups)} launches.",
              f"Register to first frame read: median {statistics.median(firsts):.0f} ms." if firsts else "No frames were read.", ""]
    notes = [(r["spec"], r["result"]) for r in results if r["result"]["errors"] or r["result"]["xshm_init_failures"] or r["result"]["xshm_grab_failures"]]
    if notes:
        lines.append("Notes (first errors per scenario):")
        for s, res in notes:
            lines.append(f"- {s['compositor']} {s['size'][0]}x{s['size'][1]} fps{s['fps']}: {(res['xshm_init_failures'] + res['xshm_grab_failures'] + res['errors'])[:2]}")
    return "\n".join(lines) + "\n"


def run_probe(args, env, size, client):
    """Start a client window, then time individual X reads against it (see probe_main)."""
    width, height = size
    benv = dict(env)
    benv["PYTHONPATH"] = os.path.abspath("python")
    work = os.path.join(args.out, "raw")
    os.makedirs(work, exist_ok=True)
    log = os.path.join(work, "probe-window.json")
    if client == "window":
        proc = subprocess.Popen([sys.executable, os.path.abspath(__file__), "window", "--width", str(width), "--height", str(height),
                                 "--hz", "144", "--log", log], env=env, stdout=subprocess.PIPE, text=True)
        line = proc.stdout.readline().split()
        if not line or line[0] != "XID":
            raise RuntimeError("probe window failed to start")
        target = ["--xid", line[1]]
    else:
        proc = subprocess.Popen(["glxgears", "-geometry", f"{min(width, 1280)}x{min(height, 720)}+0+0"], env=env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        target = ["--name", "glxgears"]
    try:
        time.sleep(1.0)
        done = subprocess.run([sys.executable, os.path.abspath(__file__), "probe", *target], env=benv,
                              capture_output=True, text=True, timeout=120)
        for line in done.stdout.splitlines():
            if line.startswith("PROBE "):
                return json.loads(line[6:])
        raise RuntimeError((done.stderr or done.stdout)[-600:] or "probe produced no result")
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()


def probe_markdown(meta, probes):
    good = [p for p in probes if "failed" not in p["result"]]
    lines = ["", "## Read-call probe", "",
             "Mean / p95 ms per call, each variant run back to back for 2.5 s at full speed against a live window. "
             "'grab() as shipped' is what the capture thread runs per frame.", ""]
    for name, note in meta.get("notes", {}).items():
        if note:
            lines.append(f"- {name}: {json.dumps(note)}")
    if not good:
        return "\n".join(lines + ["", "No probe results.", ""])
    labels = list(good[0]["result"]["variants"])
    lines += ["", "| display | client | size | composite | " + " | ".join(labels) + " |",
              "|---|---|---|---|" + "---|" * len(labels)]
    for p in good:
        r = p["result"]
        cells = []
        for label in labels:
            v = r["variants"].get(label) or {}
            cells.append("-" if not v.get("n") else f"{v['mean']}/{v['p95']}" + (f" ({v['fails']} fails)" if v.get("fails") else ""))
        lines.append(f"| {p['compositor']} | {p['client']} | {p['size'][0]}x{p['size'][1]} | {r['composite']} | " + " | ".join(cells) + " |")
    lines += ["", "Damage-driven grabbing against polling the same window at full speed "
              "(unique = distinct frame contents per second; a big gap means damage events miss frames):", "",
              "| display | client | size | event driven | damage: wakeups/s | grabs/s | unique/s | timeouts | counter gaps | polled: grabs/s | unique/s |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
    for p in good:
        r = p["result"]
        d, q = r["damage-driven"], r["polled"]
        lines.append(f"| {p['compositor']} | {p['client']} | {p['size'][0]}x{p['size'][1]} | {r['event_driven']} | {d['wakeups_per_s']} | "
                     f"{d['grabs_per_s']} | {d['unique_per_s']} | {d['timeouts']} | {d['counter_gaps']} | {q['grabs_per_s']} | {q['unique_per_s']} |")
    for p in probes:
        if "failed" in p["result"]:
            lines.append(f"- probe failed: {p['compositor']} {p['client']} {p['size']}: {p['result']['failed']}")
    return "\n".join(lines) + "\n"


def orchestrate(args):
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.bench_classes, exist_ok=True)
    subprocess.run(["javac", "--release", "25", "-cp", args.jar, "-d", args.bench_classes,
                    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "native", "tests", "LinuxPipelineBench.java")], check=True)
    sizes = [tuple(map(int, s.split("x"))) for s in args.sizes.split(",")]
    matrix = [(144, 60, False), (144, 60, True), (144, 144, False), (144, 144, True), (60, 60, False)]
    meta = {"cpu": next((l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")), "unknown"),
            "cores": os.cpu_count(), "kernel": platform.release(), "python": "Python " + platform.python_version(),
            "java": command_output("java", "-version") or "", "xvfb": command_output("dpkg-query", "-W", "-f=Xvfb ${Version}", "xvfb")}
    try:
        meta["java"] = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
    except Exception:
        pass
    results, probes, notes, index = [], [], {}, 0
    xwayland_sizes = [tuple(map(int, v.split("x"))) for v in args.xwayland_sizes.split(",")]
    for number, compositor in enumerate(args.compositors.split(","), start=90):
        try:
            env, procs, pids, note = start_display(number, compositor, args.out)
        except Exception as error:
            print(f"{compositor}: display failed to start: {error}", flush=True)
            results.append({"spec": {"compositor": compositor, "size": [0, 0], "hz": 0, "fps": 0, "paced": False},
                            "result": {"failed": str(error)}})
            continue
        notes[compositor] = note
        try:
            for size in (xwayland_sizes if compositor == "xwayland" else sizes):
                for hz, fps, paced in matrix:
                    spec = {"compositor": compositor, "size": size, "hz": hz, "fps": fps, "paced": paced}
                    index += 1
                    print(f"[{index}] {spec}", flush=True)
                    try:
                        results.append({"spec": spec, "result": run_scenario(args, env, pids, spec, index)})
                    except Exception as error:
                        print("scenario failed:", error, flush=True)
                        results.append({"spec": spec, "result": {"failed": str(error)}})
            for size in ((1920, 1080), (2560, 1440)) if compositor == "xwayland" else ((1920, 1080),):
                for client in ("window", "glxgears"):
                    try:
                        probes.append({"compositor": compositor, "size": size, "client": client,
                                       "result": run_probe(args, env, size, client)})
                    except Exception as error:
                        print("probe failed:", compositor, size, client, error, flush=True)
                        probes.append({"compositor": compositor, "size": size, "client": client, "result": {"failed": str(error)}})
        finally:
            for proc in procs:
                terminate(proc)
    meta["notes"] = notes
    good = [r for r in results if "failed" not in r["result"]]
    with open(os.path.join(args.out, "results.json"), "w") as h:
        json.dump({"meta": meta, "results": results, "probes": probes}, h, indent=1)
    with open(os.path.join(args.out, "results.md"), "w") as h:
        text = (markdown(meta, good) if good else "# Linux X11 pipeline benchmark\n\nNo scenario produced results.\n") + probe_markdown(meta, probes)
        h.write(text)
    print(text)
    return 0 if len(good) == len(results) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)
    run = sub.add_parser("run")
    run.add_argument("--jar", default="build/internal/MultiMini-Linux.jar")
    run.add_argument("--out", default="build/bench")
    run.add_argument("--bench-classes", default="build/bench-classes")
    run.add_argument("--sizes", default="1280x720,1920x1080")
    run.add_argument("--compositors", default="none,xcompmgr", help="none, xcompmgr and/or xwayland (headless weston + Xwayland)")
    run.add_argument("--xwayland-sizes", default="1920x1080,2560x1440")
    run.add_argument("--seconds", type=float, default=10)
    run.add_argument("--warmup", type=float, default=3)
    run.add_argument("--keep-raw", action="store_true")
    win = sub.add_parser("window")
    win.add_argument("--width", type=int, required=True)
    win.add_argument("--height", type=int, required=True)
    win.add_argument("--hz", type=float, default=144)
    win.add_argument("--log", required=True)
    win.add_argument("--seconds", type=float, default=0)
    sub.add_parser("backend")
    probe = sub.add_parser("probe")
    probe.add_argument("--xid", type=int, default=0)
    probe.add_argument("--name", default="")
    args = parser.parse_args()
    if args.mode == "window":
        window_main(args)
    elif args.mode == "backend":
        backend_main(args)
    elif args.mode == "probe":
        probe_main(args)
    else:
        sys.exit(orchestrate(args))


if __name__ == "__main__":
    main()

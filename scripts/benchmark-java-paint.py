#!/usr/bin/env python3
"""Java read + paint benchmark for the Linux frame pipeline (Xvfb).

    python3 scripts/benchmark-java-paint.py run --jar base=build/base.jar --jar new=build/internal/MultiMini-Linux.jar --out build/paint-bench

Starts Xvfb, compiles native/tests/LinuxPaintBench.java against each jar and runs it
for every scenario / resolution / Java2D pipeline. A separate Python process publishes
moving frames through the real FrameStream at 144 fps (scripts/bench-frame-producer.py).
Writes results.json and results.md.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
JVM = ['--enable-native-access=ALL-UNNAMED', '-Xms64m', '-Xmx2g', '-XX:G1PeriodicGCInterval=30000',
       '-XX:MinHeapFreeRatio=10', '-XX:MaxHeapFreeRatio=30', '-XX:+UseG1GC', '-XX:G1HeapRegionSize=32m',
       '-XX:MaxGCPauseMillis=8']
PIPELINES = {
    'xrender': [],
    'no-xrender': ['-Dsun.java2d.xrender=false'],
    'opengl': ['-Dsun.java2d.opengl=true'],
}
RESOLUTIONS = [(1920, 1080), (2560, 1440)]
SCENARIOS = [('configure', 'window'), ('locked', 'window'), ('locked', 'display')]
NOISE = ('EventDispatchThread', 'EventQueue.', 'InvocationEvent', 'ForkJoinPool', 'AbstractQueued', 'LockSupport',
         'RepaintManager', 'JComponent', 'JLayeredPane', 'LinuxPaintBench')
IMAGE_TYPES = {'rgb': 1, 'argb': 2, 'argb_pre': 3}


def run_one(display, classes, jar, label, pipeline, res, scenario, extra, args, pattern='full', profile=False):
    mode, kind = scenario
    out = os.path.join(args.out, 'raw-%s%s-%s-%dx%d-%s-%s-%s%s.json' % ('prof-' if profile else '', label, pipeline, res[0], res[1], mode, kind, pattern,
                                                                  ('-' + extra[0].split('=')[-1]) if extra else ''))
    cmd = ['java'] + JVM + PIPELINES[pipeline] + extra + ['-cp', os.pathsep.join((classes, jar)),
           'app.multimin.ui.LinuxPaintBench',
           'python=' + sys.executable, 'producer=' + os.path.join(ROOT, 'scripts', 'bench-frame-producer.py'),
           'pythonpath=' + os.path.join(ROOT, 'python'), 'width=%d' % res[0], 'height=%d' % res[1],
           'fps=%d' % args.fps, 'pattern=' + pattern, 'profile=' + str(profile).lower(), 'mode=' + mode, 'kind=' + kind, 'warmup=%s' % args.warmup,
           'seconds=%s' % args.seconds, 'out=' + out, 'sync=%s' % str(args.sync).lower()]
    env = dict(os.environ, DISPLAY=display)
    try:
        proc = subprocess.run(cmd, env=env, check=True, timeout=120, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with open(out) as f:
            data = json.load(f)
        if data.get('paints_per_s', 0) < 1:
            data['stderr'] = (proc.stderr or '')[-900:]
    except Exception as error:  # keep going: report the failure in the table
        detail = getattr(error, 'stderr', '') or ''
        return {'error': str(error)[:200] + ' ' + detail[-400:]}
    data.update(label=label, pipeline=pipeline, pattern=pattern, profiled=profile, image_type=extra[0].split('=')[-1] if extra else 'default')
    return data


def run(args):
    os.makedirs(args.out, exist_ok=True)
    display = ':99'
    xvfb = subprocess.Popen(['Xvfb', display, '-screen', '0', '2560x1440x24', '+extension', 'GLX', '+render', '-noreset'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)
    results = []
    try:
        jars = [j.split('=', 1) for j in args.jar]
        classes = os.path.join(args.out, 'classes')
        shutil.rmtree(classes, ignore_errors=True)
        os.makedirs(classes)
        subprocess.run(['javac', '--release', '25', '-cp', jars[0][1], '-d', classes,
                        os.path.join(ROOT, 'native', 'tests', 'LinuxPaintBench.java')], check=True)
        for res in RESOLUTIONS:
            micro = subprocess.run(['java', '-cp', os.pathsep.join((classes, jars[0][1])), 'app.multimin.ui.LinuxPaintBench',
                                    'micro=true', 'width=%d' % res[0], 'height=%d' % res[1], 'mode=configure'],
                                   env=dict(os.environ, DISPLAY=display), capture_output=True, text=True, timeout=180)
            try:
                results.append({'micro': json.loads(micro.stdout.strip().splitlines()[-1])['micro'], 'res': res})
            except Exception:
                results.append({'micro_error': (micro.stderr or micro.stdout)[-300:], 'res': res})
        # Core comparison: default pipeline, every scenario, content that changes everywhere or in one region.
        for label, jar in jars:
            for res in RESOLUTIONS:
                for scenario in (SCENARIOS[:2] if args.quick else SCENARIOS):
                    for pattern in ('full', 'region'):
                        print('run', label, res, scenario, pattern, flush=True)
                        results.append(run_one(display, classes, jar, label, 'xrender', res, scenario, [], args, pattern))
        for label, jar in jars:
            for scenario in SCENARIOS[:2]:
                print('profile', label, scenario, flush=True)
                results.append(run_one(display, classes, jar, label, 'xrender', RESOLUTIONS[0], scenario, [], args, 'full', True))
        if args.full:
            for label, jar in jars:
                for pipeline in ('no-xrender', 'opengl'):
                    for res in RESOLUTIONS:
                        for scenario in SCENARIOS[:2]:
                            print('run', label, pipeline, res, scenario, flush=True)
                            results.append(run_one(display, classes, jar, label, pipeline, res, scenario, [], args))
        if args.types:
            label, jar = jars[-1]
            for name, code in IMAGE_TYPES.items():
                for res in RESOLUTIONS:
                    print('run type', name, res, flush=True)
                    results.append(run_one(display, classes, jar, label, 'xrender', res, SCENARIOS[1],
                                           ['-Dmultimin.frameImageType=%d' % code], args))
    finally:
        xvfb.terminate()
    with open(os.path.join(args.out, 'results.json'), 'w') as f:
        json.dump(results, f, indent=1)
    with open(os.path.join(args.out, 'results.md'), 'w') as f:
        f.write(markdown(results, args))
    print(markdown(results, args))


def markdown(results, args):
    lines = ['# Java read + paint benchmark (Xvfb, %d fps producer, %ss warmup, %ss measured)\n' % (args.fps, args.warmup, args.seconds)]
    for item in results:
        if 'micro' in item:
            lines.append('Micro costs at %dx%d, median ms per call with X sync: ' % tuple(item['res'])
                         + ', '.join('%s=%.2f' % kv for kv in item['micro'].items()) + '\n')
        if 'micro_error' in item:
            lines.append('Micro error: ' + item['micro_error'] + '\n')
    lines.append('jar/pipe/img | res mode/kind | paints/s | EDT% | paint p50/p95 | read | apply | age p50/p95 | JVM% | py% | Xvfb% | heap | RSS | GC/s')
    for r in results:
        if 'micro' in r or 'micro_error' in r:
            continue
        if r.get('profiled'):
            prof = r.get('profile', {})
            lines.append('PROFILE %s %dx%d %s/%s (%s samples, %% of EDT samples) INCLUSIVE: %s SELF: %s' % (
                r['label'], r['width'], r['height'], r['mode'], r['kind'], prof.get('samples'),
                ' '.join(n for n in prof.get('inclusive', []) if not n.startswith(NOISE)), ' '.join(prof.get('self', []))))
            continue
        if 'error' in r:
            lines.append('ERROR ' + r['error'].replace('|', '/').replace('\n', ' ')[:600])
            continue
        lines.append('%s/%s/%s | %dx%d %s/%s | %.1f | %.0f | %.2f/%.2f | %.2f | %.3f | %.1f/%.1f | %.0f | %.0f | %.0f | %.0f | %.0f | %.1f%s' % (
            r['label'], r['pipeline'], r['image_type'] + '/' + r.get('pattern', 'full'), r['width'], r['height'], r['mode'], r['kind'],
            r['paints_per_s'], r['edt_busy_pct'],
            r['paint_ms']['p50'], r['paint_ms']['p95'], r['read_ms']['mean'], r['apply_ms']['mean'],
            r['age_publish_to_paint_ms']['p50'], r['age_publish_to_paint_ms']['p95'], r['jvm_cpu_pct'],
            r['producer_cpu_pct'], r['xvfb_cpu_pct'], r['heap_used_max_mb'], r['rss_peak_mb'], r['gc_per_s'],
            ('  STDERR: ' + r['stderr'].replace('\n', ' ')) if r.get('stderr') else ''))
    return '\n'.join(lines) + '\n'


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('run')
    r.add_argument('--jar', action='append', required=True, help='label=path (repeatable)')
    r.add_argument('--out', default='build/paint-bench')
    r.add_argument('--fps', type=int, default=144)
    r.add_argument('--warmup', type=float, default=3)
    r.add_argument('--seconds', type=float, default=8)
    r.add_argument('--quick', action='store_true', help='skip the display overlay scenario')
    r.add_argument('--full', action='store_true', help='also run no-xrender and opengl pipelines')
    r.add_argument('--types', action='store_true', help='also compare liveFrame image types (needs the new jar)')
    r.add_argument('--sync', action='store_true', help='XSync after each paint so X server time is included')
    args = p.parse_args()
    run(args)


if __name__ == '__main__':
    main()

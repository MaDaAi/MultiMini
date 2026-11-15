#!/usr/bin/env python3
"""Verify native presentation using only an owned renderer and disposable UI."""
import argparse
import importlib.util
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time
from Xlib import display
from Xlib.ext import shape
from multimin_backend.linux_native_input import NativeInput

args=argparse.ArgumentParser()
args.add_argument('--flatpak',action='store_true',help='Use the installed package JRE and product JAR')
options=args.parse_args()
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('renderer_probe',root/'scripts/check-linux-renderers.py')
probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
subprocess.run(['javac','--release','25','-cp','build/internal/MultiMini-Linux.jar','-d','build/checks',
                'app/src/test/java/app/multimin/ui/LinuxDirectPresentationFixture.java'],cwd=root,check=True)
owner=display.Display()
renderer=subprocess.Popen(['glxgears','-geometry','320x240'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
fixture=router=None

def receive(prefix):
    deadline=time.monotonic()+8
    while time.monotonic()<deadline:
        if select.select([fixture.stdout],[],[],.2)[0]:
            line=fixture.stdout.readline().strip()
            if line.startswith(prefix):return line.split()
        if fixture.poll() is not None:break
    raise RuntimeError('Owned UI fixture did not return '+prefix)

try:
    target,_,_=probe.own_window(owner,renderer.pid,time.monotonic()+8)
    assert probe.process_owns_window(owner,target,renderer.pid)
    with tempfile.TemporaryDirectory(prefix='multimini-direct-check-') as profile:
        env=dict(os.environ,XDG_CONFIG_HOME=profile+'/config',XDG_DATA_HOME=profile+'/data')
        command=['java','--enable-native-access=ALL-UNNAMED','-cp','build/checks:build/internal/MultiMini-Linux.jar',
            'app.multimin.ui.LinuxDirectPresentationFixture',str(target)]
        if options.flatpak:
            command=['flatpak','run',f'--filesystem={root / "build/checks"}:ro',
                f'--env=XDG_CONFIG_HOME={profile}/config',f'--env=XDG_DATA_HOME={profile}/data',
                '--command=/app/jre/bin/java','io.github.MaDaAi.MultiMini','--enable-native-access=ALL-UNNAMED',
                '-cp',f'{root / "build/checks"}:/app/share/multimini/MultiMini.jar',
                'app.multimin.ui.LinuxDirectPresentationFixture',str(target)]
        fixture=subprocess.Popen(command,cwd=root,env=dict(os.environ) if options.flatpak else env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        fields=receive('OWNED ')
        pid,xid,x,y,width,height=map(int,fields[1:])
        if not options.flatpak:assert pid==fixture.pid
        assert probe.process_owns_window(owner,xid,pid)
        router=NativeInput();router.overlay_hint=xid
        router.activate(target,(x,y,width,height),pid)
        assert router.focused_target(target)
        fixture.stdin.write('native\n');fixture.stdin.flush()
        assert receive('DIRECT ')[1]=='true'
        window=owner.create_resource_object('window',xid)
        rectangles=window.shape_get_rectangles(shape.SK.Bounding).rectangles
        overlay_rect=router._geometry(window)
        cx=x-overlay_rect[0]+width//2;cy=y-overlay_rect[1]+height//2
        deadline=time.monotonic()+1
        while any(r.x<=cx<r.x+r.width and r.y<=cy<r.y+r.height for r in rectangles) and time.monotonic()<deadline:
            time.sleep(.02)
            rectangles=window.shape_get_rectangles(shape.SK.Bounding).rectangles
        assert not any(r.x<=cx<r.x+r.width and r.y<=cy<r.y+r.height for r in rectangles), [(r.x,r.y,r.width,r.height) for r in rectangles]
        assert router.focused_target(target)
        print('PASS: owned OpenGL window fitted/focused; product visual hole exposes its native surface; no input injected')
        fixture.stdin.write('reset\n');fixture.stdin.flush();receive('RESET')
        router.close();router=None
        fixture.stdin.write('stop\n');fixture.stdin.flush();fixture.wait(timeout=8)
finally:
    if fixture is not None and fixture.poll() is None:
        fixture.terminate();fixture.wait(timeout=5)
    if router is not None:router.close()
    if renderer.poll() is None:renderer.terminate();renderer.wait(timeout=5)
    owner.close()

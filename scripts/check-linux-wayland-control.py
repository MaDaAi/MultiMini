"""Interactive native Wayland portal check using only an owned GTK test window.

Desktop grants are user actions. Input is sent only while this exact window and
entry report active focus; no unrelated windows or app contents are inspected.
"""
import os
import sys
import threading
import time
os.environ['GDK_BACKEND']='wayland'
import gi
gi.require_version('Gtk','4.0')
from gi.repository import Gtk,GLib
from multimin_backend.linux_portal import Portal

p=Portal()
created=threading.Event()
focused=threading.Event()
finished=threading.Event()
closed=threading.Event()
state={}
marker='multimini'

def create():
    Gtk.init()
    win=Gtk.Window(title='MultiMini Wayland input test')
    win.set_default_size(580,240)
    box=Gtk.Box(orientation=Gtk.Orientation.VERTICAL,spacing=16)
    for setter in (box.set_margin_top,box.set_margin_bottom,box.set_margin_start,box.set_margin_end):setter(24)
    label=Gtk.Label(label='Approve KDE sharing/control prompts, then click the box below.\nThe controlled test types “multimini” only while this window has focus.')
    label.set_wrap(True)
    entry=Gtk.Entry()
    entry.set_placeholder_text('Click here after approving permissions')
    box.append(label);box.append(entry);win.set_child(box)
    win.connect('close-request',lambda *_:(closed.set(),False)[1])
    def focus_update(*_):
        if win.is_active() and (entry.has_focus() or entry.get_delegate().has_focus()):focused.set()
        else:focused.clear()
    win.connect('notify::is-active',focus_update)
    entry.connect('notify::has-focus',focus_update)
    entry.get_delegate().connect('notify::has-focus',focus_update)
    entry.connect('changed',lambda e:finished.set() if e.get_text()==marker else None)
    win.present()
    state.update(win=win,entry=entry,label=label)
    created.set()
    return False
GLib.idle_add(create)
assert created.wait(5),'GTK test window did not start'
session=None
capture=None
try:
    if '--keyboard-only' not in sys.argv:
        print('Select MultiMini Wayland input test in the sharing picker.',flush=True)
        frames=[]
        capture=p.capture(2,lambda w,h,data:frames.append((w,h)),lambda err:print('Capture:',err,flush=True))
        deadline=time.monotonic()+10
        while not frames and time.monotonic()<deadline and not closed.is_set():time.sleep(.05)
        assert frames,'No native Wayland frames arrived'
        print('PASS: native Wayland PipeWire frames',frames[-1],flush=True)
    session=p.create_session('RemoteDesktop')
    p.request('RemoteDesktop','SelectDevices','(oa{sv})',(session,),{'types':p.GLib.Variant('u',3)})
    result=p.request('RemoteDesktop','Start','(osa{sv})',(session,''))
    assert result.get('devices',0)&1,'Keyboard permission was not granted'
    print('Click the typing box in MultiMini Wayland input test now.',flush=True)
    deadline=time.monotonic()+90
    while not focused.wait(.1):
        if closed.is_set() or time.monotonic()>deadline:raise RuntimeError('Owned typing box was not focused')
    time.sleep(.5)
    for char in marker:
        if not focused.is_set():raise RuntimeError('Owned window lost focus; stopped input immediately')
        p.call('RemoteDesktop','NotifyKeyboardKeysym','(oa{sv}iu)',(session,{},ord(char),1))
        p.call('RemoteDesktop','NotifyKeyboardKeysym','(oa{sv}iu)',(session,{},ord(char),0))
        time.sleep(.08)
    assert finished.wait(5),'GTK did not receive the marker through the portal'
    print('PASS: authorized RemoteDesktop keyboard events reached the owned native Wayland entry',flush=True)
    GLib.idle_add(lambda:state['label'].set_text('PASS: native Wayland capture and authorized keyboard input verified.'))
    time.sleep(2)
finally:
    if capture:capture.close()
    if session:p.close_session(session)
    GLib.idle_add(lambda:state['win'].destroy())
    time.sleep(.1)
    p.close()

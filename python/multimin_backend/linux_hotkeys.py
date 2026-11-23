"""Passive shortcuts on the X11 connection, active only during multitasking."""
import select
import threading


def normalized_state(state, X):
    """Return keyboard modifiers, ignoring lock keys and pointer button masks."""
    ignored = X.LockMask | X.Mod2Mask
    for button in range(1, 6):
        ignored |= getattr(X, f'Button{button}Mask', 0)
    return state & ~ignored


def is_autorepeat_release(event, next_event, X):
    """X11 autorepeat represents a held key as adjacent release/press events."""
    return (event.type == X.KeyRelease and next_event is not None
            and next_event.type == X.KeyPress
            and next_event.detail == event.detail
            and next_event.time == event.time)


def accelerator(binding):
    from Xlib import XK, X
    if not binding:
        return None
    aliases = {'RIGHT_CTRL':'Control_R','LEFT_CTRL':'Control_L','RIGHT_ALT':'Alt_R',
        'LEFT_ALT':'Alt_L','RIGHT_SHIFT':'Shift_R','LEFT_SHIFT':'Shift_L',
        'END':'End','PAUSE':'Pause','INSERT':'Insert','ESCAPE':'Escape','SPACE':'space',
        'PAGE_UP':'Prior','PAGE_DOWN':'Next','BACK_SPACE':'BackSpace','DELETE':'Delete'}
    mods={'CTRL':X.ControlMask,'SHIFT':X.ShiftMask,'ALT':X.Mod1Mask,'WIN':X.Mod4Mask}
    parts=binding.split('+')
    state=0
    for part in parts[:-1]:
        if part not in mods:
            raise ValueError('Unsupported shortcut modifier: '+part)
        state |= mods[part]
    name=aliases.get(parts[-1],parts[-1])
    sym=XK.string_to_keysym(name if len(name)>1 else name.lower())
    if not sym:
        raise ValueError('Unsupported shortcut key: '+binding)
    return sym,state


class X11Hotkeys:
    def __init__(self, bindings, emit, on_error):
        self.stop=threading.Event()
        self.thread=threading.Thread(target=self._run,args=(bindings,emit,on_error),daemon=True,name='multimini-x11-hotkeys')
        self.thread.start()

    def _run(self,bindings,emit,on_error):
        from Xlib import display,X
        d=display.Display()
        root=d.screen().root
        lookup={}
        errors=[]
        try:
            for action,binding in bindings:
                parsed=accelerator(binding)
                if parsed is None:
                    continue
                sym,mods=parsed
                code=d.keysym_to_keycode(sym)
                if not code:
                    on_error('No X11 keycode for '+binding)
                    continue
                lookup[(code,mods)]=action
                for locks in (0,X.LockMask,X.Mod2Mask,X.LockMask|X.Mod2Mask):
                    root.grab_key(code,mods|locks,False,X.GrabModeAsync,X.GrabModeAsync,
                                  onerror=lambda error,_request:errors.append(str(error)))
            d.sync()
            if errors:
                on_error('Some X11 shortcuts are already reserved; use the exit control or change bindings')
            held=set()
            deferred=None
            while not self.stop.is_set():
                if deferred is None and not d.pending_events():
                    select.select([d.fileno()],[],[],.25)
                while deferred is not None or d.pending_events():
                    if deferred is not None:
                        event=deferred
                        deferred=None
                    else:
                        event=d.next_event()
                    if event.type==X.KeyRelease:
                        next_event=d.next_event() if d.pending_events() else None
                        if is_autorepeat_release(event,next_event,X):
                            continue
                        if next_event is not None:
                            deferred=next_event
                        held.discard(event.detail)
                    elif event.type==X.KeyPress:
                        action=lookup.get((event.detail,normalized_state(event.state,X)))
                        if action and event.detail not in held:
                            held.add(event.detail)
                            emit({'event':action})
        except Exception as error:
            on_error('X11 shortcuts unavailable: '+str(error))
        finally:
            root.ungrab_key(X.AnyKey,X.AnyModifier)
            d.sync()
            d.close()

    def close(self):
        self.stop.set()
        self.thread.join(timeout=2)

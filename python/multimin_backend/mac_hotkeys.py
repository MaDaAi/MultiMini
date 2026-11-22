"""macOS system-wide hotkeys and keyboard forwarding. UNTESTED on real Macs.

A borderless Java overlay rarely becomes the key window on macOS, so hotkeys
handled inside the app never fired. This module installs a Quartz event tap
(needs the Accessibility permission) that:

* recognises MultiMini's hotkeys anywhere and swallows them, like the
  Windows/Linux global hooks;
* while multitasking with MultiMini in front, forwards other key events to the
  selected app with CGEventPostToPid, so typing reaches it.

Binding strings use the Java recorder format: "CTRL+ALT+E", "RIGHT_ALT", "F5".
"""
from __future__ import annotations

import threading

CONTROL, SHIFT, ALTERNATE, COMMAND = 0x40000, 0x20000, 0x80000, 0x100000
MODIFIER_MASK = CONTROL | SHIFT | ALTERNATE | COMMAND
MODIFIER_NAMES = {"CTRL": CONTROL, "SHIFT": SHIFT, "ALT": ALTERNATE, "OPTION": ALTERNATE,
                  "META": COMMAND, "CMD": COMMAND, "COMMAND": COMMAND}
# Single modifier keys usable alone (no letters needed): keycode and its flag.
MODIFIER_KEYS = {"RIGHT_CTRL": (0x3E, CONTROL), "LEFT_CTRL": (0x3B, CONTROL),
                 "RIGHT_ALT": (0x3D, ALTERNATE), "LEFT_ALT": (0x3A, ALTERNATE),
                 "RIGHT_CMD": (0x36, COMMAND), "RIGHT_SHIFT": (0x3C, SHIFT)}
KEYCODES = {
    "A": 0x00, "S": 0x01, "D": 0x02, "F": 0x03, "H": 0x04, "G": 0x05, "Z": 0x06, "X": 0x07,
    "C": 0x08, "V": 0x09, "B": 0x0B, "Q": 0x0C, "W": 0x0D, "E": 0x0E, "R": 0x0F, "Y": 0x10,
    "T": 0x11, "1": 0x12, "2": 0x13, "3": 0x14, "4": 0x15, "6": 0x16, "5": 0x17, "EQUALS": 0x18,
    "9": 0x19, "7": 0x1A, "MINUS": 0x1B, "8": 0x1C, "0": 0x1D, "CLOSE_BRACKET": 0x1E, "O": 0x1F,
    "U": 0x20, "OPEN_BRACKET": 0x21, "I": 0x22, "P": 0x23, "ENTER": 0x24, "L": 0x25, "J": 0x26,
    "QUOTE": 0x27, "K": 0x28, "SEMICOLON": 0x29, "BACK_SLASH": 0x2A, "COMMA": 0x2B, "SLASH": 0x2C,
    "N": 0x2D, "M": 0x2E, "PERIOD": 0x2F, "TAB": 0x30, "SPACE": 0x31, "BACK_QUOTE": 0x32,
    "BACK_SPACE": 0x33, "ESCAPE": 0x35,
    "F1": 0x7A, "F2": 0x78, "F3": 0x63, "F4": 0x76, "F5": 0x60, "F6": 0x61, "F7": 0x62, "F8": 0x64,
    "F9": 0x65, "F10": 0x6D, "F11": 0x67, "F12": 0x6F, "F13": 0x69, "F14": 0x6B, "F15": 0x71,
    "F16": 0x6A, "F17": 0x40, "F18": 0x4F, "F19": 0x50, "F20": 0x5A,
    "HOME": 0x73, "PAGE_UP": 0x74, "DELETE": 0x75, "END": 0x77, "PAGE_DOWN": 0x79,
    "LEFT": 0x7B, "RIGHT": 0x7C, "DOWN": 0x7D, "UP": 0x7E,
    # Windows keys missing on Mac keyboards map to their usual Mac stand-ins.
    "INSERT": 0x72, "HELP": 0x72, "PAUSE": 0x71, "PRINTSCREEN": 0x69, "PRINT_SCREEN": 0x69, "SCROLL_LOCK": 0x6B,
}
KEY_DOWN, KEY_UP, FLAGS_CHANGED = 10, 11, 12
TAP_DISABLED_BY_TIMEOUT, TAP_DISABLED_BY_USER = 0xFFFFFFFE, 0xFFFFFFFF


class Binding:
    """A parsed hotkey; `modifier_only` bindings fire on the modifier key press."""
    def __init__(self, action, keycode, flags, modifier_only=False):
        self.action, self.keycode, self.flags, self.modifier_only = action, keycode, flags, modifier_only

    def __repr__(self):
        return f"Binding({self.action!r}, {self.keycode:#x}, {self.flags:#x}, {self.modifier_only})"


def parse_binding(action, text):
    """Parse a recorder string; None when it has no Mac equivalent."""
    text = (text or "").strip().upper()
    if not text:
        return None
    if text in MODIFIER_KEYS:
        keycode, flag = MODIFIER_KEYS[text]
        return Binding(action, keycode, flag, True)
    parts = text.split("+")
    flags = 0
    for part in parts[:-1]:
        if part not in MODIFIER_NAMES:
            return None
        flags |= MODIFIER_NAMES[part]
    key = parts[-1]
    if key not in KEYCODES:
        return None
    return Binding(action, KEYCODES[key], flags)


def match(bindings, event_type, keycode, flags, autorepeat=False):
    """Return the action for this key event, or None."""
    flags &= MODIFIER_MASK
    for binding in bindings:
        if binding.keycode != keycode:
            continue
        if binding.modifier_only:
            # A modifier key goes down when its flag appears in flagsChanged.
            if event_type == FLAGS_CHANGED and flags & binding.flags:
                return binding.action
        elif event_type == KEY_DOWN and not autorepeat and flags == binding.flags:
            return binding.action
    return None


def swallows_release(bindings, event_type, keycode):
    """Key-ups of hotkeys are swallowed too, so the target never sees half a key."""
    return event_type == KEY_UP and any(b.keycode == keycode and not b.modifier_only for b in bindings)


class MacKeyTap:
    """Quartz event tap on its own CFRunLoop thread."""

    def __init__(self, bindings, on_action, forward_target):
        """forward_target() -> pid to forward ordinary keys to, or 0."""
        self.bindings = list(bindings)
        self.on_action = on_action
        self.forward_target = forward_target
        self.loop = None
        self.tap = None
        self.ready = threading.Event()
        self.error = None
        self.thread = threading.Thread(target=self._run, name="multimini-mac-keys", daemon=True)
        self.thread.start()
        self.ready.wait(3)
        if self.error:
            raise RuntimeError(self.error)

    def _callback(self, proxy, event_type, event, refcon):
        import Quartz as Q
        if event_type in (TAP_DISABLED_BY_TIMEOUT, TAP_DISABLED_BY_USER):
            if self.tap is not None:
                Q.CGEventTapEnable(self.tap, True)
            return event
        try:
            keycode = int(Q.CGEventGetIntegerValueField(event, Q.kCGKeyboardEventKeycode))
            flags = int(Q.CGEventGetFlags(event))
            repeat = bool(Q.CGEventGetIntegerValueField(event, Q.kCGKeyboardEventAutorepeat))
            action = match(self.bindings, event_type, keycode, flags, repeat)
            if action:
                self.on_action(action)
                return None
            if swallows_release(self.bindings, event_type, keycode):
                return None
            pid = self.forward_target()
            if pid and event_type in (KEY_DOWN, KEY_UP):
                Q.CGEventPostToPid(pid, event)
                return None
        except Exception:
            pass
        return event

    def _run(self):
        try:
            import Quartz as Q
            mask = (Q.CGEventMaskBit(Q.kCGEventKeyDown) | Q.CGEventMaskBit(Q.kCGEventKeyUp)
                    | Q.CGEventMaskBit(Q.kCGEventFlagsChanged))
            self.tap = Q.CGEventTapCreate(Q.kCGSessionEventTap, Q.kCGHeadInsertEventTap,
                                          Q.kCGEventTapOptionDefault, mask, self._callback, None)
            if self.tap is None:
                self.error = ("macOS blocked the keyboard listener. Allow MultiMini in System Settings → "
                              "Privacy & Security → Accessibility (and Input Monitoring), then restart MultiMini.")
                self.ready.set()
                return
            source = Q.CFMachPortCreateRunLoopSource(None, self.tap, 0)
            self.loop = Q.CFRunLoopGetCurrent()
            Q.CFRunLoopAddSource(self.loop, source, Q.kCFRunLoopCommonModes)
            Q.CGEventTapEnable(self.tap, True)
            self.ready.set()
            Q.CFRunLoopRun()
        except Exception as error:
            self.error = f"Keyboard listener failed: {error}"
            self.ready.set()

    def close(self):
        try:
            import Quartz as Q
            if self.tap is not None:
                Q.CGEventTapEnable(self.tap, False)
            if self.loop is not None:
                Q.CFRunLoopStop(self.loop)
        except Exception:
            pass
        self.thread.join(timeout=2)


def frontmost_pid():
    try:
        from AppKit import NSWorkspace
        app = NSWorkspace.sharedWorkspace().frontmostApplication()
        return int(app.processIdentifier()) if app is not None else 0
    except Exception:
        return 0

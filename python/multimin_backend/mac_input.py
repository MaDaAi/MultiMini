"""macOS directed input. UNTESTED: written without access to a Mac.

Java forwards mouse/keyboard events received by the MultiMini overlay. This
module re-posts them to the selected app's process with CGEventPostToPid, at
the matching point inside the real window, and tags mouse events with that
window's id so the app routes them even when it is not frontmost.

Requires the Accessibility permission (System Settings → Privacy & Security →
Accessibility). Games that read raw HID input may ignore these events.
"""
from __future__ import annotations

# X11 keysym (sent by Java) -> macOS virtual key code.
SPECIAL_KEYS = {
    0xff0d: 0x24, 0xff08: 0x33, 0xff09: 0x30, 0xff1b: 0x35, 0xffff: 0x75,
    0xff51: 0x7B, 0xff52: 0x7E, 0xff53: 0x7C, 0xff54: 0x7D,
    0xff55: 0x74, 0xff56: 0x79, 0xff50: 0x73, 0xff57: 0x77, 0xff63: 0x72,
    0xffe1: 0x38, 0xffe2: 0x3C, 0xffe3: 0x3B, 0xffe4: 0x3E, 0xffe9: 0x3A, 0xffea: 0x3D,
    0xffbe: 0x7A, 0xffbf: 0x78, 0xffc0: 0x63, 0xffc1: 0x76, 0xffc2: 0x60, 0xffc3: 0x61,
    0xffc4: 0x62, 0xffc5: 0x64, 0xffc6: 0x65, 0xffc7: 0x6D, 0xffc8: 0x67, 0xffc9: 0x6F,
}
CHAR_KEYS = {
    "a": 0x00, "s": 0x01, "d": 0x02, "f": 0x03, "h": 0x04, "g": 0x05, "z": 0x06, "x": 0x07,
    "c": 0x08, "v": 0x09, "b": 0x0B, "q": 0x0C, "w": 0x0D, "e": 0x0E, "r": 0x0F, "y": 0x10,
    "t": 0x11, "1": 0x12, "2": 0x13, "3": 0x14, "4": 0x15, "6": 0x16, "5": 0x17, "=": 0x18,
    "9": 0x19, "7": 0x1A, "-": 0x1B, "8": 0x1C, "0": 0x1D, "]": 0x1E, "o": 0x1F, "u": 0x20,
    "[": 0x21, "i": 0x22, "p": 0x23, "l": 0x25, "j": 0x26, "'": 0x27, "k": 0x28, ";": 0x29,
    "\\": 0x2A, ",": 0x2B, "/": 0x2C, "n": 0x2D, "m": 0x2E, ".": 0x2F, " ": 0x31, "`": 0x32,
}
SHIFTED = {"!": "1", "@": "2", "#": "3", "$": "4", "%": "5", "^": "6", "&": "7", "*": "8",
           "(": "9", ")": "0", "_": "-", "+": "=", "{": "[", "}": "]", "|": "\\", ":": ";",
           "\"": "'", "<": ",", ">": ".", "?": "/", "~": "`"}
WINDOW_UNDER_POINTER = 91          # kCGMouseEventWindowUnderMousePointer
WINDOW_THAT_CAN_HANDLE = 92        # kCGMouseEventWindowUnderMousePointerThatCanHandleThisEvent


def keycode_for(keysym):
    keysym = int(keysym)
    if keysym in SPECIAL_KEYS:
        return SPECIAL_KEYS[keysym]
    if 32 <= keysym < 0x110000:
        char = chr(keysym).lower()
        char = SHIFTED.get(char, char)
        return CHAR_KEYS.get(char)
    return None


def map_to_window(x, y, rect, bounds):
    """Map an overlay point in rect (points) to global points inside bounds."""
    left, top, width, height = rect
    if width <= 0 or height <= 0 or not (left <= x < left + width and top <= y < top + height):
        return None
    bx, by, bw, bh = bounds
    return (bx + (x - left) * bw / width, by + (y - top) * bh / height)


def accessibility_trusted():
    try:
        from ApplicationServices import AXIsProcessTrusted
        return bool(AXIsProcessTrusted())
    except Exception:
        try:
            import HIServices
            return bool(HIServices.AXIsProcessTrusted())
        except Exception:
            return False


def request_accessibility():
    """Ask macOS to show the Accessibility prompt once."""
    try:
        from ApplicationServices import AXIsProcessTrustedWithOptions, kAXTrustedCheckOptionPrompt
        return bool(AXIsProcessTrustedWithOptions({kAXTrustedCheckOptionPrompt: True}))
    except Exception:
        return False


class MacInput:
    def __init__(self):
        import Quartz
        self.Q = Quartz
        self.held_buttons = {}
        self.held_keys = {}

    def _flags(self, state):
        Q = self.Q
        flags = 0
        if state & 1: flags |= Q.kCGEventFlagMaskShift
        if state & 4: flags |= Q.kCGEventFlagMaskControl
        if state & 8: flags |= Q.kCGEventFlagMaskAlternate
        if state & 64: flags |= Q.kCGEventFlagMaskCommand
        return flags

    def send(self, target, message, rect):
        """target = dict(pid, window_id, bounds) of the selected window."""
        Q = self.Q
        pid, window_id, bounds = target["pid"], target["window_id"], target["bounds"]
        kind = message.get("kind")
        state = int(message.get("state", 0))
        if kind in ("key_press", "key_release"):
            code = keycode_for(message.get("keysym", 0))
            if code is None:
                return False
            down = kind == "key_press"
            event = Q.CGEventCreateKeyboardEvent(None, code, down)
            Q.CGEventSetFlags(event, self._flags(state))
            Q.CGEventPostToPid(pid, event)
            if down:
                self.held_keys[code] = pid
            else:
                self.held_keys.pop(code, None)
            return True
        x, y = float(message.get("x", 0)), float(message.get("y", 0))
        button = int(message.get("button", 1))
        if kind in ("press", "release") and button in (4, 5):
            if kind == "press":
                event = Q.CGEventCreateScrollWheelEvent(None, Q.kCGScrollEventUnitLine, 1, 3 if button == 4 else -3)
                Q.CGEventPostToPid(pid, event)
            return True
        point = map_to_window(x, y, rect, bounds)
        if point is None and kind == "release" and (pid, button) in self.held_buttons:
            point = self.held_buttons[(pid, button)]
        if point is None:
            return False
        kinds = {
            1: (Q.kCGEventLeftMouseDown, Q.kCGEventLeftMouseUp, Q.kCGEventLeftMouseDragged, Q.kCGMouseButtonLeft),
            3: (Q.kCGEventRightMouseDown, Q.kCGEventRightMouseUp, Q.kCGEventRightMouseDragged, Q.kCGMouseButtonRight),
            2: (Q.kCGEventOtherMouseDown, Q.kCGEventOtherMouseUp, Q.kCGEventOtherMouseDragged, Q.kCGMouseButtonCenter),
        }
        if kind == "motion":
            held = [b for (p, b) in self.held_buttons if p == pid]
            if held:
                event_type, mouse_button = kinds.get(held[0], kinds[1])[2], kinds.get(held[0], kinds[1])[3]
            else:
                event_type, mouse_button = Q.kCGEventMouseMoved, Q.kCGMouseButtonLeft
        else:
            down_type, up_type, _drag, mouse_button = kinds.get(button, kinds[1])
            event_type = down_type if kind == "press" else up_type
        event = Q.CGEventCreateMouseEvent(None, event_type, Q.CGPointMake(*point), mouse_button)
        Q.CGEventSetFlags(event, self._flags(state))
        Q.CGEventSetIntegerValueField(event, WINDOW_UNDER_POINTER, int(window_id))
        Q.CGEventSetIntegerValueField(event, WINDOW_THAT_CAN_HANDLE, int(window_id))
        Q.CGEventPostToPid(pid, event)
        if kind == "press":
            self.held_buttons[(pid, button)] = point
        elif kind == "release":
            self.held_buttons.pop((pid, button), None)
        return True

    def release_all(self):
        Q = self.Q
        for (pid, button), point in list(self.held_buttons.items()):
            try:
                up = {1: Q.kCGEventLeftMouseUp, 3: Q.kCGEventRightMouseUp}.get(button, Q.kCGEventOtherMouseUp)
                Q.CGEventPostToPid(pid, Q.CGEventCreateMouseEvent(None, up, Q.CGPointMake(*point), Q.kCGMouseButtonLeft))
            except Exception:
                pass
        for code, pid in list(self.held_keys.items()):
            try:
                Q.CGEventPostToPid(pid, Q.CGEventCreateKeyboardEvent(None, code, False))
            except Exception:
                pass
        self.held_buttons.clear()
        self.held_keys.clear()

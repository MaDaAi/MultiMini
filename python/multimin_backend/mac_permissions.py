"""macOS privacy permissions: check, prompt and open the right Settings page.

Apps cannot grant themselves these permissions. MultiMini asks macOS to show its
own prompt (once per permission) and opens System Settings on the exact list
with MultiMini's switch, so the user only has to flip it.
"""
from __future__ import annotations

import subprocess

PANES = {
    "screen": "Privacy_ScreenCapture",
    "accessibility": "Privacy_Accessibility",
    "input": "Privacy_ListenEvent",
}
SETTINGS_URL = "x-apple.systempreferences:com.apple.preference.security?"


def _quartz():
    try:
        import Quartz
        return Quartz
    except Exception:
        return None


def status():
    """Which permissions are granted. Missing APIs count as granted (older macOS)."""
    from .mac_input import accessibility_trusted
    Q = _quartz()
    def call(name):
        function = getattr(Q, name, None) if Q is not None else None
        try:
            return True if function is None else bool(function())
        except Exception:
            return False
    return {"screen": call("CGPreflightScreenCaptureAccess"),
            "accessibility": accessibility_trusted(),
            "input": call("CGPreflightListenEventAccess")}


def settings_url(kind):
    return SETTINGS_URL + PANES[kind]


def request(kind, opener=None):
    """Show macOS's own prompt for `kind`, then open its Settings page."""
    if kind not in PANES:
        raise ValueError(f"Unknown permission: {kind}")
    Q = _quartz()
    try:
        if kind == "screen" and Q is not None and hasattr(Q, "CGRequestScreenCaptureAccess"):
            Q.CGRequestScreenCaptureAccess()
        elif kind == "input" and Q is not None and hasattr(Q, "CGRequestListenEventAccess"):
            Q.CGRequestListenEventAccess()
        elif kind == "accessibility":
            from .mac_input import request_accessibility
            request_accessibility()
    except Exception:
        pass  # the Settings page below still lets the user switch it on
    (opener or (lambda url: subprocess.Popen(["/usr/bin/open", url])))(settings_url(kind))

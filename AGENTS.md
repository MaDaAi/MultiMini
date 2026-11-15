# MultiMini shared Windows/Linux agent guide

This checkout is the user-selected `MultiMini-main` source export, reattached to
`https://github.com/MaDaAi/MultiMini` on local branch `codex/linux-flatpak`.
Do not use the sibling `MultiMini` directory. Inspect status and preserve local
changes. Do not reset, clean, merge, push or publish without explicit instruction.
The parent folder's longer AGENTS.md remains applicable in this local workspace.

## Linux status

Read docs/LINUX.md and docs/LINUX_VERIFICATION.md before claiming support.
Linux is an unpublished port. Wayland capture uses ScreenCast + PipeWire.
KDE native window input uses `linux_kwin.py` for scoped placement/focus plus an
XWayland overlay input hole. Other Wayland compositors remain capture-only.
X11/XWayland native input uses real focus/hit testing; directed-event fallback
is not game/raw-input certification. Fullscreen and transformed desktop bounds
must match, and original geometry/focus must restore on target switch/exit.
Never claim perfect input or universal games from controlled renderer results.

## Invariants

- Right Ctrl selects input only; Pause changes visual order only; neither changes opacity.
- Configure never focuses, controls or reorders captured applications.
- Settings stage edits: Apply commits, OK commits/closes, Cancel discards unapplied edits.
- Keep at least one view; preserve sources, visibility, input participation, transforms and theme behavior.
- Retain publisher branding, MIT license, third-party notices and the complete README origin section.
- Never accept agreements for the user. Use disposable XDG/APPDATA profiles for tests.
- Never touch unrelated private apps or captured contents for testing. Use controlled fixtures.
- Build/compilation does not verify live capture, routing, shortcuts, OBS or packaging on Windows.

## Ownership and builds

See ROUTING_MAP.md, ARCHITECTURE.md, docs/LINUX.md and docs/RELEASES.md (one `main`, one VERSION, one release with per-OS assets). Shared Java Swing/model code
is in app/src/main/java. Windows Python service and JNI remain in their existing
files. Linux platform code lives in `linux_service.py`, `linux_portal.py`,
`linux_x11.py`, `linux_native_input.py`, `linux_hotkeys.py`, `linux_kwin.py`,
PlatformSupport and LinuxOverlayInput. `linux_xshm.py` is the fast MIT-SHM X11 reader (falls back to Python Xlib).
macOS (untested preview) lives in `mac_service.py`, `mac_capture.py`, `mac_input.py`; see docs/MACOS.md.
`PlatformSupport.isPortable()` (Linux or macOS) gates the shared non-Windows Java paths.

```
./scripts/build-linux.sh
PYTHONPATH=python python3 -m unittest discover -s python/tests -q
./scripts/package-linux.sh
```

Windows continues to use scripts/build.ps1 and scripts/package-windows.ps1.
Both packagers read VERSION by default. Windows build scripts still need a Windows
JDK/native environment. The historical release-1.0.6.yml publishes on its own
file-path trigger: leave it alone. build-platforms.yml only creates CI artifacts.

Flatpak source staging is allowlisted. Keep `.flatpak-builder/`, build/, dist/,
venvs, credentials, screenshots and saved profiles out of source/package inputs.
Keep dependency URLs/hashes pinned. The Flatpak updater is Flatpak itself; never
include or invoke the PowerShell updater on Linux.

Linux overlay identity is explicit: `LinuxWindowIdentity.java` and the original
`native/LinuxWindow.cpp` JAWT bridge tag/report only our realized overlay/control
XIDs. Linux non-focusable overlays use POPUP type on KDE to remain viewable.
Hide/suspend the overlay during portal permission UI, and close input holes
before restoring a target's geometry. Do not stop a producer while holding its
callback lock. Preserve these cleanup/permission boundaries.

Single-source Linux native presentation is an explicit eligibility path, separate
from Windows DWM and graphics hooking. It exposes only the sole visible opaque
window once native input is confirmed. Close its visual hole before restoring
geometry or showing permission dialogs. Keep captured screenshots current.
Multiple visible sources and alpha require capture composition; preserve input
and visual independence. KDE fitting temporarily saves/removes/restores noBorder.

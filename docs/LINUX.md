# Linux preview

This is an unpublished development port, sharing the Java interface, models,
rendering, customization, settings, workspace schema, gallery and frame protocol
with Windows. It is not yet feature-equivalent to Windows. VERSION is shared by
both packagers; a Linux preview does not change or replace the Windows release.

## Desktop capabilities

| Feature | KDE Wayland | X11 / XWayland |
| --- | --- | --- |
| Window/game-window capture | Desktop picker + PipeWire | XComposite/XGetImage; backing pixmap refreshed for each frame |
| Display capture | Desktop picker + PipeWire | Desktop portal backend required |
| Layers, transforms, opacity, visibility | Shared Java renderer | Shared Java renderer |
| Selected-window input | KDE adapter fits/focuses the selected window beneath an input-shaped overlay | Native focus and hit testing beneath the overlay; directed-event fallback |
| Desktop input | Full-size, aligned display only | Full-size, aligned display only |
| Shortcuts | Desktop-owned global bindings; applied descriptions are shown | Passive X11 grabs, including Right Ctrl; local overlay fallback |
| Screenshots/gallery/settings/workspaces | Shared source and XDG paths | Same |
| Native WGC/DWM/DX11 test | Windows only | Windows only |

On KDE, **KDE window (recommended)** is first and includes both Wayland and
XWayland windows. **X11 / XWayland (compatibility)** is second and lists only X11
clients. The separate capture-only choice is offered on other Wayland desktops.
For KDE sources, select
the SAME window in the desktop sharing picker. This selection is explicit:
MultiMini never guesses a native window from a captured image. Other Wayland
desktops can use **Desktop sharing**; the KDE controller is not a
generic Wayland window-control API.

Native input uses real compositor focus and pointer hit testing rather than
synthetic background messages. Only in multitask mode, the selected window is
fitted behind the composition and an input hole lets real clicks reach it.
Original geometry and focus are restored on switching targets or leaving the
mode. A focus or geometry change closes input passthrough and exits control.
Display control also requires matching monitor position and size from the portal.
Configure never moves/focuses captured apps. The composition retains its
original source reference size during fitting, so capture resizing does not
shrink the composition repeatedly. App content may reflow when fitted. Linux window/image-only multitask views use
an opaque top-level window to avoid XWayland per-pixel transparency flashing.
Views containing a visible display retain transparency; their compositor and
capture-feedback behavior remains a compatibility limitation.

Fullscreen/maximized/size-constrained apps can refuse a fit. Fullscreen sources
must already match the desired rectangle. A failed fit keeps the overlay from
passing clicks to a mismatched target. The X11 directed-event fallback works only
with cooperative clients and is explicitly reported as `directed`, not native.
Some games lock/grab input, reject capture, or require unmodified fullscreen;
compatibility must be checked per game. No graphics API hook, anti-cheat bypass,
universal game support, fixed FPS or zero-latency claim is made.

Right Ctrl changes input selection only. Pause changes visual order only. Neither
changes opacity. For native Wayland focus, global keys are assigned by the desktop:
a bare right-side modifier is not a portable portal accelerator. Assign an allowed
shortcut when KDE asks; hints/tooltips show the desktop's applied descriptions.
End and the separate exit control leave multitasking. Test exit before relying on
the mode. KDE restrictions, raw mouse locking, multi-monitor/mixed DPI, minimized
windows and ordinary games still require hands-on compatibility checks.

Capture permission and control are separate. RemoteDesktop keyboard injection was
verified with an owned native GTK fixture after the user's grant; the product's
KDE native route uses real physical input and KWin placement, not that test's
keyboard injection. KWin scripts are one-shot, sender-validated, UUID-targeted,
and deleted/unloaded after each command. Source selection reads local window
metadata. No journals, unrelated app contents, cloud service or account are used.

A monitor containing MultiMini can recurse. Prefer a window source. Restored
portal sources require a fresh desktop grant; missing/ambiguous windows require
reselection. Never blindly reuse native IDs from saved workspaces.

## Game/window presentation and frame pacing

Game capture and Window capture share the Linux capture implementation. No
separate graphics API hook is advertised. For exactly one visible, fully opaque,
controlled window/game source, native input placement can expose that real
window through the overlay rather than showing a delayed captured copy. This
path requires verified native input and fitting; it closes before restoring the
target, a permission dialog, a target change, or leaving multitask mode. Multiple
visible sources and reduced opacity use captured composition. Configure source
includes an opt-out for native presentation. Capture continues in the background
for current screenshots and a usable fallback, so capture CPU/readback cost still
exists even when presentation is native.

KDE fitting temporarily removes server decorations so client pixels align with
the source rectangle, and restores the original decoration/geometry/focus state.
Size-constrained and fullscreen apps may reject placement. X11 compatibility may
also fail to fit a decorated client at a screen edge and use its reported
fallback; native presentation is then unavailable.

Packed PipeWire BGRA buffers now avoid per-row slice/join copies. Padded/mutable
layouts still make an owned packed copy. The fixed-rate limiter preserves its
deadline through small producer jitter instead of repeatedly discarding every
other near-60-Hz sample. Latest-frame transport still drops superseded frames.
These changes do not turn multi-layer Swing composition into GPU-zero-copy
capture. See LINUX_VERIFICATION.md for measurements and remaining game checks.

The KDE text selection identifies the input window; the desktop picker grants
capture separately. The standardized ScreenCast API does not accept that KWin
UUID as a capture target, so first-time permission selection is still required.
API references: [ScreenCast](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.ScreenCast.html),
[KWin Window API](https://develop.kde.org/docs/plasma/kwin/api/).

## Build from source

JDK 25 or newer, a C++ compiler with X11 development headers, Python 3, PyGObject, GStreamer (including appsink, videoconvert
and pipewiresrc), PipeWire, xdg-desktop-portal and the desktop's portal
backend are needed. CachyOS/Arch package names include `jdk-openjdk`,
`python-gobject`, `gst-python`, `gst-plugins-base`, `gst-plugins-good`,
`gst-plugin-pipewire`, `xdg-desktop-portal` and `xdg-desktop-portal-kde`.
Do not install KDE's portal on an unrelated desktop just for this app.

```bash
python3 -m venv --system-site-packages python/.venv
python/.venv/bin/pip install python-xlib==0.33
./scripts/build-linux.sh
./scripts/run-linux.sh
```

The source launcher uses the venv when present. Set MULTIMIN_PYTHON to override it.
A system-site-packages venv allows the distribution's PyGObject to load. The
Linux JAR bundles backend source, but an independently moved JAR still needs its
Python/GStreamer dependencies. The Flatpak supplies these dependencies.

## Build a local Flatpak

One-time build tools and runtimes (large downloads):

```bash
flatpak --user remote-add --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.flatpak.Builder org.gnome.Platform//49 org.gnome.Sdk//49 org.freedesktop.Sdk.Extension.openjdk25//25.08
./scripts/package-linux.sh
flatpak --user install ./dist/MultiMini-1.0.7-Linux-x86_64.flatpak
flatpak run io.github.MaDaAi.MultiMini
```

`flatpak-builder` installed through the distro is also supported. Build contexts
are allowlisted under build/linux-source. Python-Xlib and six wheels are pinned
by URL and SHA-256; license metadata is retained. The package explicitly copies the
OpenJDK extension's legal notices (its installer alone omits that directory). GNOME runtimes supply GI, GStreamer and PipeWire.
The package uses X11/XWayland for Swing even on Wayland; native desktop capture
still goes through the portal. It allows X11, shared IPC and GPU access, with a narrowly named `org.kde.KWin` D-Bus grant for KDE window placement and no
home/host filesystem permission, direct session-bus socket, or network grant.
The frame socket works inside the app's private network namespace. Image selection uses the FileChooser portal so only the selected document is
granted to the sandbox. File grants, clipboard integration and reopening selected
images after restart still need hands-on verification.

No Flatpak repository is configured for distribution. A local bundle can be
updated by installing a rebuilt bundle; `flatpak update` only discovers updates
once a future distribution remote is explicitly configured. Linux never launches
the Windows updater. Nothing here submits to Flathub or publishes a release.

## Saved data and testing

Native config: `$XDG_CONFIG_HOME/multimin` (default `~/.config/multimin`).
Native screenshots: `$XDG_DATA_HOME/MultiMin/screenshots`
(default `~/.local/share/MultiMin/screenshots`).
Flatpak maps these below `~/.var/app/io.github.MaDaAi.MultiMini/`.
Java and Python coordinate screenshots using POSIX record locks on Linux.

Use disposable profiles for tests:

```bash
profile=$(mktemp -d)
XDG_CONFIG_HOME="$profile/config" XDG_DATA_HOME="$profile/data" ./scripts/run-linux.sh
PYTHONPATH=python python3 -m unittest discover -s python/tests -q
```

Read LINUX_VERIFICATION.md for actual results. Build/launch success is not evidence
of capture, input routing or shortcut correctness. If a picker is cancelled,
reselect with Configure source. Check portal availability with:

```bash
gdbus introspect --session --dest org.freedesktop.portal.Desktop --object-path /org/freedesktop/portal/desktop
flatpak run --command=sh io.github.MaDaAi.MultiMini
```

Inside the sandbox, test imports with `python3 -c 'import gi; from gi.repository import Gio'`.
Use app-specific logs; do not collect unrelated desktop/window contents.

## Maintaining one source tree

Shared Java/UI changes build on both platforms without copying files. Windows
keeps `service.py`, `windows_input.py`, JNI and PowerShell packaging. Linux uses
`linux_service.py`, `linux_portal.py`, `linux_x11.py`, `linux_native_input.py`,
`linux_hotkeys.py`, `linux_kwin.py` and PlatformSupport.
Python selection happens at runtime. Add modules to bundled extraction in
PythonBridge when introducing modules. Platform-dependent features still need
implementation and live tests on each OS.

`.github/workflows/build-platforms.yml` checks Windows and Linux Java builds,
Python regressions and a Linux Flatpak artifact from the same revision. It does
not publish releases or merge branches. The historical release workflow is left
untouched. Windows native builds, portable packaging, updater integration and
live routing remain separate required Windows checks before a joint release.
Do not interpret CI compilation as Windows or Linux release certification.

The Linux build includes a small original JAWT bridge (`native/LinuxWindow.cpp`)
that tags/reports only MultiMini's realized overlay window. This avoids guessing
its identity from hidden toolkit windows and works with the Flatpak PID namespace.
It is separate from the existing Windows JNI implementation.

## Updates (1.10.1+)

Install from the MultiMini Flatpak repository once:

```bash
flatpak install --user https://madaai.github.io/MultiMini/multimini.flatpakref
```

After that, Settings → Updates installs new versions in the app (through the
Flatpak portal) and restarts MultiMini; `flatpak update` and software centres
work too. Release bundles also carry this update source.

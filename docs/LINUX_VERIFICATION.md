# Linux verification — 2026-11-14

Linux port verification log. Windows and Linux remain in one repository with
shared UI/models/settings and a shared VERSION. Entries below record what was
actually run and where; later releases are listed in docs/RELEASE_V*.md.
Implementation is original project code using documented platform APIs;
existing licensed Java/Python/desktop dependencies are retained.

## Verified

- Linux Java + C++/JAWT build, local Flatpak packaging and installation.
- 88 Python tests: OK, one Windows shared-memory test skipped on Linux.
- Shared theme/accent contrast and bundled Help/policy resource checks.
- Disposable Linux settings persistence, screenshot PNG storage, Keep/Delete.
- Java/Python socket transport: BGRA/alpha, resize, unchanged frames, reconnect/close.
- Controlled UI state check: empty workspace, independent input/layer switching,
  opacity preserved, composition PNG, workspace reload without stale HWNDs.
- Controlled X11/XWayland capture and input fixture: correct pixels/mapping;
  Configure blocks input; held inputs release to their owners on target switch.
- Real native X11 focus/hit testing through an input-shaped overlay: mouse/key
  press and release have `send_event=False`; original geometry/focus/shape restore.
- Closed/lost captures invalidate their live handles and leave control mode;
  source-path checks reject queued frames from a previous capture registration.
- Live Right Ctrl/Pause/End grabs emit exactly their separate actions once;
  key releases do not trigger extra actions.
- OpenGL glxgears and Vulkan vkcube (xcb) controlled renderers: changing 320x240
  captured frames; native Escape press/release reaches each owned renderer and
  both exit. A DRI3/Present stale-pixmap bug was found and fixed by reacquiring
  the selected window backing pixmap per frame. These are renderers, not proof
  of ordinary-game or anti-cheat compatibility.
- User-assisted native GTK Wayland ScreenCast/PipeWire capture: frames arrived.
- User-assisted RemoteDesktop keyboard permission: marker reached only the
  owned focused GTK entry. Test code checks focus before every key.
- KDE one-shot, sender-validated window API: PID-scoped discovery, exact UUID
  placement/focus, geometry and prior-focus restoration; scripts unload/delete.
- Final installed Flatpak: packaged JRE 25 loads the JAWT bridge; exact owned
  popup XID is viewable and has the correct namespace PID. KWin private
  callback/unload/cleanup and bundled JRE legal files pass. Bundle SHA-256 matches.
- Input heartbeat tests close passthrough on lost focus or changed geometry;
  display input rejects missing or mismatched monitor metadata.
- Exact Java overlay identity via an original JAWT bridge. Normal non-focusable
  windows stayed unmapped on this KDE session; Linux popup overlays were
  immediately viewable. The product now uses popup-type overlay/control windows.
- Permission-pending regression coverage verifies native input suspends until
  the grant returns. The UI hides the overlay while the dialog is open; the full
  interactive dialog/overlay lifecycle remains a hands-on check.
- Original README content, including the complete origin section, stays verbatim.

## Multitask flicker follow-up

The user reported severe flashing only after entering multitask mode with window
sources. An owned synthetic popup reproduced blank samples during Java's
per-pixel translucent presentation. A transparent-popup attempt using a complete
offscreen frame still had 26/180 blank samples; disabling XRender was worse and
is not enabled in the product. Window/image-only compositions already paint a
black background, so they now use an opaque Linux top-level window and draw the
captured raster directly rather than another volatile texture. The same owned
changing-color probe then recorded 0/180 blank samples, both on the host JDK
and with the final installed Flatpak JRE 25 and bundled product JAR. This tests presentation,
not the user's particular captured applications.

A separate repaint regression reproduced stale alpha and old layer pixels.
Linux overlays that need transparency now rebuild a complete device-resolution
raster before drawing it. Display-containing transparent overlays still require
live compatibility work; this change does not establish that their XWayland
presentation or display-capture feedback is fixed.

```
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-overlay-rendering.py
```

The probe samples only the XID/PID of the synthetic popup it launches, makes its
input shape empty, sends no input and closes only its own child. Regression
sources: LinuxOverlayRepaintCheck.java and LinuxOverlayPaintFixture.java.

## Selection and game performance follow-up

- Exact overlay/control XIDs avoid scanning other apps by potentially colliding
  Flatpak namespace PIDs; a regression checks that no PID-based list is read.
- KDE is first/default, X11 compatibility second. Capture-only desktop sharing
  remains available where KDE control is unavailable by desktop type.
- KDE lists managed XWayland as well as Wayland windows. Source names populate
  when the initial scan completes; KDE labels use window titles rather than
  internal desktop identifiers.
- 88 Python tests pass (one Windows-only skip); fixed-rate jitter/cap/stall tests
  and packed/padded/offset/mutable pixel-layout checks are included.
- Packed immutable-buffer microbenchmark: old Python row slicing/join median
  2.444 ms at 1080p and 11.862 ms at 4K; packed-buffer fast path approximately
  0.0024 ms at either size on this machine. This isolates row packing, excludes
  GStreamer mapping/conversion, capture readback, transport and painting, and is
  not a Minecraft FPS or end-to-end latency measurement.
- Headless Java checks verify single-source native eligibility, rejection of
  multi-layer/alpha/non-controlled/no-frame sources, and printAll screenshot
  rendering while native presentation is active.
- `check-linux-direct-presentation.py` passes with an owned OpenGL renderer and
  disposable MainWindow profile: exact owned XIDs, verified fit/focus, and product
  overlay bounding-shape hole. This passes both on the host and with the installed
  Flatpak JRE 25/product JAR (`--flatpak`). The packaged headless eligibility and
  screenshot check also passes. No input was injected. The initial edge-position
  probe was refused by the WM's decorated-window constraints; the inset probe
  passes. This does not establish universal placement or ordinary-game support.
- `check-linux-kwin.py` passes native GTK placement with temporary borderless
  geometry and restoration of original geometry/decoration state.
- Minecraft-specific FPS/relative-mouse and multi-layer latency still need a user
  trial. Background capture remains enabled for screenshots/fallback even with
  native presentation; it can still cost GPU readback and CPU bandwidth.

```
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-direct-presentation.py
```

## Pending or limited

- A final repeat of the raw X11 native-input fixture stopped before injection:
  KDE left the two managed fixture windows unmapped, while its popup mapped.
  Earlier native-input results above remain historical live results; this repeat
  is not a pass. The final installed Java popup bridge check did pass.

- The combined native Wayland overlay click/type trial has not completed. An
  emulated-input attempt waited for a fresh KDE permission response; a manual
  trial timed out without receiving the expected marker. Neither is counted as
  a successful full-app Wayland input test. The native controller and physical
  overlay-hole path are implemented, but final hands-on validation is required.
- KDE is the native Wayland controller supported by this implementation. Other
  Wayland compositors retain portal capture only. Source selection and the
  desktop capture picker must refer to the same window.
- Global native Wayland shortcuts are desktop-assigned; bare Right Ctrl is not
  portable there. Applied descriptions appear in hints/tooltips. X11/XWayland
  passive grabs preserve Right Ctrl, Pause, End and configured bindings.
- Fullscreen/maximized/size-constrained targets may reject fitting. Transformed
  desktop input is refused; the full-size display must align with the overlay.
  Directed X11 fallback is cooperative-client-only and is reported explicitly.
- Ordinary games, locked/relative mouse behavior, mixed DPI/multiple monitors,
  clipboard/file-location integration, region selection and other distributions
  still need hands-on compatibility checks. No universal/perfect input promise.
- Windows native runtime, updater and portable packaging were not run on Linux.
  Existing Windows capture/input/JNI and the historical release workflow remain
  intact; shared compilation and Python fixtures are not Windows certification.
- CI was prepared locally, not executed remotely. No public Flatpak remote exists.
- No agreement was accepted on behalf of the user.

## Reproduce

```
./scripts/check-linux.sh
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-native-input.py
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-hotkeys.py
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-renderers.py --native-input
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-kwin.py
```

Interactive controlled probes:

```
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-wayland-control.py
PYTHONPATH=python python/.venv/bin/python scripts/check-linux-kde-routing.py --manual
```

Use the local installed app with `flatpak run io.github.MaDaAi.MultiMini`.
See LINUX.md for exact build/install/update commands and platform boundaries.

## 2026-11-14 follow-up (worked on Windows; no Linux run yet)

Changes made from Windows. They have unit tests here but have **not** run on Linux:

- **Background capture throttle during native presentation.** While the
  single-source fast path shows the real window, Java sends
  `linux_background_capture` (12 fps) for that source and clears it (0) when
  the fast path closes. The service caps publication per source, and
  PipeWire drops surplus buffers in a pad probe *before* `videoconvert`,
  so conversion, mapping and copying are skipped too. `videoconvert` now uses
  up to 4 threads. Capture keeps running for screenshots, loss detection and
  fallback; a screenshot taken in this mode can be up to about 80 ms old.
  Goal: less CPU/GPU competition with games such as Minecraft. Not yet measured.
- **ScreenCast restore tokens.** `SelectSources` requests `persist_mode=2`.
  The token the portal returns is saved per source (`portalRestoreToken`,
  validated and capped in WorkspaceStore) and sent back on the next startup
  reconnect, so the desktop thumbnail picker can be skipped when the grant is
  still valid. Tokens are single use; each new grant replaces the old one.
  Reselecting a source clears its token. The first add on KDE still needs
  both the text list (input identity) and the picker (capture grant).
- Python: 93 tests pass on Windows (1 Windows-only skip). Java on Windows:
  AppearancePolicy, LinuxDirectPresentation, Workspace (now including token
  round trip/rejection), UpdateDialog and FrameBridge (socket) pass;
  LinuxOverlayRepaintCheck now reports SKIP off Linux.

Linux checks to run next:

1. `./scripts/check-linux.sh` (now also runs WorkspaceCheck).
2. Add a KDE window source, restart the app: the picker should not reappear
   if KDE kept the grant (Plasma may show an "allow restore" option).
3. Minecraft, single visible opaque source in multitask mode: compare game
   FPS and MultiMini CPU with the previous build.

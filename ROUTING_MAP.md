# Shared platform routing

See ARCHITECTURE.md for the original Windows architecture and docs/LINUX.md for
Linux capability boundaries. This map describes source paths, not live-test proof.

| Concern | Owner | Verification |
| --- | --- | --- |
| Runtime selection | python/multimin_backend/__main__.py; platforms.py | test_linux_backend.py |
| Shared interface/models/rendering | app/src/main/java/app/multimin/ui; model | AppearancePolicyCheck; Windows UI checks |
| Java platform paths | backend/PlatformSupport.java | LinuxPlatformCheck |
| Python startup/command events | backend/PythonBridge.java | protocol smoke check |
| Windows capture/input | python/multimin_backend/service.py; windows_input.py; native | existing Python/native tests; Windows live checks |
| Linux dispatch/capture lifecycle | python/multimin_backend/linux_service.py | test_linux_backend.py |
| Wayland capture/file picker/portal requests | python/multimin_backend/linux_portal.py | controlled live capture and file-picker checks required |
| X11 window enumeration/directed input | python/multimin_backend/linux_x11.py | map tests; live client checks required |
| Linux overlay event delivery | backend/LinuxOverlayInput.java; ui/MainWindow.java | live X11 checks required |
| Latest-frame memory transport | python/multimin_backend/frame_stream.py; backend/WindowFrameReader.java | check-frame-bridge.py; test_frame_stream.py |
| Linux composition screenshots | MainWindow.linuxComposition; ScreenshotSelection | LinuxPlatformCheck covers storage only; live region test required |
| Screenshot locking/retention | ui/ScreenshotStore.java; Python snapshots.py | test_snapshots.py; LinuxPlatformCheck |
| Settings and workspaces | AppPreferences; WorkspaceStore | LinuxPlatformCheck covers settings; existing WorkspaceCheck |
| Linux package | packaging/linux; scripts/stage-linux.py; build-linux.sh; package-linux.sh | local Flatpak build/import/protocol checks |
| Windows package | scripts/build.ps1; package-windows.ps1 | Windows checks required |
| Shared version | VERSION | both packagers default to this file |
| CI | .github/workflows/build-platforms.yml | source checks on both OS; Linux package; no publishing |

Data flow:

`MainWindow → PythonBridge → selected OS service → latest-frame stream → WindowFrameReader → shared PreviewPanel`

Linux Wayland capture:

`user picker → ScreenCast session → authorized PipeWire FD → GStreamer appsink → FrameStream`

Linux X11 input:

`focused MultiMini overlay → LinuxOverlayInput → linux_input command → selected X11 window (XSendEvent)`

Configure does not enter that input route. Java owns composition layer order.
Native Linux input may temporarily fit/focus the selected real window behind the
overlay, and restores it on exit/switch. KDE can manage native Wayland windows;
other Wayland compositors remain capture-only. GlobalShortcuts requires a
separate desktop-owned binding session.

Controlled Linux verification: `scripts/check-linux.sh`,
`scripts/check-linux-x11.py`, `scripts/check-linux-portal.py`,
`app/src/test/java/app/multimin/ui/LinuxPlatformCheck.java` and
`LinuxUiStateCheck.java`. The portal probe is interactive and was not run after
the user chose to test sharing later.

## Native Linux input and game capture

- `linux_native_input.py`: scoped real X11 focus/fit, overlay input holes, cleanup.
- `linux_kwin.py`: optional KDE UUID-targeted window metadata/placement/focus and restoration; one-shot scripts.
- `linux_hotkeys.py`: passive X11 grabs and repeat filtering; native Wayland uses portal bindings.
- `Source.inputReferenceWidth/Height` and `PreviewPanel`: stable composition bounds during native fitting.
- `X11Capture`: reacquires Composite backing pixmaps per frame, including DRI3/Present swaps.
- Controlled probes: `check-linux-native-input.py`, `check-linux-renderers.py --native-input`,
  `check-linux-kwin.py`, `check-linux-wayland-control.py`, `check-linux-kde-routing.py`.

Configure never enters native placement. Right Ctrl/desktop input action changes
input only; Pause changes composition order only. No graphics hooks are added.

- `native/LinuxWindow.cpp` + `backend/LinuxWindowIdentity.java`: own-overlay
  JAWT identity/tag bridge. Linux builder compiles/bundles it; the Java bridge
  supplies the exact XID to NativeInput with PID validation.

### Linux multitask presentation regressions

`PreviewPanel.needsTransparentOverlay()` selects opaque window/image-only
presentation in `MainWindow`; transparent views compose into a complete
device-resolution raster. `LinuxOverlayRepaintCheck` checks alpha/old-pixel
cleanup; `LinuxOverlayPaintFixture` with `scripts/check-linux-overlay-rendering.py`
samples only an owned changing-color popup for blank frames.

### Linux native single-source presentation

`PreviewPanel.linuxDirectCandidate` permits only a single visible opaque window
matching the selected input source. `MainWindow.updateLinuxDirectPresentation`
opens a visual bounding-shape hole after native input succeeds; reset closes it
before target restoration/permissions/exit. `printAll` includes captured frames
for screenshots. Background capture remains active. `LinuxDirectPresentationCheck`,
`LinuxDirectPresentationFixture` and `scripts/check-linux-direct-presentation.py`
cover eligibility, screenshots and a controlled owned renderer. Never generalize
this visual hole to competing layers; input switching must not reorder visuals.

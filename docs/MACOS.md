# macOS port (untested preview)

Written and packaged **without access to a Mac**. Nothing here has run on real
hardware. Treat every feature as unverified until someone reports results.

## How it works

- Same Java Swing UI, settings, workspaces, themes, screenshots and gallery as
  Windows and Linux. `PlatformSupport.isPortable()` routes macOS through the
  same Java paths as Linux (Java-side composition, overlay input forwarding).
- Python backend: `mac_service.py` (protocol, same commands as `linux_service.py`),
  `mac_capture.py` (ScreenCaptureKit `SCScreenshotManager` on macOS 14+, falling
  back to `CGWindowListCreateImage`/`CGDisplayCreateImage`), `mac_input.py`
  (directed input with `CGEventPostToPid`).
- Data: `~/Library/Application Support/MultiMini/` (workspace, settings) and
  `.../MultiMini/screenshots`.
- Input is "directed": while multitasking, the MultiMini overlay keeps focus and
  forwards your clicks and keys to the selected window's app. Hotkeys (End,
  Right Ctrl, Pause, Insert) work while the overlay is focused; there are no
  system-wide hotkeys yet. Display sources are view-only.

## Permissions (asked on first use)

- **Screen & System Audio Recording**: needed for any capture and for window titles.
- **Accessibility**: needed to forward input to the selected app.
- Ad-hoc signed builds change signature every version, so macOS may ask again
  after updating.

## Why there's a Gatekeeper warning, and how to remove it

Windows shows SmartScreen for unsigned apps; macOS Gatekeeper is stricter. The
only way to open with no warning is a **Developer ID** signature plus Apple
**notarization**, which needs an Apple Developer Program membership
(US$99/year). Without it, `package-mac.sh` signs ad-hoc (required on Apple
Silicon, and it prevents the "damaged" error) and users click "Open Anyway"
once (see `MACOS_FIRST_LAUNCH.txt`, which ships inside the .dmg).

To switch to warning-free builds later, add these repository secrets; the CI
job already uses them when present:

| Secret | Value |
|---|---|
| `MACOS_CERT_P12` | Base64 of the exported "Developer ID Application" certificate (.p12) |
| `MACOS_CERT_PASSWORD` | Password of that .p12 |
| `MACOS_SIGN_IDENTITY` | e.g. `Developer ID Application: Your Name (TEAMID)` |
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` | For `notarytool` (app-specific password) |

## Build

On a Mac with a JDK 25 (Temurin) on PATH:

```bash
./scripts/package-mac.sh
```

Output: `dist/MultiMini-<VERSION>-macOS-arm64.dmg` and `.sha256`. CI builds the
same file on `macos-15` (Apple Silicon). Intel Macs are not built yet.

## Known gaps / first things to test

1. App opens after "Open Anyway"; Settings shows **Mac Build**.
2. Add a Window source: list shows titles after granting Screen Recording.
3. Live preview updates; check FPS (one screenshot call per frame, so expect
   lower rates than Windows).
4. Multitask mode: clicks/keys reach the selected app after granting Accessibility.
5. Insert screenshot, gallery, workspace save/restore, theme switching.
6. Retina coordinates (clicks landing in the right place).

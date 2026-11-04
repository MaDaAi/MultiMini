# MultiMini

MultiMini is a desktop multitasking utility for arranging display and application-window sources as layers, then viewing them together in a configurable overlay. It is built with Java/Swing and a Python helper for Windows capture and input routing.

## Current release status

- **Display Capture:** captures the desktop into a source layer.
- **Window Capture:** lists visible windows and captures the selected window. Windows Graphics Capture (WGC) uses in-memory BGRA frame transfer; a BMP fallback remains available.
- **Multitask mode:** overlays the composition and forwards input to the selected capture source using the configured hotkeys.
- **Low-overhead window presentation:** eligible single-monitor window-only multitask layouts use Windows DWM thumbnails; unsupported or mixed layouts fall back to the regular preview pipeline.
- **Images:** local image files can be added as sources.
- **Temporary snapshots:** stored locally and cleaned up after seven days.
- **Game Capture:** not included in the release source menu. Experimental DX11 development code is retained under `native/` and is not needed to build or run the release.
- **Platform support:** Windows is the supported platform. Linux capture and input support are not implemented.

MultiMini does not record video or stream. Capture is performed locally. WGC and input forwarding depend on Windows permissions and the target application's behavior.

## Install on Windows

Download `MultiMini-Setup-0.02-Windows-x64.exe` from GitHub Releases and run it. The installer bundles Java, Python, and the capture libraries. Users do not need to install those separately. Launch MultiMini from the Start menu or desktop shortcut. New views begin with no sources; use Add to choose your captures.

The installer is currently unsigned. A clean Windows machine test is still recommended before public distribution. See [installer build instructions](docs/WINDOWS_INSTALLER.md).

## Run the standalone JAR

Install a Java 26 or newer runtime and Python 3.10 or newer. Download `MultiMini-Beta-0.01-Windows.jar` from the project's GitHub Releases page and start it with:

```powershell
java --enable-native-access=ALL-UNNAMED -Xmx2g -XX:G1HeapRegionSize=32m -jar .\MultiMini-Beta-0.01-Windows.jar
```

Python dependencies for WGC and snapshots are listed in `python/requirements.txt`. Install them with `py -3 -m pip install -r python/requirements.txt` if those optional features are unavailable. Windows may request screen-capture permission.

## Build from source

Requirements: JDK 26+, Python 3.10+, and (for WGC/snapshot extras) the packages in `python/requirements.txt`.

```powershell
.\scripts\build.ps1
.\scripts\run.ps1
```

The regular JAR is `build/MultiMini.jar`. Build the experimental version with `scripts/build.ps1 -DevelopmentGameCapture`; its JAR is `build/MultiMini-Experimental.jar`, and double-clicking it enables the development source menu. `scripts/run.ps1 -DevelopmentGameCapture` builds and starts that same version. Older builds are kept under `build/archive/`, and compiler/test outputs under `build/internal/`. Builds no longer generate extra update versions when a JAR is open: close that app before rebuilding it.

Native C++/MSVC and the Windows SDK are not required to run the app. The optional native helper DLL is bundled in the release build when available; the app falls back to its regular capture path if it cannot load. Native development instructions are in [native/README.md](native/README.md). See [performance notes](docs/PERFORMANCE.md) for measured results and limits.

## Settings and data

Preferences and workspaces are stored in `%APPDATA%\MultiMin`, outside the installation, and survive updates. Snapshot cleanup removes snapshots older than seven days when the app runs its cleanup routine. MultiMini does not intentionally upload captured content. Optional update checks contact GitHub after user confirmation. Read the [Privacy Notice](PRIVACY.md) and [User Agreement](TERMS.md) for details.

## Contributing and licensing

Contributions and bug reports are welcome. The application is licensed under the [MIT License](LICENSE); third-party libraries retain their own terms. See [Terms of Service](TERMS.md) for use conditions. OBS terminology and workflow may be familiar, but MultiMini's implementation is independent and does not include OBS source code.

Workspace saving, optional GitHub updates and the agreement flow are described in [release review notes](docs/WORKSPACES_UPDATES_LEGAL.md).

This beta keeps the repository private and uses manual updates. In-app update checks are disabled. Run a newer installer over the existing installation to keep local workspace data.

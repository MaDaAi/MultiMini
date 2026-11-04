# MultiMini

MultiMini is a desktop multitasking utility for arranging display and application-window sources as layers, then viewing them together in a configurable overlay. It is built with Java/Swing and a Python helper for Windows capture and input routing.

## Beta 0.02 features

- **Display Capture:** captures the desktop into a source layer.
- **Window Capture:** lists visible windows and captures the selected window. Windows Graphics Capture (WGC) uses in-memory BGRA frame transfer; a BMP fallback remains available.
- **Multitask mode:** overlays the composition and forwards input to the selected capture source using the configured hotkeys.
- **Low-overhead window presentation:** eligible single-monitor window-only multitask layouts use Windows DWM thumbnails; unsupported or mixed layouts fall back to the regular preview pipeline.
- **Images:** local image files can be added as sources.
- **Temporary snapshots:** stored locally; snapshots older than seven days are removed when cleanup runs.
- **Game Capture:** not included in the release source menu. Experimental DX11 development code is retained under `native/` and is not needed to build or run the release.
- **Platform support:** Windows is the supported platform. Linux capture and input support are not implemented.

MultiMini does not record video or stream. Capture is performed locally. WGC and input forwarding depend on Windows permissions and the target application's behavior.

## Install on Windows

Download `MultiMini-Setup-0.02-Windows-x64.exe` from [the Beta 0.02 release](https://github.com/MaDaAi/MultiMini/releases/tag/v0.02-beta) and run it. This repository is private: you must sign in to a GitHub account with access to download releases or open support issues. The installer bundles Java, Python, and the capture libraries. Users do not need to install those separately. Launch MultiMini from the Start menu or desktop shortcut. New views begin with no sources; use Add to choose your captures.

The installer is currently unsigned. A clean Windows machine test is still recommended before public distribution. See [installer build instructions](docs/WINDOWS_INSTALLER.md).

## Using MultiMini

Add sources to a view, arrange them in the preview, then start multitasking. Default shortcuts are **Right Ctrl** to switch the input target, **Pause** to swap visual layers, and **End** to leave multitask mode. These shortcuts can be changed in Settings. Use each source's mouse icon to include it in input switching.

Views, source order, transforms, opacity and control selections are saved automatically and on normal exit. Images require the original files to remain available. Window sources reconnect only to a unique title-and-process match; reselect a target if it is missing or ambiguous.

## Updating

This private beta uses **manual updates**; in-app update checks are disabled. Download a newer installer through your authorized GitHub account, close MultiMini, and run the installer over the same installation. Workspaces and settings remain in your user-data folder. Back up that folder before an upgrade.

## Build from source

Requirements for developers: JDK 26+, Python 3.10+, and the packages in `python/requirements.txt` for WGC and snapshots. The installer already bundles these for end users.

```powershell
.\scripts\build.ps1
.\scripts\run.ps1
```

The regular JAR is `build/MultiMini.jar`. Build the experimental version with `scripts/build.ps1 -DevelopmentGameCapture`; its JAR is `build/MultiMini-Experimental.jar`, and double-clicking it enables the development source menu. `scripts/run.ps1 -DevelopmentGameCapture` builds and starts that same version. Older builds are kept under `build/archive/`, and compiler/test outputs under `build/internal/`. Builds no longer generate extra update versions when a JAR is open: close that app before rebuilding it.

Native C++/MSVC and the Windows SDK are not required to run the app. The optional native helper DLL is bundled in the release build when available; the app falls back to its regular capture path if it cannot load. Native development instructions are in [native/README.md](native/README.md). See [performance notes](docs/PERFORMANCE.md) for measured results and limits.

## Settings and data

Preferences and workspaces are stored in `%APPDATA%\MultiMin`, outside the installation, and survive updates. Workspace saves include a `.bak` recovery file. Uninstalling preserves this folder. Snapshot cleanup removes snapshots older than seven days when the app runs its cleanup routine. MultiMini does not intentionally upload captured content. This build does not perform update checks. Read the [Privacy Notice](PRIVACY.md) and [User Agreement](TERMS.md) for details.

## Contributing and licensing

Publisher: **Mansoor Alakoozai, Canada**. Collaborators with repository access can submit bug reports through [GitHub Issues](https://github.com/MaDaAi/MultiMini/issues). Do not post private captures or credentials. The application is licensed under the [MIT License](LICENSE); third-party libraries retain their own terms. See [Terms of Service](TERMS.md) for use conditions. OBS terminology and workflow may be familiar, but MultiMini's implementation is independent and does not include OBS source code.

Workspace saving, optional GitHub updates and the agreement flow are described in [release review notes](docs/WORKSPACES_UPDATES_LEGAL.md).


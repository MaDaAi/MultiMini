# MultiMini v1.0 — Windows only

First public Windows x64 release. Linux and macOS support are coming soon and are not available in this release.

## Install
Download **MultiMini-Setup-1.0-Windows-x64.exe** and run it. Java, Python and native capture libraries are bundled; no development tools are required. The installer is currently unsigned.

## Features
- Layered window, display, image and WGC game-window capture.
- Input switching, visual layer swapping and configurable multitask shortcuts.
- Full-view and rectangular screenshots, Gallery, Copy, Keep and retention timers.
- Dark/light themes, accent colors, view management, alignment snapping and persistent sidebar widths.
- Saved workspaces and settings across restarts and updates.

## Updates
The installed app checks GitHub for newer regular releases at launch. Choose Update to download, verify, install and relaunch automatically. Checksums are handled in the background; users download only the installer for initial setup. Updates preserve your saved data.

For the next update test, install this v1.0 EXE, then publish a regular release such as v1.0.1 with a correspondingly versioned installer and matching checksum asset. A JAR launched directly does not support installer-based updates.

## Validation and known limitations
Native/Java builds and available automated checks are run before publication. Full in-place update/relaunch still needs validation with the next higher public release. Capture performance depends on hardware and the target app. Game-window capture uses WGC compatibility, not universal graphics hooks; the controlled DX11 renderer is testing only. Protected/exclusive fullscreen apps may be unsupported. Secondary-monitor display input remains a reported issue.

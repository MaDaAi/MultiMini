# MultiMini Beta 0.02 — Windows x64

Download **MultiMini-Setup-0.02-Windows-x64.exe** and the matching **.exe.sha256** checksum file below. Java, Python and capture libraries are bundled; no separate runtime installation is needed.

## Included
- Window Capture, Display Capture, image sources and layered multitasking.
- Native DWM presentation for eligible single-monitor window-only layouts, with capture fallback.
- Saved views, source order, transforms, opacity and control selections. New workspaces start without sources.
- Image file references and safe window reconnection by unique title/process; missing or ambiguous targets require reselection.
- Versioned first-run user agreement and privacy notice, with publisher Mansoor Alakoozai, Canada.
- Manual updates that preserve the user-data folder. Public in-app update checks are disabled for this private beta.
- Experimental Game Capture remains disabled in the release build.

## Installation and upgrades
Run the EXE, then launch MultiMini from the Start menu or desktop shortcut. For upgrades, close the app and install into the existing location. Workspace and settings files stay in `%APPDATA%\\MultiMin`; back them up before upgrading.

Defaults: Right Ctrl switches input, Pause swaps visual layers, and End exits multitask mode. Change shortcuts in Settings.

## Beta limitations and verification
The installer is unsigned, so Windows may display a publisher warning. A clean Windows installation test remains outstanding. Native presentation is limited to eligible layouts; no fixed FPS or latency is guaranteed. Legal documents are drafts for professional review.

Both builds compile. All 34 existing Python capture/input tests pass, as do workspace roundtrip/backup recovery tests and simulated updater discovery, checksum and rejection checks. An end-to-end live update installation is unverified.

This repository and release remain private; downloads and support issues require repository access.

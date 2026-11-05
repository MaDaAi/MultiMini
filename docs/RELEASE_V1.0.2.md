# MultiMini v1.0.2 — Windows only

## Plain English
- The update window is smaller and cleaner, with the installed version and new version shown together.
- Switch between a short, everyday-language changelog and the full technical notes.
- Choose Update now to download and restart, or Remind me later to be asked on the next launch.
- The download window follows your theme and accent color.

## Detailed
- Replaced the generic update confirmation with a resizable themed dialog, compact version row, and understated changelog tabs.
- Update discovery transfers release notes as UTF-8/base64 and displays them as inert text.
- Separate Plain English and Detailed release-note sections are supported, with fallback display for older notes.
- Download preparation is pinned to the version shown in the update prompt, preventing a different release from being silently selected if releases change during confirmation.
- Retains automatic checksum verification, workspace saving, installation and relaunch.

## Installation and limits
Windows x64 only. Linux and macOS support are coming soon. Download only the installer for first setup. Existing installations can use their launch update prompt or Settings to update. Saved data is preserved. The installer is currently unsigned. Older installed versions show their existing update prompt once; the redesigned dialog becomes available after this update is installed.

Build, updater safety checks, dialog rendering checks and capture regressions were run before publication. Actual update/relaunch on the affected user's PC remains to be confirmed.

# MultiMini Beta 0.05 Experimental — Windows x64

Includes bundled Java, Python and native capture components. Install over the existing installation to preserve views, sources, settings and saved screenshots.

## Changes
- Eligible multi-monitor window/game-window compositions can use native DWM presentation.
- Auto WGC capture follows source updates without the extra frame-publication cap; explicit limits remain available.
- Reduced redundant frame notifications, shared-memory remapping and unchanged native placement updates.
- Compatibility capture timing accounts for capture work rather than adding a full extra frame delay.
- Insert saves the multitask view. Ctrl+Shift+F12 opens a rectangular screenshot selector with Copy, Save, Copy and Save, and Cancel. Shortcuts are configurable in Settings and only active in multitask mode.
- Screenshot selection uses an accent overlay and takes priority over the multitask display.
- Backend cleanup handles input-stream closure and monitors Java termination to restore windows and release input hooks.
- Release packaging copies Python source modules only; local caches and user data are excluded.

## Validation and limits
Java/native compilation and 43 Python regression tests passed during development. Cross-PC latency, live region selection and force-termination recovery have not been fully verified. These improvements do not guarantee native FPS or zero latency on every system. Secondary-monitor display input remains a reported issue.

Game capture remains experimental: WGC game-window compatibility is not a universal graphics API hook. The repository and downloads remain private; authorized GitHub access is required. Previous prereleases remain available for rollback.

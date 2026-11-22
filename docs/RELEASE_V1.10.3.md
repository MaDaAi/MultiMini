## Plain English
- Mac: hotkeys and typing now work. MacBooks have no End, Pause or Insert keys, so the Mac version has its own defaults: Control+Option+E exits, Right Option switches input, Control+Option+L swaps layers, Control+Option+S takes a screenshot. You can change them in Settings → Controls.
- Mac: windows on your other desktops (the ones you swipe between) now show up in the window list.
- Mac: sources are no longer zoomed in on Retina screens, and the full-screen view stays clear of the menu bar and notch.
- Mac needs Accessibility (and Input Monitoring) permission for keys: System Settings → Privacy & Security, turn on MultiMini, then restart it.
- Windows and Linux: no changes.

## Detailed
- macOS: new system-wide key listener (Quartz event tap) recognises MultiMini's hotkeys in any app and swallows them, and forwards other keys to the selected app while MultiMini is in front. In-app key handling stays as a fallback.
- macOS: the hotkey recorder uses stable key names (it used to record macOS symbols that the backend couldn't read), records Command and Right Option, and shows Mac names (Control, Option, Command). Saved Windows defaults are mapped to the Mac defaults.
- macOS: window list uses all Spaces instead of only the current one; ScreenCaptureKit captures windows on other Spaces.
- macOS: source sizing compares captured pixels with display pixels (points × Retina scale), fixing the 2× zoom. The multitask overlay is placed below the menu bar.

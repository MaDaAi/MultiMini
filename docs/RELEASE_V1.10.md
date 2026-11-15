## Plain English
- MultiMini now comes for three systems: Windows, Linux (beta) and Mac (untested preview).
- Settings → General shows which one you're on: "Windows Build", "Linux Build" or "Mac Build".
- Windows works the same as 1.0.7; this release is mostly about bringing MultiMini to Linux and Mac.
- Linux: game and window capture should no longer turn black, many games no longer ask you to pick the window twice on KDE, and capture uses less CPU.
- Mac: a first preview made without access to a Mac. It may not work yet; please report what happens on GitHub Issues.

## Detailed
- Shared source for all three systems; one version (1.10) and one release with a file per system. Each in-app updater only looks at its own file.
- Windows: same capture, input routing and updater as 1.0.7. The build script now picks the newest installed JDK.
- Linux (Flatpak, beta):
  - Captured frames are always opaque (GL/Vulkan games often leave alpha at zero, which showed as black).
  - KDE: windows running through XWayland (Minecraft and most Java/Steam games) are captured straight from X11, so the desktop sharing picker no longer appears for them. Native Wayland windows still need the picker once; the grant is remembered for later sessions.
  - Faster X11 capture using shared memory (MIT-SHM) instead of decoding frames in Python.
  - Lower background capture rate while a single window is shown natively; up to 4 threads for colour conversion.
  - The app can now check GitHub for updates (network permission added) and opens the release page.
- Mac (untested preview, Apple Silicon): ScreenCaptureKit window/display capture, input forwarded to the selected app, macOS data folders, ad-hoc signed .dmg. First launch needs "Open Anyway" in Privacy & Security, plus Screen Recording and Accessibility permission.

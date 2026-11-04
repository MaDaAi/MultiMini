# Experimental game-window compatibility

Run `scripts/run.ps1 -DevelopmentGameCapture`, then Add → Game capture (experimental) → Game window — WGC compatibility. Select the game from detected application windows. The list intentionally includes all titled windows: there is no universal reliable way to identify every game by executable name. Separate windows retain their HWND identity.

This backend uses Windows Graphics Capture. It supports application windows independently of whether their renderer uses DirectX or OpenGL, subject to Windows and application capture restrictions. It uses in-memory BGRA transfer, latest-frame storage, existing Java accelerated composition, and the configured capture FPS limit. It has no BMP/desktop fallback. It is not graphics API interception or GPU texture sharing.

The source supports the existing transforms, opacity, visibility, ordering, duplicate/remove lifecycle, target reconfiguration, and window input mapping. Its input toggle defaults on. Relative/raw-input camera control in games is not verified and may need dedicated handling; ordinary window input support does not guarantee gameplay input compatibility.

The separate Controlled DX11 test renderer option retains the native development backend. Neither mode bypasses protected processes or anti-cheat. Minimized windows, exclusive fullscreen, and protected content may not capture. Borderless or windowed mode is the best initial test configuration.

## Validation on November 3, 2026

Minecraft Java 26.3 was detected by title and HWND. A ten-second WGC receiver check obtained 299 frames at 1920×1020 over 10.06 seconds with 96 distinct sampled buffers and no error events. Its live image appeared in MultiMini with backend explicitly labeled WGC game-window compatibility. Multitask mode opened and End returned to Configure. The game counter showed roughly 30 FPS in the background and over 100 FPS when focused; these are observations, not measurements of capture overhead. Mouse gameplay and keyboard forwarding through the overlay remain unverified because the automation target becomes hidden during multitask mode.

All 28 existing Python regression tests passed. No claim of universal game compatibility or stable 144 FPS capture is made. Next steps are relative mouse gameplay testing and controlled frame-time/CPU measurements with one MultiMini instance, followed by native OpenGL/DXGI adapters if needed.

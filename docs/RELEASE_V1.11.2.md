## Plain English
- "Check for updates" no longer fails with the message "null" on Linux and macOS. If GitHub can't be reached, or is limiting update checks, the app now says so in plain words. On Flatpak it also offers to update through Flatpak instead.
- Linux window capture is much smoother. In configure mode, the preview of a live window now keeps up with the window instead of stuttering at about 10–20 fps. Windows running through XWayland (most games through Proton/Wine) are captured at close to the full refresh rate.
- Linux on KDE and other Wayland desktops: MultiMini now asks for your screen's full refresh rate. Before, it accepted the desktop's default of 60 fps, so on a 144 Hz screen it showed fewer than half the frames.
- Linux: if you remove a native Wayland window and add the same app again, MultiMini reuses the permission you already gave. The KDE "Share" prompt no longer comes back for it. The prompt still appears the first time for each new native Wayland window. It is a desktop security check, and apps can't skip it.
- The Linux app starts with much less memory reserved and returns unused memory to the system while idle.

## Detailed
- Updates: the update check used a regular expression on long release notes, and on large release notes the Java regex engine overflowed its stack. The resulting error had no message, which the dialog showed as "null". The notes are now read with a simple scanner. Network errors without a message are described by their cause: timeout, connection refused or DNS failure. An HTTP 403/429 is reported as rate limiting. `MultiMini --check-updates` prints the result for diagnosis, and CI runs it inside the Flatpak sandbox.
- PipeWire capture: the stream now offers descending `max-framerate` ranges (360 down to 60 fps), so the compositor fixes the stream at its fastest supported rate instead of 60/1. This is not yet tested on a real KDE 144 Hz session.
- X11/XWayland capture:
  - Frames are published at least one full period apart, and the next grab is paced from the publish time. Previously, grab-time jitter made the service's own limiter drop many frames.
  - In CI under XWayland, 1080p at a 144 fps target went from about 118 fps to about 140 fps, and 1440p from about 110 fps to about 137 fps. At a 60 fps target, 1080p went from about 55 fps to about 59 fps, and 1440p from about 47 fps to about 59 fps.
  - Windows that never report damage are now checked by comparing frames, so a still window gets its first frame. The map-state check and event drain run every 100 ms instead of every frame, and a redundant XSync per frame was removed.
- Linux preview painting:
  - Configure mode uses bilinear instead of bicubic scaling. Bicubic fell back to software under XRender and took 33–47 ms per frame.
  - The configure-mode background (shadow, frame, grid) is drawn once and reused.
  - Only the rows of a frame that changed are repainted.
  - The display mode is cached instead of queried from the X server on every paint.
  - In CI under Xvfb, the configure preview went from about 11–18 fps to about 144 fps at a 144 fps source. JVM CPU in configure mode roughly halved. Windows and macOS drawing is unchanged except for the partial repaint and display-mode cache, which also run on macOS.
- Linux JVM: the heap starts at 64 MB instead of 512 MB, the heap free ratio is lower, and G1 runs periodic collections so idle memory is returned. The global-shortcut reader wakes up 4 times a second instead of 20.
- All measurements came from GitHub's virtual machines with software rendering. Real-GPU and real-KDE results may differ.

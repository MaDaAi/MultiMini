## Plain English
- Linux: game capture with a single source no longer shows a black screen.
- Linux: game and window capture should feel much smoother and more responsive.
- Mac: the same smoother capture path.
- Windows: no changes, so Windows stays on 1.10.1.

## Detailed
- Linux: turned off the single-source "native presentation" shortcut by default. On KDE/XWayland the overlay hole showed black instead of the real window; single sources now draw captured frames like multi-source views.
- Linux/Mac: frames now travel from the capture backend to the app through shared memory (a private file in /dev/shm), as Windows already does, instead of sending about 8 MB per 1080p frame through a local socket.
- Linux/Mac: frames are opaque RGB images, which removes an extra pass per frame and draws faster on X11.
- Linux X11/XWayland (Minecraft and most games): capture now waits for the game to present a frame (X Damage events) and grabs it immediately, instead of polling on a timer. This is the same idea as Windows Graphics Capture's frame-arrived event. The frame-rate cap still applies.

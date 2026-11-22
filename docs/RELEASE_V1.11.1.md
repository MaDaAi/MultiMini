## Plain English
- Multitask mode on Windows is much faster and smoother. The first time you start it no longer lags; live window capture now keeps up with fast screens (up to 144 fps on a 144 Hz display instead of about 50) and uses far less CPU.
- Windows you capture stay their own size while you multitask and go back to where they were when you exit.
- Linux: the downloadable Flatpak installs again. Earlier Linux downloads could fail with a signing (GPG) error.

## Detailed
- Windows: the capture library (numpy/OpenCV) is now loaded when the backend starts. Loading it lazily could stall behind the backend's command reader, which delayed the first live frame by several seconds.
- Windows: the multitask overlay is opaque whenever nothing needs to show through it and paints as soon as it opens, which avoids a full-screen software copy on every frame. In our tests capture rendering went from about 50 to about 144 fps, and Java CPU went from about one full core to about 60%.
- Frames carry their age instead of a clock reading, so latency is measured on one clock. The JVM starts with a larger heap and shorter GC pauses. A failed native (DWM) presentation retries with backoff instead of staying off.
- A window that has not produced a frame yet is moved but never resized to a guessed size. Exit restores the original position and size; this restore silently failed on 64-bit Windows.
- A window capture that never delivers its first frame is restarted automatically.
- Linux: release bundles are signed with the MultiMini repository key, so `flatpak install` accepts them and installed copies keep updating in-app. CI now installs every bundle to catch this.

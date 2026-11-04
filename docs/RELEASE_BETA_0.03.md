# MultiMini Beta 0.03 Experimental — Windows x64

The Windows installer now packages the experimental JAR by default. The normal JAR is the safety fallback. The package includes WGC game-window compatibility and the controlled DX11 test renderer, along with the existing native DWM presentation path for eligible layouts. These are not universal game hooks, and no zero-latency or fixed-FPS guarantee is made.

- Publisher-supplied PNG logo embedded in the EXE, installer and shortcuts, with Windows icon sizes from 16 to 256 pixels.
- Dark Windows-style installer, branded welcome/header images and plain-text agreement content.
- Settings split into General, Controls and Performance with consistent accent controls and preserved Apply/OK/Cancel behavior.
- Private updates: download the newer EXE and matching .exe.sha256 through your authorized GitHub account, then use Settings → General → Updates → Install downloaded update. Checksums and version are verified before staged installation and restart. Saved workspaces remain outside the app directory. No embedded GitHub credentials.

For an existing 0.02 installation, install 0.03 into the same location once. Subsequent newer versions can be applied through the new Settings update action. Keep both downloaded files in the same folder and retain their original filenames.

Validation: both Windows icon resources contain the supplied branding; packaged DX11 initialization probe passes; Settings tabs and staged hotkey Apply/Cancel/OK behavior pass in an isolated profile; workspace recovery and 34 existing Python capture/input tests pass; local update staging rejects corrupt files, missing checksums and same/older versions. Game gameplay/raw-input compatibility, clean-machine installation, actual input-to-photon latency and a live in-place update/relaunch remain unverified. The installer is unsigned.

The repository stays private. Downloads require repository access. Existing Beta 0.02 remains available as a rollback release.

A packaged end-to-end controlled DX11 run delivered 99.2 FPS into the Java preview over roughly seven seconds, with 694 painted frames and seven sampled changing colors; the child renderer exited safely with the app. This measures the controlled test bridge, not commercial-game performance or input-to-photon latency.

Settings now persists custom input/layer/stop hotkeys and the exit-button position after Apply, so they survive restarting and updating.

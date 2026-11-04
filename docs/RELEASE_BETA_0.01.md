# MultiMini Beta 0.01 — Windows

Windows x64 beta release with native DWM window presentation for eligible single-monitor window-only multitask layouts, improved frame delivery, shared-memory transport, and sharper preview scaling.

Download `MultiMini-Beta-0.01-Windows.jar`.

## Requirements and launch

Install Java 17 or newer and Python 3.10 or newer. WGC dependencies are listed in `python/requirements.txt` in the source repository. Install them with `py -3 -m pip install -r python/requirements.txt` from the downloaded source checkout.

```powershell
java --enable-native-access=ALL-UNNAMED -Xmx2g -XX:G1HeapRegionSize=32m -jar .\MultiMini-Beta-0.01-Windows.jar
```

Use Window Capture with Automatic/WGC, FPS Auto, then enter multitask mode for eligible native presentation. Mixed layouts and unsupported configurations use the capture fallback. Native helper DLL is bundled; no C++ compiler is required to run it.

## Validation and limits

Both JAR builds compile. All 34 Python tests pass. Native shared-memory/socket integration and preview pixel-quality checks pass. The regular JAR's live integration activates DWM presentation and shuts down safely. A separate DWM pixel fixture failed its color assertion during this release pass; its result remains unresolved. Native presentation input latency and sustained output refresh have not been measured end to end.

Game Capture remains hidden in this release. Development code is preserved. This beta does not guarantee zero latency, a fixed capture FPS, protected-content capture, or compatibility with every application. Multiple-monitor native presentation is not enabled.

## Publishing checklist

- Test this exact JAR locally: window capture, display capture, click/drag/scroll/typing, Right Ctrl input switching, Pause layer switching, opacity, and End exit.
- On GitHub create a release from commit `866a46d` with a new tag such as `v0.01-beta`.
- Title: `MultiMini Beta 0.01 — Windows`.
- Attach the JAR and `SHA256SUMS.txt` from `build/release`.
- Mark as a prerelease and review the notes before publishing.

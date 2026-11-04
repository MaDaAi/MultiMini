# Native window presentation

Open `build/MultiMini.jar` (or the experimental build), add an ordinary Window Capture source, and enter multitask mode. The source's Configure dialog has a checked-by-default **Native window presentation** option. Game Capture remains separately gated to the experimental build. Hover the exit button to see whether native composition is active.

For eligible compositions, Windows DWM presents the source window directly in the overlay. No Python pixel capture, shared-memory frame copying, or Java image painting is needed for those window layers. Updates are paced by Windows composition and source application updates. This uses [DWM thumbnail relationships](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmregisterthumbnail) and [thumbnail properties](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ns-dwmapi-dwm_thumbnail_properties), including source cropping, destination geometry, visibility, and opacity. It is native window composition, not a DirectX/OpenGL hook.

## Eligibility and fallback

- Single-monitor Windows, release or experimental build, multitask mode.
- Auto/native-refresh FPS setting. A user cap below monitor refresh uses the capture renderer because DWM surface updates cannot be throttled per thumbnail.
- Rendered layers must be ordinary window or WGC-compatible game-window sources with valid HWNDs and Automatic/WGC methods. The full-size opaque primary-desktop background may remain directly presented below the overlay.
- Mixed rendered image/display/video layers, manual capture methods, cursor-in-capture, unavailable native APIs, or native registration/update failures use the existing capture renderer.
- Protected content, exclusive fullscreen, minimized/closed sources, and game-specific rendering restrictions are not guaranteed. The mode cannot bypass capture protections. It can be disabled per source in Configure.

Layer ordering, swapping, sizing, opacity, visibility and the canvas mask are mapped to DWM properties. Native handles and obsolete cached CPU surfaces are released on view changes, composition changes, mode exit and window disposal. The existing HWND-based input-routing code is reused; this pass did not independently measure gameplay mouse/keyboard input latency on the native presentation path.

## What was verified

Controlled desktop pixel checks passed for opaque and transparent destinations, live red-to-green source updates, 50% opacity, two overlapping layers, reordering, removal, and resource disposal. The actual MainWindow pipeline activated native composition with zero delivered CPU frames while the source kept animating. This verifies a working native presentation path; zero CPU frame counters do not mean zero visual updates.

In one roughly 11-second MainWindow sample, the Java consumer used 0.781 process CPU seconds and 24.6 MiB of Java heap. This includes startup and excludes source-process/Python CPU, native allocations and GPU use. The controlled source reported around 129–131 draw FPS under that test load on a 144 Hz display. Source/output 144 FPS and input-to-photon latency were not measured or guaranteed.

## Fallback-path improvements

The regular capture renderer now receives frame-ready notifications instead of waiting for the next capture poll. It coalesces pending work, requests the latest frame, keeps a bounded amount of work, and retains timed fallback for older helpers. A WIC native high-quality scaler replaces costly Java2D downscaling where available; original Java rendering remains available when the DLL/scaler is unavailable. The copy-deferral experiment was removed because it did not improve measured throughput.

Controlled moving-window tests found about 50–54 delivered FPS in the previous editor, around 67–74 with native scaling, and around 2–3 ms paint time in the fullscreen 1:1 capture-preview path. Sampled publication-to-paint age there was approximately 3.5–4.5 ms. These times begin after the pixel frame is published; they exclude earlier GPU readback, input processing, display scanout and panel response. Full capture delivery remained around 68–78 FPS under the test load, motivating the direct DWM path rather than claiming that polling changes achieved native FPS.

Tests and measurements can be repeated using the Java fixtures in `native/tests`, `scripts/benchmark-pipeline.py`, and `scripts/benchmark-wgc.py`. Benchmarks create only controlled windows and do not inject input or interact with games.

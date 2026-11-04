# Capture pacing and performance

The latest native window presentation path and full-pipeline measurements are documented in [native presentation](NATIVE_PRESENTATION.md). Eligible multitask window layers can use direct DWM presentation in both release and experimental builds. The regular renderer also has event-driven frame delivery and Windows-native high-quality scaling. Earlier transport-only timings below remain stage-specific benchmarks.

## Current builds and display improvements

`build/MultiMini.jar` is the regular release build; `build/MultiMini-Experimental.jar` additionally enables experimental Game Capture directly when opened. Native window presentation is enabled in both. Feature choices are packaged in each JAR. Previous builds are under `build/archive/`; compiler, diagnostic and test artifacts are under `build/internal/`. The build refuses to replace a locked JAR rather than generating update-name variants.

Frame presentation snaps to physical pixel boundaries. At 125% Windows DPI an odd-sized 601×399 window passes the native-size pixel test without changes. Bicubic filtering is used for reduced editor previews; multitask resizing uses the cheaper bilinear path, while native-size rendering uses exact pixel sampling. A physically smaller preview cannot retain every source pixel.

On the tested single-monitor Windows setup, Display Capture now attempts WGC monitor capture through the shared-memory bridge and disables Robot only after a valid native frame arrives. Missing dependencies or failure to apply capture exclusion retain Robot; a closed WGC session re-enables fallback. Multiple-monitor setups retain Robot pending monitor identity validation. Capture exclusion is applied through JNI from the owning Java process, avoiding a monitor feedback loop. The native exclusion integration test passes. An opaque bottom primary-desktop layer already presented directly in multitask mode no longer needs capture work.

A ten-second native-monitor receiver sample delivered 346 frames at 1920×1080 over 10.028 seconds and used 0.781 Python process CPU seconds (about 7.8% of one logical core). It had no error events. This includes neither Java rendering nor GPU usage and does not demonstrate sustained 144 FPS; desktop change rate affects frame delivery. Overall input latency and sustained full-app CPU/GPU use remain unmeasured.

## Capture quality at Windows 125% scaling

The preview previously left image interpolation at Java2D's default, causing nearest-neighbor aliasing when reducing captured windows. A half-size fine-detail fixture rendered through the actual PreviewPanel produced 50,400 aliased pixels in the sampled region in the previous build and zero after explicit filtered scaling. Native-size pixel preservation passed in both builds; shared-memory transport was not reducing resolution. Presentation now uses bilinear filtering when resized and exact nearest-neighbor sampling at native physical size.

HiDPI accelerated textures also used a 1.25x upload transform on a 125% display. The cache now bypasses that intermediate surface for HiDPI physical-pixel captures, avoiding an unnecessary resampling stage. This retains source resolution and adds no pre-scaled image buffer. A preview fitted into a smaller editor pane still has fewer displayed pixels than the source; its detail cannot equal a full-size native view.

The main `build/MultiMini.jar` includes the fix. `native/tests/FrameQualityCheck.java` verifies native pixel preservation and downscaled fine-detail filtering on the actual Windows graphics environment. All 31 Python tests and the JNI/socket integration checks passed after the change.

Auto targets the refresh rate of the monitor hosting the preview. Moving the application between monitors or changing the display refresh rate updates the target within approximately one second. An explicit lower FPS choice remains a cap. Display capture also respects its own monitor's refresh rate. Unknown monitor rates use 60 Hz. Auto accepts rates up to 1000 Hz; hardware and backend limits still apply.

The target, delivered source FPS, and preview paint FPS are separate measurements. A static File Explorer window may produce few new WGC frames even on a 144 Hz monitor; repeating a frame does not create additional captured motion. Source delivery FPS is measured over the actual elapsed interval. Frame age measures capture publication to Java decode; it does not measure full input-to-photon latency.

The frame transport stores only the latest frame for each source. Nagle buffering is disabled on both ends of the Java frame channel and on the controlled native DX11 sender. Java decodes BGRA in bulk into reusable image storage. Nanosecond scheduling avoids whole-millisecond refresh rounding; pending EDT work is coalesced. Display capture skips bursts of overdue capture work. Hidden/inactive window sources suspend WGC sessions; changing the FPS cap does not restart hidden captures. WGC limits excessive callback copying before building its pixel buffer.

The fallback CPU path still performs readback, memory transfer and image upload. It cannot guarantee native-refresh capture at every resolution with low resource use or zero latency. Java Robot display capture remains particularly expensive on fallback or multi-monitor configurations. Native DWM presentation avoids that pixel path for supported window-only layouts; other layouts retain the existing capture pipeline.

## Windows shared-memory transport

The optional `native/FrameBridge.cpp` JNI DLL is built by `scripts/build-native.ps1` and included in the JAR by `scripts/build.ps1` when present. It uses Windows page-file backed named shared memory, not a disk frame file. Python writes each requested frame into a connection-owned mapping; Java opens that mapping read-only and copies BGRA directly into its reusable ARGB image array. The same serialized connection cannot request the next frame until this copy completes, preventing producer overwrite during the copy. Mapping resize, reconnect, and shutdown release native handles. Reader close is serialized with reads to prevent native use-after-free.

Java requests the shared protocol only when the bundled DLL loads. Missing DLLs, load failures, mapping failures, and transport incompatibility retain or switch to the socket pixel path. `-Dmultimin.disableSharedFrames=true` forces that fallback. Direct JAR launch on recent JDKs should include `--enable-native-access=ALL-UNNAMED`; `scripts/run.ps1` includes this option. This is CPU shared memory, not GPU texture sharing or zero-copy capture.

With the same 1080p benchmark (30 warm-ups, 100 measured requests), the socket reader averaged **12.766 ms/frame**, and the shared-memory reader averaged **3.632 ms/frame**, about **71.5% less time** in the transport/decode stage. This is below the 6.94 ms budget for that stage at 144 Hz, but capture, scheduling and composition also consume time. It does not establish end-to-end 144 FPS or input latency. File Explorer was verified in the actual UI using the shared-memory backend. The native x64 build, Java build, 31 Python tests, and Java JNI integration tests passed; integration checks cover alpha/channel correctness, mapping resize, repeated open/close, unchanged-frame handling and forced socket fallback.

To rerun the native integration checks after building the DLL and JAR:

```powershell
& "$env:JAVA_HOME\bin\javac.exe" -cp build/MultiMin.jar -d build native/tests/FrameBridgeCheck.java
$env:PYTHONPATH = "$PWD\python"
py -3 scripts/check-frame-bridge.py build/MultiMin.jar
```

## Measurements, November 3, 2026

A localhost Java frame-reader benchmark requested 1920×1080 BGRA buffers with image reuse, 30 warm-up frames and 100 measured frames. It verified first/last pixel channel order and alpha. With the same updated Python server, the previous Java reader averaged 13.461 ms and the updated reader averaged 12.702 ms. This single run shows about a 5.6% improvement in transport plus decode; it does not isolate Nagle changes, quantify CPU/GPU use, or establish game/preview FPS. Other desktop applications were running, so these figures are not a controlled system benchmark.

The final Java build and 34 Python tests passed, including capture suspension and refresh rates above 240 Hz. Whole-application 144 Hz capture throughput and sustained CPU/GPU/memory use still require a controlled live benchmark. Use Settings → Performance with Auto and diagnostics enabled, one MultiMini instance, moving content, and compare target versus delivered FPS, paint time, frame age and skipped frames. Keep the capture target application unminimized. Lowering the cap reduces capture work when native-refresh throughput is unattainable.

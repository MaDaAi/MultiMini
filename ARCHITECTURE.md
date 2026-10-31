# MultiMin architecture

## Process model

```text
Java desktop process
├── Swing configuration window
├── translucent fullscreen overlay
├── view and layer state
├── global shortcuts and safe-corner detection
├── primary-display capture (in-memory Java Robot frames)
└── PythonBridge
       │ newline-delimited JSON on stdin/stdout
       ▼
Python capture process
├── Windows click-through and global-hotkey adapter (ctypes)
├── image and video adapters
└── temporary snapshot retention
```

The Python process is a child of Java. Commands use standard input/output; WGC pixel frames use a loopback-only TCP stream on an ephemeral port announced in the ready message. The stream retains only the newest frame per source and skips unchanged payloads. It is local IPC and has no remote listener. Shutdown closes the socket and releases WGC sessions.

## Capture backends

- Display capture: Java Robot feeds a BufferedImage directly to Swing.
- Windows Graphics Capture: windows-capture obtains a D3D11 capture frame, exposes a CPU BGRA buffer, and publishes the newest frame to FrameStream. WindowFrameReader reads that buffer from loopback and converts it to Java ARGB off the event thread. No frame is encoded, decoded, or written as BMP on this path.
- Window fallback: If WGC cannot start, the existing PrintWindow/screen method writes BMP to a temporary path. WindowFrameReader.readDisk retains compatibility.
- Third-party game capture: No game-present interception or shared D3D texture exists yet. Ordinary games remain unavailable.
- Controlled DirectX 11 test capture: `native/GameCapture` accepts an application-owned DX11 swap chain and sends its rendered back buffer to the separate, authenticated `GameIngest` receiver. It is enabled only when `native/build/multimin-dx11-test.exe --probe` succeeds. This code is present but its native binary has not been compiled on the current machine; third-party games remain unsupported. This is distinct from WGC and does not rename Window Capture.

The WindowFrameReader boundary separates source management/preview from transport details. A future native backend should deliver a frame handle and metadata there while preserving the fallback. Swing cannot consume a D3D shared texture directly; a GPU compositor or interop surface is required before GPU-to-GPU composition is possible.

### Phase 2 renderer milestone

`FrameTextureCache` now keeps a reusable Java2D `VolatileImage` for each visible source. On Windows, Java2D can back these surfaces with Direct3D; the preview uploads a changed CPU frame once and uses the accelerated surface for scaling, opacity, clipping, and layer ordering. If the device does not accelerate the surface, it draws the original `BufferedImage`. Removed sources and closed panels release their surfaces. The diagnostics display the number of accelerated source surfaces. `MULTIMIN_SOFTWARE_RENDERER=1` disables Direct3D if a driver is unreliable.

The WGC transport remains CPU BGRA. `WindowFrameReader` reuses its transfer byte buffer and alternates decoded ARGB images after the event thread swaps the visible frame. This removes one full-frame ARGB array and repeated transfer-buffer allocations. It does not remove WGC CPU readback, the BGRA-to-ARGB loop, or the CPU-to-GPU upload. Java Robot display capture still allocates frames. This is a GPU-capable composition step, not a zero-copy or GPU-native capture path.

The PowerShell launcher caps Java heap at 2 GB and uses 32 MB G1 regions. The native-resolution Robot frames then avoid G1 humongous allocation at the tested 1920×1080 resolution. Direct `java -jar` launches do not receive those options.

The next native milestone needs a C++/WinRT D3D11 module that obtains WGC `ID3D11Texture2D` frames, copies the newest complete frame into a shareable texture, and exposes an NT shared handle and fence/keyed-mutex metadata across process boundaries. A native rendering bridge must import that handle into a Direct3D compositor surface inside the Java window. The current `windows-capture` Python API exposes `frame_buffer` on the CPU and no shareable texture handle, so its existing callback cannot supply that path. A future DirectX 11 game-present backend must be a separate capture provider; WGC remains labeled WGC.

OBS Game Capture uses a graphics hook and shared texture path with memory fallback. Its [source](https://github.com/obsproject/obs-studio/blob/master/plugins/win-capture/game-capture.c) is GPL-2.0-or-later, while this project is MIT; directly integrating that code requires a deliberate license change and compatibility review. The next original-code milestone is a DX11-only native module with explicit opt-in game process selection, safe lifecycle, and shared texture handle transfer. Protected or anti-cheat restricted games must be left untouched.

## Modes

### Configuration

The native desktop window contains view tabs, a source stack, a composition preview, and selected-layer properties. Users add captures, arrange layers, set opacity, choose the input target, configure reveal behavior, and assign hotkeys.

### Multitask

A translucent fullscreen Java window presents the source layers over the real desktop. The display source is not drawn a second time in this mode, avoiding recursive capture; Java pauses display sampling until configuration returns. On Windows, the Python helper makes the overlay click-through and registers Right Ctrl to cycle input-enabled sources. Ctrl+Shift+Space restores configuration. If the helper is unavailable, the Java preview remains usable but global input passthrough is unavailable.

## Responsibility boundary

Java owns user interaction, native windows, screen sampling, theme state, and durable configuration. Python owns Windows input/overlay integration and temporary-snapshot cleanup. Captured pixels remain in Java memory and are never serialized through the JSON command channel.

## Privacy invariants

1. No recording pipeline exists in the default application.
2. Capture sessions require user and operating-system consent.
3. Captured frames stay local and in memory unless a snapshot is requested.
4. Snapshots live in an app-owned temporary directory and expire after seven days.
5. Network features require a separate, visible, opt-in design.
6. Logs contain source types and errors, never captured pixels or window contents.

## Milestones

1. Native Java application shell and persistent configuration.
2. Window frame capture and source-to-window binding.
3. Linux overlay window, global hotkeys, and click-through behavior.
4. Linux XDG Portal/PipeWire adapter.
5. Image/video sources, transforms, and drag reordering.
6. Input-target switching where the operating system safely supports it.
7. Package Java with `jpackage` and freeze Python with PyInstaller.
8. Evaluate game capture separately; do not promise universal support.

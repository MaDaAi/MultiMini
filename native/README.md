# MultiMin DirectX 11 test capture

This directory contains an original, opt-in DirectX 11 capture component and a controlled test application. `GameCapture` receives an `IDXGISwapChain` directly from the test application, copies its BGRA back buffer into a CPU-readable staging texture, and sends pixels through a token-checked loopback connection to the Python backend. The normal window and WGC capture code is not used for this source. The test renderer changes colour continuously so the preview can be checked for live updates.

This is **not** a third-party game hook. It captures only an application that calls `GameCapture::capture` itself. MultiMin keeps Game Capture unavailable until this executable exists and its `--probe` command successfully creates a hardware D3D11 device. Ordinary games remain unsupported; adding consent-based DXGI Present interception is a separate milestone. No OBS code was copied. OBS's [DXGI Present hook](https://github.com/obsproject/obs-studio/blob/master/plugins/win-capture/graphics-hook/dxgi-capture.cpp) was consulted for architecture only; its GPL code was not incorporated.

## Build requirements

Install Visual Studio Build Tools (2022 or 2026), including the **Desktop development with C++** workload, **MSVC x64/x86 build tools**, and a **Windows 10 or 11 SDK**. Open **x64 Developer PowerShell for Visual Studio** and run:

```powershell
cd C:\Users\manso\Documents\ChatGPT\MultiMini
powershell -ExecutionPolicy Bypass -File scripts\build-native.ps1
powershell -ExecutionPolicy Bypass -File scripts\run.ps1 -DevelopmentGameCapture
```

The build creates `native\build\multimin-dx11-test.exe`. Run `native\build\multimin-dx11-test.exe --probe`; exit code 0 means a hardware D3D11 device was created. In MultiMin, choose **Add → Game capture (DX11 test)**. A new test renderer window should open and its changing colour should appear in the preview. Drag or resize the source, change opacity, enter multitask mode, then exit with End. Close the test window to confirm MultiMin stays open and reports that it is waiting for a game. Remove the source to stop the renderer.

The current bridge performs GPU-to-CPU readback and synchronous socket transfer on the controlled renderer's thread. It is deliberately a functionality milestone, not a performance design for commercial games. The next implementation needs a nonblocking ring of shared textures and a separate, consent-based Present adapter for ordinary unprotected DX11 processes. Protected processes and anti-cheat bypasses are out of scope.

The probe prints the adapter, HRESULT, feature level, and PASS/FAIL. Because the executable uses the Windows GUI subsystem, PowerShell should wait for it explicitly:

```powershell
$probe = Start-Process .\native\build\multimin-dx11-test.exe -ArgumentList '--probe' -Wait -PassThru -RedirectStandardOutput probe.txt
Get-Content probe.txt
$probe.ExitCode
```

Exit 0 means hardware DX11 initialization succeeded; exit 1 means initialization failed or the device is below feature level 11.0. A probe does not test frame delivery. A missing MSVC runtime may prevent executable startup before the probe runs.

On November 3, 2026, the VS2026 x64 build and hardware probe passed on AMD Radeon(TM) Graphics (feature level 11.0). The development UI displayed continuously changing DX11 frames and source movement, resizing, opacity, and visibility were exercised. All 28 Python tests passed. A GUI diagnostic sample showed 63 unique capture FPS, but rates varied; this is not a sustained performance benchmark. Ordinary game interception and GPU texture sharing remain unimplemented.

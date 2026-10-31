# MultiMin DirectX 11 test capture

This directory contains an original, opt-in DirectX 11 capture component and a controlled test application. `GameCapture` receives an `IDXGISwapChain` directly from the test application, copies its BGRA back buffer into a CPU-readable staging texture, and sends pixels through a token-checked loopback connection to the Python backend. The normal window and WGC capture code is not used for this source. The test renderer changes colour continuously so the preview can be checked for live updates.

This is **not** a third-party game hook. It captures only an application that calls `GameCapture::capture` itself. MultiMin keeps Game Capture unavailable until this executable exists and its `--probe` command successfully creates a hardware D3D11 device. Ordinary games remain unsupported; adding consent-based DXGI Present interception is a separate milestone. No OBS code was copied. OBS's [DXGI Present hook](https://github.com/obsproject/obs-studio/blob/master/plugins/win-capture/graphics-hook/dxgi-capture.cpp) was consulted for architecture only; its GPL code was not incorporated.

## Build requirements

Install **Build Tools for Visual Studio 2022**, including the **Desktop development with C++** workload, **MSVC x64/x86 build tools**, and a **Windows 10 or 11 SDK**. Open **x64 Developer PowerShell for Visual Studio** and run:

```powershell
cd C:\Users\manso\Documents\ChatGPT\MultiMini
powershell -ExecutionPolicy Bypass -File scripts\build-native.ps1
powershell -ExecutionPolicy Bypass -File scripts\run.ps1
```

The build creates `native\build\multimin-dx11-test.exe`. Run `native\build\multimin-dx11-test.exe --probe`; exit code 0 means a hardware D3D11 device was created. In MultiMin, choose **Add → Game capture (DX11 test)**. A new test renderer window should open and its changing colour should appear in the preview. Drag or resize the source, change opacity, enter multitask mode, then exit with End. Close the test window to confirm MultiMin stays open and reports that it is waiting for a game. Remove the source to stop the renderer.

The current bridge performs GPU-to-CPU readback and synchronous socket transfer on the controlled renderer's thread. It is deliberately a functionality milestone, not a performance design for commercial games. The next implementation needs a nonblocking ring of shared textures and a separate, consent-based Present adapter for ordinary unprotected DX11 processes. Protected processes and anti-cheat bypasses are out of scope.

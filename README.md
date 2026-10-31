# MultiMin

MultiMin is a desktop multitasking utility for arranging display and application-window sources as layers, then viewing them together in a configurable overlay. It is built with Java/Swing and a Python helper for Windows capture and input routing.

## Current release status

- **Display Capture:** captures the desktop into a source layer.
- **Window Capture:** lists visible windows and captures the selected window. Windows Graphics Capture (WGC) uses in-memory BGRA frame transfer; a BMP fallback remains available.
- **Multitask mode:** overlays the composition and forwards input to the selected capture source using the configured hotkeys.
- **Images:** local image files can be added as sources.
- **Temporary snapshots:** stored locally and cleaned up after seven days.
- **Game Capture:** not included in the release source menu. Experimental DX11 development code is retained under `native/` and is not needed to build or run the release.
- **Platform support:** Windows is the supported platform. Linux capture and input support are not implemented.

MultiMin does not record video or stream. Capture is performed locally. WGC and input forwarding depend on Windows permissions and the target application's behavior.

## Run the release JAR

Install a Java 17 or newer runtime and Python 3.10 or newer. Download `MultiMin.jar` from the project's GitHub Releases page and start it with:

```powershell
java -Xmx2g -XX:G1HeapRegionSize=32m -jar .\MultiMin.jar
```

Python dependencies for WGC and snapshots are listed in `python/requirements.txt`. Install them with `py -3 -m pip install -r python/requirements.txt` if those optional features are unavailable. Windows may request screen-capture permission.

## Build from source

Requirements: JDK 17+, Python 3.10+, and (for WGC/snapshot extras) the packages in `python/requirements.txt`.

```powershell
.
scripts\build.ps1
scripts\run.ps1
```

The JAR is written to `build/MultiMin.jar`. Native C++/MSVC and the Windows SDK are not required for the release build. The experimental native DX11 test component has separate instructions in [native/README.md](native/README.md) and is not exposed in the release UI.

## Settings and data

Preferences are stored in the user's local Java preferences. Temporary snapshots are stored locally and automatically removed after seven days. MultiMin does not intentionally upload captured content. Do not capture content unless you have permission to access and display it.

## Contributing and licensing

Contributions and bug reports are welcome. The application is licensed under the [MIT License](LICENSE); third-party libraries retain their own terms. See [Terms of Service](TERMS.md) for use conditions. OBS terminology and workflow may be familiar, but MultiMin's implementation is independent and does not include OBS source code.

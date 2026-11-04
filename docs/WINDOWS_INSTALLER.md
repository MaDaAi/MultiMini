# Windows installer

Build on Windows x64 with a JDK containing jpackage, Python 3.14 x64 with pip, and Inno Setup 6.7.4 or compatible. The native helper DLL must already be built. These tools are needed only on the packaging machine.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/package-windows.ps1 -PythonExecutable 'C:\path\to\python.exe' -InnoCompiler 'C:\path\to\ISCC.exe'
```

Output: `dist/MultiMini-Setup-0.01-Windows-x64.exe` and its SHA256 checksum.

The installer includes a jpackage launcher and Java runtime, the official CPython 3.14.0 x64 embeddable runtime, the backend modules, and binary packages from python/requirements.txt. Embedded Python uses explicit private import paths. The app selects its packaged interpreter before searching for system Python. Third-party notices remain alongside their libraries and in the Java runtime legal directory.

The installer installs per user without requiring administrator privileges. It creates Start menu and optional desktop shortcuts and includes an uninstaller. It is unsigned; signing requires a publisher certificate.

Verified: embedded imports for WGC, NumPy, OpenCV, Pillow, MSS and platformdirs; backend ready handshake from an unrelated working folder; actual child interpreter path inside the app image; empty initial source model. A clean Windows VM without Java/Python and live capture/input testing of the installed EXE remain release validation steps.

For GitHub, create a prerelease titled MultiMini Beta 0.01 — Windows and upload the installer EXE and checksum from dist. Review the app on a clean machine before publishing it broadly.

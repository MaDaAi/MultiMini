# MultiMini v1.0.4 — Windows x64 portable

## Recommended download
Download `MultiMini-1.0.4-Windows-x64.zip`, extract the entire folder to a writable location, and open `MultiMini.exe`. Keep `app` and `runtime` beside the EXE. Java and Python are included; no separate runtime installation is needed. Do not run it from inside the ZIP.

The app shows its user agreement only if the current agreement has not already been accepted on this Windows account. Existing views, sources, settings and screenshots continue using their existing user-data folders.

## Plain English
- Added a self-contained portable Windows download.
- Added support for downloading and applying future ZIP updates.
- Kept support for installer updates and existing saved data.

## Detailed
- Bundles the native launcher, Java runtime, embedded Python, capture dependencies and application resources.
- ZIP updates verify SHA-256, validate archive paths and required runtime files, wait for app shutdown, replace packaged files, and restart the app.
- Failed file replacement attempts restore overwritten files from a recovery copy.

## Existing installer users — important
Versions through 1.0.3 only discover installer updates. They cannot automatically discover this ZIP. Either:
1. Close MultiMini, extract this portable release and run its EXE; existing user data is reused on the same Windows account; or
2. Install a transition installer containing version 1.0.4 or newer with ZIP-update support.

Do not remove installer updates entirely until that transition has been offered. Shortcuts for the old installation still open the old app when choosing a separate portable folder.

## Update assets
Upload the ZIP with the exact filename above and its matching `.zip.sha256` alongside it. The checksum is used by the app automatically; users only need to download the ZIP.

## Security and testing
Windows only. The application remains unsigned. A ZIP does not guarantee Smart App Control or SmartScreen will allow it. Portable packaging and updater tests have passed; this download still needs testing on a separate Windows PC, including capture and multitasking.

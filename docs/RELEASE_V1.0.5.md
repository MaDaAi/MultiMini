## Plain English
- Settings → Updates now says “Congrats, you are on the latest available version” when no newer release is available.
- This release lets you test the in-app update from the v1.0.4 portable version.

## Detailed
- Updated the manual update-check confirmation; startup checks remain silent when up to date.
- Bundled Java, Python, capture dependencies and the ZIP-aware updater in the Windows x64 portable package.
- Existing views, sources, settings, screenshots and agreement acceptance remain in their existing user-data folders.

## Download
Download **MultiMini-1.0.5-Windows-x64.zip**, extract the entire folder, then run **MultiMini.exe**. Keep `app` and `runtime` beside the EXE. No separate Java or Python installation is required.

**Already using v1.0.4 portable?** Open Settings → Updates and select **Update now**. The app downloads the update, closes, replaces the packaged files, and relaunches. The checksum asset is used automatically.

**Versions through v1.0.3:** their updater only supports installer assets; manually move to the portable release to receive future ZIP updates. A transition installer is not included in this release.

Windows x64 only. This application is unsigned; Windows security may warn about or block it. Automated ZIP updater tests passed; this release is intended for the first live portable-to-portable update test, not a claim that that test has already passed.

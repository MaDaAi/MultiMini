## Plain English
- Cleaner Settings navigation and easier-to-read Help pages.
- A shorter step-by-step guide to adding windows, switching input and taking screenshots.
- Help documents have their own bordered reading area; the footer matches Settings.
- The EULA and Terms are clearly labelled, while preserving the MIT software license. Updated terms require renewed acceptance.
- Normal app launches allow screen recording; use OBS Display Capture to record transitions between Configure and multitask mode.

## Detailed
- Added bounded connection counts to local frame servers and size limits to published frames.
- Disabled slow PowerShell download-progress rendering in the updater.
- Bundled the current UI, offline documentation, native bridge and Python backend with Java and Python runtimes.

## Download and update
Windows x64 only. Extract **MultiMini-1.0.7-Windows-x64.zip** and run **MultiMini.exe**, keeping `app` and `runtime` beside it. Existing portable users can use Settings > Updates. Saved data remains in the existing user-data folders. The checksum asset is handled automatically by the updater.

## Known limitations
Windows binaries are unsigned. OBS Window Capture attached to Configure cannot follow the separate multitask window; use Display Capture. Live OBS verification remains outstanding.
No universal game compatibility or latency guarantee is made.

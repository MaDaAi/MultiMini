# MultiMini 1.0.6

The approved UI refresh adds distinct source rows, cleaner controls, compact multitask actions, the supplied vector logo, and a GUI helpers setting (on by default). Settings includes offline Help and updated policies.

Preview resizing now writes native scaling output directly into the reusable image buffer, removing a redundant full-output pixel copy and scratch buffer. Existing latest-frame delivery, native DWM presentation and fallback capture paths remain. This is a reduction in preview work, not a claim of zero latency or universally higher FPS.

Removed unused agreement loading and logo imports/state. Capture and input controls remain available.

Download the Windows x64 ZIP, extract the entire folder, and run MultiMini.exe with app and runtime beside it. Saved views and preferences remain in the normal user-data folders. Updated policy documents require renewed local acceptance.

Validation: 43 Python tests; shared-memory and socket transport integration; native-size and downscaled pixel-quality checks; appearance/policy, Settings and GUI-helper checks. Performance and compatibility depend on the source, hardware and capture method. Windows binaries remain unsigned.

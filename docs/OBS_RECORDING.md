# Recording MultiMini with OBS

Normal launches, including double-clicking the experimental JAR, allow capture by default. No demo launcher is required.

Close the previous MultiMini instance before opening the rebuilt JAR: an already-running JVM retains its old capture-exclusion behavior.

In OBS, add Display Capture for the monitor running MultiMini. This records Configure, multitask content, and the exit control while it is visible. The exit control still auto-hides by design; hover its position to reveal it.

OBS Window Capture attached to Configure cannot follow multitask mode: Configure is hidden and a separate overlay window is shown. A frozen Configure image in that source does not mean the live app has frozen. Use Display Capture for a demonstration spanning both modes.

Prefer window/game-window sources inside MultiMini during recording. Capturing the same display that contains the app can create feedback. Normal recording mode uses the existing Java display fallback with its overlay-region mask where applicable; WGC monitor behavior still needs a live check.

Advanced: MULTIMINI_RECORDING_DEMO=0 opts into capture exclusion for feedback protection. This intentionally hides app windows from external capture and is not suitable for an OBS demo.

Verification: Java compilation and Python regression tests are automated checks. Live OBS recording and Windows Win+Shift+S before/after mode transitions require manual verification on the target PC.

# MultiMini Beta 0.02 — Windows

Download MultiMini-Setup-0.02-Windows-x64.exe and its .sha256 file. Java, Python and capture libraries are bundled.

- Saved views, sources, ordering, transforms, opacity and input selections survive restarts and updates. New workspaces remain empty of sources.
- Image sources retain their file paths. Window sources reconnect to unique title-and-process matches; missing or ambiguous windows require reconfiguration.
- Settings includes optional GitHub update checks. Newer packaged releases can be downloaded, verified and applied in place after saving and closing the app, with no manual reinstall wizard. Checks happen only when requested.
- Versioned first-run agreement and privacy notice identify Mansoor Alakoozai, Canada, with support through GitHub Issues. Declining exits before capture starts.

Tests passed: workspace roundtrip and backup recovery; simulated update discovery/download, version guards and rejected foreign sources/paths; 34 existing Python capture/input tests. End-to-end installation of a live published update and clean-machine GUI validation remain unverified. Legal documents are drafts for professional review and cannot guarantee immunity from claims. The installer is unsigned.

Publish as a prerelease with tag v0.02-beta. Upload the EXE and matching .sha256 asset; keep both filenames unchanged for updater compatibility. Increment the numeric version on subsequent releases.

Current distribution choice: private repository, manual updates. The in-app updater is disabled by the build flag; Settings provides manual update guidance. Public GitHub publication is not authorized.

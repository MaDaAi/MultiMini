# Workspace, updates and release review

Workspaces are saved every five seconds when changed and on normal exit, in `%APPDATA%\MultiMin\workspace.properties`, with a last-save `.bak`. Files preserve source/view order, transforms, visibility, image paths and the controlled source. Capture pixels, temporary paths and Windows handles are excluded. Corrupt primary files recover from backup; unreadable/unknown versions disable saving to protect the originals. Windows reconnect only to a unique title-and-process match; missing or ambiguous targets require reconfiguration. Images still need their original file. The app always starts in Configure mode.

Settings → Application → Check for updates contacts the public MaDaAi/MultiMini GitHub Releases API only after user confirmation. Published beta releases are eligible. The updater chooses a newer numeric Windows installer filename, e.g. `MultiMini-Setup-0.02-Windows-x64.exe`, and requires its matching `.sha256` asset. Increment the package version on every release; never replace an existing version with different code.

The app downloads and verifies before it exits, saves the workspace, then runs the installer silently into the existing installation and restarts. This is an in-place installer upgrade without a manual reinstall wizard. User data is outside the installation and the installer/uninstaller does not delete it. Failed download/checksum validation leaves the running app unchanged. Post-exit failures are logged in `%LOCALAPPDATA%\MultiMin\updates`. Installer rollback behavior and a real update/restart need end-to-end testing with a published newer release. Updates are unsigned; a SHA256 file on the same release verifies integrity but does not protect a compromised GitHub account. Protect repository access and consider publisher signing before broad distribution.

The versioned agreement prompt appears before backend startup. Declining does not start capture. Acceptance is recorded locally, and a changed agreement version prompts again. Settings includes readable agreement/privacy documents. The MIT license and third-party license notices remain included.

Publisher: Mansoor Alakoozai, Canada. Support: GitHub Issues, as requested by the publisher. These terms are a project-specific draft, not an opinion that the app is legally compliant or immune from claims. A Canadian lawyer should review the final distribution model, province-specific requirements, consumer protections and any future collection of support data. Do not add tracking or new upload purposes without updating implementation, notice and consent.

Research consulted November 4, 2026:
- Office of the Privacy Commissioner of Canada, meaningful consent: https://www.priv.gc.ca/en/privacy-topics/privacy-for-businesses/appropriate-handling-of-personal-information/collecting-personal-information-and-consent/consent/gl_omc_201805/
- Ontario consumer agreements guidance (province-specific example, not an assumed governing-law clause): https://www.ontario.ca/document/business-guide-consumer-protection/general-information-about-agreements
- GitHub Releases API and public release assets: https://docs.github.com/en/rest/releases/releases

Before public release, test an installed old version → published new version on a clean Windows VM; confirm saved workspace/settings, decline/accept consent, input routing and capture, updater interruption and relaunch. No live update installation or legal acceptance was automated in this development pass.

The updater uses anonymous public release downloads. A private source repository's releases are not publicly accessible: GitHub returns 404 without authorization. For the requested single-repository setup, public updates require making MaDaAi/MultiMini public; no credential is embedded in the app. Repository visibility changes require the publisher's explicit choice.

Publisher decision: keep the repository private and use manual updates for now. Release builds set publicUpdates=false, so the application does not make update API requests. Settings shows manual update instructions. The updater implementation remains available for future public downloads; enabling it requires deliberately changing the build flag and publishing accessible release assets.

Beta 0.03 adds private downloaded updates: the user downloads the EXE and matching checksum through an authorized browser session and selects it in Settings. No app-side GitHub login or token storage is used. The staging helper verifies version, filename and SHA256 before applying the in-place installer. This provides a private update path without changing repository visibility. End-to-end update/relaunch still requires testing with a newer installed release.

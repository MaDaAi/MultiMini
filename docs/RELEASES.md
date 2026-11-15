# Releases and updates across Windows, Linux and macOS

Status: plan plus the parts already in source. Nothing here publishes anything.
Publication still needs an explicit request from the publisher.

## One branch, one version, one release per version

- `main` is the only long-lived branch. Windows, Linux and (later) macOS build
  from the **same commit**. Do not keep per-OS branches: they force someone to
  copy every UI, settings or model change by hand, and they drift.
- `dev` is the working branch. Larger features can use short-lived branches
  (for example `feature/…`) merged into `dev`; `dev` is merged into `main` for
  each release. A platform port is a feature, not a fork.
- `VERSION` is the single app version. `scripts/package-windows.ps1`,
  `scripts/stage-linux.py`/`package-linux.sh` and a future macOS packager all
  read it. Bump it once per release.
- OS differences live behind platform seams, not branches:
  - Java: `backend/PlatformSupport` (paths, OS/desktop detection) and
    `isLinux()`-style gates around OS-only UI paths.
  - Python: `multimin_backend/platforms.py` selects `service.py` (Windows),
    `linux_service.py` (Linux), and later a `macos_service.py`.
  - Native: `native/FrameBridge.cpp` (Windows JNI), `native/LinuxWindow.cpp`
    (Linux JAWT); a macOS bridge would be a third file.

A shared change to the Java UI, themes, settings, models or platform-neutral
Python reaches every OS through the shared source. It still needs a check on
each OS, because shared source does not prove platform behaviour.

## One GitHub release per version, one asset set per OS

Tag `vX.Y.Z` on `main` and attach every platform's files to that **one** release:

| Platform | Asset | How users receive it |
|---|---|---|
| Windows | `MultiMini-X.Y.Z-Windows-x64.zip` + `.zip.sha256` | In-app updater (GithubUpdater → updater.ps1) |
| Linux | `MultiMini-X.Y.Z-Linux-x86_64.flatpak` + `.flatpak.sha256` | Flatpak (bundle now; a Flatpak repository later) |
| macOS (untested preview) | `MultiMini-X.Y.Z-macOS-arm64.dmg` + `.sha256` | In-app notice opens the release page; user replaces the app |

All platforms share the same release notes (`## Plain English` / `## Detailed`),
so users get the same notes whatever OS they run.

This is safe today because each updater matches only its own assets:

- `updater.ps1` accepts only `^MultiMini-(?:Setup-)?X.Y.Z-Windows-x64\.(exe|zip)$`
  with a matching `.sha256`, and skips drafts and prereleases. Linux and macOS
  files on the same release are ignored.
- The Linux app never runs the PowerShell updater; `GithubUpdater` shows Flatpak
  guidance on Linux instead.
- Linux and macOS (`GithubUpdater.portableCheck`) read `releases/latest` and match only `-Linux-x86_64.flatpak` or `-macOS-*.dmg`; they announce and open the page, never install.
- Windows without a native build machine can use `scripts/package-windows-from-baseline.ps1`, which updates a verified earlier release ZIP (runtime, Python, DLL) with the new JAR and backend sources.

A platform that is not ready for a version simply omits its asset. Its users
stay on the previous version until a later release ships their file.

## Linux update path

Since 1.10 the Flatpak has network access so Settings → Updates (and the
startup check) can read the same GitHub release as Windows and show its notes;
it then opens the release page instead of installing anything. Flatpak itself
still performs the install:

1. **Now (trial):** install the `.flatpak` bundle from the release. Upgrading
   means installing the newer bundle.
2. **Recommended next step:** publish a signed Flatpak repository (an OSTree
   repo built by `flatpak build-export` + `build-update-repo`, hosted on GitHub
   Pages) plus a `.flatpakref`. After that, `flatpak update` and software
   centres (Discover, GNOME Software) find new versions automatically, and
   the GitHub release keeps the bundle for manual installs.
3. **Optional later:** Flathub submission, which replaces step 2's hosting.

Keep `packaging/linux/io.github.MaDaAi.MultiMini.metainfo.xml` `<releases>` in
step with `VERSION`; software centres show those entries as the changelog.

## Release checklist (same commit for every platform)

1. Bump `VERSION` and the metainfo `<release>` entry; write release notes.
2. CI (`.github/workflows/build-platforms.yml`) passes on Windows and Linux.
   It only builds and uploads CI artifacts; it never publishes.
3. Windows: `scripts/build.ps1`, Python tests, Java checks, updater tests
   (`test-updater.ps1`, `test-portable-updater.ps1`), native build, then
   `package-windows.ps1 -PortableOnly` and `hash-portable.ps1`.
4. Linux: `scripts/check-linux.sh`, `scripts/package-linux.sh`, install the
   bundle, run the live checks in docs/LINUX_VERIFICATION.md.
5. Create one release for the tag and upload every platform's asset with its
   `.sha256`. Never overwrite assets that are already published.

The historical `.github/workflows/release-1.0.6.yml` publishes on its own
trigger; leave it alone. Any future release workflow should be
`workflow_dispatch`-only and create a **draft** release (drafts are invisible
to the Windows updater) until the publisher promotes it.

## In-app updates on Linux and macOS (since 1.10.1)

Settings → Updates (and the startup check) behave the same on every OS: read
the latest GitHub release, show its notes, and install on confirmation.

- **Linux (Flatpak):** `GithubUpdater.flatpakInstall` → backend `flatpak_update`
  → `linux_update.FlatpakUpdater` asks the Flatpak portal
  (`org.freedesktop.portal.Flatpak` UpdateMonitor) to update this app from its
  remote, then `flatpak_restart` spawns the newest version and the app exits.
  No host commands and no extra sandbox permissions. The remote is the signed
  OSTree repo at https://madaai.github.io/MultiMini/repo/, published by
  `.github/workflows/publish-linux-repo.yml` whenever a release is published
  (signing key: repository secret `FLATPAK_GPG_PRIVATE_KEY`, public key
  `packaging/linux/multimini-repo.gpg`, fingerprint
  48EE2A545E12328FF86A7E1E2559C6CB1D3E27F2). Bundles from 1.10.1 on also
  point at that repo. Copies installed from the 1.10 (or older) bundle need
  one reinstall: `flatpak install --user --reinstall https://madaai.github.io/MultiMini/multimini.flatpakref`.
  Software centres and `flatpak update` see the same updates.
- **macOS:** `MacUpdater` downloads the release `.dmg` and `.sha256` from this
  repository only, verifies SHA-256, saves the workspace, quits, and a helper
  script mounts the image, checks the app signature, swaps the app with a
  rollback backup and relaunches. Works when MultiMini.app sits in a folder the
  user can write (normally /Applications).

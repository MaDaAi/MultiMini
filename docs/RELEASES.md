# Releases and updates across Windows, Linux and macOS

Status: plan plus the parts already in source. Nothing here publishes anything.
Publication still needs an explicit request from the publisher.

## One branch, one version, one release per version

- `main` is the only long-lived branch. Windows, Linux and (later) macOS build
  from the **same commit**. Do not keep per-OS branches: they force someone to
  copy every UI, settings or model change by hand, and they drift.
- Feature work uses short-lived branches (`codex/…`, `claude/…`) merged into
  `main` after review. A platform port is one of those branches, not a fork.
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
| macOS (future) | `MultiMini-X.Y.Z-macOS-<arch>.dmg` + `.sha256` | Future macOS updater or manual download |

All platforms share the same release notes (`## Plain English` / `## Detailed`),
so users get the same notes whatever OS they run.

This is safe today because each updater matches only its own assets:

- `updater.ps1` accepts only `^MultiMini-(?:Setup-)?X.Y.Z-Windows-x64\.(exe|zip)$`
  with a matching `.sha256`, and skips drafts and prereleases. Linux and macOS
  files on the same release are ignored.
- The Linux app never runs the PowerShell updater; `GithubUpdater` shows Flatpak
  guidance on Linux instead.
- A future macOS updater must likewise match only `-macOS-` assets.

A platform that is not ready for a version simply omits its asset. Its users
stay on the previous version until a later release ships their file.

## Linux update path

The Flatpak deliberately has **no network permission**, so the app does not
check GitHub itself. Updates come from Flatpak:

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

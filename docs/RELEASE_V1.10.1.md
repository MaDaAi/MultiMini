## Plain English
- Linux and Mac can now update themselves from inside the app, just like Windows: Settings → Updates, confirm, and MultiMini restarts on the new version.
- Linux users on 1.10: reinstall once from the link below; after that you never need to download files again.
  `flatpak install --user --reinstall https://madaai.github.io/MultiMini/multimini.flatpakref`
- Windows works the same as 1.10.

## Detailed
- Linux (beta): MultiMini now has its own signed Flatpak repository on GitHub Pages. The app updates through the Flatpak portal (no extra permissions) and then relaunches the newest version. `flatpak update` and software centres (Discover, GNOME Software) also see updates. Release bundles point at the same repository.
- Mac (untested preview): the app downloads the new .dmg from this GitHub release, checks its SHA-256 checksum and code signature, saves your workspace, replaces itself with a rollback copy kept until the new version is in place, and reopens.
- Windows: unchanged apart from the version number.

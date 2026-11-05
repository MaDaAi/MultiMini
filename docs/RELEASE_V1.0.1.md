# MultiMini v1.0.1 — Windows only

Fixes taskbar relaunch metadata for installed copies. The app now resolves the installed MultiMini.exe directly, uses its embedded icon, and registers that EXE as the relaunch command instead of falling back to Java. Relaunch properties are set before the window AppUserModelID, and branding is reapplied after the window opens.

Install v1.0 and reopen it to check for this update, then choose Update. The installer and checksum are downloaded and verified automatically; saved views, sources, settings and screenshots are preserved.

Windows can retain old pinned shortcut metadata. If a previously pinned Java shortcut still appears after updating, unpin it, open MultiMini using the installed Start menu shortcut, and pin that instance again. The fix still needs confirmation on the affected PC.

Windows x64 only. Linux and macOS support are coming soon. The installer is currently unsigned.

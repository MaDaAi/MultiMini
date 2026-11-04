# Private updates on another computer

Keep MaDaAi/MultiMini private. Sign in to an account that has repository access on the test computer. MultiMini does not contain your GitHub password or token and does not change repository visibility.

## First upgrade from 0.02
Download MultiMini-Setup-0.03-Windows-x64.exe and run it over the existing installation. Do not uninstall or delete the user-data folder. Version 0.02 does not have the downloaded-update picker. Version 0.03 is experimental, includes the game-window compatibility chooser, and retains the normal JAR as a separate safety fallback.

## Later upgrades from 0.03
1. Open GitHub Releases in your signed-in browser.
2. Download the newer MultiMini-Setup-VERSION-Windows-x64.exe and its matching .exe.sha256 file into the same folder. Keep the original filenames.
3. Open Settings → General → Updates → Install downloaded update in MultiMini.
4. Select the newer EXE. MultiMini rejects same/older versions and missing/mismatched checksums.
5. Confirm restart. Your workspace is saved; the update is staged outside the app directory, applied silently into the existing installation, and the app relaunches.

Your views and sources live at %APPDATA%\MultiMin. Back that folder up before updating. The installer does not remove it. Public update checks remain disabled; downloading is authorized through your own browser session. Fully automatic private GitHub downloads would require a separate user authentication flow; that is not implemented here.

The EXE is unsigned. SHA256 detects corruption but does not establish an independent publisher identity. Obtain both files only from your authorized release. End-to-end installed-version upgrade/relaunch still needs a clean test with a newer version; the local staging and rejection paths are tested without executing an installer.

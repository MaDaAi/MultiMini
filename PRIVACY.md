# MultiMini Privacy Policy

Version: November 6, 2026. Publisher and privacy responsibility: Mansoor Alakoozai, Ontario, Canada. Support: https://github.com/MaDaAi/MultiMini/issues.

## What stays on your device
Selected screen/window pixels and routed input are processed locally for the preview and multitask overlay. MultiMini does not intentionally upload captured pixels, typed text or workspace contents. No account, analytics or advertising SDK is included. Source windows may themselves connect to their own online services when you interact with them.

The app stores view names, source names, window titles, image file paths, monitor choices, transforms, opacity, ordering, control-source selections, keybinds and appearance settings. Workspaces are automatically saved and backed up in your Windows roaming user profile at %APPDATA%\MultiMin. Existing settings remain there for compatibility. The workspace does not store captured frames, keyboard input, or reusable Windows handles. Image sources reference your original image file; moving or deleting it prevents restoration. Your OS or backup provider may sync or back up the roaming profile. These files can reveal your activity, so protect your user account.

Temporary capture buffers/files may be created under the OS temporary directory. The app attempts to remove its session files on normal shutdown; crashes may leave files behind. Optional screenshots are written locally to `%LOCALAPPDATA%\MultiMin\screenshots` (or `~/.local/share/MultiMin/screenshots` when LOCALAPPDATA is unavailable). The Gallery lists these files and their remaining retention time. Choosing Keep stores a local sidecar marker and prevents automatic deletion until you delete the photo or turn Keep off. Turning Keep off starts a fresh seven-day timer. Unkept screenshots older than seven days are removed when the snapshot cleanup code runs while the app is running; this is not a guaranteed wall-clock deadline or secure erasure. Copies and OS backups are outside the app's control.

Agreement acceptance records the accepted document version locally. No acceptance record is sent to the publisher.

## Update checks and downloads
Installed Windows builds contact GitHub's public Releases API on launch and when you request an update check. GitHub and its download hosts receive your IP address, request time, User-Agent and ordinary network metadata under their own policies. MultiMini sends no capture content or workspace for this check. An update download is checked against the release's SHA256 checksum before execution. A checksum detects corruption; it does not independently authenticate the publisher or protect against a compromised release account. Update packages and status logs are staged under %LOCALAPPDATA%\MultiMin\updates. Launch checks run in the background. Downloads and installation require your confirmation; you may decline an update and install manually.

## Retention and choices
Saved workspaces and settings remain until you remove them. Uninstalling the program preserves them so an upgrade or reinstall does not erase your setup. To remove local settings/workspaces, close the app, back up anything needed, and delete %APPDATA%\MultiMin; fallback settings may also exist in ~/.multimin, ~/.config/multimin or a legacy multimin-settings.properties in a former launch folder. Update downloads/logs can be removed from %LOCALAPPDATA%\MultiMin\updates while no update is running. Remove snapshots from their configured local snapshot folder separately. This does not erase backups or third-party application data.

Support is through public GitHub Issues. Do not submit passwords, private captures, access tokens or other people's personal information. The publisher may receive information you voluntarily include there; GitHub also processes it under its privacy statement. A material change to these practices will be disclosed in a new notice and agreement prompt. This notice describes current implementation and is not a guarantee against bugs or unauthorized access to your computer.

## Cookies, forms and third-party content
The desktop app does not set browser cookies, embed websites or videos, or provide analytics, advertising, account registration, payment or support-submission forms. The Cookie and Local Storage Policy gives details. Names and settings entered in app controls stay in the local workspace/settings files. Agreement acceptance is not consent to marketing or unrelated processing. MultiMini does not ask for a name, email address or payment details to use the app.

## Clipboard and local transport
Copying a screenshot places it on the system clipboard. Other software, clipboard history or OS clipboard sync may access it; the app does not control those copies. Live frame transport uses local memory and a loopback connection between app components. Local processing does not protect captures from other software running with access to your Windows account. Avoid capturing secrets or other people's information without permission.

## Privacy requests and support data
The publisher is responsible for privacy questions about information received through support. Public GitHub support content is hosted and processed by GitHub, potentially outside Canada. Voluntarily shared issue details may be used to investigate the problem and respond; avoid unnecessary personal information. Public issues can persist in repository history, third-party copies and backups; no fixed support-record deletion period is promised here.

You can remove local app data using the instructions above. The publisher cannot remotely retrieve or erase files on your computer. Requests about access, correction or deletion of information you submitted to support must be considered under applicable law; GitHub also provides its own privacy controls. A confidential privacy contact has not yet been published. Do not put confidential requests in public issues. The publisher must establish a private contact and support-record retention process before soliciting sensitive support data.

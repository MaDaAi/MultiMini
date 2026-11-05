# MultiMini

A Windows desktop multitasking utility that combines windows, games, displays, and images into configurable layers. Arrange sources, adjust visibility, and switch which source receives your input.

# Why I decided to create this app

I was behind on lecture videos and I wanted to play minecraft in the background, but I needed to see what was happening on the video. I was in a single player world on minecraft and I know I am not going to be completely focused on minecraft, so I wanted to watch the lecture videos overlayed on my Minecraft and have the visibility turned down. Thats what this app specializing in. The whole purpose of this app is to help users multitask just like I did. This app has many features for multitasking. This app can solve multitasking issues and help make multitasking easier on a single monitor setup.

## Features

- Window, display, image, and WGC game-window capture.
- Layer positioning, resizing, opacity, visibility, and alignment snapping.
- Multiple views with rename, duplicate, and delete controls.
- Multitask overlay with input switching and configurable shortcuts.
- Full-view and rectangular screenshots, clipboard copying, and a local gallery.
- Dark and light themes, accent colors, saved workspaces, and remembered sidebar widths.

Capture stays local. MultiMini does not record video or stream. Game-window capture uses Windows Graphics Capture compatibility; the controlled DX11 renderer is for testing only. Universal game graphics hooks are not implemented, and protected or exclusive fullscreen applications may be unsupported.

## Install

Download the Windows x64 installer from [Releases](https://github.com/MaDaAi/MultiMini/releases) and run it. Java, Python, and capture dependencies are bundled. Launch through the installed Start menu or desktop shortcut.

**Windows only (64-bit).** Linux and macOS support are coming soon; those versions are not available yet. Releases and support are public. The Windows installer is currently unsigned.

## Use

Add sources to a view, arrange them in the preview, then start multitasking. Right-click a view tab to rename, duplicate, or delete it; at least one view remains. The mouse icon beside a source includes it in input switching. Hold Alt while dragging to bypass alignment snapping.

Default multitask shortcuts:

| Shortcut | Action |
| --- | --- |
| Right Ctrl | Switch input source |
| Pause | Swap visual layers |
| End | Exit multitask mode |
| Insert | Save the full multitask view |
| Ctrl+Shift+F12 | Select a screenshot region |

Change shortcuts and appearance in Settings. Screenshot shortcuts work only in multitask mode. Region capture offers Copy, Save, Copy and Save, and Cancel.

## Screenshots and saved data

Gallery provides previews, Copy, Keep, Delete, and Open file location. Unkept screenshots expire after seven days when cleanup runs; Keep prevents automatic deletion.

- Workspaces and preferences: `%APPDATA%\MultiMin`
- Screenshots: `%LOCALAPPDATA%\MultiMin\screenshots`

Views, sources, transforms, and settings are saved automatically. Keep original image files available and reselect window targets if they cannot reconnect uniquely. Updates and uninstalling preserve user data. Read the [Privacy Notice](PRIVACY.md) and [User Agreement](TERMS.md).

## Updates

Download only the installer to set up MultiMini. Installed Windows builds check for updates on launch. When a newer public release is available, choose Update: MultiMini downloads and verifies it automatically, saves your workspace, installs it, and relaunches. You do not need to download or handle checksum files. Saved views, sources, settings, and screenshots remain outside the installation folder and are preserved.

Only regular public releases are offered automatically. Offline checks fail quietly; you can check again through Settings or download the newer installer from Releases. GitHub credentials are not embedded in the app.

## Development

Developers need a JDK and Python with the dependencies in `python/requirements.txt`. See [Windows packaging](docs/WINDOWS_INSTALLER.md) and [native development](native/README.md) for toolchain requirements.

```powershell
.\scripts\run.ps1 -DevelopmentGameCapture
```

The regular build is available as a safety fallback. Capture performance depends on hardware, monitor configuration, source behavior, and Windows permissions; native FPS and zero latency are not guaranteed.

## Support and license

Publisher: Mansoor Alakoozai, Canada. Report problems through [GitHub Issues](https://github.com/MaDaAi/MultiMini/issues). Remove private captures, personal data, and credentials before sharing reports.

MultiMini uses the [MIT License](LICENSE). Third-party dependencies retain their own licenses. Inno Setup builds the Windows installer. MultiMini does not include OBS source code.

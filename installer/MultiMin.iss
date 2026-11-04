#define AppName "MultiMini"
#ifndef ReleaseVersion
  #define ReleaseVersion "0.02"
#endif
#ifndef AppImageDir
  #define AppImageDir "..\build\internal\package\image\MultiMini"
#endif
[Setup]
AppId={{9F5C3C23-13F1-4C6A-A355-7B361F649AF1}
AppName={#AppName}
AppVersion={#ReleaseVersion}
AppPublisher=MultiMini contributors
DefaultDirName={localappdata}\Programs\MultiMini
DefaultGroupName=MultiMini
OutputDir=..\dist
OutputBaseFilename=MultiMini-Setup-{#ReleaseVersion}-Windows-x64
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
WizardStyle=modern
UninstallDisplayIcon={app}\MultiMini.exe
LicenseFile=..\TERMS.md
DisableProgramGroupPage=yes
Compression=lzma2/fast
SolidCompression=yes
[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\MultiMini"; Filename: "{app}\MultiMini.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\MultiMini"; Filename: "{app}\MultiMini.exe"; WorkingDir: "{app}"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"
[Run]
Filename: "{app}\MultiMini.exe"; Description: "Launch MultiMini"; Flags: nowait postinstall skipifsilent

#define AppName "MultiMini"
#ifndef AppChannel
  #define AppChannel "Experimental"
#endif
#ifndef ReleaseVersion
  #define ReleaseVersion "0.03"
#endif
#ifndef AppImageDir
  #define AppImageDir "..\build\internal\package\image\MultiMini"
#endif
[Setup]
AppId={{9F5C3C23-13F1-4C6A-A355-7B361F649AF1}
AppName={#AppName}
AppVersion={#ReleaseVersion}
AppVerName=MultiMini {#ReleaseVersion} {#AppChannel}
AppPublisher=Mansoor Alakoozai
DefaultDirName={localappdata}\Programs\MultiMini
DefaultGroupName=MultiMini
OutputDir=..\dist
OutputBaseFilename=MultiMini-Setup-{#ReleaseVersion}-Windows-x64
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
WizardStyle=modern dark windows11 hidebevels includetitlebar
WizardSizePercent=110
SetupIconFile=assets\multimini.ico
WizardImageFile=assets\wizard.png
WizardSmallImageFile=assets\wizard-small.png
WizardImageStretch=yes
DisableWelcomePage=no
UninstallDisplayIcon={app}\MultiMini.exe
LicenseFile=assets\TERMS.txt
DisableProgramGroupPage=yes
Compression=lzma2/fast
SolidCompression=yes
[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\MultiMini"; Filename: "{app}\MultiMini.exe"; WorkingDir: "{app}"; AppUserModelID: "MaDaAi.MultiMini"; IconFilename: "{app}\MultiMini.exe"
Name: "{autodesktop}\MultiMini"; Filename: "{app}\MultiMini.exe"; WorkingDir: "{app}"; AppUserModelID: "MaDaAi.MultiMini"; IconFilename: "{app}\MultiMini.exe"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"
[Run]
Filename: "{app}\MultiMini.exe"; Description: "Launch MultiMini"; Flags: nowait postinstall skipifsilent
[Messages]
WelcomeLabel1=Set up MultiMini {#AppChannel}
WelcomeLabel2=This installs MultiMini with its own Java and Python runtimes.%n%nYour saved views and sources are kept when updating.

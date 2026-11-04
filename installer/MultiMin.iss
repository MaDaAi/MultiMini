#define AppName "MultiMini"
#define AppVersion "0.1.0"
#define AppPublisher "MultiMini contributors"
#define JarPath "..\build\MultiMini.jar"

[Setup]
AppId={{9F5C3C23-13F1-4C6A-A355-7B361F649AF1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\MultiMini
DefaultGroupName=MultiMini
OutputDir=..\dist
OutputBaseFilename=MultiMini-Setup-{#AppVersion}
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\MultiMini.jar
LicenseFile=..\LICENSE
DisableProgramGroupPage=yes

[Files]
Source: "{#JarPath}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\TERMS.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\MultiMini"; Filename: "{sys}\cmd.exe"; Parameters: "/c javaw -Xmx2g -XX:G1HeapRegionSize=32m -jar ""{app}\MultiMini.jar"""; WorkingDir: "{app}"
Name: "{autodesktop}\MultiMini"; Filename: "{sys}\cmd.exe"; Parameters: "/c javaw -Xmx2g -XX:G1HeapRegionSize=32m -jar ""{app}\MultiMini.jar"""; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Run]
Filename: "{sys}\cmd.exe"; Parameters: "/c java -version"; StatusMsg: "Checking for Java..."; Flags: runhidden; Check: not IsJavaPresent

[Code]
function IsJavaPresent: Boolean;
var
  ResultCode: Integer;
begin
  Result := Exec(ExpandConstant('{sys}\cmd.exe'), '/c java -version', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

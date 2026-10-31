#define AppName "MultiMin"
#define AppVersion "0.1.0"
#define AppPublisher "MultiMin contributors"
#define JarPath "..\build\MultiMin.jar"

[Setup]
AppId={{9F5C3C23-13F1-4C6A-A355-7B361F649AF1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\MultiMin
DefaultGroupName=MultiMin
OutputDir=..\dist
OutputBaseFilename=MultiMin-Setup-{#AppVersion}
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\MultiMin.jar
LicenseFile=..\LICENSE
DisableProgramGroupPage=yes

[Files]
Source: "{#JarPath}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\TERMS.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\MultiMin"; Filename: "{sys}\cmd.exe"; Parameters: "/c javaw -Xmx2g -XX:G1HeapRegionSize=32m -jar ""{app}\MultiMin.jar"""; WorkingDir: "{app}"
Name: "{autodesktop}\MultiMin"; Filename: "{sys}\cmd.exe"; Parameters: "/c javaw -Xmx2g -XX:G1HeapRegionSize=32m -jar ""{app}\MultiMin.jar"""; WorkingDir: "{app}"; Tasks: desktopicon

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

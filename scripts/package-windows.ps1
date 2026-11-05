param([string]$Version = '0.06', [string]$PythonExecutable = '', [string]$InnoCompiler = '', [switch]$SafetyBuild)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $ProjectRoot
$Jdk = $env:JAVA_HOME
if (-not $Jdk) { $Jdk = (Get-ChildItem "$env:ProgramFiles\Java" -Directory -Filter 'jdk-*' | Sort-Object Name -Descending | Select-Object -First 1).FullName }
if (-not (Test-Path "$Jdk\bin\jpackage.exe")) { throw 'A JDK with jpackage is required on the build machine.' }
if (-not $PythonExecutable) { $PythonExecutable = Get-ChildItem "$env:LOCALAPPDATA\Python\pythoncore-*\python.exe" | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName }
if (-not $PythonExecutable) { throw 'Pass -PythonExecutable with a Python 3.14 x64 interpreter containing pip.' }
& $PythonExecutable -c "import sys,struct; assert sys.version_info[:2] == (3,14) and struct.calcsize('P') == 8, 'Packaging needs Python 3.14 x64'"
if ($LASTEXITCODE -ne 0) { throw 'Unsupported build Python.' }
if (-not $InnoCompiler) { $InnoCompiler = Join-Path $ProjectRoot 'build\tools\inno\ISCC.exe' }
if (-not (Test-Path -LiteralPath $InnoCompiler)) { throw 'Pass -InnoCompiler with the path to ISCC.exe.' }
if (-not (Test-Path 'native\build\multimin-frame-bridge.dll')) { throw 'Build the native frame bridge with scripts/build-native.ps1 first.' }
$BuildOptions = @{ OutputName = 'internal\MultiMini-Package.jar' }
if (-not $SafetyBuild) { $BuildOptions.DevelopmentGameCapture = $true }
& "$PSScriptRoot\build.ps1" @BuildOptions
if ($LASTEXITCODE -ne 0) { throw 'App compilation failed.' }
$PackageRoot = Join-Path $ProjectRoot 'build\internal\package'
if (Test-Path -LiteralPath $PackageRoot) {
    $Resolved = (Resolve-Path -LiteralPath $PackageRoot).Path
    if ($Resolved -ne (Join-Path $ProjectRoot 'build\internal\package')) { throw 'Unexpected packaging directory.' }
    Remove-Item -LiteralPath $Resolved -Recurse -Force
}
$InputRoot = Join-Path $PackageRoot 'input'
$PythonRoot = Join-Path $InputRoot 'python'
$RuntimeRoot = Join-Path $PythonRoot 'runtime'
New-Item -ItemType Directory -Force $RuntimeRoot | Out-Null
$SelectedJar = 'build\internal\MultiMini-Package.jar'
Copy-Item -LiteralPath $SelectedJar -Destination (Join-Path $InputRoot 'MultiMini.jar')
if (-not $SafetyBuild -and (Test-Path 'native\build\multimin-dx11-test.exe')) {
    New-Item -ItemType Directory -Force (Join-Path $InputRoot 'native\build') | Out-Null
    Copy-Item 'native\build\multimin-dx11-test.exe' (Join-Path $InputRoot 'native\build')
}
Copy-Item README.md,LICENSE,TERMS.md,PRIVACY.md $InputRoot
# Package executable modules only; never copy caches, local data or credentials.
$BackendRoot = Join-Path $PythonRoot 'multimin_backend'
New-Item -ItemType Directory -Force $BackendRoot | Out-Null
Get-ChildItem -LiteralPath 'python\multimin_backend' -File -Filter '*.py' | Copy-Item -Destination $BackendRoot
$Archive = Join-Path $ProjectRoot 'build\tools\python-3.14.0-embed-amd64.zip'
if (-not (Test-Path -LiteralPath $Archive)) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $Archive) | Out-Null
    Invoke-WebRequest 'https://www.python.org/ftp/python/3.14.0/python-3.14.0-embed-amd64.zip' -OutFile $Archive
}
Expand-Archive -LiteralPath $Archive -DestinationPath $RuntimeRoot
# Embedded Python ignores user installations and PYTHONPATH. All import roots are explicit.
Set-Content -LiteralPath (Join-Path $RuntimeRoot 'python314._pth') -Encoding ascii -Value @('python314.zip','.', '..', '..\vendor')
& $PythonExecutable -m pip install --disable-pip-version-check --only-binary=:all: --target "$PythonRoot\vendor" -r python\requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Bundling Python dependencies failed.' }
& "$RuntimeRoot\python.exe" -I -c "import windows_capture,cv2,numpy,PIL,mss,platformdirs,multimin_backend; print('Bundled Python imports OK')"
if ($LASTEXITCODE -ne 0) { throw 'Embedded runtime dependency check failed.' }
$Channel = if ($SafetyBuild) { 'Safety' } else { 'Experimental' }
& "$RuntimeRoot\python.exe" (Join-Path $PSScriptRoot 'generate-branding.py') --channel $Channel
if ($LASTEXITCODE -ne 0) { throw 'Branding asset generation failed.' }
$ImageRoot = Join-Path $PackageRoot 'image'
& "$Jdk\bin\jpackage.exe" --type app-image --name MultiMini --app-version $Version --vendor 'Mansoor Alakoozai' --icon (Join-Path $ProjectRoot 'installer\assets\multimini.ico') --input $InputRoot --main-jar MultiMini.jar --dest $ImageRoot --add-modules java.desktop,java.management,jdk.management,jdk.unsupported --java-options '--enable-native-access=ALL-UNNAMED' --java-options '-Xmx2g' --java-options '-XX:G1HeapRegionSize=32m'
if ($LASTEXITCODE -ne 0) { throw 'Native launcher packaging failed.' }
$AppImage = Join-Path $ImageRoot 'MultiMini'
$OutputRoot = Join-Path $ProjectRoot $(if ($SafetyBuild) { 'dist\safety' } else { 'dist' })
& $InnoCompiler "/O$OutputRoot" "/DAppImageDir=$AppImage" "/DReleaseVersion=$Version" "/DAppChannel=$Channel" installer\MultiMin.iss
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
$Installer = Join-Path $OutputRoot "MultiMini-Setup-$Version-Windows-x64.exe"
$Hash = (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$Installer.sha256" -Encoding ascii -Value "$Hash  $([IO.Path]::GetFileName($Installer))"
Write-Output $Installer

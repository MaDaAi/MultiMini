param(
    [string]$BaselineZip = '',       # A verified published MultiMini-<old>-Windows-x64.zip
    [string]$Version = ''
)
# Builds the portable Windows ZIP by updating a verified earlier release.
# The baseline supplies the bundled Java runtime, embedded Python, Python
# dependencies and the native frame bridge DLL; this script replaces the JAR,
# the Python backend sources, the legal/help documents and the version.
# Use this when no MSVC/Python 3.14 build machine is available; a full
# package-windows.ps1 build remains the reference path.
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $ProjectRoot
if (-not $Version) { $Version = (Get-Content -LiteralPath 'VERSION' -Raw).Trim() }
if (-not (Test-Path -LiteralPath $BaselineZip)) { throw 'Pass -BaselineZip with a published MultiMini-<version>-Windows-x64.zip' }
$sidecar = $BaselineZip + '.sha256'
if (-not (Test-Path -LiteralPath $sidecar)) { throw 'The baseline .sha256 file must sit beside the ZIP.' }
$expected = ((Get-Content -LiteralPath $sidecar -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
if ((Get-FileHash -LiteralPath $BaselineZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Baseline checksum mismatch' }

$work = Join-Path $ProjectRoot 'build\internal\windows-baseline'
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null
Expand-Archive -LiteralPath $BaselineZip -DestinationPath (Join-Path $work 'extracted')
$launcher = Get-ChildItem (Join-Path $work 'extracted') -Recurse -Filter MultiMini.exe | Select-Object -First 1
if (-not $launcher) { throw 'Baseline has no MultiMini.exe' }
$baseline = $launcher.Directory.FullName

# Reuse the baseline's verified native bridge so the new JAR bundles it too.
New-Item -ItemType Directory -Force (Join-Path $work 'native-extract'), 'native\build' | Out-Null
$jarTool = (Get-Command jar -ErrorAction SilentlyContinue).Source
if (-not $jarTool) {
    $jdkHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { (Get-ChildItem "$env:ProgramFiles\Java" -Directory -Filter 'jdk-*' | Sort-Object { [int](($_.Name -replace '^jdk-','') -split '\.')[0] } -Descending | Select-Object -First 1).FullName }
    $jarTool = Join-Path $jdkHome 'bin\jar.exe'
}
Push-Location (Join-Path $work 'native-extract')
& $jarTool xf (Join-Path $baseline 'app\MultiMini.jar') native/multimin-frame-bridge.dll
Pop-Location
$dll = Join-Path $work 'native-extract\native\multimin-frame-bridge.dll'
if (-not (Test-Path -LiteralPath $dll)) { throw 'Baseline JAR has no frame bridge DLL' }
$hadDll = Test-Path 'native\build\multimin-frame-bridge.dll'
if (-not $hadDll) { Copy-Item -LiteralPath $dll 'native\build\multimin-frame-bridge.dll' }
try {
    & "$PSScriptRoot\build.ps1" -OutputName 'internal\MultiMini-Release.jar' -DevelopmentGameCapture | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
} finally {
    if (-not $hadDll) { Remove-Item -LiteralPath 'native\build\multimin-frame-bridge.dll' -Force }
}

$name = "MultiMini-$Version-Windows-x64"
$output = Join-Path $work $name
Copy-Item -LiteralPath $baseline -Destination $output -Recurse
Copy-Item 'build\internal\MultiMini-Release.jar' (Join-Path $output 'app\MultiMini.jar') -Force
Copy-Item README.md,LICENSE,TERMS.md,EULA.md,PRIVACY.md,COOKIE_POLICY.md,REFUND_POLICY.md,HELP.md,THIRD_PARTY_NOTICES.md (Join-Path $output 'app') -Force
$backend = Join-Path $output 'app\python\multimin_backend'
if (-not (Test-Path -LiteralPath $backend)) { throw 'Baseline layout changed: app\python\multimin_backend missing' }
Get-ChildItem $backend -Filter '*.py' | Remove-Item -Force
Get-ChildItem 'python\multimin_backend' -File -Filter '*.py' | Copy-Item -Destination $backend
$cfg = Join-Path $output 'app\MultiMini.cfg'
$text = Get-Content -LiteralPath $cfg -Raw
$updated = [regex]::Replace($text, 'jpackage\.app-version=[0-9.]+', "jpackage.app-version=$Version")
if ($updated -eq $text -and $text -notmatch "jpackage\.app-version=$([regex]::Escape($Version))") { throw 'Could not set version in MultiMini.cfg' }
# Startup heap and short GC pauses: the first full-screen multitask paint used to grow
# the heap and trigger collections mid-frame. Older baselines lack these options.
foreach ($option in @('-Xms512m', '-XX:MaxGCPauseMillis=8')) {
    if ($updated -notmatch [regex]::Escape("java-options=$option")) { $updated = $updated -replace '(java-options=-Xmx2g)', "java-options=$option`r`n`$1" }
}
Set-Content -LiteralPath $cfg -Value $updated -Encoding ascii -NoNewline

# Smoke-test the embedded Python with the new backend sources.
$python = Join-Path $output 'app\python\runtime\python.exe'
& $python -I -c "import multimin_backend.platforms as p, multimin_backend.service, multimin_backend.snapshots; assert p.service_class().__name__=='CaptureService'; print('Embedded backend OK')"
if ($LASTEXITCODE -ne 0) { throw 'Embedded backend import failed' }

New-Item -ItemType Directory -Force 'dist' | Out-Null
$zip = Join-Path $ProjectRoot "dist\$name.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path $output -DestinationPath $zip
& "$PSScriptRoot\hash-portable.ps1" -Archive $zip | Out-Null
Write-Output $zip

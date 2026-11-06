$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\main\resources\app\multimin\updater.ps1'
$root=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) ('MultiMin\updates\test-'+[guid]::NewGuid())
$image=Join-Path $root 'source\MultiMini';$install=Join-Path $root 'installed'
New-Item -ItemType Directory -Force $image,$install | Out-Null
foreach($file in @('MultiMini.exe','app\MultiMini.jar','runtime\bin\java.dll','app\python\runtime\python.exe')){$path=Join-Path $image $file;New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null;Set-Content -LiteralPath $path -Value 'new test file'}
Set-Content -LiteralPath (Join-Path $install 'MultiMini.exe') -Value 'old'
Set-Content -LiteralPath (Join-Path $install 'unins000.exe') -Value 'keep uninstall'
$zip=Join-Path $root 'MultiMini-1.0.4-Windows-x64.zip'
Compress-Archive -Path $image -DestinationPath $zip
$hash=(Get-FileHash -LiteralPath $zip).Hash
function Start-Process {param($FilePath,$WorkingDirectory,$WindowStyle) $global:zipRestart=$FilePath}
& $script -Apply -Prepared $zip -ExpectedHash $hash -AppPid 2147483647 -InstallDir $install
if($LASTEXITCODE -ne 0){throw 'ZIP apply failed'}
if((Get-Content -LiteralPath (Join-Path $install 'MultiMini.exe')) -ne 'new test file'){throw 'Launcher not replaced'}
if((Get-Content -LiteralPath (Join-Path $install 'unins000.exe')) -ne 'keep uninstall'){throw 'Uninstaller not preserved'}
if($global:zipRestart -ne (Join-Path $install 'MultiMini.exe')){throw 'Restart not requested'}
Write-Output 'PASS: ZIP extraction, replacement, uninstaller preservation and restart request; dummy files only, no app launched'

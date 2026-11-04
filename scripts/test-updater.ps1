$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\main\resources\app\multimin\updater.ps1'
$name='MultiMini-Setup-0.02-Windows-x64.exe';$url='https://github.com/MaDaAi/MultiMini/releases/download/v0.02-beta/'+$name
$payload=[Text.Encoding]::UTF8.GetBytes('Test payload; never executed')
$hash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($payload)).Replace('-','').ToLowerInvariant()
function Invoke-RestMethod { param($Uri,$Headers,$TimeoutSec) @([pscustomobject]@{draft=$false;assets=@([pscustomobject]@{name=$name;browser_download_url=$url},[pscustomobject]@{name=($name+'.sha256');browser_download_url=($url+'.sha256')})}) }
function Invoke-WebRequest { param($Uri,$OutFile,[switch]$UseBasicParsing,$TimeoutSec) if($Uri.EndsWith('.sha256')){[IO.File]::WriteAllText($OutFile,"$hash  $name")}else{[IO.File]::WriteAllBytes($OutFile,$payload)} }
$result=& $script -CurrentVersion '0.02';if($result -ne 'NONE'){throw 'Downgrade/same-version guard failed'}
$result=& $script -CurrentVersion '0.01';if($result -ne '0.02'){throw 'Release discovery failed'}
$prepared=& $script -CurrentVersion '0.01' -Prepare
if($LASTEXITCODE -ne 0 -or $prepared.Count -ne 2 -or $prepared[1] -ne $hash){throw 'Verified preparation failed'}
try{if((Get-FileHash -LiteralPath $prepared[0] -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'Prepared payload differs'}}finally{Remove-Item -LiteralPath $prepared[0],($prepared[0]+'.sha256') -Force;[IO.Directory]::Delete([IO.Path]::GetDirectoryName($prepared[0]))}
$originalUrl=$url;$url='https://untrusted.invalid/'+$name
$null=& $script -CurrentVersion '0.01'
if($LASTEXITCODE -eq 0){throw 'Untrusted asset URL was accepted'}
$url=$originalUrl
$null=& $script -Apply -Prepared 'C:\untrusted\payload.exe' -ExpectedHash ('0'*64)
if($LASTEXITCODE -eq 0){throw 'Untrusted staged path was accepted'}
$hash='0'*64
$global:MultiMiniTestChecksumPath=$null
function Invoke-WebRequest { param($Uri,$OutFile,[switch]$UseBasicParsing,$TimeoutSec) if($Uri.EndsWith('.sha256')){$global:MultiMiniTestChecksumPath=$OutFile;[IO.File]::WriteAllText($OutFile,"$hash  $name")}else{[IO.File]::WriteAllBytes($OutFile,$payload)} }
$null=& $script -CurrentVersion '0.01' -Prepare
if($LASTEXITCODE -eq 0){throw 'Corrupted download was accepted'}
if($global:MultiMiniTestChecksumPath){Remove-Item -LiteralPath $global:MultiMiniTestChecksumPath -Force;[IO.Directory]::Delete([IO.Path]::GetDirectoryName($global:MultiMiniTestChecksumPath))}
Write-Output 'PASS: discovery, version guards, verified download, foreign URL/path rejection and corrupt-download rejection; no installer executed'

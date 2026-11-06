param([Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference='Stop'
$file=Get-Item -LiteralPath $Archive
if($file.Name -notmatch '^MultiMini-[0-9.]+-Windows-x64\.zip$'){throw 'Use the documented portable release filename.'}
$hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath ($file.FullName+'.sha256') -Encoding ascii -Value "$hash  $($file.Name)"
Write-Output ($file.FullName+'.sha256')

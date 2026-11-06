param([switch]$Details,[string]$TargetVersion='',[switch]$Prepare,[switch]$Apply,[string]$CurrentVersion='0.01',[string]$Prepared='', [string]$ExpectedHash='', [int]$AppPid=0,[string]$InstallDir='')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
 $cache=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'MultiMin\updates'
 if($Apply){
  $full=[IO.Path]::GetFullPath($Prepared)
  if(-not $full.StartsWith($cache+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($full) -notmatch '^MultiMini-(?:Setup-[0-9.]+-Windows-x64\.exe|[0-9.]+-Windows-x64\.zip)$'){throw 'Invalid staged update path'}
  if($ExpectedHash -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash -ne $ExpectedHash){throw 'Update checksum mismatch'}
  if(-not (Test-Path -LiteralPath (Join-Path $InstallDir 'MultiMini.exe'))){throw 'Installed launcher not found'}
  for($i=0;$i -lt 120 -and (Get-Process -Id $AppPid -ErrorAction SilentlyContinue);$i++){Start-Sleep -Seconds 1}
  if(Get-Process -Id $AppPid -ErrorAction SilentlyContinue){throw 'MultiMini did not close; update cancelled'}
  if($full.EndsWith('.zip',[StringComparison]::OrdinalIgnoreCase)){
   Add-Type -AssemblyName System.IO.Compression.FileSystem
   $unpack=Join-Path $cache ([guid]::NewGuid().ToString())
   New-Item -ItemType Directory $unpack | Out-Null
   $archive=[IO.Compression.ZipFile]::OpenRead($full)
   try {
    $total=0L
    foreach($entry in $archive.Entries){
     $total+=$entry.Length
     $destination=[IO.Path]::GetFullPath((Join-Path $unpack $entry.FullName))
     if(!$destination.StartsWith($unpack+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $entry.FullName.Contains(':') -or (($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000 -or $total -gt 4GB){throw 'Unsafe update archive'}
    }
   } finally {$archive.Dispose()}
   [IO.Compression.ZipFile]::ExtractToDirectory($full,$unpack)
   $launchers=@(Get-ChildItem -LiteralPath $unpack -Filter MultiMini.exe -Recurse -File)
   if($launchers.Count -ne 1){throw 'Update must contain exactly one launcher'}
   $image=$launchers[0].Directory.FullName
   foreach($required in @('app\MultiMini.jar','runtime\bin\java.dll','app\python\runtime\python.exe')){if(!(Test-Path -LiteralPath (Join-Path $image $required))){throw "Incomplete update: $required"}}
   $backup=Join-Path $cache ('rollback-'+[guid]::NewGuid())
   New-Item -ItemType Directory $backup | Out-Null
   $written=[Collections.Generic.List[string]]::new()
   try {
    foreach($file in Get-ChildItem -LiteralPath $image -Recurse -File){
     $relative=$file.FullName.Substring($image.Length+1);$target=Join-Path $InstallDir $relative
     if(Test-Path -LiteralPath $target){$old=Join-Path $backup $relative;New-Item -ItemType Directory -Force (Split-Path -Parent $old) | Out-Null;Copy-Item -LiteralPath $target -Destination $old}
     New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
     $written.Add($relative);Copy-Item -LiteralPath $file.FullName -Destination $target -Force
    }
   } catch {
    foreach($relative in $written){$old=Join-Path $backup $relative;$target=Join-Path $InstallDir $relative;if(Test-Path -LiteralPath $old){Copy-Item -LiteralPath $old -Destination $target -Force}else{Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue}}
    throw
   }
   Start-Process -FilePath (Join-Path $InstallDir 'MultiMini.exe') -WorkingDirectory $InstallDir -WindowStyle Hidden
   exit 0
  }
  $log=Join-Path $cache 'install.log'
  $process=Start-Process -FilePath $full -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/NOICONS',('/DIR="'+$InstallDir+'"'),('/LOG="'+$log+'"')) -WindowStyle Hidden -Wait -PassThru
  if($process.ExitCode -ne 0){throw "Update installation failed ($($process.ExitCode)); see $log"}
  Start-Process -FilePath (Join-Path $InstallDir 'MultiMini.exe') -WorkingDirectory $InstallDir -WindowStyle Hidden
  exit 0
 }
 $headers=@{'User-Agent'='MultiMini-Updater';'Accept'='application/vnd.github+json'}
 try { $releases=Invoke-RestMethod -Uri 'https://api.github.com/repos/MaDaAi/MultiMini/releases?per_page=10' -Headers $headers -TimeoutSec 30 }
 catch { if($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404){Write-Output 'PRIVATE';exit 0};throw }
 $candidate=$null
 foreach($release in $releases){if($release.draft -or $release.prerelease){continue};foreach($asset in $release.assets){if($asset.name -match '^MultiMini-(?:Setup-)?([0-9.]+)-Windows-x64\.(?:exe|zip)$'){$version=$Matches[1];if((!$TargetVersion -or $version -eq $TargetVersion) -and [version]$version -gt [version]$CurrentVersion -and (!$candidate -or ([version]$version -gt [version]$candidate.Version -or ([version]$version -eq [version]$candidate.Version -and $asset.name.EndsWith('.zip'))))){$sum=$release.assets | Where-Object name -eq ($asset.name+'.sha256') | Select-Object -First 1;if($sum){$candidate=[pscustomobject]@{Version=$version;Asset=$asset;Sum=$sum;Notes=[string]$release.body}}}}}}
 if(!$candidate){Write-Output 'NONE';exit 0}
 foreach($url in @($candidate.Asset.browser_download_url,$candidate.Sum.browser_download_url)){if(-not $url.StartsWith('https://github.com/MaDaAi/MultiMini/releases/download/',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected update source'}}
 if(!$Prepare){Write-Output $candidate.Version;if($Details){Write-Output ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($candidate.Notes)))};exit 0}
 $stage=Join-Path $cache ([guid]::NewGuid().ToString());New-Item -ItemType Directory -Force $stage | Out-Null
 $exe=Join-Path $stage $candidate.Asset.name
 Invoke-WebRequest -Uri $candidate.Sum.browser_download_url -OutFile ($exe+'.sha256') -UseBasicParsing -TimeoutSec 60
 $sumText=(Get-Content -LiteralPath ($exe+'.sha256') -Raw).Trim()
 if($sumText -notmatch ('^([a-fA-F0-9]{64})\s+\*?'+[regex]::Escape($candidate.Asset.name)+'$')){throw 'Invalid release checksum file'}
 $hash=$Matches[1]
 Invoke-WebRequest -Uri $candidate.Asset.browser_download_url -OutFile $exe -UseBasicParsing -TimeoutSec 300
 if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $hash){Remove-Item -LiteralPath $exe;throw 'Download checksum mismatch'}
 Write-Output $exe;Write-Output $hash
} catch { [Console]::Error.WriteLine($_.Exception.Message);exit 1 }

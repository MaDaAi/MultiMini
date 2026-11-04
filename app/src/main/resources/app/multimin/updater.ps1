param([switch]$Prepare,[switch]$Apply,[string]$CurrentVersion='0.01',[string]$Prepared='', [string]$ExpectedHash='', [int]$AppPid=0,[string]$InstallDir='')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
 $cache=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'MultiMin\updates'
 if($Apply){
  $full=[IO.Path]::GetFullPath($Prepared)
  if(-not $full.StartsWith($cache+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($full) -notmatch '^MultiMini-Setup-[0-9.]+-Windows-x64\.exe$'){throw 'Invalid staged update path'}
  if($ExpectedHash -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash -ne $ExpectedHash){throw 'Update checksum mismatch'}
  if(-not (Test-Path -LiteralPath (Join-Path $InstallDir 'MultiMini.exe'))){throw 'Installed launcher not found'}
  for($i=0;$i -lt 120 -and (Get-Process -Id $AppPid -ErrorAction SilentlyContinue);$i++){Start-Sleep -Seconds 1}
  if(Get-Process -Id $AppPid -ErrorAction SilentlyContinue){throw 'MultiMini did not close; update cancelled'}
  $log=Join-Path $cache 'install.log'
  $process=Start-Process -FilePath $full -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/NOICONS',('/DIR="'+$InstallDir+'"'),('/LOG="'+$log+'"')) -WindowStyle Hidden -Wait -PassThru
  if($process.ExitCode -ne 0){throw "Update installation failed ($($process.ExitCode)); see $log"}
  Start-Process -FilePath (Join-Path $InstallDir 'MultiMini.exe') -WorkingDirectory $InstallDir -WindowStyle Hidden
  exit 0
 }
 $headers=@{'User-Agent'='MultiMini-Updater';'Accept'='application/vnd.github+json'}
 $releases=Invoke-RestMethod -Uri 'https://api.github.com/repos/MaDaAi/MultiMini/releases?per_page=10' -Headers $headers -TimeoutSec 30
 $candidate=$null
 foreach($release in $releases){if($release.draft){continue};foreach($asset in $release.assets){if($asset.name -match '^MultiMini-Setup-([0-9.]+)-Windows-x64\.exe$'){$version=$Matches[1];if([version]$version -gt [version]$CurrentVersion -and (!$candidate -or [version]$version -gt [version]$candidate.Version)){$sum=$release.assets | Where-Object name -eq ($asset.name+'.sha256') | Select-Object -First 1;if($sum){$candidate=[pscustomobject]@{Version=$version;Asset=$asset;Sum=$sum}}}}}}
 if(!$candidate){Write-Output 'NONE';exit 0}
 foreach($url in @($candidate.Asset.browser_download_url,$candidate.Sum.browser_download_url)){if(-not $url.StartsWith('https://github.com/MaDaAi/MultiMini/releases/download/',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected update source'}}
 if(!$Prepare){Write-Output $candidate.Version;exit 0}
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

param([switch]$DevelopmentGameCapture)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildArgs = @{ DevelopmentGameCapture = $DevelopmentGameCapture }
$BuildOutput = & (Join-Path $PSScriptRoot 'build.ps1') @BuildArgs
$Jar = $BuildOutput
if ($LASTEXITCODE -ne 0 -or -not $Jar) { throw 'MultiMin build failed.' }
$env:PYTHONPATH = Join-Path $ProjectRoot 'python'
# Robot display frames are large enough to become G1 humongous allocations
# with the default region size. Keep them in ordinary regions and bound heap growth.
$JavaArgs = @('--enable-native-access=ALL-UNNAMED', '-Xmx2g', '-XX:G1HeapRegionSize=32m')
$JavaArgs += @('-jar', ($Jar | Select-Object -Last 1))
& java @JavaArgs

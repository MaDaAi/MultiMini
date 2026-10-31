$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Jar = & (Join-Path $PSScriptRoot 'build.ps1')
if ($LASTEXITCODE -ne 0 -or -not $Jar) { throw 'MultiMin build failed.' }
$env:PYTHONPATH = Join-Path $ProjectRoot 'python'
# Robot display frames are large enough to become G1 humongous allocations
# with the default region size. Keep them in ordinary regions and bound heap growth.
& java -Xmx2g -XX:G1HeapRegionSize=32m -jar ($Jar | Select-Object -Last 1)

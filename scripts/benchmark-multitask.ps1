# Multitask launch benchmark runner. Measures only; changes no app source.
#   .\scripts\benchmark-multitask.ps1 [-Repetitions 2] [-Seconds 8] [-NoRobot] [-Jar build\internal\MultiMini-Bench.jar]
#                                     [-PythonDeps <dir with windows_capture etc.>] [-OutDir <dir>] [-Modes native,capture]
# Each JVM run = one mode (so entry 1 is really first-after-launch). Uses disposable APPDATA/LOCALAPPDATA/user.home.
# NOTE: multitask mode installs the app's global input hooks; mouse/keyboard input during a run is forwarded to the
# fixture window. Leave the machine idle during the ~1 minute each run takes (End key exits multitask).
param(
    [int]$Repetitions = 2,
    [int]$Seconds = 8,
    [switch]$NoRobot,
    [string]$Jar = 'build\internal\MultiMini-Bench.jar',
    [string]$PythonDeps = '',
    [string]$OutDir = '',
    [string[]]$Modes = @('native', 'capture'),
    [int]$RunTimeoutSeconds = 180,
    [string]$DumpDir = '',
    [switch]$Verify,
    [string[]]$ExtraJvmArgs = @(),
    [switch]$OldFlags,
    [switch]$Translucent,
    [switch]$NoStandin,
    [switch]$Diag,
    [switch]$RealFlow,
    [int]$CfgWait = 12
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot
$JarPath = (Resolve-Path -LiteralPath $Jar).Path
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP 'multimini-bench-out' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Classes = Join-Path $ProjectRoot 'build\internal\bench-classes'
New-Item -ItemType Directory -Force -Path $Classes | Out-Null
& javac -encoding UTF-8 -cp $JarPath -d $Classes (Join-Path $ProjectRoot 'native\tests\MultitaskLaunchBenchmark.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed for MultitaskLaunchBenchmark' }

$Runs = @()
for ($rep = 1; $rep -le $Repetitions; $rep++) {
    foreach ($mode in $Modes) {
        $profile = Join-Path ([System.IO.Path]::GetTempPath()) ("mmbench-profile-" + [guid]::NewGuid().ToString('N'))
        foreach ($d in 'roaming', 'local', 'home') { New-Item -ItemType Directory -Force -Path (Join-Path $profile $d) | Out-Null }
        $json = Join-Path $OutDir ("run-rep$rep-$mode.json")
        Remove-Item -LiteralPath $json -ErrorAction SilentlyContinue
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = (Get-Command java).Source
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $heapArgs = if ($OldFlags) { @('-Xmx2g', '-XX:G1HeapRegionSize=32m') } else { @('-Xms512m', '-Xmx2g', '-XX:G1HeapRegionSize=32m', '-XX:MaxGCPauseMillis=8') }
        $jargs = @('--enable-native-access=ALL-UNNAMED') + $heapArgs + $ExtraJvmArgs + $(if ($Translucent) { @('-Dmultimin.translucentOverlay=true') } else { @() }) + @(
            "-Duser.home=$(Join-Path $profile 'home')", '-cp', "$Classes;$JarPath", 'MultitaskLaunchBenchmark',
            '--mode', $mode, '--out', $json, '--seconds', "$Seconds")
        if ($NoRobot) { $jargs += '--no-robot' }
        if ($Verify) { $jargs += '--verify' }
        if ($NoStandin) { $jargs += '--no-standin' }
        if ($Diag) { $jargs += '--diag' }
        $jargs += @('--cfgwait', "$CfgWait")
        if ($RealFlow) { $jargs += '--realflow' }
        if ($DumpDir) { $jargs += @('--dump', $DumpDir) }
        $psi.Arguments = ($jargs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
        $psi.EnvironmentVariables['APPDATA'] = Join-Path $profile 'roaming'
        $psi.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $profile 'local'
        $psi.EnvironmentVariables['USERPROFILE'] = Join-Path $profile 'home'
        if ($PythonDeps) { $psi.EnvironmentVariables['PYTHONPATH'] = $PythonDeps }
        $psi.EnvironmentVariables.Remove('MULTIMINI_RECORDING_DEMO')
        Write-Host "== rep $rep mode $mode (profile $profile)"
        $p = [System.Diagnostics.Process]::Start($psi)
        $errTask = $p.StandardError.ReadToEndAsync()
        $outTask = $p.StandardOutput.ReadToEndAsync()
        if (-not $p.WaitForExit($RunTimeoutSeconds * 1000)) {
            Write-Warning "run timed out; killing the JVM we started (pid $($p.Id)) and its children"
            Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            $p.Kill()
        }
        $p.WaitForExit()
        $stderr = $errTask.Result
        if ($stderr) { Write-Host ($stderr.Trim() -split "`n" | Select-Object -Last 12 | Out-String) }
        if (Test-Path -LiteralPath $json) { $Runs += [pscustomobject]@{ repetition = $rep; mode = $mode; result = (Get-Content -Raw -LiteralPath $json | ConvertFrom-Json) } }
        else { $Runs += [pscustomobject]@{ repetition = $rep; mode = $mode; result = @{ error = 'no output produced' } } }
        Remove-Item -LiteralPath $profile -Recurse -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}
$summaryPath = Join-Path $OutDir 'multitask-benchmark-summary.json'
$summary = [pscustomobject]@{ generated = (Get-Date).ToString('s'); seconds = $Seconds; robot = (-not $NoRobot); runs = $Runs }
$text = $summary | ConvertTo-Json -Depth 12
Set-Content -LiteralPath $summaryPath -Value $text -Encoding UTF8
Write-Host "Summary written to $summaryPath"
$text

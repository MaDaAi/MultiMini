$ErrorActionPreference = 'Stop'
$previous = $env:MULTIMINI_RECORDING_DEMO
try {
    $env:MULTIMINI_RECORDING_DEMO = '1'
    & (Join-Path $PSScriptRoot 'run.ps1') -DevelopmentGameCapture
} finally {
    $env:MULTIMINI_RECORDING_DEMO = $previous
}

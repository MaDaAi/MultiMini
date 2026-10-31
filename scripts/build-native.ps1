$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
if (-not $Compiler) {
    throw 'MSVC cl.exe was not found. Install Visual Studio Build Tools with Desktop development with C++, Windows 10/11 SDK, and MSVC x64 tools; run this script from x64 Developer PowerShell.'
}
if ($env:VSCMD_ARG_TGT_ARCH -ne 'x64') {
    throw 'Run from x64 Developer PowerShell for Visual Studio (VSCMD_ARG_TGT_ARCH=x64).'
}
$Native = Join-Path $ProjectRoot 'native'
$Output = Join-Path $Native 'build'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
& $Compiler.Source /nologo /std:c++20 /EHsc /O2 /W4 /MD /DUNICODE /D_UNICODE `
    "/Fo$Output\" "/Fe$Output\multimin-dx11-test.exe" `
    (Join-Path $Native 'GameCapture.cpp') (Join-Path $Native 'Dx11TestApp.cpp') `
    /link /SUBSYSTEM:WINDOWS d3d11.lib dxgi.lib ws2_32.lib user32.lib shell32.lib
if ($LASTEXITCODE -ne 0) { throw 'Native DirectX 11 test build failed.' }
Write-Output (Join-Path $Output 'multimin-dx11-test.exe')

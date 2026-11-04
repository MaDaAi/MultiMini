$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
if (-not $Compiler) {
    throw 'MSVC cl.exe was not found. Install Visual Studio Build Tools with Desktop development with C++, Windows 10/11 SDK, and MSVC x64 tools; run this script from x64 Developer PowerShell.'
}
if ($env:VSCMD_ARG_TGT_ARCH -and $env:VSCMD_ARG_TGT_ARCH -ne 'x64') {
    throw "The active Visual Studio compiler target is '$env:VSCMD_ARG_TGT_ARCH'. Open an x64 Native Tools Command Prompt or x64 Developer PowerShell."
}
if (-not $env:VSCMD_ARG_TGT_ARCH -and $env:Platform -and $env:Platform -ne 'X64') {
    throw "The active compiler platform is '$env:Platform'. Use an x64 Visual Studio developer shell."
}
$Native = Join-Path $ProjectRoot 'native'
$Output = Join-Path $Native 'build'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
& $Compiler.Source /nologo /std:c++20 /EHsc /O2 /W4 /MT /DNOMINMAX /DUNICODE /D_UNICODE `
    "/Fo$Output\" "/Fe$Output\multimin-dx11-test.exe" `
    (Join-Path $Native 'GameCapture.cpp') (Join-Path $Native 'Dx11TestApp.cpp') `
    /link /SUBSYSTEM:WINDOWS d3d11.lib dxgi.lib ws2_32.lib user32.lib shell32.lib
if ($LASTEXITCODE -ne 0) { throw 'Native DirectX 11 test build failed.' }
Write-Output (Join-Path $Output 'multimin-dx11-test.exe')
$JniJdk = $env:JAVA_HOME
if (-not $JniJdk -or -not (Test-Path (Join-Path $JniJdk 'include\jni.h'))) {
    $JniJdk = Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk-*' | Where-Object { Test-Path (Join-Path $_.FullName 'include\jni.h') } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JniJdk) { throw 'JNI frame bridge requires a JDK with include/jni.h. Set JAVA_HOME to that JDK.' }
& $Compiler.Source /nologo /std:c++20 /EHsc /O2 /W4 /MT /LD `
    "/I$JniJdk\include" "/I$JniJdk\include\win32" "/Fo$Output\FrameBridge.obj" `
    "/Fe$Output\multimin-frame-bridge.dll" (Join-Path $Native 'FrameBridge.cpp') `
    /link "/IMPLIB:$Output\multimin-frame-bridge.lib" user32.lib ole32.lib windowscodecs.lib dwmapi.lib
if ($LASTEXITCODE -ne 0) { throw 'JNI frame bridge build failed.' }
Write-Output (Join-Path $Output 'multimin-frame-bridge.dll')

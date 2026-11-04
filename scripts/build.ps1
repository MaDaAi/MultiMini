param([string]$OutputName = '', [switch]$DevelopmentGameCapture)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SourceRoot = Join-Path $ProjectRoot 'app\src\main\java'
$BuildRoot = Join-Path $ProjectRoot 'build'
$ClassRoot = Join-Path $BuildRoot $(if ($DevelopmentGameCapture) { 'internal\classes-experimental' } else { 'internal\classes-regular' })
if (-not $OutputName) { $OutputName = if ($DevelopmentGameCapture) { 'MultiMini-Experimental.jar' } else { 'MultiMini.jar' } }
if ($OutputName) {
    $PrimaryJar = Join-Path $BuildRoot $OutputName
    if (Test-Path -LiteralPath $PrimaryJar) {
        try {
            $JarLockCheck = [System.IO.File]::Open($PrimaryJar, [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $JarLockCheck.Dispose()
        } catch [System.IO.IOException] {
            throw "Close $OutputName before rebuilding it. The build will not create another version."
        }
    }
}

New-Item -ItemType Directory -Force -Path $ClassRoot | Out-Null
$Sources = Get-ChildItem -LiteralPath $SourceRoot -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $Sources) { throw 'No Java sources found.' }

$JdkCandidates = @()
if ($env:JAVA_HOME) { $JdkCandidates += $env:JAVA_HOME }
if ($env:ProgramFiles) {
    $JdkCandidates += Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
}
$JavacExecutable = $JdkCandidates | ForEach-Object { Join-Path $_ 'bin\javac.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $JavacExecutable) {
    $JavacCommand = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $JavacCommand) { throw 'Could not locate the JDK compiler.' }
    $JavacExecutable = $JavacCommand.Source
}

& $JavacExecutable -encoding UTF-8 -d $ClassRoot $Sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$GameCaptureFeature = if ($DevelopmentGameCapture) { 'true' } else { 'false' }
Set-Content -LiteralPath (Join-Path $ClassRoot 'build.properties') -Value @(
    "experimentalGameCapture=$GameCaptureFeature"
    'nativeWindowPresentation=true'
) -Encoding Ascii

$ResourceRoot = Join-Path $ProjectRoot 'app\src\main\resources'
if (Test-Path -LiteralPath $ResourceRoot) {
    Get-ChildItem -LiteralPath $ResourceRoot -File -Recurse | ForEach-Object {
        $relative = $_.FullName.Substring($ResourceRoot.Length).TrimStart('\')
        $destination = Join-Path $ClassRoot $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
    }
}

# Include the standard-library-only input service so a JAR launched outside
$FrameBridge = Join-Path $ProjectRoot 'native\build\multimin-frame-bridge.dll'
if (Test-Path -LiteralPath $FrameBridge) {
    New-Item -ItemType Directory -Force -Path (Join-Path $ClassRoot 'native') | Out-Null
    Copy-Item -LiteralPath $FrameBridge -Destination (Join-Path $ClassRoot 'native\multimin-frame-bridge.dll') -Force
}
# Include the standard-library-only input service so a JAR launched outside
# the source checkout can still start mouse and keyboard routing.
$BackendSource = Join-Path $ProjectRoot 'python\multimin_backend'
$BackendDestination = Join-Path $ClassRoot 'bundled-python\multimin_backend'
New-Item -ItemType Directory -Force -Path $BackendDestination | Out-Null
Get-ChildItem -LiteralPath $BackendSource -File -Filter '*.py' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $BackendDestination $_.Name) -Force
}

$JarCommand = Get-Command jar -ErrorAction SilentlyContinue
if ($JarCommand) {
    $JarExecutable = $JarCommand.Source
} else {
    $JarExecutable = $JdkCandidates | ForEach-Object { Join-Path $_ 'bin\jar.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $JarExecutable) {
        $JavaSettings = & java -XshowSettings:properties -version 2>&1
        $JavaHomeLine = $JavaSettings | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if (-not $JavaHomeLine) { throw 'Could not locate the JDK home or jar executable.' }
        $JavaHome = ($JavaHomeLine -split '=', 2)[1].Trim()
        $JarExecutable = Join-Path $JavaHome 'bin\jar.exe'
    }
}

# Keep jar's atomic temporary file under the workspace. On Windows the system
# temp directory may be on a sandboxed volume that cannot be moved into build/.
$JarPath = Join-Path $BuildRoot $OutputName
& $JarExecutable "-J-Djava.io.tmpdir=$BuildRoot" --create --file $JarPath --main-class app.multimin.Main -C $ClassRoot .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }

Write-Output $JarPath

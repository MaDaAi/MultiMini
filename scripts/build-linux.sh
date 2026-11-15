#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d "$PWD/build-linux-classes.XXXXXX")
trap 'rm -rf -- "$classes"' EXIT
mkdir -p build/internal
find app/src/main/java -name '*.java' -print0 | xargs -0 javac --release 25 -encoding UTF-8 -d "$classes"
cp -r app/src/main/resources/. "$classes/"
# A Linux build never contains the PowerShell updater or Windows binaries.
rm -f "$classes/app/multimin/updater.ps1"
printf 'experimentalGameCapture=false\nnativeWindowPresentation=false\npublicUpdates=true\n' > "$classes/build.properties"
mkdir -p "$classes/legal" "$classes/bundled-python/multimin_backend"
cp TERMS.md EULA.md PRIVACY.md LICENSE COOKIE_POLICY.md REFUND_POLICY.md HELP.md THIRD_PARTY_NOTICES.md "$classes/legal/"
cp python/multimin_backend/*.py "$classes/bundled-python/multimin_backend/"
mkdir -p "$classes/native"
multimini_javac=$(readlink -f "$(command -v javac)")
multimini_jdk=${JAVA_HOME:-$(dirname "$(dirname "$multimini_javac")")}
c++ -std=c++17 -O2 -fPIC -shared -I"$multimini_jdk/include" -I"$multimini_jdk/include/linux" \
    native/LinuxWindow.cpp -L"$multimini_jdk/lib" -ljawt -lX11 -o "$classes/native/libmultimini-linux.so"
jar --create --file build/internal/MultiMini-Linux.jar --main-class app.multimin.Main -C "$classes" .
printf '%s\n' "$PWD/build/internal/MultiMini-Linux.jar"

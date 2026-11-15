#!/usr/bin/env bash
# Build the macOS JAR from the shared source. UNTESTED on real Macs.
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d "$PWD/build-mac-classes.XXXXXX")
trap 'rm -rf -- "$classes"' EXIT
mkdir -p build/internal
find app/src/main/java -name '*.java' -print0 | xargs -0 javac --release 25 -encoding UTF-8 -d "$classes"
cp -r app/src/main/resources/. "$classes/"
# A macOS build never contains the PowerShell updater or Windows/Linux binaries.
rm -f "$classes/app/multimin/updater.ps1"
printf 'experimentalGameCapture=false\nnativeWindowPresentation=false\npublicUpdates=true\n' > "$classes/build.properties"
mkdir -p "$classes/legal" "$classes/bundled-python/multimin_backend"
cp TERMS.md EULA.md PRIVACY.md LICENSE COOKIE_POLICY.md REFUND_POLICY.md HELP.md THIRD_PARTY_NOTICES.md "$classes/legal/"
cp python/multimin_backend/*.py "$classes/bundled-python/multimin_backend/"
jar --create --file build/internal/MultiMini-Mac.jar --main-class app.multimin.Main -C "$classes" .
printf '%s\n' "$PWD/build/internal/MultiMini-Mac.jar"

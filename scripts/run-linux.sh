#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f build/internal/MultiMini-Linux.jar ]] || ./scripts/build-linux.sh
export MULTIMIN_HOME="$PWD"
exec java --enable-native-access=ALL-UNNAMED -Djpackage.app-version="$(cat VERSION)" -jar build/internal/MultiMini-Linux.jar "$@"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f build/internal/MultiMini-Linux.jar ]] || ./scripts/build-linux.sh
export MULTIMIN_HOME="$PWD"
exec java --enable-native-access=ALL-UNNAMED -Xms64m -Xmx2g -XX:G1PeriodicGCInterval=30000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:+UseG1GC -XX:G1HeapRegionSize=32m -XX:MaxGCPauseMillis=8 -Djpackage.app-version="$(cat VERSION)" -jar build/internal/MultiMini-Linux.jar "$@"

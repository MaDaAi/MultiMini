#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/build-linux.sh
PYTHONPATH=python "${MULTIMIN_PYTHON:-python3}" -m unittest discover -s python/tests -q
mkdir -p build/checks
javac --release 25 -cp build/internal/MultiMini-Linux.jar -d build/checks app/src/test/java/app/multimin/ui/AppearancePolicyCheck.java app/src/test/java/app/multimin/ui/LinuxPlatformCheck.java native/tests/FrameBridgeCheck.java app/src/test/java/app/multimin/ui/LinuxOverlayRepaintCheck.java app/src/test/java/app/multimin/ui/LinuxDirectPresentationCheck.java native/tests/WorkspaceCheck.java
java -Djava.awt.headless=true -cp build/checks:build/internal/MultiMini-Linux.jar app.multimin.ui.AppearancePolicyCheck
java -Djava.awt.headless=true -cp build/checks:build/internal/MultiMini-Linux.jar app.multimin.ui.LinuxOverlayRepaintCheck
java -Djava.awt.headless=true -cp build/checks:build/internal/MultiMini-Linux.jar app.multimin.ui.LinuxDirectPresentationCheck
java -Djava.awt.headless=true -cp build/checks:build/internal/MultiMini-Linux.jar app.multimin.ui.WorkspaceCheck
profile=$(mktemp -d)
trap 'rm -rf -- "$profile"' EXIT
XDG_CONFIG_HOME="$profile/config" XDG_DATA_HOME="$profile/data" java -Duser.home="$profile" -Djava.awt.headless=true -cp build/checks:build/internal/MultiMini-Linux.jar app.multimin.ui.LinuxPlatformCheck
PYTHONPATH=python "${MULTIMIN_PYTHON:-python3}" scripts/check-frame-bridge.py build/internal/MultiMini-Linux.jar build/checks

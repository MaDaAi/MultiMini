#!/usr/bin/env bash
# Package MultiMini for macOS (Apple Silicon) as a .dmg. UNTESTED on real Macs.
#
# Produces dist/MultiMini-<VERSION>-macOS-arm64.dmg and its .sha256.
# Signing:
#   * Default: ad-hoc signature (codesign -s -). Required for Apple Silicon to run
#     the app at all. Gatekeeper still warns on first launch (see docs/MACOS.md).
#   * If MACOS_SIGN_IDENTITY ("Developer ID Application: ...") is set, signs with
#     hardened runtime; if APPLE_ID, APPLE_TEAM_ID and APPLE_APP_PASSWORD are also
#     set, notarizes and staples so Gatekeeper opens it without warnings.
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(tr -d '[:space:]' < VERSION)
arch=arm64
py_url="https://github.com/astral-sh/python-build-standalone/releases/download/20261026/cpython-3.13.18%2B20261026-aarch64-apple-darwin-install_only.tar.gz"
py_sha="e5af2fde6a937a0e0c795a0c595c4efb70578d57463a2657c819992b9b9acbac"

./scripts/build-mac.sh
work=build/mac
rm -rf "$work"; mkdir -p "$work/input/python" "$work/image" dist

# 1. Relocatable Python + PyObjC, placed where PythonBridge looks (app/python/runtime).
curl -fsSL "$py_url" -o "$work/python.tar.gz"
echo "$py_sha  $work/python.tar.gz" | shasum -a 256 -c -
tar -xzf "$work/python.tar.gz" -C "$work"
mv "$work/python" "$work/input/python/runtime"
"$work/input/python/runtime/bin/python3" -m pip install --no-cache-dir --only-binary=:all: \
    --disable-pip-version-check -r python/requirements-macos.txt
cp -R python/multimin_backend "$work/input/python/multimin_backend"
find "$work/input/python" -name '__pycache__' -type d -prune -exec rm -rf {} +
cp build/internal/MultiMini-Mac.jar "$work/input/MultiMini.jar"

# 2. Icon from the publisher-supplied logo.
iconset="$work/MultiMini.iconset"; mkdir -p "$iconset"
for size in 16 32 64 128 256 512; do
  sips -z $size $size app/src/main/resources/app/multimin/multimini-logo.png --out "$iconset/icon_${size}x${size}.png" >/dev/null
  double=$((size*2))
  sips -z $double $double app/src/main/resources/app/multimin/multimini-logo.png --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$work/MultiMini.icns"

# 3. App image with a bundled Java runtime.
jpackage --type app-image --name MultiMini --app-version "$version" --vendor 'Mansoor Alakoozai' \
  --icon "$work/MultiMini.icns" --input "$work/input" --main-jar MultiMini.jar --dest "$work/image" \
  --mac-package-identifier io.github.MaDaAi.MultiMini --mac-package-name MultiMini \
  --add-modules java.desktop,java.management,java.net.http,jdk.management,jdk.unsupported \
  --java-options '--enable-native-access=ALL-UNNAMED' --java-options '-Xmx2g' \
  --java-options '-Dapple.awt.application.name=MultiMini'
app="$work/image/MultiMini.app"
plist="$app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Add :NSScreenCaptureUsageDescription string 'MultiMini shows the windows and displays you choose inside your layered view.'" "$plist" || true
/usr/libexec/PlistBuddy -c "Add :NSAppleEventsUsageDescription string 'MultiMini uses this to show the image file chooser.'" "$plist" || true
/usr/libexec/PlistBuddy -c "Add :LSMinimumSystemVersion string 12.3" "$plist" || true
for doc in LICENSE TERMS.md PRIVACY.md THIRD_PARTY_NOTICES.md; do cp "$doc" "$app/Contents/Resources/"; done

# 4. Sign every Mach-O inside first, then the bundle. jpackage's own changes
#    break the JDK's signature, which macOS reports as "damaged" unless re-signed.
identity="${MACOS_SIGN_IDENTITY:--}"
sign_args=(--force --timestamp=none --sign "$identity")
if [ "$identity" != "-" ]; then sign_args=(--force --timestamp --options runtime --sign "$identity"); fi
entitlements="$work/entitlements.plist"
cat > "$entitlements" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.cs.allow-jit</key><true/>
  <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
  <key>com.apple.security.cs.disable-library-validation</key><true/>
  <key>com.apple.security.automation.apple-events</key><true/>
</dict></plist>
PLIST
while IFS= read -r -d '' file; do
  if file -b "$file" | grep -q 'Mach-O'; then codesign "${sign_args[@]}" --entitlements "$entitlements" "$file"; fi
done < <(find "$app/Contents" -type f -print0)
codesign "${sign_args[@]}" --entitlements "$entitlements" "$app"
codesign --verify --deep --strict "$app"

# 5. Disk image with an Applications shortcut.
dmg_root="$work/dmg"; mkdir -p "$dmg_root"
cp -R "$app" "$dmg_root/"
ln -s /Applications "$dmg_root/Applications"
cp docs/MACOS_FIRST_LAUNCH.txt "$dmg_root/READ ME FIRST.txt"
dmg="dist/MultiMini-$version-macOS-$arch.dmg"
rm -f "$dmg"
hdiutil create -volname "MultiMini $version" -srcfolder "$dmg_root" -ov -format UDZO "$dmg"
if [ "$identity" != "-" ]; then codesign --force --timestamp --sign "$identity" "$dmg"; fi

if [ "$identity" != "-" ] && [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ]; then
  xcrun notarytool submit "$dmg" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
  xcrun stapler staple "$dmg"
fi

(cd dist && shasum -a 256 "$(basename "$dmg")" > "$(basename "$dmg").sha256")
printf '%s\n' "$PWD/$dmg"

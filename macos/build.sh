#!/usr/bin/env bash
# Builds MacDroid.app with SwiftPM, so no Xcode project is needed.
# You need Xcode or the Command Line Tools (`xcode-select --install`).
#   ./build.sh            -> build/MacDroid.app
#   ./build.sh --install  -> also copies it to /Applications and launches it
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
BIN_DIR="$(swift build -c release --show-bin-path)"

APP="build/MacDroid.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/MacDroid" "$APP/Contents/MacOS/MacDroid"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# Ad-hoc signature: enough to run locally and use notifications. Distribution needs a real Developer ID.
codesign --force --sign - "$APP"
echo "Built $APP"

if [[ "${1:-}" == "--install" ]]; then
    pkill -x MacDroid 2>/dev/null || true
    rm -rf /Applications/MacDroid.app
    cp -R "$APP" /Applications/
    open /Applications/MacDroid.app
    echo "Installed and launched /Applications/MacDroid.app"
fi

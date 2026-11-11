#!/usr/bin/env bash
# Builds MacDroid.app with SwiftPM, so no Xcode project is needed.
# You need Xcode or the Command Line Tools (`xcode-select --install`).
#   ./build.sh            -> build/MacDroid.app
#   ./build.sh --install  -> also copies it to /Applications and launches it
set -euo pipefail
cd "$(dirname "$0")"

# Build with Apple's own toolchain, in a clean environment. Swift is called by its full
# path inside Xcode (or the Command Line Tools), because `xcrun swift` can pick up a
# swift.org toolchain installed in /Library/Developer/Toolchains instead.
# Override with: DEVELOPER_DIR=/Library/Developer/CommandLineTools ./build.sh
DEV_DIR="${DEVELOPER_DIR:-$(xcode-select -p)}"
SWIFT_BIN=""
for candidate in "$DEV_DIR/Toolchains/XcodeDefault.xctoolchain/usr/bin/swift" "$DEV_DIR/usr/bin/swift"; do
    if [[ -x "$candidate" ]]; then SWIFT_BIN="$candidate"; break; fi
done
if [[ -z "$SWIFT_BIN" ]]; then
    echo "No Swift found in $DEV_DIR. Install Xcode or run: xcode-select --install" >&2
    exit 1
fi

SWIFT=(env -i
    HOME="$HOME"
    USER="${USER:-$(id -un)}"
    TMPDIR="${TMPDIR:-/tmp}"
    PATH="/usr/bin:/bin:/usr/sbin:/sbin"
    DEVELOPER_DIR="$DEV_DIR"
    "$SWIFT_BIN")

VERSION="$("${SWIFT[@]}" --version 2>&1 | head -1)"
echo "Developer dir: $DEV_DIR"
echo "Using: $VERSION"

# A .build folder made by a different Swift version can't be reused, so start fresh.
if [[ -d .build && "$(cat .build/.macdroid-swift-version 2>/dev/null)" != "$VERSION" ]]; then
    echo "Swift version changed, cleaning .build"
    rm -rf .build
fi

"${SWIFT[@]}" build -c release
echo "$VERSION" > .build/.macdroid-swift-version
BIN_DIR="$("${SWIFT[@]}" build -c release --show-bin-path)"

APP="build/MacDroid.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/MacDroid" "$APP/Contents/MacOS/MacDroid"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# Signing. macOS ties permissions (Local Network, Notifications) to the app's signature.
# An ad-hoc signature changes on every build, and macOS may never show the Local Network
# prompt for it, which leaves MacDroid unable to answer the phone. A free
# "Apple Development" certificate gives a stable identity, so we use one when available.
#   Override with: SIGN_IDENTITY="Apple Development: you@example.com (TEAMID)" ./build.sh
SIGN_IDENTITY="${SIGN_IDENTITY:-$(security find-identity -v -p codesigning 2>/dev/null \
    | grep -E '"(Apple Development|Developer ID Application|Mac Developer)' \
    | head -1 | sed -E 's/.*"(.*)"/\1/' || true)}"
if [[ -n "$SIGN_IDENTITY" ]]; then
    echo "Signing with: $SIGN_IDENTITY"
    codesign --force --options runtime --sign "$SIGN_IDENTITY" "$APP"
else
    echo "WARNING: no Apple Development certificate found, using an ad-hoc signature."
    echo "         macOS may block MacDroid from the local network. See README → Troubleshooting."
    codesign --force --sign - "$APP"
fi
echo "Built $APP"

if [[ "${1:-}" == "--install" ]]; then
    pkill -x MacDroid 2>/dev/null || true
    rm -rf /Applications/MacDroid.app
    cp -R "$APP" /Applications/
    open /Applications/MacDroid.app
    echo "Installed and launched /Applications/MacDroid.app"
fi

#!/usr/bin/env bash
# Runs tools/lan_relay.py in the background at login, via launchd, so you don't need a
# Terminal window open. Use this when your Mac's security software blocks MacDroid.app
# from answering the phone directly (see README → Troubleshooting).
#   tools/relay-agent.sh install     # start now and at every login
#   tools/relay-agent.sh uninstall
#   tools/relay-agent.sh status
set -euo pipefail
LABEL="dev.macdroid.relay"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/MacDroid-relay.log"
SRC="$(cd "$(dirname "$0")" && pwd)/lan_relay.py"
DEST_DIR="$HOME/Library/Application Support/MacDroid"

case "${1:-}" in
install)
    PY="$(command -v python3)"
    mkdir -p "$DEST_DIR" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
    cp "$SRC" "$DEST_DIR/lan_relay.py"
    cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$PY</string>
        <string>-u</string>
        <string>$DEST_DIR/lan_relay.py</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardOutPath</key><string>$LOG</string>
    <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
PLIST
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    launchctl bootstrap "gui/$(id -u)" "$PLIST"
    echo "Relay installed with $PY. On the phone, use port 7320. Log: $LOG"
    ;;
uninstall)
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    echo "Relay removed."
    ;;
status)
    launchctl print "gui/$(id -u)/$LABEL" 2>/dev/null | grep -E "state|pid" || echo "Not running."
    tail -5 "$LOG" 2>/dev/null || true
    ;;
*)
    echo "Usage: $0 install|uninstall|status" >&2
    exit 1
    ;;
esac

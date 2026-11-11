# Roadmap

## Phase 0: prove it works ✅
Android notification listener → WebSocket on the LAN → native macOS notification with inline reply.
Confirmed working: a real text message was replied to from the macOS banner. On a managed Mac whose
security software blocks MacDroid from answering on the network, the connection goes through the
loopback relay (`tools/relay-agent.sh`).

## Phase 1: reliable and private ← *next*

- QR-code pairing: the Mac shows a QR code containing its public key, the phone scans it, and both sides use TLS with that pinned key. This replaces the 6-digit code and plain `ws://`.
- Android foreground service, plus `CompanionDeviceManager` so the app survives OEM battery savers and wakes up when the Mac is nearby.
- Queue notifications while disconnected, and send them once reconnected (latest version per key only).
- Re-sync on reconnect, so dismissals made while offline don't leave stale notifications on either side.
- Make the relay part of the product: MacDroid detects that it is blocked from the network (self-test against its own LAN address) and offers to install the relay, and advertises the relay port over Bonjour so the phone needs no manual port.

## Phase 2: full mirror
- App icons and contact avatars (cached by hash), shown as notification attachments. Big-picture images.
- A conversation view in the menu bar built from MessagingStyle history, where you can type replies.
- Per-app allow and block lists, and muting for noisy apps.
- Clicking a notification on the Mac opens the conversation view.

## Phase 3: anywhere
- Tailscale: works away from home with no code changes (the phone connects to the Mac's tailnet IP).
- BLE fallback for text-only notifications and replies without Wi-Fi.
- Optional end-to-end-encrypted relay.

## Phase 4: creative extras
- One-time codes: detect a 2FA code and add a "Copy code" button, or copy it to the clipboard automatically.
- Desk mode: while the Mac is active, the phone stays silent and the Mac takes over.
- Focus sync: a Mac Focus mode and Android Do Not Disturb follow each other.
- Smart replies: AI-drafted replies that use the conversation history.
- Calls: incoming call banner with Decline and Silence buttons.
- Media: control the phone's playback from the Mac.

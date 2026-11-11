# MacDroid wire protocol — v1 (Phase 0)

The Mac runs a WebSocket server; the Android phone connects to it as a client.

| Item            | Value                                   |
|-----------------|-----------------------------------------|
| Transport       | WebSocket, text frames, one JSON object per frame |
| Default port    | `7319`                                  |
| Bonjour type    | `_macdroid._tcp` (the Mac advertises, the phone discovers) |
| Keep-alive      | WebSocket ping/pong every 20 s (sent by the phone) |
| Security        | **Phase 0: plain `ws://` on the LAN plus a 6-digit pairing code.** Phase 1 replaces this with a QR-code key exchange and TLS with a pinned key. |

Every message has a `type` field. Unknown types and unknown fields must be ignored so either side can be upgraded first.

## Handshake

The first frame on every connection is the phone's `hello`. The Mac answers `hello_ok` or `hello_rejected` and then closes. It closes any frame other than `hello` that arrives before authentication.

```jsonc
// phone → mac
{ "type": "hello", "version": 1, "device": "Pixel 8", "code": "482913" }

// mac → phone
{ "type": "hello_ok", "version": 1, "name": "Hamzeh's MacBook Pro" }
{ "type": "hello_rejected", "reason": "bad_code" }
```

Only one phone is active at a time: a new authenticated connection replaces the previous one.

## Phone → Mac

### `notification_posted`
This is sent when a notification is posted or updated. `key` is Android's `StatusBarNotification.key` and is stable across updates, so the Mac replaces the notification that has the same key.

```jsonc
{
  "type": "notification_posted",
  "notification": {
    "key": "0|com.whatsapp|1|xyz|10123",
    "package": "com.whatsapp",
    "appName": "WhatsApp",
    "title": "Alice",                // conversation title or EXTRA_TITLE
    "text": "See you at 7?",         // best single-line body
    "subText": null,
    "conversationId": "com.whatsapp:Alice", // used for grouping/threading
    "isGroup": false,
    "messages": [                    // MessagingStyle history, oldest first (may be empty)
      { "sender": "Alice", "text": "See you at 7?", "time": 1731250000000, "fromSelf": false }
    ],
    "actions": [
      { "id": "w0", "title": "Reply", "isReply": true,  "choices": ["Yes", "On my way"] },
      { "id": "n1", "title": "Mark as read", "isReply": false, "choices": [] }
    ],
    "postedAt": 1731250000000,
    "silent": false                  // true = state update only, don't alert again
  }
}
```

The action `id` is opaque to the Mac. It is echoed back to the phone unchanged. The phone currently uses `n<i>` for `Notification.actions[i]` and `w<i>` for Wear-extender actions, which is where WhatsApp and others put their reply action.

`silent` is `true` when the latest message is the user's own reply (the app re-posting the notification after a reply) or when the app asked for alert-once on an update.

### `app_icon`
This is sent once per package per connection, just before that app's first `notification_posted`. It carries the app's launcher icon as a 128×128 PNG. The Mac caches it on disk and attaches it to that app's notifications.
```jsonc
{ "type": "app_icon", "package": "com.whatsapp", "png": "<base64 PNG>" }
```

### `notification_removed`
```jsonc
{ "type": "notification_removed", "key": "0|com.whatsapp|1|xyz|10123" }
```

### `action_result`
This answers a `reply` or `action` command.
```jsonc
{ "type": "action_result", "requestId": "6F1C…", "ok": false, "error": "notification_gone" }
```
Error codes: `notification_gone`, `action_not_found`, `not_replyable`, `intent_cancelled`, `listener_not_connected`, `internal`.

## Mac → Phone

```jsonc
// Reply through the action's RemoteInput
{ "type": "reply",  "requestId": "6F1C…", "key": "…", "actionId": "w0", "text": "Yes!" }

// Fire a plain action button
{ "type": "action", "requestId": "7A2B…", "key": "…", "actionId": "n1" }

// The user closed the notification on the Mac, so clear it on the phone too
{ "type": "dismiss", "key": "…" }
```

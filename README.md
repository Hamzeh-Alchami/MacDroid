# MacDroid

Mirror your Android notifications to your Mac, and reply to them from the native macOS notification banner.

```
Android (Kotlin)                                   macOS (Swift menu-bar app)
NotificationListenerService ──┐                 ┌── Bonjour (_macdroid._tcp) + WebSocket :7319
  title, text, chat history,  │   JSON over     │   UNUserNotificationCenter
  actions, reply boxes        ├── WebSocket ───►│   native banner + inline "Reply" field
Fills RemoteInput and fires   │◄────────────────┤   action buttons, quick replies,
the app's reply action        ┘  reply/action   └── dismiss sync, menu-bar status
```

The protocol is in [`docs/PROTOCOL.md`](docs/PROTOCOL.md) and the roadmap is in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Status: Phase 0 (proof of concept)

The goal of Phase 0: **a WhatsApp, Telegram or SMS message arrives, you reply from the Mac banner, and the reply shows up in the chat.**

What works in Phase 0:
- Every notification you can clear is mirrored, with app name, title and text. For chat apps, the latest message includes the sender's name in group chats.
- Inline **Reply** works for any app with a reply box (most chat apps). This includes apps that only give the reply action to smartwatches (Wear), such as WhatsApp.
- The app's own **quick replies** (for example "👍" or "On my way") appear as one-click buttons.
- Other **action buttons** are mirrored, for example "Mark as read" or "Archive".
- **Dismiss sync:** clearing a notification on the phone removes it from the Mac. Closing it on the Mac clears it on the phone.
- When an app echoes your reply back as a notification update, the Mac stays quiet.
- The phone finds the Mac automatically on Wi-Fi, and you can enter its address by hand as a fallback.

Known Phase 0 limits, which later phases fix:
- **The connection is not encrypted.** It's plain `ws://` on your local network, protected only by a 6-digit pairing code. Use it on your home Wi-Fi only. Phase 1 adds QR-code pairing and TLS with a pinned key.
- There's no foreground service yet, so aggressive battery savers (Samsung, Xiaomi and others) may stop the connection. If that happens, set MacDroid to *Unrestricted* in battery settings.
- No icons or images yet. Banners show the MacDroid icon, with the source app's name as the subtitle.
- It only works on the same network, unless you use Tailscale (see the roadmap).

## Setup

### 1. Mac
You need macOS 13 or later and Xcode or the Command Line Tools (`xcode-select --install`).

```bash
cd macos
./build.sh --install
```

A phone icon appears in the menu bar. When asked, allow **notifications** and **local network** access. If the firewall asks, allow incoming connections. Open the menu to see your **pairing code**.

Click **Test on Mac** to check the Mac half on its own: hover the banner, choose **Reply**, type something and press Send.

> Tip: under **System Settings → Notifications → MacDroid**, set the alert style to **Alerts** instead of Banners. Alerts stay on screen until you deal with them, which makes replying easier.

### 2. Android
You need Android 8 or later. Either build the APK yourself:

```bash
cd android
./gradlew assembleDebug
```

or use Android Studio (open the `android/` folder). Then install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone:
1. Open MacDroid and tap **Grant notification access**, then turn MacDroid on.
   - On Android 13 or later, a sideloaded app may show *"Restricted setting"*. Go to **Settings → Apps → MacDroid → ⋮ → Allow restricted settings**, then try again.
2. The Mac's address fills in automatically once the phone finds it on Wi-Fi. If it doesn't, type the IP address shown in the Mac menu.
3. Enter the 6-digit pairing code and tap **Connect**. The status should turn 🟢.
4. Tap **Send test notification**. A fake chat appears on the Mac. Reply from the banner and watch your reply appear in the phone's notification. **Mark as read** clears it on both devices.
5. Now try a real message from WhatsApp, Telegram or Messages.

## Phase 0 test checklist
Please report back on each of these:
- [ ] Test notification shows on the Mac
- [ ] Reply from the Mac shows up in the test chat on the phone
- [ ] Quick reply button (👍) works
- [ ] "Mark as read" clears the notification on both sides
- [ ] Real WhatsApp message: reply arrives in the chat
- [ ] Real Telegram, Signal or Messages (SMS) reply
- [ ] Group chat shows "Sender: message"
- [ ] Closing on the Mac clears it on the phone, and the reverse
- [ ] Still connected after the phone has been locked for 30 minutes
- [ ] (Android 15+) Does an SMS one-time code show its digits on the Mac, or is it hidden?

## Repo layout
```
android/   Kotlin app: NotificationListenerService + OkHttp WebSocket client
macos/     Swift menu-bar app: Network.framework WebSocket server + UserNotifications
docs/      Protocol spec and roadmap
```

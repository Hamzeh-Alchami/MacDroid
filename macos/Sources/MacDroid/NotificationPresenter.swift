import Foundation
import UserNotifications

/// Shows phone notifications as native macOS notifications. Each Android action becomes
/// a button, and Android reply boxes become an inline "Reply" text field.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate, ObservableObject {
    @Published private(set) var authorized: Bool?

    private let center = UNUserNotificationCenter.current()
    private let server: MirrorServer
    private var categories: [String: UNNotificationCategory] = [:]
    /// requestId -> human description, used to explain failures.
    private var pendingRequests: [String: String] = [:]

    static let localTestKey = "macdroid-local-test"

    init(server: MirrorServer) {
        self.server = server
        super.init()
        center.delegate = self
        server.onPosted = { [weak self] in self?.present($0) }
        server.onRemoved = { [weak self] key in
            self?.center.removeDeliveredNotifications(withIdentifiers: [key])
        }
        server.onActionResult = { [weak self] in self?.handleResult($0) }
    }

    func requestAuthorization() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, _ in
            DispatchQueue.main.async { self?.authorized = granted }
        }
    }

    // MARK: Presenting

    func present(_ n: MirroredNotification) {
        // Silent updates (e.g. the app echoing your own reply) shouldn't ping you again.
        if n.silent == true { return }

        let content = UNMutableNotificationContent()
        if n.title.isEmpty {
            content.title = n.appName
        } else {
            content.title = n.title
            content.subtitle = n.appName
        }
        content.body = n.text
        content.sound = .default
        content.threadIdentifier = n.conversationId ?? n.packageName
        content.userInfo = ["key": n.key]
        content.categoryIdentifier = registerCategory(for: n.actions)

        let request = UNNotificationRequest(identifier: n.key, content: content, trigger: nil)
        // Categories register asynchronously. Reading them back first makes sure the
        // buttons exist before the notification is shown.
        center.getNotificationCategories { [weak self] _ in
            self?.center.add(request)
        }
    }

    /// Shows a fake notification without a phone, to check the Mac side on its own.
    func presentLocalTest() {
        present(MirroredNotification(
            key: Self.localTestKey + "-\(Int(Date().timeIntervalSince1970))",
            packageName: "dev.macdroid.test",
            appName: "MacDroid",
            title: "Mac-only test",
            text: "Hover and choose Reply. This one stays on the Mac.",
            subText: nil,
            conversationId: "dev.macdroid.test",
            isGroup: false,
            messages: [],
            actions: [.init(id: "w0", title: "Reply", isReply: true, choices: ["👍"])],
            postedAt: Int64(Date().timeIntervalSince1970 * 1000),
            silent: false
        ))
    }

    private func registerCategory(for actions: [MirroredNotification.Action]) -> String {
        let identifier = "mdc:" + actions.map { a in
            "\(a.id)|\(a.title)|\(a.isReply)|\((a.choices ?? []).joined(separator: ","))"
        }.joined(separator: ";")
        if categories[identifier] != nil { return identifier }

        var unActions: [UNNotificationAction] = []
        for a in actions {
            if a.isReply {
                unActions.append(UNTextInputNotificationAction(
                    identifier: "reply:\(a.id)",
                    title: a.title,
                    options: [],
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: "Reply from your Mac…"
                ))
                // The app's suggested quick replies become one-click buttons.
                for choice in (a.choices ?? []).prefix(3) {
                    unActions.append(UNNotificationAction(
                        identifier: "choice:\(a.id):\(choice)", title: "↩︎ \(choice)", options: []
                    ))
                }
            } else {
                unActions.append(UNNotificationAction(identifier: "act:\(a.id)", title: a.title, options: []))
            }
        }
        let category = UNNotificationCategory(
            identifier: identifier,
            actions: unActions,
            intentIdentifiers: [],
            options: [.customDismissAction] // tells us when you close it, so the phone can clear it too
        )
        categories[identifier] = category
        center.setNotificationCategories(Set(categories.values))
        return identifier
    }

    // MARK: UNUserNotificationCenterDelegate

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        let actionId = response.actionIdentifier
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        DispatchQueue.main.async {
            self.handleResponse(content: content, actionId: actionId, typedText: typed)
            completionHandler()
        }
    }

    private func handleResponse(content: UNNotificationContent, actionId: String, typedText: String?) {
        guard let key = content.userInfo["key"] as? String else { return }
        let who = content.title

        if key.hasPrefix(Self.localTestKey) {
            if let text = typedText ?? choiceText(actionId) {
                showStatus(title: "Mac side works ✅", body: "You replied: \(text)")
            }
            return
        }

        switch actionId {
        case UNNotificationDismissActionIdentifier:
            server.sendDismiss(key: key)
        case UNNotificationDefaultActionIdentifier:
            break // clicking the banner does nothing yet; Phase 2 opens a conversation view
        case let id where id.hasPrefix("reply:"):
            guard let text = typedText, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            track(server.sendReply(key: key, actionId: String(id.dropFirst("reply:".count)), text: text),
                  description: "reply to \(who)")
        case let id where id.hasPrefix("choice:"):
            let parts = id.split(separator: ":", maxSplits: 2).map(String.init)
            guard parts.count == 3 else { return }
            track(server.sendReply(key: key, actionId: parts[1], text: parts[2]), description: "reply to \(who)")
        case let id where id.hasPrefix("act:"):
            track(server.sendAction(key: key, actionId: String(id.dropFirst("act:".count))),
                  description: "action on \(who)")
        default:
            break
        }
    }

    private func choiceText(_ actionId: String) -> String? {
        let parts = actionId.split(separator: ":", maxSplits: 2).map(String.init)
        return parts.count == 3 && parts[0] == "choice" ? parts[2] : nil
    }

    private func track(_ requestId: String?, description: String) {
        if let requestId {
            pendingRequests[requestId] = description
        } else {
            showStatus(title: "Couldn't send \(description)", body: "Your phone isn't connected.")
        }
    }

    private func handleResult(_ result: Incoming.ActionResult) {
        let description = pendingRequests.removeValue(forKey: result.requestId) ?? "action"
        guard !result.ok else { return }
        let reason: String
        switch result.error {
        case "notification_gone": reason = "The notification is no longer on your phone."
        case "not_replyable", "action_not_found": reason = "That app no longer accepts this action."
        case "intent_cancelled": reason = "The app cancelled this action."
        case "listener_not_connected": reason = "MacDroid lost notification access on your phone."
        default: reason = "Something went wrong on the phone (\(result.error ?? "unknown"))."
        }
        showStatus(title: "Couldn't send \(description)", body: reason)
    }

    private func showStatus(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}

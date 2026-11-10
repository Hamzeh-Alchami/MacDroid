import AppKit
import SwiftUI

@main
struct MacDroidApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra {
            MenuView(server: appDelegate.server, presenter: appDelegate.presenter)
        } label: {
            MenuBarLabel(server: appDelegate.server)
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    let server: MirrorServer
    let presenter: NotificationPresenter

    override init() {
        server = MirrorServer()
        // Created early so the notification delegate is in place before launch finishes.
        presenter = NotificationPresenter(server: server)
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        presenter.requestAuthorization()
        server.start()
    }
}

struct MenuBarLabel: View {
    @ObservedObject var server: MirrorServer

    var body: some View {
        Image(systemName: server.phoneName == nil ? "iphone.slash" : "iphone.radiowaves.left.and.right")
    }
}

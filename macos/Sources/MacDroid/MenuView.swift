import AppKit
import SwiftUI

struct MenuView: View {
    @ObservedObject var server: MirrorServer
    @ObservedObject var presenter: NotificationPresenter
    @State private var addresses: [String] = LocalAddresses.ipv4()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Circle().fill(statusColor).frame(width: 9, height: 9)
                Text(statusText).font(.headline)
            }

            if presenter.authorized == false {
                Text("Notifications are turned off for MacDroid. Turn them on in System Settings → Notifications → MacDroid.")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }

            GroupBox {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Pairing code").font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Text(server.pairingCode)
                            .font(.system(size: 26, weight: .semibold, design: .monospaced))
                            .textSelection(.enabled)
                        Spacer()
                        Button("New code") { server.regeneratePairingCode() }
                    }
                    Text(addressText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text("Recent").font(.headline)
            if server.recent.isEmpty {
                Text("Nothing yet. Notifications from your phone appear here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(server.recent.prefix(8)) { n in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(n.appName) · \(n.title)").font(.caption).bold().lineLimit(1)
                        Text(n.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
            }

            Divider()
            HStack {
                Button("Test on Mac") { presenter.presentLocalTest() }
                    .help("Shows a fake notification with a Reply field, without the phone")
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 330)
        .onAppear { addresses = LocalAddresses.ipv4() }
    }

    private var statusText: String {
        if let phone = server.phoneName { return "Connected to \(phone)" }
        switch server.status {
        case .starting: return "Starting…"
        case .listening: return "Waiting for your phone"
        case .failed(let message): return "Server error: \(message)"
        }
    }

    private var statusColor: Color {
        if server.phoneName != nil { return .green }
        switch server.status {
        case .listening: return .yellow
        case .starting: return .gray
        case .failed: return .red
        }
    }

    private var addressText: String {
        let port = ProtocolInfo.port
        if addresses.isEmpty { return "No network address found. Is Wi-Fi on?" }
        return "Your phone should find this Mac automatically. If it doesn't, enter "
            + addresses.map { "\($0)" }.joined(separator: " or ")
            + " (port \(port))."
    }
}

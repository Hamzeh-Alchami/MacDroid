import Foundation
import Network

/// WebSocket server the Android phone connects to (docs/PROTOCOL.md).
/// Everything runs on the main queue: the traffic is tiny, and the UI state stays simple.
final class MirrorServer: ObservableObject {
    enum Status: Equatable {
        case starting
        case listening
        case failed(String)
    }

    @Published private(set) var status: Status = .starting
    @Published private(set) var phoneName: String?
    @Published private(set) var recent: [MirroredNotification] = []
    @Published private(set) var pairingCode: String
    /// Recent connection events, newest first, shown in the menu for troubleshooting.
    @Published private(set) var events: [String] = []
    /// True when macOS blocks MacDroid from the local network (Privacy & Security → Local Network).
    @Published private(set) var localNetworkDenied = false

    var onPosted: ((MirroredNotification) -> Void)?
    var onRemoved: ((String) -> Void)?
    var onActionResult: ((Incoming.ActionResult) -> Void)?
    var onAppIcon: ((String, Data) -> Void)?

    let macName = Host.current().localizedName ?? "Mac"

    private var listener: NWListener?
    private var browser: NWBrowser?
    private let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    /// The authenticated phone connection, if any.
    private var active: NWConnection?
    private let decoder = JSONDecoder()

    init() {
        if let saved = UserDefaults.standard.string(forKey: "pairingCode"), saved.count == 6 {
            pairingCode = saved
        } else {
            pairingCode = MirrorServer.makeCode()
            UserDefaults.standard.set(pairingCode, forKey: "pairingCode")
        }
    }

    // MARK: Lifecycle

    func log(_ message: String) {
        NSLog("MacDroid: %@", message)
        events.insert("\(timeFormatter.string(from: Date()))  \(message)", at: 0)
        if events.count > 40 { events.removeLast(events.count - 40) }
    }

    /// Browsing our own Bonjour type makes macOS ask for Local Network permission right
    /// away, and tells us when it's denied (the browser waits with a PolicyDenied error).
    private func checkLocalNetworkAccess() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjour(type: ProtocolInfo.bonjourType, domain: nil), using: .tcp)
        b.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .waiting(let error), .failed(let error):
                if case .dns(let code) = error, code == -65570 { // kDNSServiceErr_PolicyDenied
                    if !self.localNetworkDenied {
                        self.localNetworkDenied = true
                        self.log("Local Network access is OFF for MacDroid")
                    }
                } else {
                    self.log("Bonjour browser: \(error.localizedDescription)")
                }
            case .ready:
                if self.localNetworkDenied { self.log("Local Network access is now on") }
                self.localNetworkDenied = false
            default:
                break
            }
        }
        b.browseResultsChangedHandler = { [weak self] _, _ in
            // Seeing results means local network traffic is allowed.
            if self?.localNetworkDenied == true {
                self?.localNetworkDenied = false
                self?.log("Local Network access is now on")
            }
        }
        b.start(queue: .main)
        browser = b
    }

    func start() {
        checkLocalNetworkAccess()
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 1 << 20

        let params = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        params.allowLocalEndpointReuse = true

        do {
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: ProtocolInfo.port)!)
            l.service = NWListener.Service(type: ProtocolInfo.bonjourType)
            l.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.status = .listening
                    self.log("Listening on port \(ProtocolInfo.port)")
                case .failed(let error):
                    self.status = .failed(error.localizedDescription)
                    self.log("Server failed: \(error.localizedDescription)")
                    self.listener?.cancel()
                    self.listener = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in self?.start() }
                default:
                    break
                }
            }
            l.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            l.start(queue: .main)
            listener = l
        } catch {
            status = .failed(error.localizedDescription)
            log("Could not start server: \(error.localizedDescription)")
        }
    }

    func regeneratePairingCode() {
        pairingCode = MirrorServer.makeCode()
        UserDefaults.standard.set(pairingCode, forKey: "pairingCode")
        active?.cancel() // the phone must pair again with the new code
    }

    private static func makeCode() -> String {
        String(format: "%06d", Int.random(in: 0...999_999))
    }

    // MARK: Commands to the phone

    /// Returns the requestId, or nil if no phone is connected.
    func sendReply(key: String, actionId: String, text: String) -> String? {
        let requestId = UUID().uuidString
        let ok = send(["type": "reply", "requestId": requestId, "key": key, "actionId": actionId, "text": text])
        return ok ? requestId : nil
    }

    func sendAction(key: String, actionId: String) -> String? {
        let requestId = UUID().uuidString
        let ok = send(["type": "action", "requestId": requestId, "key": key, "actionId": actionId])
        return ok ? requestId : nil
    }

    func sendDismiss(key: String) {
        send(["type": "dismiss", "key": key])
    }

    // MARK: Connections

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        let peer = MirrorServer.describe(connection.endpoint)
        log("Incoming connection from \(peer)")
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.log("WebSocket open with \(peer), waiting for pairing code")
            case .waiting(let error):
                self.log("Connection from \(peer) waiting: \(error.localizedDescription)")
            case .failed(let error):
                self.log("Connection from \(peer) failed: \(error.localizedDescription)")
                self.closed(connection)
            case .cancelled:
                self.closed(connection)
            default:
                break
            }
        }
        connection.start(queue: .main)
        receive(on: connection)

        // Drop connections that don't complete the handshake quickly.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self, weak connection] in
            guard let self, let connection else { return }
            if self.active !== connection && self.connections[ObjectIdentifier(connection)] != nil {
                if case .ready = connection.state {
                    self.log("\(peer) never sent its pairing code, closing")
                } else {
                    self.log("WebSocket handshake with \(peer) didn't finish in 10s. Check Local Network permission.")
                }
                connection.cancel()
            }
        }
    }

    private func closed(_ connection: NWConnection) {
        guard connections.removeValue(forKey: ObjectIdentifier(connection)) != nil else { return }
        if active === connection {
            log("Phone disconnected")
            active = nil
            phoneName = nil
        }
    }

    private func receive(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self else { return }
            if error != nil {
                connection.cancel()
                return
            }
            let meta = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata
            if meta?.opcode == .close {
                connection.cancel()
                return
            }
            if let data, !data.isEmpty, meta?.opcode == .text || meta?.opcode == .binary {
                self.handle(data, from: connection)
            }
            if context?.isFinal == true && (data?.isEmpty ?? true) {
                connection.cancel()
                return
            }
            if self.connections[ObjectIdentifier(connection)] != nil {
                self.receive(on: connection)
            }
        }
    }

    private func handle(_ data: Data, from connection: NWConnection) {
        guard let envelope = try? decoder.decode(Incoming.Envelope.self, from: data) else { return }

        // Handshake: the first message must be a hello with the right pairing code.
        if active !== connection {
            guard envelope.type == "hello",
                  let hello = try? decoder.decode(Incoming.Hello.self, from: data) else {
                connection.cancel()
                return
            }
            if hello.code == pairingCode {
                log("Paired with \(hello.device ?? "phone")")
                if let old = active { old.cancel() }
                active = connection
                phoneName = hello.device ?? "Android phone"
                send(["type": "hello_ok", "version": ProtocolInfo.version, "name": macName], to: connection)
            } else {
                log("Wrong pairing code from \(hello.device ?? "phone")")
                send(["type": "hello_rejected", "reason": "bad_code"], to: connection) {
                    connection.cancel()
                }
            }
            return
        }

        switch envelope.type {
        case "notification_posted":
            guard let posted = try? decoder.decode(Incoming.Posted.self, from: data) else { return }
            let n = posted.notification
            recent.removeAll { $0.key == n.key }
            recent.insert(n, at: 0)
            if recent.count > 30 { recent.removeLast(recent.count - 30) }
            onPosted?(n)
        case "notification_removed":
            guard let removed = try? decoder.decode(Incoming.Removed.self, from: data) else { return }
            recent.removeAll { $0.key == removed.key }
            onRemoved?(removed.key)
        case "app_icon":
            guard let icon = try? decoder.decode(Incoming.AppIcon.self, from: data),
                  let png = Data(base64Encoded: icon.png) else { return }
            onAppIcon?(icon.packageName, png)
        case "action_result":
            guard let result = try? decoder.decode(Incoming.ActionResult.self, from: data) else { return }
            onActionResult?(result)
        default:
            break // forward compatible: ignore unknown types
        }
    }

    private static func describe(_ endpoint: NWEndpoint) -> String {
        if case .hostPort(let host, _) = endpoint {
            return "\(host)".components(separatedBy: "%").first ?? "\(host)"
        }
        return "\(endpoint)"
    }

    @discardableResult
    private func send(_ object: [String: Any], to target: NWConnection? = nil, completion: (() -> Void)? = nil) -> Bool {
        guard let connection = target ?? active,
              let data = try? JSONSerialization.data(withJSONObject: object) else { return false }
        let meta = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "macdroid", metadata: [meta])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in
            completion?()
        })
        return true
    }
}

/// IPv4 addresses of this Mac's network interfaces, for manual setup on the phone.
enum LocalAddresses {
    static func ipv4() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let iface = ptr.pointee
            guard let addr = iface.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: iface.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("utun") || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if !ip.hasPrefix("169.254.") { result.append(ip) }
            }
        }
        return result
    }
}

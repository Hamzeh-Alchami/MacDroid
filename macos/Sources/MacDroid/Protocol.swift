import Foundation

// Wire types for docs/PROTOCOL.md (v1). Unknown fields are ignored by Codable.

struct MirroredNotification: Codable, Identifiable, Equatable {
    struct Message: Codable, Equatable {
        let sender: String
        let text: String
        let time: Int64
        let fromSelf: Bool
    }

    struct Action: Codable, Equatable {
        let id: String
        let title: String
        let isReply: Bool
        let choices: [String]?
    }

    let key: String
    let packageName: String
    let appName: String
    let title: String
    let text: String
    let subText: String?
    let conversationId: String?
    let isGroup: Bool?
    let messages: [Message]?
    let actions: [Action]
    let postedAt: Int64
    let silent: Bool?

    var id: String { key }

    enum CodingKeys: String, CodingKey {
        case key, appName, title, text, subText, conversationId, isGroup, messages, actions, postedAt, silent
        case packageName = "package"
    }
}

enum Incoming {
    struct Envelope: Decodable { let type: String }
    struct Hello: Decodable { let version: Int?; let device: String?; let code: String? }
    struct Posted: Decodable { let notification: MirroredNotification }
    struct Removed: Decodable { let key: String }
    struct ActionResult: Decodable { let requestId: String; let ok: Bool; let error: String? }
}

enum ProtocolInfo {
    static let version = 1
    static let port: UInt16 = 7319
    static let bonjourType = "_macdroid._tcp"
}

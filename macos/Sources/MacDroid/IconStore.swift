import Foundation
import UserNotifications

/// Android app icons received from the phone, cached on disk by package name.
enum IconStore {
    private static let directory: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("MacDroid/icons", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static func url(for package: String) -> URL {
        let safe = package.replacingOccurrences(of: "/", with: "_")
        return directory.appendingPathComponent("\(safe).png")
    }

    static func save(_ png: Data, for package: String) {
        try? png.write(to: url(for: package), options: .atomic)
    }

    /// A fresh attachment for each notification. macOS moves attachment files into its
    /// own store, so we hand it a temporary copy rather than the cached icon itself.
    static func attachment(for package: String) -> UNNotificationAttachment? {
        let source = url(for: package)
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("macdroid-icon-\(UUID().uuidString).png")
        do {
            try FileManager.default.copyItem(at: source, to: temp)
            return try UNNotificationAttachment(identifier: "app-icon", url: temp, options: nil)
        } catch {
            return nil
        }
    }
}

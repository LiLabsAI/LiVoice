import UIKit
import Foundation
import WoVoiceCore

/// Separate command and status files prevent cross-process read-modify-write losses.
/// Tokens, editor snapshots, history and audio never enter the App Group.
struct KeyboardCommand: Codable {
    enum Action: String, Codable { case start, finish, retry, cancel, acknowledge, suggest, endSession }
    let id: UUID
    let request: UUID
    let owner: String
    let action: Action
    let createdAt: Date
    let context: DictationContext?
    let sentenceStart: Bool
    var suggestion: String? = nil
    var recordID: String? = nil
}
struct KeyboardStatus: Codable {
    var heartbeat = Date.now
    var expiresAt = Date.distantPast
    var owner: String?
    var request: UUID?
    var phase = "unavailable"
    var message = "Open LiVoice to start a keyboard session."
    var result: String?
    var resultExpiresAt: Date?
    var preferences = ContextPreferences()
    var level: Double = 0
    var elapsed: Double = 0
    var haptics = true
    var animate = true
    var waveform = true
    var learning = true
    var canRetry = false
    var recordID: String?
    var available: Bool { owner != nil && expiresAt > .now && Date.now.timeIntervalSince(heartbeat) < 4 }
}
struct KeyboardLease: Codable {
    let id: UUID
    let request: UUID
    let owner: String
    let heartbeat: Date
}
struct KeyboardBridge {
    private let container: URL?
    init() {
        let group = Bundle.main.object(forInfoDictionaryKey: "WoVoiceAppGroup") as? String ?? "group.com.aliahad.wovoice.ios"
        container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
        if var container {
            var resources = URLResourceValues(); resources.isExcludedFromBackup = true
            try? container.setResourceValues(resources)
        }
    }
    var isAvailable: Bool { container != nil }
    func read<T: Decodable>(_ name: String, as: T.Type) -> T? {
        guard let url = container?.appendingPathComponent(name), let data = try? Data(contentsOf: url), data.count < 16384 else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
    func write<T: Encodable>(_ value: T, name: String) throws {
        guard let url = container?.appendingPathComponent(name) else { throw APIError(code: "APP_GROUP_UNAVAILABLE", message: "The shared keyboard container is unavailable. Check signing and App Group setup.") }
        try JSONEncoder().encode(value).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func remove(_ name: String) { if let url = container?.appendingPathComponent(name) { try? FileManager.default.removeItem(at: url) } }
    func renewLease(request: UUID, owner: String) throws {
        try write(KeyboardLease(id: UUID(), request: request, owner: owner, heartbeat: .now), name: leaseName(request))
    }
    func lease(request: UUID) -> KeyboardLease? { read(leaseName(request), as: KeyboardLease.self) }
    func removeLease(request: UUID) { remove(leaseName(request)) }
    private func leaseName(_ request: UUID) -> String { "lease-\(request.uuidString).json" }
    func clearLeases() {
        guard let container, let files = try? FileManager.default.contentsOfDirectory(at: container, includingPropertiesForKeys: nil) else { return }
        for file in files where file.lastPathComponent.hasPrefix("lease-") && file.pathExtension == "json" { try? FileManager.default.removeItem(at: file) }
    }
    func enqueue(_ command: KeyboardCommand) throws {
        guard let directory = container?.appendingPathComponent("commands", isDirectory: true) else { throw APIError(code: "APP_GROUP_UNAVAILABLE", message: "The shared keyboard container is unavailable.") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        guard files.count < 64 else { throw APIError(code: "KEYBOARD_BUSY", message: "Open LiVoice to reconnect your keyboard session.") }
        try JSONEncoder().encode(command).write(to: directory.appendingPathComponent(command.id.uuidString + ".json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func drainCommands() -> [KeyboardCommand] {
        guard let directory = container?.appendingPathComponent("commands", isDirectory: true),
              let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return [] }
        var commands: [KeyboardCommand] = []
        for file in files.prefix(64) where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file), data.count <= 16384, let command = try? JSONDecoder().decode(KeyboardCommand.self, from: data) { commands.append(command) }
            try? FileManager.default.removeItem(at: file)
        }
        return commands.sorted { $0.createdAt == $1.createdAt ? $0.id.uuidString < $1.id.uuidString : $0.createdAt < $1.createdAt }
    }
}

/// UIKit can return nil while detaching an editor despite the nonnull Swift
/// declaration. KVC reads the public Objective-C property without forcing its
/// UUID bridge; an unavailable identity must never be used for insertion.
enum EditorIdentity {
    static func document(from object: NSObject) -> UUID? {
        guard object.responds(to: #selector(getter: UITextDocumentProxy.documentIdentifier)) else { return nil }
        return object.value(forKey: #keyPath(UITextDocumentProxy.documentIdentifier)) as? UUID
    }
}

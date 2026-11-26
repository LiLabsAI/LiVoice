import Foundation

public enum JSONValue: Codable, Sendable, Equatable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    public var string: String? { if case .string(let v) = self { v } else { nil } }
    public var number: Double? { if case .number(let v) = self { v } else { nil } }
    public var bool: Bool? { if case .bool(let v) = self { v } else { nil } }
}
public typealias Payload = [String: JSONValue]

public struct AccountStatus: Codable, Sendable {
    public var state: String
    public var suspendedUntil: Double?
    public var publicMessage: String?
    public let supportEmail: String
}
public struct User: Codable, Sendable, Identifiable {
    public let id: String
    public let email: String
    public let role: String
    public var accountStatus: AccountStatus
}
public struct Tokens: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let accessExpiresIn: Double
    public let refreshExpiresIn: Double
    public var user: User
}
public struct StoredSession: Codable, Sendable {
    public var tokens: Tokens
    public var accessExpiresAt: Date
    public init(tokens: Tokens, now: Date = .now) {
        self.tokens = tokens
        accessExpiresAt = now.addingTimeInterval(tokens.accessExpiresIn)
    }
}
public struct Quota: Codable, Sendable {
    public let limitAudioSeconds: Double
    public let remainingAudioSeconds: Double
    public let resetAt: Double
}
public struct Profile: Codable, Sendable {
    public let user: User
    public let quota: Quota?
}
public struct DeviceSession: Codable, Sendable, Identifiable {
    public let id: String
    public let deviceName: String
    public let createdAt: Double
    public let lastSeenAt: Double
    public let current: Int
}
public struct Transcription: Codable, Sendable {
    public let text: String
    public let rawText: String
    public let polished: Bool
    public let asrModel: String
    public let requestId: String
    public let timingsMs: Timings
    public let usage: Payload?
    public struct Timings: Codable, Sendable { public let asr: Double; public let polish: Double; public let total: Double }
}
public enum RecordType: String, Codable, Sendable, CaseIterable { case history, dictionary, analytics }
public struct CloudRecord: Codable, Sendable, Identifiable, Equatable {
    public var id: String
    public var type: RecordType
    public var version: Int
    public var deleted: Bool
    public var payload: Payload?
    public init(id: String, type: RecordType, version: Int = 0, deleted: Bool = false, payload: Payload?) {
        self.id = id; self.type = type; self.version = version; self.deleted = deleted; self.payload = payload
    }
}
public struct CloudSettings: Codable, Sendable, Equatable {
    public var historySyncEnabled: Bool
    public var historyRetentionDays: Int?
    public var legacyVault: Bool
    public init(historySyncEnabled: Bool = true, historyRetentionDays: Int? = nil, legacyVault: Bool = false) {
        self.historySyncEnabled = historySyncEnabled; self.historyRetentionDays = historyRetentionDays; self.legacyVault = legacyVault
    }
}
public struct CloudPage: Codable, Sendable {
    public let nextCursor: Int
    public let hasMore: Bool
    public let items: [CloudRecord]
    public let settings: CloudSettings?
}
public struct CloudWrite: Codable, Sendable {
    public let id: String
    public let type: RecordType
    public let baseVersion: Int
    public let deleted: Bool
    public let payload: Payload?
    public init(record: CloudRecord) {
        id = record.id; type = record.type; baseVersion = record.version; deleted = record.deleted; payload = record.payload
    }
}
public struct PushResult: Codable, Sendable {
    public let applied: [Applied]
    public struct Applied: Codable, Sendable { public let id: String; public let type: RecordType; public let version: Int }
}
public struct APIError: Error, LocalizedError, Sendable {
    public let code: String
    public let message: String
    public let retryable: Bool
    public let status: Int
    public let conflicts: [CloudRecord]
    public var errorDescription: String? { message }
    public init(code: String, message: String, retryable: Bool = false, status: Int = 0, conflicts: [CloudRecord] = []) {
        self.code = code; self.message = message; self.retryable = retryable; self.status = status; self.conflicts = conflicts
    }
}

public enum RecordPayload {
    public static func history(_ result: Transcription, durationMs: Double, now: Date = .now) -> Payload {
        var p: Payload = [
            "schemaVersion": .number(1), "text": .string(result.text), "createdAtMs": .number(now.timeIntervalSince1970 * 1000),
            "zoneId": .string(TimeZone.current.identifier), "offsetSeconds": .number(Double(TimeZone.current.secondsFromGMT(for: now))),
            "wordCount": .number(Double(result.text.split(whereSeparator: \.isWhitespace).count)), "audioDurationMs": .number(durationMs),
            "asrModel": .string(result.asrModel), "polished": .bool(result.polished),
            "asrMs": .number(result.timingsMs.asr), "polishMs": .number(result.timingsMs.polish), "totalMs": .number(result.timingsMs.total)
        ]
        if let usage = result.usage { for (key, value) in usage { p[key] = value } }
        return p
    }
    public static func cleanDictionaryTerm(_ raw: String) -> (term: String, normalized: String)? {
        guard !raw.contains("\n"), !raw.contains("\r") else { return nil }
        let collapsed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        guard (2...80).contains(collapsed.utf16.count) else { return nil }
        let normalized = collapsed.precomposedStringWithCompatibilityMapping.lowercased()
        return (collapsed, normalized)
    }
    public static func dictionary(_ term: String, status: String = "confirmed", source: String = "manual", now: Date = .now) -> Payload {
        let cleaned = cleanDictionaryTerm(term)
        let display = cleaned?.term ?? term.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = cleaned?.normalized ?? display.precomposedStringWithCompatibilityMapping.lowercased()
        return ["schemaVersion": .number(1), "term": .string(display), "normalizedTerm": .string(normalized),
         "status": .string(status), "source": .string(source), "createdAtMs": .number(now.timeIntervalSince1970 * 1000),
         "lastUsedAtMs": .number(0), "useCount": .number(0)]
    }
    public static func analytics(history: Payload) -> Payload {
        var p = history
        p.removeValue(forKey: "text"); p.removeValue(forKey: "offsetSeconds"); p.removeValue(forKey: "asrModel")
        p["processingMs"] = history["totalMs"] ?? .number(0)
        p["corrected"] = .bool(false)
        return p
    }
}

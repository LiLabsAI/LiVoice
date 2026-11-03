import Foundation

public enum ContextLevel: String, Codable, Sendable, CaseIterable { case off, app, nearby }
public enum ContextStyle: String, Codable, Sendable, CaseIterable { case auto, natural, polite, professional, concise }
public enum FieldKind: String, Codable, Sendable { case text, search, literal, code }
public struct ContextPreferences: Codable, Sendable {
    public var level: ContextLevel
    public var style: ContextStyle
    public init(level: ContextLevel = .off, style: ContextStyle = .auto) { self.level = level; self.style = style }
}
public struct DictationContext: Codable, Sendable, Equatable {
    public var version = 1
    public var level: ContextLevel
    public var purpose: String
    public var audience = "unknown"
    public var source = "unknown"
    public var style: ContextStyle
    public var before: String?
    public var after: String?

    public static func capture(preferences: ContextPreferences, field: FieldKind, before: String?, after: String?) -> Self? {
        guard preferences.level != .off else { return nil }
        // iOS does not expose a host app identifier or field label to keyboards.
        var result = Self(level: preferences.level, purpose: field == .text ? "unknown" : field.rawValue,
                          source: field == .text ? "unknown" : "field", style: preferences.style)
        guard preferences.level == .nearby, field == .text else { return result }
        let left = bounded(before ?? "", utf16: 512, utf8: 1536, tail: true)
        let right = bounded(after ?? "", utf16: 128, utf8: 2048 - left.utf8.count, tail: false)
        result.before = left.isEmpty ? nil : left
        result.after = right.isEmpty ? nil : right
        if let range = left.range(of: #"(?:^|\n)\s*(?:dear|hi|hello|hey)\s+(professor|prof\.?|mom|mum|dad|team|customer|client)\b"#, options: [.regularExpression, .caseInsensitive]) {
            let greeting = String(left[range]).lowercased()
            result.audience = greeting.contains("prof") ? "academic" : greeting.contains("team") ? "colleague" : greeting.contains("customer") || greeting.contains("client") ? "customer" : "family"
            result.source = "nearby"
        }
        return result
    }
    public func restricted(to level: ContextLevel) -> Self? {
        guard level != .off else { return nil }
        if level == .nearby { return self }
        var copy = self; copy.level = .app; copy.before = nil; copy.after = nil; copy.audience = "unknown"
        if copy.source == "nearby" { copy.source = "unknown" }
        return copy
    }
    private static func bounded(_ text: String, utf16: Int, utf8: Int, tail: Bool) -> String {
        let clean = text.replacingOccurrences(of: "\0", with: "")
        var scalars: [Unicode.Scalar] = []
        var units = 0; var bytes = 0
        let source = Array(clean.unicodeScalars)
        for scalar in tail ? source.reversed() : source {
            let s = String(scalar)
            guard units + s.utf16.count <= utf16, bytes + s.utf8.count <= utf8 else { break }
            scalars.append(scalar); units += s.utf16.count; bytes += s.utf8.count
        }
        return String(String.UnicodeScalarView(tail ? scalars.reversed() : scalars))
    }
}

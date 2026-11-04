import Foundation

/// A keyboard may be recreated while the containing app still has an older
/// request. Tolerate the initial IPC delay, then release an ended or unaccepted
/// request so the next Speak tap can start a fresh dictation.
public struct PendingDictation: Sendable {
    public private(set) var id: UUID?
    private var accepted = false
    private var deadline: ContinuousClock.Instant?
    public init() {}
    public var awaitingAcknowledgement: Bool { id != nil && !accepted }
    public mutating func begin(id: UUID, now: ContinuousClock.Instant = .now) {
        self.id = id; accepted = false; deadline = now.advanced(by: .seconds(3))
    }
    public mutating func reset() { id = nil; accepted = false; deadline = nil }
    public mutating func shouldAbandon(parentRequest: UUID?, now: ContinuousClock.Instant = .now) -> Bool {
        guard let id else { return false }
        if parentRequest == id { accepted = true; return false }
        return accepted || deadline.map { now >= $0 } == true
    }
}

public enum TextPolicy {
    public static func sentenceStart(before: String?) -> Bool {
        guard let last = before?.replacingOccurrences(of: "[ \\t\\u00a0]+$", with: "", options: .regularExpression).last else { return true }
        return ".!?\n".contains(last)
    }
    public static func spaced(_ transcript: String, before: String?, after: String? = nil, selected: String? = nil) -> String {
        var text = transcript.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
        guard let first = text.first, selected?.isEmpty != false else { return text }
        if let last = before?.last, !last.isWhitespace, !"([{/\n".contains(last), !".,!?;:%)]}\n".contains(first) { text = " " + text }
        if let next = after?.first, !next.isWhitespace, !".,!?;:%)]}".contains(next), let last = text.last, !last.isWhitespace, !"([{/’'-".contains(last) { text += " " }
        return text
    }
}

/// Snapshot stays in the keyboard's memory. Only opted-in, bounded snippets may leave the device.
public struct EditorSnapshot: Equatable, Sendable {
    public let document: UUID
    public let before: String?
    public let after: String?
    public let selected: String?
    public init(document: UUID, before: String?, after: String?, selected: String?, hasText: Bool? = nil) {
        // UIKit can return nil contexts for a genuinely empty field. An
        // explicit public hasText == false distinguishes it from hidden text.
        self.document = document
        self.before = before ?? (hasText == false ? "" : nil)
        self.after = after ?? (hasText == false ? "" : nil)
        self.selected = selected
    }
    public var exposesContext: Bool { before != nil || after != nil || selected != nil }
    public func matches(_ other: EditorSnapshot) -> Bool { exposesContext && self == other }
    /// insertText has no success result. Confirm only when the same editor
    /// exposes the complete inserted text at the cursor; never retry blindly.
    public func confirmsInsertion(_ text: String, since original: EditorSnapshot) -> Bool {
        guard document == original.document, !text.isEmpty, selected?.isEmpty != false,
              let before, before.hasSuffix(text),
              before != original.before || original.selected?.isEmpty == false,
              ((original.before ?? "") + text).hasSuffix(before) else { return false }
        if let originalAfter = original.after, (after ?? "") != originalAfter { return false }
        return true
    }
}

/// The proxy can optimistically report an insertion that the host later rejects.
/// Keep checking separate from requesting insertion, and never retry on doubt.
public struct InsertionVerification: Sendable {
    public enum Outcome: Sendable { case waiting, confirmed, unconfirmed }
    private let original: EditorSnapshot
    private let text: String
    private let settlesAt: ContinuousClock.Instant
    public init(original: EditorSnapshot, text: String, now: ContinuousClock.Instant = .now) {
        self.original = original; self.text = text; settlesAt = now.advanced(by: .seconds(1))
    }
    public func outcome(in current: EditorSnapshot?, now: ContinuousClock.Instant = .now) -> Outcome {
        guard now >= settlesAt else { return .waiting }
        return current?.confirmsInsertion(text, since: original) == true ? .confirmed : .unconfirmed
    }
}

public struct SpeechSignal: Sendable {
    private var samples = 0
    private var activeSamples = 0
    private var peakRms: Double = 0
    public init() {}
    @discardableResult public mutating func observe(_ frame: [Int16]) -> Double {
        guard !frame.isEmpty else { return 0 }
        let rms = sqrt(frame.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(frame.count)) / 32767
        samples += frame.count
        if rms >= 0.0015 { activeSamples += frame.count }
        peakRms = max(peakRms, rms)
        return min(1, rms)
    }
    public var containsSpeech: Bool { samples >= 4000 && activeSamples >= 1920 && peakRms >= 0.0025 }
}

public enum WAV {
    public static func encode(_ samples: [Int16]) -> Data {
        var d = Data("RIFF".utf8)
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        u32(UInt32(samples.count * 2 + 36)); d.append(contentsOf: "WAVEfmt ".utf8)
        u32(16); u16(1); u16(1); u32(16000); u32(32000); u16(2); u16(16)
        d.append(contentsOf: "data".utf8); u32(UInt32(samples.count * 2))
        for sample in samples { u16(UInt16(bitPattern: sample)) }
        return d
    }
}

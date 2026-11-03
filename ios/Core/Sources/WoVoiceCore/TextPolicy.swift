import Foundation

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
    public init(document: UUID, before: String?, after: String?, selected: String?) {
        self.document = document; self.before = before; self.after = after; self.selected = selected
    }
    public var exposesContext: Bool { before != nil || after != nil || selected != nil }
    public func matches(_ other: EditorSnapshot) -> Bool { exposesContext && self == other }
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

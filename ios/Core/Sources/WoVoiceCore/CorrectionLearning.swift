import Foundation

public enum CorrectionLearning {
    private static let rejected: Set<String> = Set("a an and are as at be but by for from had has have he her his i in is it me my not of on or our she that the their there they this to was we were will with you your".split(separator: " ").map(String.init))
    public static func suggestion(generated: String, before: String) -> String? {
        let original = tokens(generated)
        guard !original.isEmpty, original.count <= 60 else { return nil }
        let current = Array(tokens(before).suffix(original.count + 2))
        var best: (term: String, unchanged: Int)?
        for count in max(1, original.count - 1)...(original.count + 1) where current.count >= count {
            let window = Array(current.suffix(count))
            var prefix = 0
            while prefix < min(original.count, window.count), original[prefix].lowercased() == window[prefix].lowercased() { prefix += 1 }
            var suffix = 0
            while suffix < original.count-prefix, suffix < window.count-prefix,
                  original[original.count-1-suffix].lowercased() == window[window.count-1-suffix].lowercased() { suffix += 1 }
            let changedOriginal = original.count-prefix-suffix
            let changedCurrent = window.count-prefix-suffix
            guard changedCurrent == 1, (1...2).contains(changedOriginal), original.count <= 2 || prefix+suffix >= original.count-2 else { continue }
            let term = window[prefix]
            if changedOriginal == 1 && original[prefix].lowercased() == term.lowercased() { continue }
            if best == nil || prefix+suffix > best!.unchanged { best = (term, prefix+suffix) }
        }
        guard let term = best?.term, (2...80).contains(term.utf16.count), !rejected.contains(term.lowercased()), !term.allSatisfy(\.isNumber) else { return nil }
        return term
    }
    private static func tokens(_ text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: #"[\p{L}\p{N}]+(?:['’‐-―-][\p{L}\p{N}]+)*"#)
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).compactMap { Range($0.range, in: text).map { String(text[$0]) } }
    }
}

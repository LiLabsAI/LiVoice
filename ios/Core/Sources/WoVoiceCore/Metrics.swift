import Foundation

public enum AnalyticsPeriod: Int, CaseIterable, Sendable {
    case today, week, month, all
    public var title: String { switch self { case .today: "Today"; case .week: "7 days"; case .month: "30 days"; case .all: "All time" } }
    public func since(now: Date = .now, calendar: Calendar = .current) -> Double {
        let start = calendar.startOfDay(for: now)
        let days = self == .week ? -6 : self == .month ? -29 : 0
        return self == .all ? 0 : (calendar.date(byAdding: .day, value: days, to: start) ?? start).timeIntervalSince1970 * 1000
    }
}
public struct Metrics: Sendable {
    public let dictations: Int
    public let words: Int
    public let audioMs: Double
    public let medianProcessingMs: Double
    public let polishedPercent: Int
    public let correctedPercent: Int
    public let cost: Double
    public let asrNeurons: Double
    public let polishNeurons: Double
    public var wpm: Int { audioMs > 0 ? Int(Double(words) * 60000/audioMs) : 0 }
    public init(records: [CloudRecord], since: Double) {
        let values = records.filter { ($0.payload?["createdAtMs"]?.number ?? 0) >= since }.compactMap(\.payload)
        dictations = values.count
        words = Int(values.reduce(0) { $0 + ($1["wordCount"]?.number ?? 0) })
        audioMs = values.reduce(0) { $0 + ($1["audioDurationMs"]?.number ?? 0) }
        let processing = values.compactMap { $0["processingMs"]?.number }.sorted()
        medianProcessingMs = processing.isEmpty ? 0 : processing.count % 2 == 1 ? processing[processing.count/2] : (processing[processing.count/2-1] + processing[processing.count/2])/2
        polishedPercent = values.isEmpty ? 0 : values.filter { $0["polished"]?.bool == true }.count * 100/values.count
        correctedPercent = values.isEmpty ? 0 : values.filter { $0["corrected"]?.bool == true }.count * 100/values.count
        cost = values.reduce(0) { $0 + ($1["estimatedCostUsd"]?.number ?? 0) }
        asrNeurons = values.reduce(0) { $0 + ($1["asrNeurons"]?.number ?? 0) }
        polishNeurons = values.reduce(0) { $0 + ($1["polishNeurons"]?.number ?? 0) }
    }
}

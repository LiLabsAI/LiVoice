import SwiftUI
import UIKit
import WoVoiceCore

struct HistoryDetailView: View {
    @Bindable var model: AppModel
    let recordID: String
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    // Resolve the current account's live record so a sync deletion cannot leave
    // a stale transcript visible in an already-open detail screen.
    private var record: CloudRecord? { model.history.first { $0.id == recordID } }

    var body: some View {
        Group {
            if let record, let payload = record.payload {
                let text = payload["text"]?.string ?? ""
                List {
                    Section("Dictated text") {
                        Text(text).textSelection(.enabled)
                    }
                    Section("Details") {
                        if let date = recordedDate(payload) { LabeledContent("Date", value: date) }
                        LabeledContent("Words", value: (payload["wordCount"]?.number ?? Double(text.split(whereSeparator: \.isWhitespace).count)).formatted(.number.precision(.fractionLength(0))))
                        LabeledContent("Audio duration", value: duration(payload["audioDurationMs"]?.number))
                        LabeledContent("Recognition model", value: payload["asrModel"]?.string ?? "Not recorded")
                        LabeledContent("Processing time", value: duration(payload["totalMs"]?.number))
                        if let polished = payload["polished"]?.bool {
                            LabeledContent("Result", value: polished ? "Polished" : "Transcribed")
                        }
                    }
                    Section {
                        Button(copied ? "Copied" : "Copy text", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = text
                            copied = true
                        }
                        Button(role: .destructive) {
                            model.delete(record)
                            dismiss()
                        } label: { Label("Delete dictation", systemImage: "trash") }
                    }
                }
            } else {
                ContentUnavailableView("Dictation removed", systemImage: "clock", description: Text("This dictation is no longer in your history."))
            }
        }
        .navigationTitle("Dictation")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: record?.payload?["text"]?.string) { copied = false }
    }

    private func duration(_ milliseconds: Double?) -> String {
        guard let milliseconds, milliseconds.isFinite, milliseconds >= 0 else { return "Not recorded" }
        if milliseconds < 1000 { return "\(milliseconds.formatted(.number.precision(.fractionLength(0)))) ms" }
        return "\((milliseconds / 1000).formatted(.number.precision(.fractionLength(0...1)))) sec"
    }

    private func recordedDate(_ payload: Payload) -> String? {
        guard let milliseconds = payload["createdAtMs"]?.number, milliseconds.isFinite else { return nil }
        let date = Date(timeIntervalSince1970: milliseconds / 1000)
        let zone = payload["zoneId"]?.string.flatMap { TimeZone(identifier: $0) }
            ?? payload["offsetSeconds"]?.number.flatMap { Int(exactly: $0) }.flatMap { TimeZone(secondsFromGMT: $0) }
            ?? .current
        var style = Date.FormatStyle(date: .abbreviated, time: .shortened)
        style.timeZone = zone
        return "\(date.formatted(style)) · \(zone.abbreviation(for: date) ?? zone.identifier)"
    }
}

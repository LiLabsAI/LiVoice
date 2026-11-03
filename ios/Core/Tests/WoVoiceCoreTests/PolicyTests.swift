import Foundation
import Testing
@testable import WoVoiceCore

@Test func keyboardRequestRecoversFromLostAcknowledgementAndParentCancellation() {
    let now = ContinuousClock.now; let id = UUID()
    var pending = PendingDictation()
    pending.begin(id: id, now: now)
    let awaiting = pending.shouldAbandon(parentRequest: nil, now: now.advanced(by: .milliseconds(200)))
    #expect(!awaiting)
    #expect(pending.awaitingAcknowledgement)
    let acknowledged = pending.shouldAbandon(parentRequest: id, now: now.advanced(by: .milliseconds(400)))
    #expect(!acknowledged)
    #expect(!pending.awaitingAcknowledgement)
    let cancelledByParent = pending.shouldAbandon(parentRequest: nil, now: now.advanced(by: .seconds(1)))
    #expect(cancelledByParent)
    pending.reset()
    let next = UUID(); pending.begin(id: next, now: now)
    let neverAccepted = pending.shouldAbandon(parentRequest: id, now: now.advanced(by: .seconds(4)))
    #expect(neverAccepted)
    pending.reset()
    let lateResult = pending.shouldAbandon(parentRequest: id)
    #expect(pending.id == nil && !lateResult)
}

@Test func contextConsentAndUnicodeBudget() throws {
    #expect(DictationContext.capture(preferences: .init(), field: .text, before: "Dear Professor", after: nil) == nil)
    let c = try #require(DictationContext.capture(preferences: .init(level: .nearby), field: .text,
                                                 before: String(repeating: "😀বাংলা", count: 200), after: String(repeating: "🌏", count: 100)))
    #expect((c.before?.utf16.count ?? 0) <= 512)
    #expect((c.after?.utf16.count ?? 0) <= 128)
    #expect((c.before?.utf8.count ?? 0) + (c.after?.utf8.count ?? 0) <= 2048)
    let app = try #require(c.restricted(to: .app))
    #expect(app.before == nil && app.after == nil && app.audience == "unknown")
    #expect(c.restricted(to: .off) == nil)
}
@Test func conservativeAudienceAndLiteralFields() throws {
    let prefs = ContextPreferences(level: .nearby)
    #expect(DictationContext.capture(preferences: prefs, field: .text, before: "Dear Professor Rahman,", after: nil)?.audience == "academic")
    #expect(DictationContext.capture(preferences: prefs, field: .text, before: "Yesterday he said hello mom", after: nil)?.audience == "unknown")
    let literal = try #require(DictationContext.capture(preferences: prefs, field: .literal, before: "Dear Professor", after: "secret"))
    #expect(literal.purpose == "literal" && literal.before == nil && literal.after == nil)
}
@Test func insertionGuardAndSpacing() {
    let document = UUID()
    let snapshot = EditorSnapshot(document: document, before: "Hello", after: " world", selected: nil)
    #expect(snapshot.matches(snapshot))
    #expect(!snapshot.matches(.init(document: UUID(), before: "Hello", after: " world", selected: nil)))
    #expect(!snapshot.matches(.init(document: document, before: "Edited", after: " world", selected: nil)))
    #expect(!EditorSnapshot(document: document, before: nil, after: nil, selected: nil).matches(.init(document: document, before: nil, after: nil, selected: nil)))
    #expect(TextPolicy.spaced("there.", before: "Hello") == " there.")
    #expect(TextPolicy.spaced(", yes.", before: "Hello") == ", yes.")
    #expect(TextPolicy.spaced("replacement", before: "Hello", selected: "old") == "replacement")
    #expect(TextPolicy.spaced("New sentence.", before: "", after: "Hello bold world.") == "New sentence. ")
    #expect(TextPolicy.spaced("insert", before: "Hello ", after: ".") == "insert")
    #expect(TextPolicy.spaced("replacement", before: "Hello", after: "world", selected: "old") == "replacement")
    #expect(TextPolicy.sentenceStart(before: "Hello. "))
    #expect(!TextPolicy.sentenceStart(before: "Hello "))
}
@Test func wavAndSilenceThresholds() {
    let samples = (0..<8000).map { Int16(sin(Double($0) * 0.1) * 150) }
    let wav = WAV.encode(samples)
    #expect(wav.count == 16044)
    #expect(String(data: wav.prefix(4), encoding: .utf8) == "RIFF")
    #expect(Array(wav[24..<28]) == [0x80,0x3e,0,0])
    var speech = SpeechSignal(); var silent = SpeechSignal()
    for start in stride(from: 0, to: samples.count, by: 320) {
        speech.observe(Array(samples[start..<min(start+320,samples.count)]))
        silent.observe(Array(repeating: 0, count: 320))
    }
    #expect(speech.containsSpeech)
    #expect(!silent.containsSpeech)
}
@Test @MainActor func durablePartitioningAndInFlightChanges() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: url) }
    let store = try RecordStore(url: url)
    var record = CloudRecord(id: "same-id", type: .dictionary, payload: RecordPayload.dictionary("Ahad"))
    try store.save(owner: "alice", record: record)
    try store.save(owner: "bob", record: .init(id: "same-id", type: .dictionary, payload: RecordPayload.dictionary("Other")))
    let sent = try #require(store.pending(owner: "alice", historyEnabled: true).first)
    record.payload = RecordPayload.dictionary("Ahad Ali")
    try store.save(owner: "alice", record: record)
    try store.settle(owner: "alice", sent: sent, version: 1)
    let pending = try #require(store.pending(owner: "alice", historyEnabled: true).first)
    #expect(pending.record.version == 1)
    #expect(pending.record.payload?["term"]?.string == "Ahad Ali")
    #expect(try store.records(owner: "bob", type: .dictionary).first?.payload?["term"]?.string == "Other")
    try store.delete(owner: "alice", record: pending.record)
    var remote = record; remote.version = 4
    try store.merge(owner: "alice", remote: remote)
    let deletion = try #require(store.pending(owner: "alice", historyEnabled: true).first)
    #expect(deletion.record.deleted && deletion.record.version == 4)
    #expect(try store.records(owner: "alice", type: .dictionary).isEmpty)
}
@Test @MainActor func feedTransactionRollsBackCursorAndRecords() throws {
    let store = try RecordStore(url: URL(fileURLWithPath: ":memory:"))
    enum Failure: Error { case interrupted }
    #expect(throws: Failure.self) {
        try store.transaction {
            try store.save(owner: "a", record: .init(id: "a", type: .history, payload: ["text": .string("hello")]), dirty: false)
            try store.setMetadata("cursor", owner: "a", value: 12)
            throw Failure.interrupted
        }
    }
    #expect(try store.records(owner: "a", type: .history).isEmpty)
    #expect(try store.metadata("cursor", owner: "a", default: 0) == 0)
}
@Test func correctionSuggestionsAvoidCommonWordsAndPreserveNames() {
    #expect(CorrectionLearning.suggestion(generated: "Please call a had", before: "Please call Ahad") == "Ahad")
    #expect(CorrectionLearning.suggestion(generated: "Meet Rahul tomorrow", before: "Meet Rahman tomorrow") == "Rahman")
    #expect(CorrectionLearning.suggestion(generated: "I am ready", before: "I was ready") == nil)
    #expect(CorrectionLearning.suggestion(generated: "Call 123", before: "Call 456") == nil)
    #expect(CorrectionLearning.suggestion(generated: "hello", before: "Hello") == nil)
}
@Test @MainActor func undoHoldsDeletionThroughFeedMerge() throws {
    let store = try RecordStore(url: URL(fileURLWithPath: ":memory:"))
    let original = CloudRecord(id: "history", type: .history, version: 1, payload: ["text": .string("Keep me"), "createdAtMs": .number(1)])
    try store.save(owner: "alice", record: original, dirty: false)
    try store.delete(owner: "alice", record: original, holdUntil: .now.addingTimeInterval(5))
    #expect(try store.pending(owner: "alice", historyEnabled: true).isEmpty)
    var remote = original; remote.version = 2
    try store.merge(owner: "alice", remote: remote)
    #expect(try store.pending(owner: "alice", historyEnabled: true).isEmpty)
    try store.restore(owner: "alice", original: original)
    let restored = try #require(store.pending(owner: "alice", historyEnabled: true).first?.record)
    #expect(!restored.deleted && restored.version == 2 && restored.payload?["text"]?.string == "Keep me")
}
@Test @MainActor func sameWordAddedOnTwoDevicesCoalescesWithoutLosingConfirmation() throws {
    let store = try RecordStore(url: URL(fileURLWithPath: ":memory:"))
    try store.save(owner: "alice", record: .init(id: "local-word", type: .dictionary, payload: RecordPayload.dictionary("Rahman")))
    try store.merge(owner: "alice", remote: .init(id: "remote-word", type: .dictionary, version: 2, payload: RecordPayload.dictionary("Rahman", status: "suggested", source: "learned")))
    let active = try store.records(owner: "alice", type: .dictionary)
    #expect(active.count == 1)
    #expect(active.first?.id == "remote-word")
    #expect(active.first?.payload?["status"]?.string == "confirmed")
    let pending = try store.pending(owner: "alice", historyEnabled: true)
    #expect(pending.contains { $0.record.id == "local-word" && $0.record.deleted })
    #expect(pending.contains { $0.record.id == "remote-word" && $0.record.version == 2 && !$0.record.deleted })
}

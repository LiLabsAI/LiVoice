import ActivityKit
import AppIntents
import Foundation

nonisolated struct SessionActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var expiresAt: Date
        var phase: String
    }
}
struct StopKeyboardSessionIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop WoVoice keyboard session"
    static let description = IntentDescription("Releases the microphone and cancels pending keyboard dictation.")
    @MainActor func perform() async throws -> some IntentResult {
        let bridge = KeyboardBridge()
        if let status = bridge.read("status.json", as: KeyboardStatus.self), let owner = status.owner {
            try bridge.enqueue(KeyboardCommand(id: UUID(), request: UUID(), owner: owner, action: .endSession, createdAt: .now, context: nil, sentenceStart: false))
        }
        return .result()
    }
}

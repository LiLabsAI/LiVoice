import AppIntents
import Foundation

enum AppRuntime {
    static weak var model: AppModel?
    static var pendingKeyboardSession = false
}
struct StartKeyboardSessionIntent: AppIntent {
    static let title: LocalizedStringResource = "Start WoVoice keyboard session"
    static let description = IntentDescription("Opens WoVoice and starts a five-minute microphone session for dictating from its keyboard.")
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        if let model = AppRuntime.model { await model.startSession() }
        else { AppRuntime.pendingKeyboardSession = true }
        return .result()
    }
}
struct WoVoiceShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartKeyboardSessionIntent(), phrases: ["Start a keyboard session in \(.applicationName)", "Dictate with \(.applicationName)"], shortTitle: "Start dictation session", systemImageName: "mic.fill")
    }
}

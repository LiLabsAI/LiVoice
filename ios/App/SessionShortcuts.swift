import AppIntents
import Foundation
import Observation

@Observable final class AppRuntime {
    static let shared = AppRuntime()
    private(set) var pendingKeyboardSession = false

    func requestKeyboardSession() { pendingKeyboardSession = true }
    func takeKeyboardSession(whileActive active: Bool) -> Bool {
        guard active, pendingKeyboardSession else { return false }
        pendingKeyboardSession = false
        return true
    }
}
struct StartKeyboardSessionIntent: AppIntent {
    static let title: LocalizedStringResource = "Start WoVoice keyboard session"
    static let description = IntentDescription("Opens WoVoice and starts a five-minute microphone session for dictating from its keyboard.")
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        // The system can invoke perform before the app's scene is active.
        // Leave audio activation to the foreground scene, including warm launches.
        AppRuntime.shared.requestKeyboardSession()
        return .result()
    }
}
struct WoVoiceShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartKeyboardSessionIntent(), phrases: ["Start a keyboard session in \(.applicationName)", "Dictate with \(.applicationName)"], shortTitle: "Start dictation session", systemImageName: "mic.fill")
    }
}

import SwiftUI
import AppIntents
import Observation

@Observable final class ProbeState {
    static let shared = ProbeState()
    var runs = 0
}
@main struct ShortcutProbeApp: App {
    var body: some Scene {
        WindowGroup { Text("Shortcut runs: \(ProbeState.shared.runs)").padding() }
    }
}
struct ProbeIntent: AppIntent {
    static let title: LocalizedStringResource = "Run WoVoice shortcut probe"
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        ProbeState.shared.runs += 1
        return .result()
    }
}
struct ProbeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: ProbeIntent(), phrases: ["Test \(.applicationName)"], shortTitle: "Run probe", systemImageName: "checkmark.circle")
    }
}

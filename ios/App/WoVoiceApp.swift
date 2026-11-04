import SwiftUI

@main struct WoVoiceApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: AppModel?
    private let startupError: String?
    init() {
        do {
            let value: AppModel
            #if DEBUG
            value = try DevelopmentFixture.enabled ? DevelopmentFixture.makeModel(useMicrophone: DevelopmentFixture.microphoneEnabled) : AppModel()
            if DevelopmentFixture.enabled, ProcessInfo.processInfo.arguments.contains("--fixture-shortcut") { AppRuntime.shared.requestKeyboardSession() }
            #else
            value = try AppModel()
            #endif
            _model = State(initialValue: value); startupError = nil
        }
        catch { startupError = error.localizedDescription }
    }
    var body: some Scene {
        WindowGroup {
            if let model {
                dashboard(model)
                    .onOpenURL { url in
                        if model.handleSessionURL(url) { AppRuntime.shared.cancelKeyboardSessionRequest() }
                    }
                    .onChange(of: scenePhase, initial: true) { _, _ in startRequestedSession(model) }
                    .onChange(of: AppRuntime.shared.pendingKeyboardSession) { _, _ in startRequestedSession(model) }
            }
            else { ContentUnavailableView("WoVoice couldn’t open", systemImage: "externaldrive.badge.exclamationmark", description: Text(startupError ?? "Please restart the app.")) }
        }
    }
    @ViewBuilder private func dashboard(_ model: AppModel) -> some View {
        #if DEBUG
        DevelopmentFixture.dashboard(model: model)
        #else
        DashboardView(model: model)
        #endif
    }
    private func startRequestedSession(_ model: AppModel) {
        guard AppRuntime.shared.takeKeyboardSession(whileActive: scenePhase == .active) else { return }
        Task { await model.startSession() }
    }
}

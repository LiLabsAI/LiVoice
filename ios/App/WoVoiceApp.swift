import SwiftUI

@main struct WoVoiceApp: App {
    @State private var model: AppModel?
    private let startupError: String?
    init() {
        do {
            let value: AppModel
            #if DEBUG
            value = try DevelopmentFixture.enabled ? DevelopmentFixture.makeModel() : AppModel()
            #else
            value = try AppModel()
            #endif
            _model = State(initialValue: value); AppRuntime.model = value; startupError = nil
        }
        catch { startupError = error.localizedDescription }
    }
    var body: some Scene {
        WindowGroup {
            if let model { DashboardView(model: model).task { if AppRuntime.pendingKeyboardSession { AppRuntime.pendingKeyboardSession = false; await model.startSession() } } }
            else { ContentUnavailableView("WoVoice couldn’t open", systemImage: "externaldrive.badge.exclamationmark", description: Text(startupError ?? "Please restart the app.")) }
        }
    }
}

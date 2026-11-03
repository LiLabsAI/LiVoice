import ActivityKit
import SwiftUI
import WidgetKit

@main struct WoVoiceWidgetBundle: WidgetBundle {
    var body: some Widget { SessionWidget() }
}
struct SessionWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SessionActivityAttributes.self) { context in
            HStack(spacing: 16) {
                Image(systemName: "waveform").font(.title2).foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 4) {
                    Text("WoVoice keyboard session").font(.headline)
                    Text(context.state.phase).font(.caption)
                    countdown(context.state.expiresAt)
                }
                Spacer()
                Button(intent: StopKeyboardSessionIntent()) { Label("Stop", systemImage: "stop.fill") }.buttonStyle(.bordered)
            }.padding(16).activityBackgroundTint(.black).activitySystemActionForegroundColor(.white).foregroundStyle(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label("WoVoice", systemImage: "waveform").foregroundStyle(.orange) }
                DynamicIslandExpandedRegion(.trailing) { countdown(context.state.expiresAt) }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack { Text(context.state.phase).font(.caption); Spacer(); Button(intent: StopKeyboardSessionIntent()) { Label("Stop microphone", systemImage: "stop.fill") } }
                }
            } compactLeading: { Image(systemName: "waveform").foregroundStyle(.orange) }
                compactTrailing: { countdown(context.state.expiresAt).frame(width: 42) }
                minimal: { Image(systemName: "mic.fill").foregroundStyle(.orange) }
        }
    }
    private func countdown(_ expiry: Date) -> some View { Text(timerInterval: Date.now...max(.now, expiry), countsDown: true).font(.caption.monospacedDigit()) }
}

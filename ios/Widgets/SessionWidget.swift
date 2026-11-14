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
                    Text("LiVoice keyboard session").font(.headline)
                    Text(context.isStale ? "Session ended · Open LiVoice" : context.state.phase).font(.caption)
                    if !context.isStale { countdown(context.state.expiresAt) }
                }
                Spacer()
                if !context.isStale { Link(destination: SessionControl.stopURL) { Label("Open to stop", systemImage: "stop.fill") }.buttonStyle(.bordered) }
            }.padding(16).activityBackgroundTint(.black).activitySystemActionForegroundColor(.white).foregroundStyle(.white)
                .widgetURL(context.isStale ? URL(string: "wovoice-ios://session")! : SessionControl.stopURL)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label("LiVoice", systemImage: "waveform").foregroundStyle(.orange) }
                DynamicIslandExpandedRegion(.trailing) {
                    if context.isStale { Text("Ended").font(.caption) }
                    else { countdown(context.state.expiresAt) }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Text(context.isStale ? "Session ended · Open LiVoice" : context.state.phase).font(.caption)
                        Spacer()
                        if !context.isStale { Link(destination: SessionControl.stopURL) { Label("Open to stop microphone", systemImage: "stop.fill") } }
                    }
                }
            } compactLeading: { Image(systemName: "waveform").foregroundStyle(.orange) }
                compactTrailing: {
                    if context.isStale { Text("Ended").font(.caption) }
                    else { countdown(context.state.expiresAt).frame(width: 42) }
                }
                minimal: { Image(systemName: "mic.fill").foregroundStyle(.orange) }
        }
    }
    private func countdown(_ expiry: Date) -> some View { Text(timerInterval: Date.now...max(.now, expiry), countsDown: true).font(.caption.monospacedDigit()) }
}

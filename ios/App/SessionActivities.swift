import ActivityKit
import Foundation

/// ActivityKit's handles lack Sendable annotations in the SDK. Keep them local
/// to one async operation; only identifiers and value-type state cross actors.
nonisolated enum SessionActivities {
    @concurrent static func update(id: String, expires: Date, phase: String) async {
        guard let activity = Activity<SessionActivityAttributes>.activities.first(where: { $0.id == id }) else { return }
        let content = ActivityContent(state: SessionActivityAttributes.ContentState(expiresAt: expires, phase: phase), staleDate: expires)
        await activity.update(content)
    }
    @concurrent static func end(id: String) async {
        guard let activity = Activity<SessionActivityAttributes>.activities.first(where: { $0.id == id }) else { return }
        await activity.end(nil, dismissalPolicy: .immediate)
    }
}

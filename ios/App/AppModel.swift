import AVFAudio
import ActivityKit
import AuthenticationServices
import Foundation
import Observation
import UIKit
import WoVoiceCore

@Observable final class AppModel {
    let account: AccountSession
    let store: RecordStore
    private let auth: any AccountAuthenticating
    private let audio: any CaptureEngine
    private let clock: () -> ContinuousClock.Instant
    private var sessionDeadline: ContinuousClock.Instant?
    private var recordingStartedAt: ContinuousClock.Instant?
    private var leaseDeadline: ContinuousClock.Instant?
    private var lastLeaseID: UUID?
    private let bridge = KeyboardBridge()
    private let syncService: SyncService
    var profile: Profile?
    var history: [CloudRecord] = []
    var dictionary: [CloudRecord] = []
    var analytics: [CloudRecord] = []
    var cloudSettings = CloudSettings()
    var preferences = ContextPreferences()
    var status = KeyboardStatus()
    var message: String?
    var busy = false
    var syncing = false
    var refreshingProfile = false
    var lastSyncedAt: Date?
    var syncWarning: String?
    var lastText = ""
    var haptics = true
    var animations = true
    var waveform = true
    var keepHistory = true
    var costEstimates = true
    var learning = true
    var sessions: [DeviceSession] = []
    private var sessionListIdentity: UUID?
    var undoRecord: CloudRecord?
    private var undoTask: Task<Void, Never>?
    private var undoOwner: String?
    private var sessionKeyboard = false
    private var activityID: String?
    private var activityPhase = ""
    private var loop: Task<Void, Never>?
    private var upload: Task<Void, Never>?
    private var generation = UUID()
    private var keyboardRequest = false
    private var currentContext: DictationContext?
    private var sentenceStart = true
    private var retryAudio: (wav: Data, duration: Double, expiresAt: Date, attempts: Int)?
    private var observers: [NSObjectProtocol] = []
    private var loadedOwner: String?
    private var refreshingForeground = false
    var owner: String? { account.user?.id }
    var accountRestriction: String? { account.restrictionMessage }
    var recording: Bool { status.phase == "recording" }
    var microphoneActive: Bool { audio.active }
    var keyboardSessionActive: Bool { sessionKeyboard && audio.active && status.expiresAt > .now && sessionDeadline.map { clock() < $0 } == true }

    init(api: APIClient = APIClient(), persistence: any SessionPersistence = KeychainSessionStore(), capture: any CaptureEngine = AudioCapture(), databaseURL: URL? = nil, clock: @escaping () -> ContinuousClock.Instant = { .now }, auth: any AccountAuthenticating = WebAuthentication()) throws {
        audio = capture; self.clock = clock; self.auth = auth
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        var local = directory; try local.setResourceValues(resources)
        store = try RecordStore(url: databaseURL ?? directory.appendingPathComponent("wovoice.sqlite"))
        account = try AccountSession(api: api, persistence: persistence)
        syncService = SyncService(account: account, store: store)
        try reload()
        account.onCleared = { [weak self] in
            guard let self else { return }
            stopSession()
            do { try reload() } catch { message = error.localizedDescription }
        }
        account.onUserChanged = { [weak self] in
            guard let self, let restriction = accountRestriction else { return }
            stopSession(message: restriction)
        }
        _ = bridge.drainCommands()
        bridge.clearLeases()
        bridge.remove("status.json")
        // A crash can leave an activity behind even though its audio engine is
        // gone. Capture only existing IDs so this cleanup cannot end a new one.
        for id in Activity<SessionActivityAttributes>.activities.map(\.id) {
            Task { await SessionActivities.end(id: id) }
        }
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            guard (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
            Task { @MainActor in self?.stopSession(message: "Audio was interrupted. Start a new session when ready.") }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
            let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            guard reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue || reason == AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue else { return }
            Task { @MainActor in self?.stopSession(message: "The audio device changed. Start a new session to use it.") }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.stopSession(message: "Audio services restarted. Start a new session when ready.") }
        })
    }
    isolated deinit {
        loop?.cancel(); upload?.cancel(); undoTask?.cancel(); audio.stopSession()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }
    func reload() throws {
        if loadedOwner != owner { lastText = ""; profile = nil; sessions = []; loadedOwner = owner }
        if sessionListIdentity != account.sessionIdentity { sessions = []; sessionListIdentity = nil }
        if undoOwner != owner { undoTask?.cancel(); undoRecord = nil; undoOwner = nil }
        guard let owner else {
            history = []; dictionary = []; analytics = []; preferences = .init(); cloudSettings = .init()
            lastSyncedAt = nil; syncWarning = nil; return
        }
        cloudSettings = try store.metadata("cloudSettings", owner: owner, default: CloudSettings())
        try store.applyRetention(owner: owner, days: cloudSettings.historyRetentionDays)
        history = try store.records(owner: owner, type: .history).sorted { ($0.payload?["createdAtMs"]?.number ?? 0) > ($1.payload?["createdAtMs"]?.number ?? 0) }
        dictionary = try store.records(owner: owner, type: .dictionary).sorted { ($0.payload?["term"]?.string ?? "") < ($1.payload?["term"]?.string ?? "") }
        analytics = try store.records(owner: owner, type: .analytics)
        preferences = try store.metadata("context", owner: owner, default: ContextPreferences())
        lastSyncedAt = try store.metadata("lastSync", owner: owner, default: nil as Date?)
        let warning = try store.metadata("syncWarning", owner: owner, default: "")
        syncWarning = warning.isEmpty ? nil : warning
        haptics = try store.metadata("haptics", owner: owner, default: true)
        animations = try store.metadata("animations", owner: owner, default: true)
        waveform = try store.metadata("waveform", owner: owner, default: true)
        keepHistory = try store.metadata("keepHistory", owner: owner, default: true)
        costEstimates = try store.metadata("costEstimates", owner: owner, default: true)
        learning = try store.metadata("learning", owner: owner, default: true)
    }
    func signIn() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            let result = try await auth.authenticate(base: account.api.baseURL, deletion: false)
            let tokens = try await account.api.exchange(code: result.code, verifier: result.verifier, device: "iOS — \(UIDevice.current.model)")
            stopSession()
            try account.accept(tokens); try reload(); await refreshProfile(); await sync()
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin { }
        catch { message = error.localizedDescription }
    }
    @discardableResult func refreshProfile() async -> Bool {
        guard owner != nil, !refreshingProfile else { return false }
        refreshingProfile = true; defer { refreshingProfile = false }
        let expected = account.sessionIdentity
        do {
            let next: Profile = try await account.call { try await account.api.request("/v1/me", token: $0) }
            guard account.sessionIdentity == expected else { throw CancellationError() }
            try account.updateUser(next.user); profile = next
            return true
        } catch is CancellationError { }
        catch { message = error.localizedDescription; if owner == nil { stopSession(); try? reload() } }
        return false
    }
    func refreshForForeground() async {
        guard owner != nil, !busy, !refreshingForeground else { return }
        refreshingForeground = true; defer { refreshingForeground = false }
        let expected = account.sessionIdentity
        guard await refreshProfile(), !Task.isCancelled, account.sessionIdentity == expected else { return }
        await sync()
    }
    func logout() async {
        guard !busy else { return }
        stopSession(); message = nil; busy = true; defer { busy = false }
        do {
            let revoked = try await account.signOut()
            guard owner == nil else { throw CancellationError() }
            try reload()
            if !revoked { message = "Signed out on this device, but the server session could not be revoked. Revoke it from another signed-in device, or let it expire." }
        } catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    func deleteAccount() async {
        guard !busy, let expected = owner else { return }
        let expectedSession = account.sessionIdentity
        stopSession(); message = nil; busy = true; defer { busy = false }
        do {
            let verification = try await auth.authenticate(base: account.api.baseURL, deletion: true)
            guard owner == expected, account.sessionIdentity == expectedSession else { throw CancellationError() }
            try await account.call { try await account.api.empty("/v1/me", method: "DELETE", token: $0, body: ["reauthToken": .string(verification.code), "codeVerifier": .string(verification.verifier)]) }
            guard account.sessionIdentity == expectedSession else { throw CancellationError() }
            try store.removeAll(owner: expected); try account.clear(); profile = nil; lastText = ""; try reload()
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin { }
        catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    func loadSessions() async {
        nonisolated struct Response: Decodable, Sendable { let sessions: [DeviceSession] }
        guard owner != nil else { sessions = []; sessionListIdentity = nil; return }
        let expected = account.sessionIdentity
        do {
            let result: Response = try await account.call { try await account.api.request("/v1/me/sessions", token: $0) }
            guard account.sessionIdentity == expected else { throw CancellationError() }
            sessions = result.sessions; sessionListIdentity = expected
        } catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    func revoke(_ device: DeviceSession) async {
        guard !busy, owner != nil else { return }
        let expected = account.sessionIdentity
        guard sessionListIdentity == expected, let current = sessions.first(where: { $0.id == device.id }) else {
            await loadSessions()
            if account.sessionIdentity == expected, owner != nil { message = "The device list changed. Review it and try again." }
            return
        }
        message = nil; busy = true; defer { busy = false }
        if current.current == 1 { stopSession() }
        do {
            let id = current.id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? current.id
            try await account.call { try await account.api.empty("/v1/me/sessions/\(id)", method: "DELETE", token: $0) }
            guard account.sessionIdentity == expected else { throw CancellationError() }
            if current.current == 1 { try account.clear(); try reload(); return }
            await loadSessions()
        } catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    func clearLocalData() async {
        guard !busy else { return }
        stopSession(); message = nil; busy = true; defer { busy = false }
        do {
            let revoked = try await account.signOut()
            guard owner == nil else { throw CancellationError() }
            try store.removeAllAccounts(); try reload()
            if !revoked { message = "Local data was cleared, but the server session could not be revoked. Revoke it from another signed-in device, or let it expire." }
        } catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    func sync() async {
        guard account.cloudServicesAllowed, !syncing else { return }; syncing = true; defer { syncing = false }
        do { try await syncService.run(); try reload() }
        catch is CancellationError { }
        catch { message = error.localizedDescription }
    }
    func updateCloud(history: Bool, retention: Int?) async {
        do {
            guard let expected = owner else { return }
            let settings = try await account.call { try await account.api.updateSettings(token: $0, history: history, retention: retention) }
            guard owner == expected else { return }
            try store.applySettings(owner: expected, settings: settings); try reload(); await sync()
        } catch { message = error.localizedDescription }
    }
    func savePreferences() {
        guard let owner else { return }
        do {
            try store.setMetadata("context", owner: owner, value: preferences)
            try store.setMetadata("haptics", owner: owner, value: haptics)
            try store.setMetadata("animations", owner: owner, value: animations)
            try store.setMetadata("waveform", owner: owner, value: waveform)
            try store.setMetadata("keepHistory", owner: owner, value: keepHistory)
            try store.setMetadata("costEstimates", owner: owner, value: costEstimates)
            try store.setMetadata("learning", owner: owner, value: learning)
            publish()
        } catch { message = error.localizedDescription }
    }
    func addTerm(_ input: String) {
        guard let owner else { return }
        guard let cleaned = RecordPayload.cleanDictionaryTerm(input) else { message = "Use a single-line word or phrase of 2 to 80 characters."; return }
        do {
            if dictionary.contains(where: { $0.payload?["normalizedTerm"]?.string == cleaned.normalized }) { return }
            try store.save(owner: owner, record: .init(id: UUID().uuidString, type: .dictionary, payload: RecordPayload.dictionary(cleaned.term)))
            try reload(); Task { await sync() }
        } catch { message = error.localizedDescription }
    }
    func delete(_ record: CloudRecord) {
        guard let owner else { return }
        do {
            try store.delete(owner: owner, record: record, holdUntil: .now.addingTimeInterval(5))
            undoRecord = record; undoOwner = owner; try reload()
            undoTask?.cancel()
            undoTask = Task {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, self.owner == owner else { return }
                undoRecord = nil; await sync()
            }
        }
        catch { message = error.localizedDescription }
    }
    func undoDelete() {
        guard let owner, owner == undoOwner, let record = undoRecord else { return }
        do { try store.restore(owner: owner, original: record); undoTask?.cancel(); undoRecord = nil; try reload(); Task { await sync() } }
        catch { message = error.localizedDescription }
    }
    func updateTerm(_ entry: CloudRecord, term: String, confirmed: Bool = true) {
        guard let owner else { return }
        guard let cleaned = RecordPayload.cleanDictionaryTerm(term) else { message = "Use a single-line word or phrase of 2 to 80 characters."; return }
        if dictionary.contains(where: { $0.id != entry.id && $0.payload?["normalizedTerm"]?.string == cleaned.normalized }) { message = "That word is already in your vocabulary."; return }
        do {
            var updated = entry; updated.payload?["term"] = .string(cleaned.term); updated.payload?["normalizedTerm"] = .string(cleaned.normalized)
            if confirmed { updated.payload?["status"] = .string("confirmed") }
            try store.save(owner: owner, record: updated); try reload(); Task { await sync() }
        } catch { message = error.localizedDescription }
    }
    private func suggestion(_ term: String?, recordID: String?) {
        guard learning, let owner, let term, (2...80).contains(term.utf16.count), !dictionary.contains(where: { $0.payload?["normalizedTerm"]?.string == term.lowercased() }) else { return }
        do {
            try store.transaction {
                try store.save(owner: owner, record: .init(id: UUID().uuidString, type: .dictionary, payload: RecordPayload.dictionary(term, status: "suggested", source: "learned")))
                if var event = analytics.first(where: { $0.id == recordID }) { event.payload?["corrected"] = .bool(true); try store.save(owner: owner, record: event) }
            }
            try reload(); Task { await sync() }
        } catch { message = error.localizedDescription }
    }
    func clear(_ type: RecordType) {
        guard let owner else { return }
        do { try store.transaction { for record in try store.records(owner: owner, type: type) { try store.delete(owner: owner, record: record) } }; try reload(); Task { await sync() } }
        catch { message = error.localizedDescription }
    }
    func startSession(keyboard: Bool = true) async {
        guard !busy, !audio.active else { return }
        guard let owner else { message = "Sign in before starting dictation."; return }
        if let restriction = accountRestriction { message = restriction; return }
        busy = true; defer { busy = false }
        let expected = generation
        do {
            try await audio.startSession()
            guard self.owner == owner, generation == expected else { audio.stopSession(); return }
            sessionKeyboard = keyboard
            sessionDeadline = clock().advanced(by: .seconds(keyboard ? 300 : 120))
            status.owner = owner; status.expiresAt = .now.addingTimeInterval(keyboard ? 300 : 120); status.phase = "ready"
            status.message = "Keyboard session ready · microphone active"; status.result = nil
            _ = bridge.drainCommands()
            bridge.clearLeases()
            if keyboard, ActivityAuthorizationInfo().areActivitiesEnabled {
                let content = ActivityContent(state: SessionActivityAttributes.ContentState(expiresAt: status.expiresAt, phase: "Microphone active · Ready"), staleDate: status.expiresAt)
                activityID = (try? Activity.request(attributes: SessionActivityAttributes(), content: content, pushType: nil))?.id
                activityPhase = status.phase
            }
            loop?.cancel()
            loop = Task { [weak self] in
                while !Task.isCancelled {
                    self?.tick()
                    try? await Task.sleep(for: .milliseconds(200))
                }
            }
            publish()
        } catch { audio.stopSession(); message = error.localizedDescription }
    }
    @discardableResult func handleSessionURL(_ url: URL) -> Bool {
        guard url == SessionControl.stopURL else { return false }
        stopSession(); return true
    }
    func stopSession(message: String? = nil) {
        if let request = status.request { bridge.removeLease(request: request) }
        generation = UUID(); upload?.cancel(); upload = nil; retryAudio = nil
        loop?.cancel(); loop = nil; audio.stopSession(); currentContext = nil
        sessionDeadline = nil; recordingStartedAt = nil; leaseDeadline = nil; lastLeaseID = nil
        if let id = activityID { Task { await SessionActivities.end(id: id) } }
        activityID = nil
        status = KeyboardStatus(); status.message = message ?? "Open LiVoice to start a keyboard session."
        _ = bridge.drainCommands(); bridge.clearLeases(); publish()
    }
    func toggleRecording() {
        guard account.cloudServicesAllowed else { stopSession(message: accountRestriction); return }
        if recording { finish() }
        else if status.phase != "processing" { begin(request: UUID(), fromKeyboard: false, context: nil, sentenceStart: true) }
    }
    private func begin(request: UUID, fromKeyboard: Bool, context: DictationContext?, sentenceStart: Bool) {
        guard account.cloudServicesAllowed, audio.active, status.phase != "processing", !recording else { return }
        generation = UUID(); retryAudio = nil; currentContext = context ?? DictationContext.capture(preferences: preferences, field: .text, before: nil, after: nil); self.sentenceStart = sentenceStart; keyboardRequest = fromKeyboard
        status.request = request; status.result = nil
        lastLeaseID = nil; leaseDeadline = fromKeyboard ? clock().advanced(by: .seconds(4)) : nil
        do { try audio.begin(); recordingStartedAt = clock(); status.phase = "recording"; status.message = "Listening…"; publish() }
        catch { status.phase = "error"; status.message = error.localizedDescription; publish() }
    }
    func cancelRecording() {
        if let request = status.request { bridge.removeLease(request: request) }
        generation = UUID(); upload?.cancel(); upload = nil; audio.cancel(); retryAudio = nil; currentContext = nil
        recordingStartedAt = nil; leaseDeadline = nil; lastLeaseID = nil
        status.phase = "ready"; status.message = "Ready · microphone active"; status.result = nil; status.request = nil; publish()
        if !sessionKeyboard { stopSession() }
    }
    private func finish() {
        guard recording else { return }
        recordingStartedAt = nil
        do {
            let recording = try audio.finish()
            retryAudio = (recording.wav, recording.duration, .now.addingTimeInterval(60), 0)
            if !sessionKeyboard {
                audio.stopSession(); status.expiresAt = .now.addingTimeInterval(100)
                sessionDeadline = clock().advanced(by: .seconds(100))
            }
            transcribe()
        } catch { status.phase = "error"; status.message = error.localizedDescription; currentContext = nil; if !sessionKeyboard { audio.stopSession() }; publish() }
    }
    func retry() {
        guard account.cloudServicesAllowed else { stopSession(message: accountRestriction); return }
        guard status.phase == "error" else { return }
        guard let retryAudio, retryAudio.expiresAt > .now, retryAudio.attempts < 2 else { cancelRecording(); return }
        self.retryAudio = (retryAudio.wav, retryAudio.duration, retryAudio.expiresAt, retryAudio.attempts + 1)
        transcribe()
    }
    private func transcribe() {
        guard let capture = retryAudio, let expectedOwner = owner, let request = status.request else { return }
        let expected = generation
        status.phase = "processing"; status.message = "Thinking…"; publish()
        upload = Task { [weak self] in
            guard let self else { return }
            do {
                let vocabulary = dictionary.filter { $0.payload?["status"]?.string == "confirmed" }.sorted {
                    let left = $0.payload?["lastUsedAtMs"]?.number ?? 0; let right = $1.payload?["lastUsedAtMs"]?.number ?? 0
                    if left != right { return left > right }
                    return ($0.payload?["createdAtMs"]?.number ?? 0) > ($1.payload?["createdAtMs"]?.number ?? 0)
                }.prefix(100)
                let glossary = vocabulary.compactMap { $0.payload?["term"]?.string }
                let result = try await account.call { token in
                    // Consent is read again for each network attempt, including token refresh.
                    try await account.api.transcribe(token: token, wav: capture.wav, glossary: glossary,
                                                     context: currentContext?.restricted(to: preferences.level), sentenceStart: sentenceStart)
                }
                guard !Task.isCancelled, generation == expected, owner == expectedOwner, status.request == request else { return }
                let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { throw APIError(code: "NO_SPEECH", message: "No speech detected. Please try again.") }
                let payload = RecordPayload.history(result, durationMs: capture.duration * 1000)
                try store.transaction {
                    if keepHistory { try store.save(owner: expectedOwner, record: .init(id: result.requestId, type: .history, payload: payload)) }
                    try store.save(owner: expectedOwner, record: .init(id: result.requestId, type: .analytics, payload: RecordPayload.analytics(history: payload)))
                    for previousEntry in vocabulary {
                        guard var entry = try store.record(owner: expectedOwner, type: .dictionary, id: previousEntry.id),
                              !entry.deleted, entry.payload?["normalizedTerm"] == previousEntry.payload?["normalizedTerm"],
                              entry.payload?["status"]?.string == "confirmed", let term = entry.payload?["term"]?.string else { continue }
                        let pattern = "(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: term) + "(?![\\p{L}\\p{N}])"
                        if text.range(of: pattern, options: [.regularExpression,.caseInsensitive]) != nil {
                            entry.payload?["lastUsedAtMs"] = .number(Date.now.timeIntervalSince1970*1000)
                            let useCount = (entry.payload?["useCount"]?.number ?? 0)+1
                            entry.payload?["useCount"] = .number(useCount)
                            try store.save(owner: expectedOwner, record: entry)
                        }
                    }
                }
                try reload(); lastText = text
                status.phase = keyboardRequest ? "result" : "ready"; status.message = keyboardRequest ? "Dictation ready" : "Ready · microphone active"
                status.result = keyboardRequest ? text : nil; status.resultExpiresAt = .now.addingTimeInterval(30)
                status.recordID = result.requestId
                retryAudio = nil; currentContext = nil; publish()
                if !sessionKeyboard { upload = nil; stopSession() }
                await sync(); await refreshProfile()
            } catch is CancellationError { }
            catch {
                guard generation == expected, owner == expectedOwner else { return }
                status.phase = "error"; status.message = error.localizedDescription
                if let error = error as? APIError, error.retryable, (capture.attempts < 2), (capture.expiresAt > .now) {
                    retryAudio = (capture.wav, capture.duration, capture.expiresAt, capture.attempts)
                } else { retryAudio = nil; currentContext = nil }
                publish()
            }
        }
    }
    private func tick() {
        guard account.cloudServicesAllowed else { stopSession(message: accountRestriction); return }
        guard owner == status.owner, audio.active || (!sessionKeyboard && (status.phase == "processing" || status.phase == "error")) else { stopSession(); return }
        guard status.expiresAt > .now, sessionDeadline.map({ clock() < $0 }) == true else {
            stopSession(message: sessionKeyboard ? "Keyboard session ended after five minutes. Open LiVoice to start again." : "Dictation timed out. Please try again."); return
        }
        let meter = audio.meter; status.level = meter.level; status.elapsed = meter.duration
        if keyboardRequest, let request = status.request, let deadline = leaseDeadline {
            if let lease = bridge.lease(request: request), lease.owner == owner, lease.request == request,
               lease.id != lastLeaseID, abs(lease.heartbeat.timeIntervalSinceNow) < 4 {
                lastLeaseID = lease.id; leaseDeadline = clock().advanced(by: .seconds(4))
            } else if clock() >= deadline { cancelRecording() }
        }
        if let startedAt = recordingStartedAt {
            let elapsed = startedAt.duration(to: clock())
            status.elapsed = min(60, max(0, Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds)/1e18))
            if recording && (elapsed >= .seconds(60) || meter.duration >= 60) { finish() }
        }
        if let expiry = status.resultExpiresAt, expiry <= .now { status.result = nil; status.resultExpiresAt = nil; if status.phase == "result" { status.phase = "ready" } }
        if let retryAudio, retryAudio.expiresAt <= .now { self.retryAudio = nil; currentContext = nil }
        for command in bridge.drainCommands() {
            if command.owner == owner, abs(command.createdAt.timeIntervalSinceNow) < 3 {
                switch command.action {
                case .start:
                    if status.request != nil && status.request != command.request { cancelRecording() }
                    begin(request: command.request, fromKeyboard: true, context: command.context, sentenceStart: command.sentenceStart)
                case .finish: if command.request == status.request { finish() }
                case .retry: if command.request == status.request { retry() }
                case .cancel: if command.request == status.request { cancelRecording() }
                case .acknowledge:
                    if command.request == status.request {
                        bridge.removeLease(request: command.request); leaseDeadline = nil; lastLeaseID = nil
                        status.result = nil; status.request = nil; status.phase = "ready"
                    }
                case .suggest: suggestion(command.suggestion, recordID: command.recordID)
                case .endSession: stopSession(); return
                }
            }
        }
        publish()
    }
    private func publish() {
        status.heartbeat = .now; status.preferences = preferences; status.haptics = haptics; status.animate = animations; status.waveform = waveform; status.learning = learning
        status.canRetry = retryAudio.map { $0.expiresAt > .now && $0.attempts < 2 } ?? false
        var outgoing = status
        if let activityID, activityPhase != status.phase {
            activityPhase = status.phase
            let phase = recording ? "Recording dictation" : status.phase == "processing" ? "Processing dictation" : "Microphone active · Ready"
            let expiry = status.expiresAt
            Task { await SessionActivities.update(id: activityID, expires: expiry, phase: phase) }
        }
        if !sessionKeyboard { outgoing = KeyboardStatus() }
        do { try bridge.write(outgoing, name: "status.json") }
        catch { if audio.active { message = error.localizedDescription } }
    }
}

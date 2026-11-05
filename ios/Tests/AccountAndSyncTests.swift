import Foundation
import AVFAudio
import ActivityKit
import AuthenticationServices
import Testing
import WoVoiceCore
@testable import WoVoice

private final class MemorySessionStore: SessionPersistence {
    var value: StoredSession?
    var removalError: APIError?
    func load() throws -> StoredSession? { value }
    func save(_ value: StoredSession) throws { self.value = value }
    func remove() throws { if let removalError { throw removalError }; value = nil }
}
private nonisolated final class TestRouter: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: @Sendable (URLRequest, Int) -> (Int, Data) = { _,_ in (500,Data()) }
    private var requests: [URLRequest] = []
    private var failure: URLError.Code?
    func configure(failure: URLError.Code? = nil, _ value: @escaping @Sendable (URLRequest, Int) -> (Int,Data)) {
        lock.lock(); defer { lock.unlock() }; handler = value; requests = []; self.failure = failure
    }
    func respond(_ request: URLRequest) -> (Int,Data,URLError.Code?) {
        var copy = request
        if copy.httpBody == nil, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var data = Data(); var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; data.append(contentsOf: buffer.prefix(count)) }
            copy.httpBody = data
        }
        lock.lock()
        requests.append(copy)
        let current = handler; let count = requests.count; let networkFailure = failure
        lock.unlock()
        let (status, data) = current(copy, count)
        return (status, data, networkFailure)
    }
    func captured() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return requests }
}
private nonisolated final class FixtureProtocol: URLProtocol, @unchecked Sendable {
    static let router = TestRouter()
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "fixture.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, data, failure) = Self.router.respond(request)
        if let failure {
            client?.urlProtocol(self, didFailWithError: URLError(failure))
            return
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private final class DetachingDocument: NSObject {
    @objc dynamic var documentIdentifier: NSUUID?
}

private final class RetryCapture: CaptureEngine {
    let wav = WAV.encode((0..<16000).map { Int16(sin(Double($0)*0.1)*1200) })
    var active = false
    var starts = 0
    var recordings = 0
    var meter: (level: Double, duration: Double) { (0.04, 1) }
    func startSession() async throws { active = true; starts += 1 }
    func begin() throws { recordings += 1 }
    func finish() throws -> (wav: Data, duration: Double) { (wav, 1) }
    func cancel() {}
    func stopSession() { active = false }
}

private final class FixtureAuthentication: AccountAuthenticating {
    typealias ResultValue = (code: String, verifier: String)
    var calls: [(base: URL, deletion: Bool)] = []
    var result: Result<ResultValue, any Error> = .success(("fresh-verification-code-for-tests", "fixture-verifier"))
    var paused = false
    private var continuation: CheckedContinuation<ResultValue, any Error>?
    var waiting: Bool { continuation != nil }
    func authenticate(base: URL, deletion: Bool) async throws -> ResultValue {
        calls.append((base, deletion))
        guard continuation == nil else { throw APIError(code: "AUTH_IN_PROGRESS", message: "A sign-in window is already open.") }
        if paused { return try await withCheckedThrowingContinuation { continuation = $0 } }
        return try result.get()
    }
    func complete(_ result: Result<ResultValue, any Error>? = nil) {
        let pending = continuation; continuation = nil
        pending?.resume(with: result ?? self.result)
    }
}

@Suite(.serialized) @MainActor struct AccountAndSyncTests {
    @Test func audioTapCreatedOnMainActorCanRunOnRealtimeQueue() async throws {
        let format = try #require(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false))
        let accumulator = try AudioAccumulator(input: format)
        accumulator.begin()
        let tap = accumulator.makeTap()
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let input = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false)!
                let buffer = AVAudioPCMBuffer(pcmFormat: input, frameCapacity: 4800)!
                buffer.frameLength = 4800
                for i in 0..<4800 { buffer.floatChannelData![0][i] = Float(sin(Double(i)*0.1)*0.08) }
                tap(buffer, AVAudioTime(sampleTime: 0, atRate: 48000))
                continuation.resume()
            }
        }
        // AVAudioConverter can retain priming frames from the first buffer.
        #expect(accumulator.state().duration > 0)
        #expect(accumulator.state().level > 0)
        #expect(!accumulator.finish().isEmpty)
    }
    @Test func shortcutWaitsForForegroundAndConsumesEachRequestOnce() async throws {
        let runtime = AppRuntime()
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        runtime.requestKeyboardSession()
        #expect(!runtime.takeKeyboardSession(whileActive: false))
        #expect(runtime.pendingKeyboardSession && !model.microphoneActive)
        if runtime.takeKeyboardSession(whileActive: true) { await model.startSession() }
        #expect(model.keyboardSessionActive && !runtime.pendingKeyboardSession)
        #expect(!runtime.takeKeyboardSession(whileActive: true))
        model.stopSession()
        runtime.requestKeyboardSession()
        #expect(runtime.takeKeyboardSession(whileActive: true))
    }
    private func api() -> APIClient {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [FixtureProtocol.self]
        return APIClient(baseURL: URL(string: "https://fixture.invalid")!, session: URLSession(configuration: config))
    }
    private func tokens(_ access: String = "access", owner: String = "alice", state: String = "active") throws -> Tokens {
        try JSONDecoder().decode(Tokens.self, from: Data("""
        {"accessToken":"\(access)","refreshToken":"refresh","accessExpiresIn":900,"refreshExpiresIn":2592000,
         "user":{"id":"\(owner)","email":"fixture@example.test","role":"user","accountStatus":{"state":"\(state)","supportEmail":"support@example.test"}}}
        """.utf8))
    }
    @Test func missingEditorIdentityIsSafeDuringKeyboardTeardown() {
        let document = DetachingDocument()
        #expect(EditorIdentity.document(from: document) == nil)
        let id = UUID(); document.documentIdentifier = id as NSUUID
        #expect(EditorIdentity.document(from: document) == id)
        document.documentIdentifier = nil
        #expect(EditorIdentity.document(from: document) == nil)
        #expect(EditorIdentity.document(from: NSObject()) == nil)
    }
    @Test func unreadableTranscriptionCanBeRetriedWithoutRecordingAgain() async throws {
        FixtureProtocol.router.configure { _,_ in (200, Data("not-json".utf8)) }
        do {
            _ = try await api().transcribe(token: "fixture", wav: Data(), glossary: [], context: nil, sentenceStart: true)
            Issue.record("Expected an unreadable response error")
        } catch let error as APIError {
            #expect(error.code == "INVALID_RESPONSE" && error.retryable)
        }
    }
    @Test func uploadRetryReusesAudioAndIgnoresRepeatedRetryWhileProcessing() async throws {
        FixtureProtocol.router.configure { request, index in
            switch request.url?.path {
            case "/v1/transcriptions":
                if index == 1 { return (503, Data(#"{"error":{"code":"TEMPORARY","message":"Please retry.","retryable":true}}"#.utf8)) }
                return (200, Data(#"{"text":"Please meet tomorrow.","rawText":"please meet tomorrow","polished":true,"asrModel":"test","requestId":"retry-result","timingsMs":{"asr":1,"polish":1,"total":2},"usage":null}"#.utf8))
            case "/v2/sync": return (200, Data(#"{"nextCursor":0,"hasMore":false,"items":[],"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false}}"#.utf8))
            case "/v2/sync/batch":
                let batch = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any]
                let items = (batch?["items"] as? [[String: Any]] ?? []).map { ["id": $0["id"] ?? "", "type": $0["type"] ?? "", "version": ($0["baseVersion"] as? Int ?? 0) + 1] }
                return (200, (try? JSONSerialization.data(withJSONObject: ["applied": items])) ?? Data())
            default: return (200, Data(#"{"user":{"id":"alice","email":"fixture@example.test","role":"user","accountStatus":{"state":"active","supportEmail":"support@example.test"}},"quota":{"limitAudioSeconds":600,"remainingAudioSeconds":600,"resetAt":1900000000000}}"#.utf8))
            }
        }
        let capture = RetryCapture()
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
        defer { model.stopSession() }
        try model.account.accept(tokens()); try model.reload()
        await model.startSession(keyboard: false)
        model.toggleRecording(); model.toggleRecording()
        for _ in 0..<100 where model.status.phase != "error" { try await Task.sleep(for: .milliseconds(20)) }
        try #require(model.status.phase == "error" && model.status.canRetry)
        #expect(!model.microphoneActive && model.history.isEmpty && model.analytics.isEmpty)
        model.retry(); model.retry()
        for _ in 0..<100 where model.history.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
        let uploads = FixtureProtocol.router.captured().filter { $0.url?.path == "/v1/transcriptions" }
        #expect(uploads.count == 2)
        #expect(uploads.allSatisfy { $0.httpBody?.range(of: capture.wav) != nil })
        #expect(capture.starts == 1 && capture.recordings == 1 && !capture.active)
        #expect(model.history.count == 1 && model.analytics.count == 1)
        #expect(model.lastText == "Please meet tomorrow." && !model.status.canRetry)
    }
    @Test func rotatingRefreshIsSingleFlightAndPersisted() async throws {
        let refreshed = try JSONEncoder().encode(tokens("rotated"))
        FixtureProtocol.router.configure { _,_ in (200,refreshed) }
        let persistence = MemorySessionStore()
        let account = try AccountSession(api: api(), persistence: persistence)
        try account.accept(tokens())
        async let first = account.token(force: true)
        async let second = account.token(force: true)
        let values = try await [first,second]
        #expect(values == ["rotated", "rotated"])
        #expect(FixtureProtocol.router.captured().count == 1)
        #expect(persistence.value?.tokens.accessToken == "rotated")
    }
    @Test func actualKeychainRoundTripUsesDedicatedTestItem() throws {
        let persistence = KeychainSessionStore(service: "com.aliahad.wovoice.ios.tests.\(UUID().uuidString)")
        defer { try? persistence.remove() }
        #expect(try persistence.load() == nil)
        try persistence.save(StoredSession(tokens: tokens("first")))
        #expect(try persistence.load()?.tokens.accessToken == "first")
        try persistence.save(StoredSession(tokens: tokens("rotated")))
        #expect(try persistence.load()?.tokens.accessToken == "rotated")
        try persistence.remove()
        #expect(try persistence.load() == nil)
    }
    @Test func oneShotDictationSavesAndReleasesMicrophoneWithoutCancellingSync() async throws {
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        await model.startSession(keyboard: false)
        model.toggleRecording()
        #expect(model.recording && model.microphoneActive)
        model.toggleRecording()
        for _ in 0..<100 where model.history.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
        #expect(model.history.count == 1)
        #expect(model.analytics.count == 1)
        #expect(model.lastText == "I would like to meet Professor Rahman tomorrow.")
        #expect(!model.keyboardSessionActive && !model.recording && !model.microphoneActive)
        try await Task.sleep(for: .milliseconds(100))
        #expect(model.message == nil)
    }
    @Test func recordingStopsAfterSixtySecondsEvenWhenSampleDeliveryStalls() async throws {
        var instant = ContinuousClock.now
        let model = try DevelopmentFixture.makeModel(clock: { instant })
        defer { model.stopSession() }
        await model.startSession(keyboard: false)
        model.toggleRecording()
        instant = instant.advanced(by: .seconds(61))
        for _ in 0..<100 where model.history.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
        #expect(!model.recording && model.history.count == 1)
        // Fixture sample duration is one second; completion is driven by the monotonic deadline.
        #expect(model.history.first?.payload?["audioDurationMs"]?.number == 1000)
    }
    @Test func keyboardSessionExpiresWithoutDependingOnTheWallClock() async throws {
        var instant = ContinuousClock.now
        let model = try DevelopmentFixture.makeModel(clock: { instant })
        defer { model.stopSession() }
        await model.startSession()
        #expect(model.keyboardSessionActive)
        instant = instant.advanced(by: .seconds(301))
        try await Task.sleep(for: .milliseconds(400))
        #expect(!model.keyboardSessionActive && model.status.owner == nil)
        #expect(model.status.message.contains("five minutes"))
    }
    @Test func keyboardStartFinishCancelCommandsAreNotLost() async throws {
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        await model.startSession()
        let bridge = KeyboardBridge(); let request = UUID(); let now = Date.now
        for (i, action) in [KeyboardCommand.Action.start, .finish, .cancel].enumerated() {
            try bridge.enqueue(.init(id: UUID(), request: request, owner: "fixture", action: action,
                                     createdAt: now.addingTimeInterval(Double(i)*0.001), context: nil, sentenceStart: true))
        }
        try await Task.sleep(for: .milliseconds(500))
        #expect(model.history.isEmpty)
        #expect(model.status.phase == "ready")
        #expect(model.status.result == nil)
    }
    @Test func recreatedKeyboardCanReplaceAnOrphanedRequest() async throws {
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        await model.startSession()
        let bridge = KeyboardBridge(); let first = UUID(); let second = UUID(); let now = Date.now
        try bridge.enqueue(.init(id: UUID(), request: first, owner: "fixture", action: .start, createdAt: now, context: nil, sentenceStart: true))
        try bridge.enqueue(.init(id: UUID(), request: second, owner: "fixture", action: .start, createdAt: now.addingTimeInterval(0.001), context: nil, sentenceStart: true))
        try bridge.renewLease(request: second, owner: "fixture")
        try await Task.sleep(for: .milliseconds(500))
        #expect(model.recording && model.status.request == second)
        #expect(model.history.isEmpty)
    }
    @Test func keyboardEvictionOrPermissionLossCancelsCapture() async throws {
        var instant = ContinuousClock.now
        let model = try DevelopmentFixture.makeModel(clock: { instant })
        defer { model.stopSession() }
        await model.startSession()
        let bridge = KeyboardBridge(); let request = UUID()
        try bridge.renewLease(request: request, owner: "fixture")
        try bridge.enqueue(.init(id: UUID(), request: request, owner: "fixture", action: .start, createdAt: .now, context: nil, sentenceStart: true))
        try await Task.sleep(for: .milliseconds(500))
        #expect(model.recording)
        instant = instant.advanced(by: .seconds(5))
        try await Task.sleep(for: .milliseconds(300))
        #expect(!model.recording && model.status.phase == "ready")
        #expect(model.history.isEmpty && model.status.result == nil)
    }
    @Test func liveActivityRegistersAndStopIntentEndsKeyboardSession() async throws {
        try #require(ActivityAuthorizationInfo().areActivitiesEnabled, "Enable Live Activities in this test simulator.")
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        await model.startSession()
        #expect(model.keyboardSessionActive)
        #expect(!Activity<SessionActivityAttributes>.activities.isEmpty)
        _ = try await StopKeyboardSessionIntent().perform()
        try await Task.sleep(for: .milliseconds(500))
        #expect(!model.keyboardSessionActive)
        #expect(model.status.result == nil)
    }
    @Test func stopLinkCancelsCaptureAndAcceptsOnlyItsFixedRoute() async throws {
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        await model.startSession()
        model.toggleRecording()
        #expect(model.recording)
        for route in ["wovoice-ios://callback?code=example", "https://example.test/session/stop", "wovoice-ios://session/start", "wovoice-ios://session/stop?extra=1"] {
            #expect(!model.handleSessionURL(URL(string: route)!))
            #expect(model.recording)
        }
        #expect(model.handleSessionURL(SessionControl.stopURL))
        #expect(!model.recording && !model.keyboardSessionActive)
        #expect(model.status.request == nil && model.status.result == nil)
        #expect(model.history.isEmpty)
        let runtime = AppRuntime(); runtime.requestKeyboardSession(); runtime.cancelKeyboardSessionRequest()
        #expect(!runtime.takeKeyboardSession(whileActive: true))
    }
    @Test func launchClearsOrphanedActivityWithoutEndingTheNewSession() async throws {
        try #require(ActivityAuthorizationInfo().areActivitiesEnabled)
        let expiry = Date.now.addingTimeInterval(300)
        let orphan = try Activity.request(attributes: SessionActivityAttributes(), content: .init(state: .init(expiresAt: expiry, phase: "Microphone active · Ready"), staleDate: expiry), pushType: nil)
        let orphanID = orphan.id
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        await model.startSession()
        try await Task.sleep(for: .milliseconds(400))
        let remaining = Activity<SessionActivityAttributes>.activities.map(\.id)
        #expect(!remaining.contains(orphanID))
        #expect(!remaining.isEmpty && model.keyboardSessionActive)
    }
    @Test func audioInterruptionsRouteChangesAndResetCancelPendingDictation() async throws {
        let events: [(Notification.Name, [String: UInt])] = [
            (AVAudioSession.interruptionNotification, [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue]),
            (AVAudioSession.routeChangeNotification, [AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue]),
            (AVAudioSession.mediaServicesWereResetNotification, [:])
        ]
        for (name, info) in events {
            let model = try DevelopmentFixture.makeModel()
            await model.startSession()
            model.toggleRecording()
            #expect(model.microphoneActive && model.recording)
            NotificationCenter.default.post(name: name, object: nil, userInfo: info)
            for _ in 0..<20 where model.microphoneActive { try await Task.sleep(for: .milliseconds(10)) }
            #expect(!model.microphoneActive && !model.keyboardSessionActive)
            #expect(model.status.request == nil && model.status.result == nil)
            #expect(model.history.isEmpty && model.analytics.isEmpty)
            model.stopSession()
        }
    }
    @Test func historyOffKeepsAnalyticsAndNoDictatedTextRecord() async throws {
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        model.keepHistory = false; model.savePreferences()
        await model.startSession(keyboard: false)
        model.toggleRecording(); model.toggleRecording()
        for _ in 0..<100 where model.analytics.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
        #expect(model.history.isEmpty)
        #expect(model.analytics.count == 1)
        #expect(model.analytics.first?.payload?["text"] == nil)
    }
    @Test func accountSwitchClearsTransientTextAndKeepsStoredAccountsSeparate() throws {
        let model = try DevelopmentFixture.makeModel()
        defer { model.stopSession() }
        model.lastText = "Previous account dictation"
        try model.account.accept(tokens(owner: "bob")); try model.reload()
        #expect(model.lastText.isEmpty && model.dictionary.isEmpty && model.history.isEmpty)
        #expect(model.profile == nil && model.sessions.isEmpty)
        #expect(try model.store.records(owner: "fixture", type: .dictionary).count == 1)
        try model.store.save(owner: "bob", record: .init(id: "bob-local", type: .dictionary, payload: RecordPayload.dictionary("Rahman")))
        try model.store.merge(owner: "bob", remote: .init(id: "bob-remote", type: .dictionary, version: 2, payload: RecordPayload.dictionary("Rahman")))
        #expect(try model.store.records(owner: "bob", type: .dictionary).count == 1)
        #expect(try model.store.records(owner: "fixture", type: .dictionary).count == 1)
    }
    @Test func invalidatedSessionImmediatelyHidesAccountData() throws {
        let model = try DevelopmentFixture.makeModel()
        #expect(!model.dictionary.isEmpty)
        model.lastText = "Private dictation"
        try model.account.clear()
        #expect(model.owner == nil && model.dictionary.isEmpty && model.history.isEmpty && model.analytics.isEmpty)
        #expect(model.lastText.isEmpty && model.status.result == nil)
    }
    @Test func signOutClearsLocalCredentialsEvenWhenRevocationFails() async throws {
        for status in [503, 401] {
            FixtureProtocol.router.configure { _,_ in
                (status, Data((status == 401
                    ? #"{"error":{"code":"AUTH_REQUIRED","message":"Sign in again.","retryable":false}}"#
                    : #"{"error":{"code":"TEMPORARY","message":"Unavailable.","retryable":true}}"#).utf8))
            }
            let persistence = MemorySessionStore(); let capture = RetryCapture()
            let model = try AppModel(api: api(), persistence: persistence, capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
            defer { model.stopSession() }
            try model.account.accept(tokens())
            try model.store.save(owner: "alice", record: .init(id: "local", type: .dictionary, payload: RecordPayload.dictionary("Rahman")))
            try model.reload()
            await model.startSession(keyboard: false); model.toggleRecording()
            #expect(model.recording && model.microphoneActive)
            await model.logout()
            #expect(model.owner == nil && persistence.value == nil)
            #expect(!model.microphoneActive && !model.recording && !model.busy)
            #expect(model.history.isEmpty && model.dictionary.isEmpty && model.lastText.isEmpty)
            #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
            #expect(status == 503 ? model.message?.contains("server session") == true : model.message == nil)
        }
    }
    @Test func clearingLocalDataWorksOfflineAndAfterSignOut() async throws {
        FixtureProtocol.router.configure(failure: .notConnectedToInternet) { _,_ in (200, Data()) }
        let persistence = MemorySessionStore()
        let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        defer { model.stopSession() }
        try model.account.accept(tokens())
        for owner in ["alice", "bob"] {
            for type in RecordType.allCases {
                let payload: Payload
                switch type {
                case .dictionary: payload = RecordPayload.dictionary("Rahman")
                case .history: payload = ["createdAtMs": .number(1), "text": .string("Synthetic record")]
                case .analytics: payload = ["createdAtMs": .number(1)]
                }
                try model.store.save(owner: owner, record: .init(id: type.rawValue, type: type, payload: payload))
            }
            try model.store.setMetadata("haptics", owner: owner, value: false)
        }
        try model.reload()
        await model.clearLocalData()
        #expect(model.owner == nil && persistence.value == nil && !model.busy)
        for owner in ["alice", "bob"] {
            for type in RecordType.allCases { #expect(try model.store.records(owner: owner, type: type).isEmpty) }
            #expect(try model.store.pending(owner: owner, historyEnabled: true).isEmpty)
            #expect(try model.store.metadata("haptics", owner: owner, default: true))
        }
        #expect(model.message?.contains("server session") == true)
        let requests = FixtureProtocol.router.captured().count
        try model.store.save(owner: "bob", record: .init(id: "signed-out-local", type: .dictionary, payload: RecordPayload.dictionary("Retained data")))
        await model.clearLocalData()
        #expect(try model.store.records(owner: "bob", type: .dictionary).isEmpty)
        #expect(FixtureProtocol.router.captured().count == requests)
    }
    @Test func delayedSignOutCannotClearANewerSessionForTheSameAccount() async throws {
        for clearData in [false, true] {
            let release = DispatchSemaphore(value: 0)
            defer { release.signal() }
            FixtureProtocol.router.configure { _,_ in
                _ = release.wait(timeout: .now() + 2)
                return (200, Data())
            }
            let persistence = MemorySessionStore()
            let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
            defer { model.stopSession() }
            try model.account.accept(tokens())
            try model.store.save(owner: "alice", record: .init(id: "local", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
            try model.reload()
            let action = Task { if clearData { await model.clearLocalData() } else { await model.logout() } }
            defer { action.cancel() }
            for _ in 0..<100 where FixtureProtocol.router.captured().isEmpty { try await Task.sleep(for: .milliseconds(10)) }
            try #require(FixtureProtocol.router.captured().first?.url?.path == "/v1/auth/logout")
            try model.account.accept(tokens("replacement")); try model.reload()
            release.signal()
            await action.value
            #expect(model.owner == "alice" && persistence.value?.tokens.accessToken == "replacement")
            #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
            #expect(model.message == nil && !model.busy)
        }
    }
    @Test func secureStorageFailureKeepsLocalDataAndReportsTheFailure() async throws {
        for clearData in [false, true] {
            FixtureProtocol.router.configure { _,_ in (200, Data()) }
            let persistence = MemorySessionStore()
            let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
            defer { model.stopSession() }
            try model.account.accept(tokens())
            try model.store.save(owner: "alice", record: .init(id: "local", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
            try model.reload()
            persistence.removalError = APIError(code: "KEYCHAIN_UNAVAILABLE", message: "Secure storage is locked.")
            if clearData { await model.clearLocalData() } else { await model.logout() }
            #expect(model.owner == "alice" && persistence.value != nil)
            #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
            #expect(model.message == "Secure storage is locked." && !model.busy)
        }
    }
    @Test func signInDuringSessionClearingPreventsStaleCleanup() async throws {
        for clearData in [false, true] {
            FixtureProtocol.router.configure { _,_ in (503, Data()) }
            let persistence = MemorySessionStore()
            let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
            try model.account.accept(tokens())
            try model.store.save(owner: "alice", record: .init(id: "local", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
            try model.reload()
            let previous = model.account.onCleared
            defer { model.account.onCleared = previous; model.stopSession() }
            let replacement = try tokens("replacement")
            model.account.onCleared = {
                previous?()
                do { try model.account.accept(replacement); try model.reload() }
                catch { Issue.record(error) }
            }
            if clearData { await model.clearLocalData() } else { await model.logout() }
            #expect(model.owner == "alice" && persistence.value?.tokens.accessToken == "replacement")
            #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
            #expect(model.message == nil && !model.busy)
        }
    }
    @Test func nativeAudioConverts48kTo16kAndDiscardsCancelledCapture() throws {
        let format = try #require(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false))
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 48000))
        buffer.frameLength = 48000
        let channel = try #require(buffer.floatChannelData?[0])
        for i in 0..<48000 { channel[i] = Float(sin(Double(i)*0.1)*0.03) }
        let audio = try AudioAccumulator(input: format)
        audio.begin(); audio.ingest(buffer)
        let samples = audio.finish()
        #expect((15000...16000).contains(samples.count))
        #expect(samples.contains { abs(Int($0)) > 500 })
        audio.begin()
        #expect(audio.finish().isEmpty)
        audio.begin()
        for _ in 0..<62 { audio.ingest(buffer) }
        #expect(audio.finish().count == 960000)
    }
    @Test func rejectedRefreshRemovesLocalSession() async throws {
        FixtureProtocol.router.configure { _,_ in (401,Data(#"{"error":{"code":"AUTH_REQUIRED","message":"Sign in again.","retryable":false}}"#.utf8)) }
        let persistence = MemorySessionStore()
        let account = try AccountSession(api: api(), persistence: persistence); try account.accept(tokens())
        await #expect(throws: APIError.self) { _ = try await account.token(force: true) }
        #expect(account.user == nil && persistence.value == nil)
    }
    @Test func expiredAccessRetriesExactlyOnce() async throws {
        let rotated = try JSONEncoder().encode(tokens("rotated"))
        FixtureProtocol.router.configure { request,_ in
            if request.url?.path == "/v1/auth/refresh" { return (200,rotated) }
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer access" { return (401,Data(#"{"error":{"code":"TOKEN_EXPIRED","message":"Refresh","retryable":true}}"#.utf8)) }
            return (200,Data(#"{"value":42}"#.utf8))
        }
        let account = try AccountSession(api: api(), persistence: MemorySessionStore()); try account.accept(tokens())
        let result: Payload = try await account.call { try await account.api.request("/test", token: $0) }
        #expect(result["value"]?.number == 42)
        #expect(FixtureProtocol.router.captured().count == 3)
    }
    @Test func syncAppliesHistoryOffBeforeAnyUploadAndPersistsCursor() async throws {
        FixtureProtocol.router.configure { request,_ in
            if request.httpMethod == "GET" {
                return (200,Data(#"{"nextCursor":4,"hasMore":false,"settings":{"historySyncEnabled":false,"historyRetentionDays":null,"legacyVault":false},"items":[]}"#.utf8))
            }
            return (200,Data(#"{"applied":[{"id":"word","type":"dictionary","version":1},{"id":"usage","type":"analytics","version":1}]}"#.utf8))
        }
        let account = try AccountSession(api: api(), persistence: MemorySessionStore()); try account.accept(tokens())
        let store = try RecordStore(url: URL(fileURLWithPath: ":memory:"))
        try store.save(owner: "alice", record: .init(id: "private", type: .history, payload: ["text": .string("Never upload"), "createdAtMs": .number(1)]))
        try store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Ahad")))
        try store.save(owner: "alice", record: .init(id: "usage", type: .analytics, payload: ["createdAtMs": .number(1)]))
        try await SyncService(account: account, store: store).run()
        let uploads = FixtureProtocol.router.captured().filter { $0.httpMethod == "POST" }
        #expect(uploads.count == 1)
        let body = String(data: try #require(uploads.first?.httpBody), encoding: .utf8) ?? ""
        #expect(!body.contains("Never upload") && !body.contains("history"))
        #expect(try store.metadata("cursor", owner: "alice", default: 0) == 4)
        #expect(try store.records(owner: "alice", type: .history).count == 1)
        #expect(try store.pending(owner: "alice", historyEnabled: false).isEmpty)
    }
    @Test func conflictRebasesLocalDeletionAndRetries() async throws {
        FixtureProtocol.router.configure { request, index in
            if request.httpMethod == "GET" { return (200,Data(#"{"nextCursor":0,"hasMore":false,"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false},"items":[]}"#.utf8)) }
            if index == 2 { return (409,Data(#"{"error":{"code":"SYNC_CONFLICT","message":"Conflict","retryable":true},"conflicts":[{"id":"word","type":"dictionary","version":4,"deleted":false,"payload":{"term":"Ahad","normalizedTerm":"ahad"}}]}"#.utf8)) }
            return (200,Data(#"{"applied":[{"id":"word","type":"dictionary","version":5}]}"#.utf8))
        }
        let account = try AccountSession(api: api(), persistence: MemorySessionStore()); try account.accept(tokens())
        let store = try RecordStore(url: URL(fileURLWithPath: ":memory:"))
        try store.save(owner: "alice", record: .init(id: "word", type: .dictionary, version: 1, deleted: true, payload: nil))
        try await SyncService(account: account, store: store).run()
        #expect(try store.pending(owner: "alice", historyEnabled: true).isEmpty)
        let record = try #require(store.records(owner: "alice", type: .dictionary, includeDeleted: true).first)
        #expect(record.deleted && record.version == 5)
    }
    @Test func fullCloudVocabularyKeepsNewWordsLocalWhileOtherRecordsSyncAndClearsWarningAfterRecovery() async throws {
        let pull = Data(#"{"nextCursor":0,"hasMore":false,"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false},"items":[]}"#.utf8)
        let warning = "Cloud vocabulary is full. New words remain on this device."
        FixtureProtocol.router.configure { request,_ in
            if request.httpMethod == "GET" { return (200, pull) }
            let batch = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any]
            let items = batch?["items"] as? [[String: Any]] ?? []
            if items.contains(where: { $0["id"] as? String == "word" }) {
                return (409, Data("{\"error\":{\"code\":\"STORAGE_LIMIT_REACHED\",\"message\":\"\(warning)\",\"retryable\":false}}".utf8))
            }
            let applied = items.map { ["id": $0["id"] ?? "", "type": $0["type"] ?? "", "version": ($0["baseVersion"] as? Int ?? 0) + 1] }
            return (200, (try? JSONSerialization.data(withJSONObject: ["applied": applied])) ?? Data())
        }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens()); try model.reload()
        let store = model.store
        let service = SyncService(account: model.account, store: store)
        try store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Ahad")))
        try store.save(owner: "alice", record: .init(id: "edit", type: .dictionary, version: 2, payload: RecordPayload.dictionary("Rahman")))
        try store.save(owner: "alice", record: .init(id: "deleted", type: .dictionary, version: 1, deleted: true, payload: nil))
        try store.save(owner: "alice", record: .init(id: "history", type: .history, payload: ["text": .string("Synthetic history")]))
        try store.save(owner: "alice", record: .init(id: "usage", type: .analytics, payload: ["createdAtMs": .number(1)]))
        try await service.run(); try model.reload()
        #expect(try store.pending(owner: "alice", historyEnabled: true).map(\.record.id) == ["word"])
        #expect(try store.records(owner: "alice", type: .dictionary).count == 2)
        #expect(try store.metadata("syncWarning", owner: "alice", default: "") == warning)
        let lastSync: Date? = try store.metadata("lastSync", owner: "alice", default: nil as Date?)
        #expect(lastSync != nil)
        #expect(model.lastSyncedAt == lastSync && model.syncWarning == warning)
        try model.account.accept(tokens(owner: "bob")); try model.reload()
        #expect(model.lastSyncedAt == nil && model.syncWarning == nil)
        try model.account.accept(tokens()); try model.reload()
        #expect(model.lastSyncedAt == lastSync && model.syncWarning == warning)
        FixtureProtocol.router.configure { request,_ in
            if request.httpMethod == "GET" { return (200, pull) }
            return (200,Data(#"{"applied":[{"id":"word","type":"dictionary","version":1}]}"#.utf8))
        }
        try await service.run(); try model.reload()
        #expect(try store.pending(owner: "alice", historyEnabled: true).isEmpty)
        #expect(try store.metadata("syncWarning", owner: "alice", default: "").isEmpty)
        #expect(model.syncWarning == nil && model.lastSyncedAt != nil)
    }
    @Test func failedSyncKeepsThePreviousSyncTimeAndSigningOutHidesIt() async throws {
        FixtureProtocol.router.configure { _,_ in (503, Data(#"{"error":{"code":"TEMPORARY","message":"Try again later.","retryable":true}}"#.utf8)) }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens())
        let previous = Date(timeIntervalSince1970: 1_790_000_000)
        try model.store.setMetadata("lastSync", owner: "alice", value: previous)
        try model.store.setMetadata("syncWarning", owner: "alice", value: "Previous cloud warning")
        try model.reload()
        await model.sync()
        #expect(model.lastSyncedAt == previous && model.syncWarning == "Previous cloud warning")
        #expect(model.message == "Try again later.")
        try model.account.clear()
        #expect(model.lastSyncedAt == nil && model.syncWarning == nil)
    }
    @Test func largeOfflineQueueReportsRemainingWorkAndFinishesOnTheNextSync() async throws {
        FixtureProtocol.router.configure { request,_ in
            if request.httpMethod == "GET" {
                return (200, Data(#"{"nextCursor":0,"hasMore":false,"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false},"items":[]}"#.utf8))
            }
            let batch = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any]
            let items = batch?["items"] as? [[String: Any]] ?? []
            let applied = items.map { ["id": $0["id"] ?? "", "type": $0["type"] ?? "", "version": ($0["baseVersion"] as? Int ?? 0) + 1] }
            return (200, (try? JSONSerialization.data(withJSONObject: ["applied": applied])) ?? Data())
        }
        let account = try AccountSession(api: api(), persistence: MemorySessionStore()); try account.accept(tokens())
        let store = try RecordStore(url: URL(fileURLWithPath: ":memory:"))
        try store.transaction {
            for index in 0..<2001 {
                try store.save(owner: "alice", record: .init(id: "history-\(index)", type: .history, payload: ["text": .string("Synthetic history \(index)")]))
            }
        }
        let service = SyncService(account: account, store: store)
        try await service.run()
        #expect(try store.pending(owner: "alice", historyEnabled: true).count == 1)
        #expect(FixtureProtocol.router.captured().filter { $0.httpMethod == "POST" }.count == 20)
        #expect(try store.metadata("syncWarning", owner: "alice", default: "").contains("waiting"))
        try await service.run()
        #expect(try store.pending(owner: "alice", historyEnabled: true).isEmpty)
        #expect(try store.metadata("syncWarning", owner: "alice", default: "").isEmpty)
    }
    @Test func accountDeletionVerifiesFreshEmailBeforeDeletingOnlyTheCurrentAccount() async throws {
        let refreshed = try JSONEncoder().encode(tokens("rotated"))
        FixtureProtocol.router.configure { request,_ in
            request.url?.path == "/v1/auth/refresh" ? (200, refreshed) : (200, Data())
        }
        let auth = FixtureAuthentication(); let persistence = MemorySessionStore(); let capture = RetryCapture()
        persistence.value = StoredSession(tokens: try tokens(), now: Date(timeIntervalSince1970: 0))
        let model = try AppModel(api: api(), persistence: persistence, capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"), auth: auth)
        for type in RecordType.allCases {
            try model.store.save(owner: "alice", record: .init(id: type.rawValue, type: type, payload: ["text": .string("Synthetic local data")]))
        }
        try model.store.save(owner: "bob", record: .init(id: "other", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
        try model.store.setMetadata("context", owner: "alice", value: ContextPreferences(level: .nearby))
        try model.reload(); try await capture.startSession()
        await model.deleteAccount()
        #expect(auth.calls.count == 1 && auth.calls.first?.deletion == true && auth.calls.first?.base == api().baseURL)
        let request = try #require(FixtureProtocol.router.captured().last)
        #expect(FixtureProtocol.router.captured().count == 2 && request.httpMethod == "DELETE" && request.url?.path == "/v1/me")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer rotated")
        let body = try JSONDecoder().decode(Payload.self, from: #require(request.httpBody))
        #expect(body["reauthToken"]?.string == "fresh-verification-code-for-tests")
        #expect(model.owner == nil && persistence.value == nil && !model.busy && !model.microphoneActive)
        for type in RecordType.allCases { #expect(try model.store.records(owner: "alice", type: type).isEmpty) }
        #expect(try model.store.records(owner: "bob", type: .dictionary).count == 1)
        #expect(try model.store.metadata("context", owner: "alice", default: ContextPreferences()).level == .off)
    }
    @Test func cancellingDeletionVerificationLeavesTheAccountAndLocalDataIntact() async throws {
        let auth = FixtureAuthentication()
        auth.result = .failure(ASWebAuthenticationSessionError(.canceledLogin))
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"), auth: auth)
        try model.account.accept(tokens())
        try model.store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
        try model.reload(); model.message = "Previous message"
        FixtureProtocol.router.configure { _,_ in (500, Data()) }
        await model.deleteAccount()
        #expect(model.owner == "alice" && !model.busy && model.message == nil)
        #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
        #expect(FixtureProtocol.router.captured().isEmpty)
    }
    @Test func repeatedDeletionDoesNotReleaseTheBusyStateOrOpenAnotherVerification() async throws {
        let auth = FixtureAuthentication(); auth.paused = true
        defer { auth.complete(.failure(CancellationError())) }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"), auth: auth)
        try model.account.accept(tokens()); FixtureProtocol.router.configure { _,_ in (200, Data()) }
        let deletion = Task { await model.deleteAccount() }
        for _ in 0..<100 where !auth.waiting { try await Task.sleep(for: .milliseconds(10)) }
        try #require(auth.waiting && model.busy)
        await model.deleteAccount()
        #expect(auth.calls.count == 1 && model.busy && model.message == nil)
        auth.complete(.failure(CancellationError())); await deletion.value
        #expect(!model.busy && model.owner == "alice" && model.message == nil)
        #expect(FixtureProtocol.router.captured().isEmpty)
    }
    @Test func deletionVerificationCannotBeReusedAfterAnAccountSessionChanges() async throws {
        for replacement in ["alice", "bob"] {
            let auth = FixtureAuthentication(); auth.paused = true
            defer { auth.complete(.failure(CancellationError())) }
            let persistence = MemorySessionStore()
            let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"), auth: auth)
            try model.account.accept(tokens())
            try model.store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
            try model.reload(); FixtureProtocol.router.configure { _,_ in (200, Data()) }
            let deletion = Task { await model.deleteAccount() }
            for _ in 0..<100 where !auth.waiting { try await Task.sleep(for: .milliseconds(10)) }
            try #require(auth.waiting)
            try model.account.accept(tokens("replacement", owner: replacement)); try model.reload()
            auth.complete(); await deletion.value
            #expect(model.owner == replacement && persistence.value?.tokens.accessToken == "replacement" && !model.busy)
            #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
            #expect(FixtureProtocol.router.captured().isEmpty && model.message == nil)
        }
    }
    @Test func rejectedAccountDeletionKeepsTheLocalPartitionAndReportsTheError() async throws {
        FixtureProtocol.router.configure { _,_ in (403, Data(#"{"error":{"code":"REAUTH_REQUIRED","message":"Verify your email again.","retryable":false}}"#.utf8)) }
        let auth = FixtureAuthentication(); let persistence = MemorySessionStore()
        let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"), auth: auth)
        try model.account.accept(tokens())
        try model.store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
        try model.reload(); await model.deleteAccount()
        #expect(model.owner == "alice" && persistence.value != nil && !model.busy)
        #expect(model.message == "Verify your email again.")
        #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
    }
    @Test func restrictedSavedSessionsCannotStartTheMicrophoneOrSync() async throws {
        for state in ["suspended", "banned"] {
            let capture = RetryCapture(); let persistence = MemorySessionStore()
            persistence.value = StoredSession(tokens: try tokens(state: state))
            let model = try AppModel(api: api(), persistence: persistence, capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
            defer { model.stopSession() }
            try model.store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
            try model.reload(); FixtureProtocol.router.configure { _,_ in (503, Data()) }
            await model.startSession(keyboard: false); await model.sync()
            #expect(capture.starts == 0 && !model.microphoneActive)
            #expect(FixtureProtocol.router.captured().isEmpty)
            #expect(model.owner == "alice" && model.dictionary.count == 1)
        }
    }
    @Test func restrictionDuringDictationStopsTheKeyboardSessionAndSurvivesRestart() async throws {
        FixtureProtocol.router.configure { _,_ in (403, Data(#"{"error":{"code":"ACCOUNT_SUSPENDED","message":"Temporarily unavailable.","retryable":false}}"#.utf8)) }
        let capture = RetryCapture(); let persistence = MemorySessionStore()
        let model = try AppModel(api: api(), persistence: persistence, capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
        defer { model.stopSession() }
        try model.account.accept(tokens()); try model.reload()
        await model.startSession(); model.toggleRecording(); model.toggleRecording()
        for _ in 0..<100 where model.status.phase == "processing" { try await Task.sleep(for: .milliseconds(10)) }
        #expect(!model.microphoneActive && !model.keyboardSessionActive && !model.status.canRetry)
        #expect(model.account.user?.accountStatus.state == "suspended")
        #expect(persistence.value?.tokens.user.accountStatus.publicMessage == "Temporarily unavailable.")
        let restarted = try AccountSession(api: api(), persistence: persistence)
        #expect(restarted.user?.accountStatus.state == "suspended")
        await model.startSession(keyboard: false)
        #expect(capture.starts == 1)
    }
    @Test func restrictionAfterTokenRefreshIsPersisted() async throws {
        let refreshed = try JSONEncoder().encode(tokens("rotated"))
        FixtureProtocol.router.configure { request,_ in
            if request.url?.path == "/v1/auth/refresh" { return (200,refreshed) }
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer access" {
                return (401,Data(#"{"error":{"code":"TOKEN_EXPIRED","message":"Expired","retryable":false}}"#.utf8))
            }
            return (403,Data(#"{"error":{"code":"ACCOUNT_BANNED","message":"Cloud services unavailable.","retryable":false}}"#.utf8))
        }
        let persistence = MemorySessionStore(); let account = try AccountSession(api: api(), persistence: persistence)
        try account.accept(tokens())
        await #expect(throws: APIError.self) { try await account.call { try await api().empty("/v1/transcriptions", method: "POST", token: $0) } }
        #expect(account.user?.accountStatus.state == "banned")
        #expect(persistence.value?.tokens.user.accountStatus.state == "banned")
        #expect(FixtureProtocol.router.captured().count == 3)
    }
    @Test func refreshedProfileRestoresARestrictedSessionAndPersistsItsStatus() async throws {
        let user = try JSONEncoder().encode(tokens().user)
        let profile = Data("{\"user\":\(String(decoding:user,as:UTF8.self))}".utf8)
        FixtureProtocol.router.configure { _,_ in (200,profile) }
        let persistence = MemorySessionStore(); let capture = RetryCapture()
        persistence.value = StoredSession(tokens: try tokens(state: "suspended"))
        let model = try AppModel(api: api(), persistence: persistence, capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
        defer { model.stopSession() }
        await model.refreshProfile()
        #expect(model.account.user?.accountStatus.state == "active")
        #expect(persistence.value?.tokens.user.accountStatus.state == "active")
        await model.startSession(keyboard: false)
        #expect(capture.starts == 1 && model.microphoneActive)
    }
    @Test func foregroundRefreshLoadsRemoteChangesWithoutStartingTheMicrophone() async throws {
        let user = try JSONEncoder().encode(tokens().user)
        let profile = Data("{\"user\":\(String(decoding:user,as:UTF8.self))}".utf8)
        FixtureProtocol.router.configure { request,_ in
            if request.url?.path == "/v1/me" { return (200, profile) }
            return (200, Data(#"{"nextCursor":1,"hasMore":false,"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false},"items":[{"id":"remote-word","type":"dictionary","version":1,"deleted":false,"payload":{"term":"Foreground word","normalizedTerm":"foreground word","status":"confirmed"}}]}"#.utf8))
        }
        let capture = RetryCapture()
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
        await model.refreshForForeground()
        #expect(FixtureProtocol.router.captured().isEmpty)
        try model.account.accept(tokens()); try model.reload()
        await model.refreshForForeground()
        #expect(model.profile?.user.id == "alice")
        #expect(model.dictionary.first?.payload?["term"]?.string == "Foreground word")
        #expect(model.lastSyncedAt != nil && !model.syncing && !model.refreshingProfile)
        #expect(capture.starts == 0 && !model.microphoneActive)
        #expect(FixtureProtocol.router.captured().map { $0.url?.path } == ["/v1/me", "/v2/sync"])
    }
    @Test func foregroundRefreshSkipsAuthenticationWorkAndCoalescesRepeatedActivation() async throws {
        let release = DispatchSemaphore(value: 0); defer { release.signal() }
        let user = try JSONEncoder().encode(tokens().user)
        let profile = Data("{\"user\":\(String(decoding:user,as:UTF8.self))}".utf8)
        FixtureProtocol.router.configure { request,_ in
            if request.url?.path == "/v1/me" { _ = release.wait(timeout: .now() + 2); return (200,profile) }
            return (200,Data(#"{"nextCursor":0,"hasMore":false,"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false},"items":[]}"#.utf8))
        }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens()); model.busy = true
        await model.refreshForForeground()
        #expect(FixtureProtocol.router.captured().isEmpty)
        model.busy = false
        let refresh = Task { await model.refreshForForeground() }
        for _ in 0..<100 where FixtureProtocol.router.captured().isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        try #require(FixtureProtocol.router.captured().count == 1)
        await model.refreshForForeground()
        #expect(FixtureProtocol.router.captured().count == 1)
        release.signal(); await refresh.value
        #expect(FixtureProtocol.router.captured().map { $0.url?.path } == ["/v1/me", "/v2/sync"])
        #expect(!model.refreshingProfile && !model.microphoneActive)
    }
    @Test func failedForegroundProfileRefreshDoesNotUploadOrAdvanceTheSyncTime() async throws {
        FixtureProtocol.router.configure { _,_ in (503,Data(#"{"error":{"code":"TEMPORARY","message":"Please try again.","retryable":true}}"#.utf8)) }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens())
        try model.store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
        try model.reload(); await model.refreshForForeground()
        #expect(FixtureProtocol.router.captured().map { $0.url?.path } == ["/v1/me"])
        #expect(model.dictionary.count == 1 && model.lastSyncedAt == nil)
        #expect(model.message == "Please try again." && !model.microphoneActive)
    }
    @Test func revokingTheCurrentDeviceSignsOutWithoutFetchingAgainAndKeepsLocalRecords() async throws {
        let release = DispatchSemaphore(value: 0); defer { release.signal() }
        FixtureProtocol.router.configure { request,_ in
            if request.httpMethod == "GET" { return (200,Data(#"{"sessions":[{"id":"current-device","deviceName":"Simulator","createdAt":1,"lastSeenAt":2,"current":1}]}"#.utf8)) }
            _ = release.wait(timeout: .now() + 2); return (200,Data())
        }
        let persistence = MemorySessionStore(); let capture = RetryCapture()
        let model = try AppModel(api: api(), persistence: persistence, capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens())
        try model.store.save(owner: "alice", record: .init(id: "word", type: .dictionary, payload: RecordPayload.dictionary("Keep this")))
        try model.reload(); await model.loadSessions(); try await capture.startSession()
        let device = try #require(model.sessions.first)
        let revocation = Task { await model.revoke(device) }
        for _ in 0..<100 where FixtureProtocol.router.captured().count == 1 { try await Task.sleep(for: .milliseconds(10)) }
        try #require(FixtureProtocol.router.captured().count == 2)
        #expect(!model.microphoneActive && model.busy)
        release.signal(); await revocation.value
        #expect(model.owner == nil && persistence.value == nil && !model.microphoneActive && !model.busy)
        #expect(model.sessions.isEmpty && model.message == nil)
        #expect(FixtureProtocol.router.captured().map { $0.url?.path } == ["/v1/me/sessions", "/v1/me/sessions/current-device"])
        #expect(try model.store.records(owner: "alice", type: .dictionary).count == 1)
    }
    @Test func revokingAnotherDeviceReloadsItsListWithoutSigningOutLocally() async throws {
        FixtureProtocol.router.configure { request,index in
            if request.httpMethod == "DELETE" { return (200,Data()) }
            let devices = index == 1 ? #"[{"id":"other-device","deviceName":"Other device","createdAt":1,"lastSeenAt":2,"current":0},{"id":"current-device","deviceName":"Simulator","createdAt":1,"lastSeenAt":2,"current":1}]"# : #"[{"id":"current-device","deviceName":"Simulator","createdAt":1,"lastSeenAt":2,"current":1}]"#
            return (200,Data("{\"sessions\":\(devices)}".utf8))
        }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens()); await model.loadSessions()
        let device = try #require(model.sessions.first(where: { $0.current == 0 }))
        await model.revoke(device)
        #expect(model.owner == "alice" && !model.busy && model.message == nil)
        #expect(model.sessions.map(\.id) == ["current-device"])
        #expect(FixtureProtocol.router.captured().map { $0.httpMethod } == ["GET", "DELETE", "GET"])
    }
    @Test func deviceRevocationRejectsAListFromAReplacedSession() async throws {
        for owner in ["alice", "bob"] {
            FixtureProtocol.router.configure { request,_ in
                if request.httpMethod == "GET" { return (200,Data(#"{"sessions":[{"id":"old-device","deviceName":"Old session","createdAt":1,"lastSeenAt":2,"current":1}]}"#.utf8)) }
                return (200,Data())
            }
            let persistence = MemorySessionStore()
            let model = try AppModel(api: api(), persistence: persistence, capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
            try model.account.accept(tokens()); try model.reload(); await model.loadSessions()
            let old = try #require(model.sessions.first)
            try model.account.accept(tokens("replacement", owner: owner)); try model.reload()
            #expect(model.sessions.isEmpty)
            await model.revoke(old)
            #expect(model.owner == owner && persistence.value?.tokens.accessToken == "replacement")
            #expect(FixtureProtocol.router.captured().allSatisfy { $0.httpMethod == "GET" })
        }
    }
    @Test func repeatedDeviceRevocationSendsOnlyOneDelete() async throws {
        let release = DispatchSemaphore(value: 0); defer { release.signal(); release.signal() }
        FixtureProtocol.router.configure { request,_ in
            if request.httpMethod == "GET" { return (200,Data(#"{"sessions":[{"id":"other-device","deviceName":"Other device","createdAt":1,"lastSeenAt":2,"current":0}]}"#.utf8)) }
            _ = release.wait(timeout: .now() + 2); return (200,Data())
        }
        let model = try AppModel(api: api(), persistence: MemorySessionStore(), capture: RetryCapture(), databaseURL: URL(fileURLWithPath: ":memory:"))
        try model.account.accept(tokens()); await model.loadSessions()
        let device = try #require(model.sessions.first)
        let first = Task { await model.revoke(device) }
        for _ in 0..<100 where FixtureProtocol.router.captured().count == 1 { try await Task.sleep(for: .milliseconds(10)) }
        try #require(FixtureProtocol.router.captured().count >= 2)
        let second = Task { await model.revoke(device) }
        try await Task.sleep(for: .milliseconds(30))
        #expect(model.busy && FixtureProtocol.router.captured().filter { $0.httpMethod == "DELETE" }.count == 1)
        release.signal(); release.signal(); await first.value; await second.value
        #expect(!model.busy && model.owner == "alice")
    }
}

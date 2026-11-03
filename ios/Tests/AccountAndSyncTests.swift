import Foundation
import AVFAudio
import ActivityKit
import Testing
import WoVoiceCore
@testable import WoVoice

private final class MemorySessionStore: SessionPersistence {
    var value: StoredSession?
    func load() throws -> StoredSession? { value }
    func save(_ value: StoredSession) throws { self.value = value }
    func remove() throws { value = nil }
}
private nonisolated final class TestRouter: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: @Sendable (URLRequest, Int) -> (Int, Data) = { _,_ in (500,Data()) }
    private var requests: [URLRequest] = []
    func configure(_ value: @escaping @Sendable (URLRequest, Int) -> (Int,Data)) { lock.lock(); defer { lock.unlock() }; handler = value; requests = [] }
    func respond(_ request: URLRequest) -> (Int,Data) {
        lock.lock(); defer { lock.unlock() }
        var copy = request
        if copy.httpBody == nil, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var data = Data(); var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; data.append(contentsOf: buffer.prefix(count)) }
            copy.httpBody = data
        }
        requests.append(copy); return handler(copy, requests.count)
    }
    func captured() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return requests }
}
private nonisolated final class FixtureProtocol: URLProtocol, @unchecked Sendable {
    static let router = TestRouter()
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "fixture.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, data) = Self.router.respond(request)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@Suite(.serialized) @MainActor struct AccountAndSyncTests {
    private func api() -> APIClient {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [FixtureProtocol.self]
        return APIClient(baseURL: URL(string: "https://fixture.invalid")!, session: URLSession(configuration: config))
    }
    private func tokens(_ access: String = "access", owner: String = "alice") throws -> Tokens {
        try JSONDecoder().decode(Tokens.self, from: Data("""
        {"accessToken":"\(access)","refreshToken":"refresh","accessExpiresIn":900,"refreshExpiresIn":2592000,
         "user":{"id":"\(owner)","email":"fixture@example.test","role":"user","accountStatus":{"state":"active","supportEmail":"support@example.test"}}}
        """.utf8))
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
        #expect(model.recording)
        model.toggleRecording()
        for _ in 0..<100 where model.history.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
        #expect(model.history.count == 1)
        #expect(model.analytics.count == 1)
        #expect(model.lastText == "I would like to meet Professor Rahman tomorrow.")
        #expect(!model.keyboardSessionActive && !model.recording)
        try await Task.sleep(for: .milliseconds(100))
        #expect(model.message == nil)
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
}

import Foundation

public struct APIClient: Sendable {
    public let baseURL: URL
    private let session: URLSession
    public init(baseURL: URL = URL(string: "https://wovoice.aliahad.com")!, session: URLSession = .shared) {
        self.baseURL = baseURL; self.session = session
    }
    private struct ErrorBody: Decodable {
        let error: Detail?
        let conflicts: [CloudRecord]?
        struct Detail: Decodable { let code: String; let message: String; let retryable: Bool? }
    }
    public func request<T: Decodable & Sendable>(_ path: String, method: String = "GET", token: String? = nil,
                                                body: Data? = nil, timeout: Double = 15, as: T.Type = T.self) async throws -> T {
        let data = try await send(path, method: method, token: token, body: body, timeout: timeout)
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw APIError(code: "INVALID_RESPONSE", message: "LiVoice sent an unreadable response. Try again.", retryable: true) }
    }
    public func empty(_ path: String, method: String, token: String, body: Payload? = nil) async throws {
        _ = try await send(path, method: method, token: token, body: try body.map { try JSONEncoder().encode($0) }, timeout: 15)
    }
    public func send(_ path: String, method: String, token: String?, body: Data?, timeout: Double,
                     contentType: String = "application/json") async throws -> Data {
        var request = URLRequest(url: baseURL.appendingPathComponent(String(path.split(separator: "?")[0])))
        if let query = path.firstIndex(of: "?") {
            var parts = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
            parts.percentEncodedQuery = String(path[path.index(after: query)...]); request.url = parts.url
        }
        request.httpMethod = method; request.httpBody = body; request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("WoVoice-iOS/1.0", forHTTPHeaderField: "User-Agent")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if body != nil { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        let data: Data; let response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        catch is CancellationError { throw CancellationError() }
        catch let error as URLError where error.code == .cancelled { throw CancellationError() }
        catch { throw APIError(code: "NETWORK_UNAVAILABLE", message: "Could not reach LiVoice. Check your connection.", retryable: true) }
        guard let http = response as? HTTPURLResponse else { throw APIError(code: "INVALID_RESPONSE", message: "Invalid server response.", retryable: true) }
        guard (200..<300).contains(http.statusCode) else {
            let parsed = try? JSONDecoder().decode(ErrorBody.self, from: data)
            throw APIError(code: parsed?.error?.code ?? "HTTP_\(http.statusCode)", message: parsed?.error?.message ?? "LiVoice request failed (\(http.statusCode)).",
                           retryable: parsed?.error?.retryable ?? (http.statusCode >= 500), status: http.statusCode, conflicts: parsed?.conflicts ?? [])
        }
        return data
    }
    public func exchange(code: String, verifier: String, device: String) async throws -> Tokens {
        try await request("/v1/auth/token", method: "POST", body: JSONEncoder().encode([
            "grantType": "authorization_code", "code": code, "codeVerifier": verifier, "deviceName": device
        ]))
    }
    public func refresh(_ token: String) async throws -> Tokens {
        try await request("/v1/auth/refresh", method: "POST", body: JSONEncoder().encode(["refreshToken": token]))
    }
    public func transcribe(token: String, wav: Data, glossary: [String], context: DictationContext?, sentenceStart: Bool) async throws -> Transcription {
        struct Options: Encodable { let locale = "en-IN"; let polish = "light"; let commands = ["new_line", "new_paragraph"]; let sentenceStart: Bool; let glossary: [String]; let context: DictationContext? }
        let boundary = "WoVoice-\(UUID().uuidString)"
        var body = Data()
        func append(_ text: String) { body.append(contentsOf: text.utf8) }
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"options\"\r\n\r\n")
        body.append(try JSONEncoder().encode(Options(sentenceStart: sentenceStart, glossary: Array(glossary.prefix(100)), context: context)))
        append("\r\n--\(boundary)\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
        body.append(wav); append("\r\n--\(boundary)--\r\n")
        let data = try await send("/v1/transcriptions", method: "POST", token: token, body: body, timeout: 90, contentType: "multipart/form-data; boundary=\(boundary)")
        do { return try JSONDecoder().decode(Transcription.self, from: data) }
        catch { throw APIError(code: "INVALID_RESPONSE", message: "LiVoice sent an unreadable response. Try again.", retryable: true) }
    }
    public func pull(token: String, cursor: Int) async throws -> CloudPage {
        try await request("/v2/sync?cursor=\(cursor)&limit=50", token: token, timeout: 30)
    }
    public func push(token: String, writes: [CloudWrite]) async throws -> PushResult {
        struct Batch: Encodable { let items: [CloudWrite] }
        return try await request("/v2/sync/batch", method: "POST", token: token, body: JSONEncoder().encode(Batch(items: writes)), timeout: 30)
    }
    public func updateSettings(token: String, history: Bool, retention: Int?) async throws -> CloudSettings {
        struct Body: Encodable {
            let historySyncEnabled: Bool; let historyRetentionDays: Int?
            enum CodingKeys: String, CodingKey { case historySyncEnabled, historyRetentionDays }
            func encode(to encoder: any Encoder) throws {
                var c = encoder.container(keyedBy: CodingKeys.self)
                try c.encode(historySyncEnabled, forKey: .historySyncEnabled)
                if let historyRetentionDays { try c.encode(historyRetentionDays, forKey: .historyRetentionDays) }
                else { try c.encodeNil(forKey: .historyRetentionDays) }
            }
        }
        struct Response: Decodable, Sendable { let settings: CloudSettings }
        let r: Response = try await request("/v2/sync/settings", method: "PUT", token: token,
                                           body: JSONEncoder().encode(Body(historySyncEnabled: history, historyRetentionDays: retention)))
        return r.settings
    }
}

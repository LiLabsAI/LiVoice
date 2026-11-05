import AuthenticationServices
import CryptoKit
import Foundation
import Observation
import Security
import UIKit
import WoVoiceCore

protocol SessionPersistence {
    func load() throws -> StoredSession?
    func save(_ value: StoredSession) throws
    func remove() throws
}
struct KeychainSessionStore: SessionPersistence {
    private let service: String
    init(service: String = "com.aliahad.wovoice.ios.session") { self.service = service }
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "current"] }
    func load() throws -> StoredSession? {
        var q = query; q[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw keychainError() }
        return try JSONDecoder().decode(StoredSession.self, from: data)
    }
    func save(_ value: StoredSession) throws {
        let data = try JSONEncoder().encode(value)
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; attributes.forEach { q[$0.key] = $0.value }
            guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw keychainError() }
        } else if status != errSecSuccess { throw keychainError() }
    }
    func remove() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw keychainError() }
    }
    private func keychainError() -> APIError { APIError(code: "KEYCHAIN_UNAVAILABLE", message: "Could not access secure account storage. Unlock your device and try again.") }
}

@Observable final class AccountSession {
    private(set) var stored: StoredSession?
    private(set) var authenticating = false
    let api: APIClient
    private let secrets: any SessionPersistence
    private var refreshTask: Task<StoredSession, any Error>?
    private var generation = UUID()
    /// Refreshes preserve identity; accepting or clearing a session replaces it.
    var sessionIdentity: UUID { generation }
    var onCleared: (() -> Void)?
    var onUserChanged: (() -> Void)?
    var user: User? { stored?.tokens.user }
    var cloudServicesAllowed: Bool { user?.accountStatus.state == "active" }
    var restrictionMessage: String? {
        guard let user, !cloudServicesAllowed else { return nil }
        return user.accountStatus.publicMessage ?? "This account cannot use voice input or cloud sync. Contact \(user.accountStatus.supportEmail) for help."
    }
    init(api: APIClient, persistence: any SessionPersistence = KeychainSessionStore()) throws {
        self.api = api; secrets = persistence; stored = try persistence.load()
    }
    func accept(_ tokens: Tokens) throws {
        let next = StoredSession(tokens: tokens)
        try secrets.save(next)
        generation = UUID(); refreshTask?.cancel(); refreshTask = nil; stored = next
    }
    func clear() throws {
        try secrets.remove()
        generation = UUID(); refreshTask?.cancel(); refreshTask = nil; stored = nil
        onCleared?()
    }
    func updateUser(_ user: User) throws {
        guard var current = stored, current.tokens.user.id == user.id else { throw CancellationError() }
        current.tokens.user = user
        // Enforce a restriction in memory even if secure storage is unavailable.
        stored = current
        defer { onUserChanged?() }
        try secrets.save(current)
    }
    private func restrict(_ error: APIError) throws {
        guard var user else { return }
        user.accountStatus.state = error.code == "ACCOUNT_SUSPENDED" ? "suspended" : "banned"
        user.accountStatus.suspendedUntil = nil
        user.accountStatus.publicMessage = error.message
        try updateUser(user)
    }
    /// False means local sign-out succeeded but server revocation was not confirmed.
    func signOut() async throws -> Bool {
        guard stored != nil else { try clear(); return true }
        let expected = generation
        var revoked = false
        do {
            try await call { try await api.empty("/v1/auth/logout", method: "POST", token: $0, body: [:]) }
            revoked = true
        } catch {
            // Revocation is best effort; a network failure must not retain the
            // local credential after the user has chosen to sign out.
        }
        guard generation == expected else {
            // An auth rejection can already have cleared this session. A newer
            // sign-in, including for the same account, must remain untouched.
            guard stored == nil else { throw CancellationError() }
            return true
        }
        try clear()
        return revoked
    }
    func token(force: Bool = false) async throws -> String {
        guard let current = stored else { throw APIError(code: "AUTH_REQUIRED", message: "Sign in to dictate.") }
        if !force && current.accessExpiresAt.timeIntervalSinceNow > 30 { return current.tokens.accessToken }
        if let refreshTask { return try await refreshTask.value.tokens.accessToken }
        let expected = generation
        let task = Task { () throws -> StoredSession in
            let tokens = try await api.refresh(current.tokens.refreshToken)
            guard generation == expected, tokens.user.id == current.tokens.user.id else { throw CancellationError() }
            let next = StoredSession(tokens: tokens)
            try secrets.save(next); stored = next; onUserChanged?()
            return next
        }
        refreshTask = task
        defer { if generation == expected { refreshTask = nil } }
        do { return try await task.value.tokens.accessToken }
        catch let e as APIError where ["AUTH_REQUIRED", "SESSION_REVOKED", "INVALID_REFRESH_TOKEN"].contains(e.code) {
            if generation == expected { try clear() }; throw e
        }
    }
    func call<T>(_ operation: (String) async throws -> T) async throws -> T {
        let expected = generation
        var refreshing = false
        while true {
            try Task.checkCancellation()
            let access = try await token(force: refreshing)
            guard generation == expected else { throw CancellationError() }
            try Task.checkCancellation()
            do {
                let result = try await operation(access)
                guard generation == expected else { throw CancellationError() }
                return result
            } catch let error as APIError {
                guard generation == expected else { throw CancellationError() }
                if error.code == "TOKEN_EXPIRED", !refreshing { refreshing = true; continue }
                if error.code == "AUTH_REQUIRED" || error.code == "SESSION_REVOKED" { try clear() }
                if error.code == "ACCOUNT_SUSPENDED" || error.code == "ACCOUNT_BANNED" { try restrict(error) }
                throw error
            }
        }
    }
}

protocol AccountAuthenticating {
    func authenticate(base: URL, deletion: Bool) async throws -> (code: String, verifier: String)
}

final class WebAuthentication: NSObject, ASWebAuthenticationPresentationContextProviding, AccountAuthenticating {
    private var session: ASWebAuthenticationSession?
    func authenticate(base: URL, deletion: Bool = false) async throws -> (code: String, verifier: String) {
        guard session == nil else { throw APIError(code: "AUTH_IN_PROGRESS", message: "A sign-in window is already open.") }
        defer { session = nil }
        let verifier = try random(48); let state = try random(32)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URL
        var url = URLComponents(url: base.appendingPathComponent("auth"), resolvingAgainstBaseURL: false)!
        url.queryItems = [.init(name: "platform", value: "ios"), .init(name: "code_challenge", value: challenge), .init(name: "state", value: state)]
        if deletion { url.queryItems?.append(.init(name: "intent", value: "delete")) }
        let callback: URL = try await withCheckedThrowingContinuation { continuation in
            let completion: ASWebAuthenticationSession.CompletionHandler = { callback, error in
                if let error { continuation.resume(throwing: error) }
                else if let callback { continuation.resume(returning: callback) }
                else { continuation.resume(throwing: APIError(code: "AUTH_CALLBACK", message: "Sign-in did not return a valid response.")) }
            }
            let web: ASWebAuthenticationSession
            if #available(iOS 17.4, *) { web = ASWebAuthenticationSession(url: url.url!, callback: .customScheme("wovoice-ios"), completionHandler: completion) }
            else { web = ASWebAuthenticationSession(url: url.url!, callbackURLScheme: "wovoice-ios", completionHandler: completion) }
            web.presentationContextProvider = self; web.prefersEphemeralWebBrowserSession = true
            session = web
            if !web.start() { continuation.resume(throwing: APIError(code: "AUTH_PRESENTATION", message: "Could not open the sign-in window.")) }
        }
        guard callback.scheme == "wovoice-ios", callback.host == "callback", callback.path.isEmpty,
              let components = URLComponents(url: callback, resolvingAgainstBaseURL: false),
              components.queryItems?.filter({ $0.name == "state" }).map(\.value) == [state],
              let code = components.queryItems?.first(where: { $0.name == (deletion ? "reauth_token" : "code") })?.value,
              code.range(of: #"^[A-Za-z0-9._~-]{20,256}$"#, options: .regularExpression) != nil else {
            throw APIError(code: "AUTH_CALLBACK", message: "The sign-in response could not be verified. Please try again.")
        }
        return (code, verifier)
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
    private func random(_ count: Int) throws -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else { throw APIError(code: "SECURE_RANDOM", message: "Could not start secure sign-in.") }
        return Data(bytes).base64URL
    }
}
private extension Data {
    var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
}

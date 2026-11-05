#if DEBUG
import Foundation
import SwiftUI
import UIKit
import WebKit
import WoVoiceCore

/// Explicit local test mode: synthetic account/server and generated audio by
/// default. The microphone variant requires separate permission. Never enabled
/// in Release, never contacts production, and never writes credentials.
enum DevelopmentFixture {
    static var microphoneEnabled: Bool { ProcessInfo.processInfo.arguments.contains("--fixture-microphone") }
    static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("--fixture") || microphoneEnabled }
    @ViewBuilder static func dashboard(model: AppModel) -> some View {
        if enabled, ProcessInfo.processInfo.arguments.contains("--fixture-large-text") {
            DashboardView(model: model).dynamicTypeSize(.accessibility5)
        } else { DashboardView(model: model) }
    }
    static func makeModel(useMicrophone: Bool = false, clock: @escaping () -> ContinuousClock.Instant = { .now }) throws -> AppModel {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [DevelopmentServer.self]
        let capture: any CaptureEngine = useMicrophone ? AudioCapture() : FixtureCapture()
        let model = try AppModel(api: APIClient(baseURL: URL(string: "https://fixture.invalid")!, session: URLSession(configuration: config)),
                                 persistence: FixtureSessionStore(), capture: capture, databaseURL: URL(fileURLWithPath: ":memory:"), clock: clock)
        let tokens = try JSONDecoder().decode(Tokens.self, from: DevelopmentServer.tokens)
        try model.account.accept(tokens); try model.reload()
        model.addTerm("Rahman")
        return model
    }
}
private struct FixtureSessionStore: SessionPersistence {
    func load() throws -> StoredSession? { nil }
    func save(_ value: StoredSession) throws {}
    func remove() throws {}
}
private final class FixtureCapture: CaptureEngine {
    var active = false
    private var started: Date?
    var meter: (level: Double, duration: Double) { (started == nil ? 0 : 0.04, started.map { min(60,Date.now.timeIntervalSince($0)) } ?? 0) }
    func startSession() async throws { active = true }
    func begin() throws { started = .now }
    func finish() throws -> (wav: Data, duration: Double) {
        started = nil
        return (WAV.encode((0..<16000).map { Int16(sin(Double($0)*0.1)*1200) }),1)
    }
    func cancel() { started = nil }
    func stopSession() { started = nil; active = false }
}
private nonisolated final class DevelopmentServer: URLProtocol, @unchecked Sendable {
    static let tokens = Data(#"{"accessToken":"fixture-access","refreshToken":"fixture-refresh","accessExpiresIn":900,"refreshExpiresIn":2592000,"user":{"id":"fixture","email":"sample@example.test","role":"user","accountStatus":{"state":"active","supportEmail":"support@example.test"}}}"#.utf8)
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url?.path ?? ""
        var code = 200
        let data: Data
        switch path {
        case "/v1/me": data = Data(#"{"user":{"id":"fixture","email":"sample@example.test","role":"user","accountStatus":{"state":"active","supportEmail":"support@example.test"}},"quota":{"limitAudioSeconds":600,"remainingAudioSeconds":600,"resetAt":1900000000000}}"#.utf8)
        case "/v1/transcriptions": data = Data("""
        {"text":"I would like to meet Professor Rahman tomorrow.","rawText":"i would like to meet professor rahman tomorrow","polished":true,"asrModel":"fixture-whisper","requestId":"\(UUID().uuidString)","timingsMs":{"asr":120,"polish":80,"total":200},"usage":null}
        """.utf8)
        case "/v2/sync": data = Data(#"{"nextCursor":0,"hasMore":false,"items":[],"settings":{"historySyncEnabled":true,"historyRetentionDays":null,"legacyVault":false}}"#.utf8)
        case "/v2/sync/batch":
            let body = Self.body(request)
            let batch = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
            let items = batch?["items"] as? [[String: Any]] ?? []
            if ProcessInfo.processInfo.arguments.contains("--fixture-sync-limit"), items.contains(where: { $0["type"] as? String == "dictionary" && $0["baseVersion"] as? Int == 0 && $0["deleted"] as? Bool != true }) {
                code = 409
                data = Data(#"{"error":{"code":"STORAGE_LIMIT_REACHED","message":"Cloud vocabulary is full. New words remain on this device.","retryable":false}}"#.utf8)
            } else {
                let applied = items.map { ["id": $0["id"] ?? "", "type": $0["type"] ?? "", "version": ($0["baseVersion"] as? Int ?? 0) + 1] }
                data = (try? JSONSerialization.data(withJSONObject: ["applied": applied])) ?? Data()
            }
        case "/v1/me/sessions": data = Data(#"{"sessions":[{"id":"fixture-device","deviceName":"iPhone simulator","createdAt":1793700000000,"lastSeenAt":1793700000000,"current":1}]}"#.utf8)
        default: code = 404; data = Data(#"{"error":{"code":"FIXTURE_ONLY","message":"This action is not part of the synthetic test server.","retryable":false}}"#.utf8)
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
    private static func body(_ request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open(); defer { stream.close() }
        var data = Data(); var bytes = [UInt8](repeating: 0,count: 4096)
        while stream.hasBytesAvailable { let n = stream.read(&bytes,maxLength: bytes.count); if n <= 0 { break }; data.append(contentsOf: bytes.prefix(n)) }
        return data
    }
}
struct FixtureEditorsView: View {
    @State private var report = "Bold preserved: yes"
    @State private var secret = ""
    @State private var kind = 0
    @State private var webReport = "Loading web editor…"
    @State private var returnType = UIReturnKeyType.send
    @State private var requireText = true
    @State private var actionReport = "Return callbacks: 0"
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
                Text("Synthetic editor tests").font(.headline)
                Text(DevelopmentFixture.microphoneEnabled ? "Local microphone test. Audio stays on this computer; the test service returns a fixed sentence." : "Uses invented text and audio. No microphone or production service.").font(.caption).foregroundStyle(.secondary)
                Picker("Editor", selection: $kind) { Text("Native").tag(0); Text("Web").tag(1); Text("Password").tag(2); Text("Limited").tag(3); Text("Actions").tag(4) }.pickerStyle(.segmented)
                if kind == 0 {
                    Text("Native rich text").font(.subheadline)
                    FixtureRichEditor(report: $report).frame(height: 130).overlay(RoundedRectangle(cornerRadius: 8).stroke(.secondary))
                    Text(report).font(.caption).accessibilityIdentifier("formatReport")
                } else if kind == 1 {
                    Text("Web rich text").font(.subheadline)
                    FixtureWebEditor(report: $webReport).frame(maxWidth: .infinity).frame(height: 130).overlay(RoundedRectangle(cornerRadius: 8).stroke(.secondary))
                    Text(webReport).font(.caption)
                } else if kind == 2 {
                    SecureField("Password field: system keyboard only", text: $secret).textFieldStyle(.roundedBorder)
                } else if kind == 3 {
                    Text("Native field · 20-character limit").font(.subheadline)
                    FixtureLimitedEditor().frame(height: 50)
                    Text("The host rejects text exceeding its limit.").font(.caption)
                } else {
                    Picker("Return action", selection: $returnType) {
                        Text("Send").tag(UIReturnKeyType.send); Text("Next").tag(UIReturnKeyType.next)
                        Text("Search").tag(UIReturnKeyType.search); Text("Go").tag(UIReturnKeyType.go)
                        Text("Done").tag(UIReturnKeyType.done); Text("Return").tag(UIReturnKeyType.default)
                    }
                    Toggle("Require text for Return", isOn: $requireText)
                    FixtureActionEditor(returnType: returnType, requireText: requireText, report: $actionReport).frame(height: 94)
                    Text(actionReport).font(.caption).accessibilityIdentifier("returnActionReport")
                    Text("Synthetic fields only. Return records a delegate callback; Next moves focus. Nothing is sent.").font(.caption)
                }
                Spacer(minLength: 0)
        }.navigationTitle("Editor checks")
            .padding()
    }
}
private struct FixtureActionEditor: UIViewRepresentable {
    let returnType: UIReturnKeyType
    let requireText: Bool
    @Binding var report: String
    func makeUIView(context: Context) -> UIStackView {
        let first = UITextField(); first.placeholder = "Action field"; first.accessibilityLabel = "Action field"
        first.accessibilityIdentifier = "returnActionEditor"; first.borderStyle = .roundedRect
        first.autocorrectionType = .no; first.delegate = context.coordinator
        let next = UITextField(); next.placeholder = "Next field"; next.accessibilityLabel = "Next field"
        next.accessibilityIdentifier = "returnNextEditor"; next.borderStyle = .roundedRect
        next.returnKeyType = .done; next.delegate = context.coordinator
        context.coordinator.first = first; context.coordinator.next = next
        let stack = UIStackView(arrangedSubviews: [first, next]); stack.axis = .vertical; stack.spacing = 8; stack.distribution = .fillEqually
        return stack
    }
    func updateUIView(_ view: UIStackView, context: Context) {
        guard let first = context.coordinator.first else { return }
        let changed = first.returnKeyType != returnType || first.enablesReturnKeyAutomatically != requireText
        first.returnKeyType = returnType; first.enablesReturnKeyAutomatically = requireText
        if changed { first.reloadInputViews() }
    }
    func makeCoordinator() -> Coordinator { Coordinator(report: $report) }
    final class Coordinator: NSObject, UITextFieldDelegate {
        var report: Binding<String>
        weak var first: UITextField?
        weak var next: UITextField?
        private var returns = 0
        init(report: Binding<String>) { self.report = report }
        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            returns += 1
            report.wrappedValue = "Return callbacks: \(returns) · \(textField === first ? "Action field" : "Next field")"
            if textField === first, textField.returnKeyType == .next { next?.becomeFirstResponder() }
            return true
        }
    }
}
private struct FixtureLimitedEditor: UIViewRepresentable {
    func makeUIView(context: Context) -> UITextField {
        let view = UITextField(); view.text = "Start: "; view.borderStyle = .roundedRect
        view.delegate = context.coordinator; view.accessibilityIdentifier = "limitedEditor"
        return view
    }
    func updateUIView(_ view: UITextField, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator() }
    final class Coordinator: NSObject, UITextFieldDelegate {
        func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
            ((textField.text ?? "") as NSString).replacingCharacters(in: range, with: string).utf16.count <= 20
        }
    }
}
private struct FixtureRichEditor: UIViewRepresentable {
    @Binding var report: String
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView(); view.font = .systemFont(ofSize: 20); view.accessibilityIdentifier = "richEditor"
        let text = NSMutableAttributedString(string: "Hello bold world.", attributes: [.font: UIFont.systemFont(ofSize: 20), .foregroundColor: UIColor.label])
        text.addAttribute(.font, value: UIFont.boldSystemFont(ofSize: 20), range: NSRange(location: 6,length: 4))
        view.attributedText = text; view.delegate = context.coordinator; view.autocorrectionType = .no
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(report: $report) }
    final class Coordinator: NSObject, UITextViewDelegate {
        var report: Binding<String>
        init(report: Binding<String>) { self.report = report }
        func textViewDidChange(_ textView: UITextView) {
            let range = (textView.text as NSString).range(of: "bold")
            let font = range.location == NSNotFound ? nil : textView.attributedText.attribute(.font, at: range.location, effectiveRange: nil) as? UIFont
            report.wrappedValue = "Bold preserved: \(font?.fontDescriptor.symbolicTraits.contains(.traitBold) == true ? "yes" : "no")"
        }
    }
}
private struct FixtureWebEditor: UIViewRepresentable {
    @Binding var report: String
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(context.coordinator, name: "format")
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.loadHTMLString("<meta name='viewport' content='width=device-width, initial-scale=1'><div contenteditable='true' style='font:20px -apple-system;border:1px solid #aaa;padding:12px;min-height:80px' aria-label='Web rich editor'>Hello <b>bold</b> web world.</div><script>document.addEventListener('input',()=>window.webkit.messageHandlers.format.postMessage(document.querySelector('b')?.textContent==='bold'?'Web bold preserved: yes':'Web bold preserved: no'))</script>", baseURL: nil)
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(report: $report) }
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var report: Binding<String>
        init(report: Binding<String>) { self.report = report }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { report.wrappedValue = "Web editor ready · Bold preserved: yes" }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: any Error) { report.wrappedValue = error.localizedDescription }
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { report.wrappedValue = "Web content process stopped" }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) { if let value = message.body as? String { report.wrappedValue = value } }
    }
}
#endif

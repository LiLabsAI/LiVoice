import UIKit
import WoVoiceCore

final class KeyboardViewController: UIInputViewController {
    private let bridge = KeyboardBridge()
    private var status = KeyboardStatus()
    private var request: UUID?
    private var target: EditorSnapshot?
    private var polling: Timer?
    private var shift = true
    private var caps = false
    private var lastShift = Date.distantPast
    private var layout = 0
    private let root = UIStackView()
    private let keys = UIStackView()
    private let statusLabel = UILabel()
    private let microphone = UIButton(type: .system)
    private let cancel = UIButton(type: .system)
    private let waveform = KeyboardWaveformView()
    private var deleteTimer: Timer?
    private var lastPhase = ""
    private var localMessage: String?
    private var correction: (text: String, document: UUID, expires: Date, recordID: String?)?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .secondarySystemBackground
        root.axis = .vertical; root.spacing = 7; root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 6), root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 8), root.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -6),
            view.heightAnchor.constraint(greaterThanOrEqualToConstant: 292)
        ])
        let header = UIStackView(); header.spacing = 10; header.alignment = .center
        statusLabel.font = .preferredFont(forTextStyle: .caption1); statusLabel.numberOfLines = 2; statusLabel.textColor = .secondaryLabel
        statusLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        microphone.configuration = .filled(); microphone.configuration?.cornerStyle = .capsule
        microphone.tintColor = .label; microphone.accessibilityIdentifier = "keyboardDictate"
        microphone.addTarget(self, action: #selector(speak), for: .touchUpInside)
        cancel.setImage(UIImage(systemName: "xmark.circle"), for: .normal); cancel.accessibilityLabel = "Cancel dictation"
        cancel.addTarget(self, action: #selector(cancelPressed), for: .touchUpInside)
        waveform.widthAnchor.constraint(equalToConstant: 34).isActive = true
        waveform.heightAnchor.constraint(equalToConstant: 24).isActive = true
        waveform.accessibilityElementsHidden = true
        header.addArrangedSubview(statusLabel); header.addArrangedSubview(waveform); header.addArrangedSubview(cancel); header.addArrangedSubview(microphone)
        root.addArrangedSubview(header)
        keys.axis = .vertical; keys.spacing = 6; keys.distribution = .fillEqually
        root.addArrangedSubview(keys)
        renderKeys(); renderStatus()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        caps = false; shift = TextPolicy.sentenceStart(before: textDocumentProxy.documentContextBeforeInput)
        poll(); polling?.invalidate(); polling = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
        renderKeys()
    }
    override func viewWillDisappear(_ animated: Bool) {
        abandon(); correction = nil; polling?.invalidate(); polling = nil; deleteTimer?.invalidate(); deleteTimer = nil
        super.viewWillDisappear(animated)
    }
    override func textDidChange(_ textInput: (any UITextInput)?) {
        super.textDidChange(textInput)
        checkTarget()
        learnCorrection()
    }
    override func selectionDidChange(_ textInput: (any UITextInput)?) {
        super.selectionDidChange(textInput); checkTarget()
    }
    private var snapshot: EditorSnapshot {
        .init(document: textDocumentProxy.documentIdentifier, before: textDocumentProxy.documentContextBeforeInput,
              after: textDocumentProxy.documentContextAfterInput, selected: textDocumentProxy.selectedText)
    }
    private var sensitive: Bool { textDocumentProxy.isSecureTextEntry == true }
    private var field: FieldKind {
        switch textDocumentProxy.keyboardType {
        case .emailAddress, .URL, .numberPad, .decimalPad, .phonePad, .asciiCapableNumberPad, .numbersAndPunctuation: return .literal
        default: break
        }
        if textDocumentProxy.returnKeyType == .search || textDocumentProxy.returnKeyType == .google || textDocumentProxy.returnKeyType == .yahoo { return .search }
        return .text
    }
    private func checkTarget() {
        if let target, !target.matches(snapshot) { abandon(); localMessage = "Editor changed. Tap Speak to start again."; renderStatus() }
    }
    private func poll() {
        if hasFullAccess, let next = bridge.read("status.json", as: KeyboardStatus.self) { status = next }
        else { status = KeyboardStatus() }
        if request != nil && (!status.available || sensitive || !hasFullAccess) { abandon() }
        if let request, status.request == request, status.phase == "result", let text = status.result, status.resultExpiresAt ?? .distantPast > .now {
            guard let target, target.matches(snapshot), status.available, !sensitive else {
                abandon(); localMessage = "Editor changed. Dictation is available in WoVoice History."; renderStatus(); return
            }
            // Clear the request before insertText can synchronously notify textDidChange.
            self.request = nil; self.target = nil
            textDocumentProxy.insertText(field == .literal ? text : TextPolicy.spaced(text, before: target.before, after: target.after, selected: target.selected))
            send(.acknowledge, request: request)
            if status.learning, field == .text { correction = (text, target.document, .now.addingTimeInterval(30), status.recordID) }
            feedback(); localMessage = "Inserted"
        }
        renderStatus()
    }
    private func renderStatus() {
        let phase = request != nil && status.request == request ? status.phase : "idle"
        cancel.isHidden = request == nil
        waveform.isHidden = phase != "recording" || !status.waveform
        waveform.update(level: status.level, animated: status.animate && !UIAccessibility.isReduceMotionEnabled)
        if !hasFullAccess { statusLabel.text = "Typing works offline. Allow Full Access in Settings for dictation." }
        else if !status.available { statusLabel.text = localMessage ?? "Open WoVoice → Start keyboard session, then return here." }
        else { statusLabel.text = localMessage ?? (phase == "recording" ? "Listening · \(Int(status.elapsed))s / 60s" : phase == "processing" ? "Thinking…" : phase == "error" ? status.message : "WoVoice · Microphone session active") }
        microphone.isEnabled = hasFullAccess && status.available && !sensitive && phase != "processing"
        let label = phase == "recording" ? "Finish" : phase == "processing" ? "Thinking" : phase == "error" && status.canRetry ? "Retry" : "Speak"
        microphone.configuration?.title = label
        microphone.configuration?.image = UIImage(systemName: phase == "recording" ? "stop.fill" : "mic.fill")
        microphone.configuration?.imagePadding = 6
        microphone.configuration?.baseBackgroundColor = phase == "recording" ? .systemRed : .label
        microphone.configuration?.baseForegroundColor = .systemBackground
        if phase != lastPhase {
            if status.animate && !UIAccessibility.isReduceMotionEnabled {
                microphone.transform = CGAffineTransform(scaleX: 0.96, y: 0.96)
                UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) { self.microphone.transform = .identity }
            }
            lastPhase = phase
        }
    }
    @objc private func speak() {
        guard hasFullAccess, status.available, !sensitive else { return }
        localMessage = nil
        if let request {
            if status.phase == "recording" { send(.finish, request: request) }
            else if status.phase == "error", status.canRetry { send(.retry, request: request) }
            else if status.phase == "error" { abandon(); speak() }
        } else {
            let current = snapshot
            guard current.exposesContext else { localMessage = "This editor does not expose a safe insertion target. Dictate in WoVoice and copy your text."; renderStatus(); return }
            let id = UUID(); request = id; target = current
            send(.start, request: id, context: DictationContext.capture(preferences: status.preferences, field: field, before: current.before, after: current.after))
        }
        feedback()
    }
    private func send(_ action: KeyboardCommand.Action, request: UUID, context: DictationContext? = nil) {
        guard hasFullAccess, let owner = status.owner else { return }
        do { try bridge.enqueue(KeyboardCommand(id: UUID(), request: request, owner: owner, action: action, createdAt: .now, context: context,
                                             sentenceStart: TextPolicy.sentenceStart(before: target?.before))) }
        catch { localMessage = error.localizedDescription; self.request = nil; target = nil; renderStatus() }
    }
    private func abandon() { if let request { send(.cancel, request: request) }; request = nil; target = nil }
    private func learnCorrection() {
        guard let correction, correction.expires > .now, status.learning, status.available, hasFullAccess, !sensitive, field == .text,
              correction.document == textDocumentProxy.documentIdentifier, let before = textDocumentProxy.documentContextBeforeInput,
              let term = CorrectionLearning.suggestion(generated: correction.text, before: before), let owner = status.owner else { return }
        self.correction = nil
        var command = KeyboardCommand(id: UUID(), request: UUID(), owner: owner, action: .suggest, createdAt: .now, context: nil, sentenceStart: false)
        command.suggestion = term; command.recordID = correction.recordID
        try? bridge.enqueue(command)
    }
    @objc private func cancelPressed() { abandon(); localMessage = "Cancelled"; renderStatus() }
    private func feedback() { if status.haptics { UIImpactFeedbackGenerator(style: .light).impactOccurred() } }
    private func renderKeys() {
        for view in keys.arrangedSubviews { keys.removeArrangedSubview(view); view.removeFromSuperview() }
        let rows: [[String]] = layout == 0 ? [Array("qwertyuiop").map(String.init), Array("asdfghjkl").map(String.init), ["shift"] + Array("zxcvbnm").map(String.init) + ["delete"]]
            : layout == 1 ? [Array("1234567890").map(String.init), ["-","/",":",";","(",")","₹","&","@","\""], ["#+=",".",",","?","!","'","delete"]]
            : [["[","]","{","}","#","%","^","*","+","="], ["_","\\","|","~","<",">","€","£","$","•"], ["123",".",",","?","!","'","delete"]]
        for values in rows { addRow(values) }
        addRow([layout == 0 ? "123" : "ABC", "globe", "space", "return"])
    }
    private func addRow(_ values: [String]) {
        let row = UIStackView(); row.axis = .horizontal; row.spacing = 5; row.distribution = .fillEqually
        for value in values {
            let button = UIButton(type: .system)
            button.backgroundColor = .systemBackground; button.layer.cornerRadius = 6; button.tintColor = .label
            button.titleLabel?.font = .systemFont(ofSize: value.count == 1 ? 21 : 15)
            switch value {
            case "globe": button.setImage(UIImage(systemName: "globe"), for: .normal); button.accessibilityLabel = "Next keyboard"; button.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
            case "shift": button.setImage(UIImage(systemName: caps ? "capslock.fill" : shift ? "shift.fill" : "shift"), for: .normal); button.accessibilityLabel = "Shift"
            case "delete":
                button.setImage(UIImage(systemName: "delete.left"), for: .normal); button.accessibilityLabel = "Delete"
                let hold = UILongPressGestureRecognizer(target: self, action: #selector(holdDelete(_:))); hold.minimumPressDuration = 0.35; button.addGestureRecognizer(hold)
            case "space": button.setTitle("space", for: .normal)
            case "return": button.setTitle(textDocumentProxy.returnKeyType == .search ? "search" : "return", for: .normal)
            default: button.setTitle(layout == 0 && shift ? value.uppercased() : value, for: .normal)
            }
            if value != "globe" { button.addAction(UIAction { [weak self] _ in self?.key(value) }, for: .touchUpInside) }
            row.addArrangedSubview(button)
        }
        row.heightAnchor.constraint(greaterThanOrEqualToConstant: 40).isActive = true
        keys.addArrangedSubview(row)
    }
    private func key(_ value: String) {
        abandon(); localMessage = nil
        switch value {
        case "shift":
            if Date.now.timeIntervalSince(lastShift) < 0.35 { caps = true; shift = true }
            else if caps { caps = false; shift = false } else { shift.toggle() }
            lastShift = .now; renderKeys()
        case "delete": textDocumentProxy.deleteBackward()
        case "123": layout = 1; renderKeys()
        case "#+=": layout = 2; renderKeys()
        case "ABC": layout = 0; renderKeys()
        case "space": textDocumentProxy.insertText(" ")
        case "return": textDocumentProxy.insertText("\n"); if !caps { shift = true; renderKeys() }
        default: textDocumentProxy.insertText(shift && layout == 0 ? value.uppercased() : value); if shift && !caps { shift = false; renderKeys() }
        }
        feedback(); renderStatus()
    }
    @objc private func holdDelete(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began:
            key("delete"); deleteTimer?.invalidate()
            deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.key("delete") } }
        case .ended, .cancelled, .failed: deleteTimer?.invalidate(); deleteTimer = nil
        default: break
        }
    }
}

private final class KeyboardWaveformView: UIView {
    private let bars = (0..<5).map { _ in CALayer() }
    override init(frame: CGRect) {
        super.init(frame: frame)
        for bar in bars { bar.cornerRadius = 1.5; layer.addSublayer(bar) }
    }
    required init?(coder: NSCoder) { nil }
    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        for (index,bar) in bars.enumerated() { bar.backgroundColor = UIColor.label.cgColor; bar.bounds = CGRect(x: 0,y: 0,width: 3,height: 6); bar.position = CGPoint(x: 3+CGFloat(index)*6,y: bounds.midY) }
        CATransaction.commit()
    }
    func update(level: Double, animated: Bool) {
        CATransaction.begin(); CATransaction.setDisableActions(!animated); CATransaction.setAnimationDuration(0.12)
        for (index,bar) in bars.enumerated() {
            let weight = index == 2 ? 1.0 : index == 1 || index == 3 ? 0.75 : 0.5
            let height = 6 + min(18,sqrt(max(0,level))*60)*weight
            bar.transform = CATransform3DMakeScale(1,height/6,1)
        }
        CATransaction.commit()
    }
}

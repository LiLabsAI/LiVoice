import UIKit
import WoVoiceCore

final class KeyboardViewController: UIInputViewController {
    private let bridge = KeyboardBridge()
    private var status = KeyboardStatus()
    private var pending = PendingDictation()
    private var request: UUID? { pending.id }
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
    private let voiceButton = UIButton(type: .system)
    private let modeButton = UIButton(type: .system)
    private let voicePanel = UIStackView()
    private let voiceHint = UILabel()
    private var showTyping = false
    private let cancel = UIButton(type: .system)
    private let waveform = KeyboardWaveformView()
    private var deleteTimer: Timer?
    private var lastPhase = ""
    private var localMessage: String?
    private var correction: (text: String, document: UUID, expires: Date, recordID: String?)?

    isolated deinit { polling?.invalidate(); deleteTimer?.invalidate() }

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
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        microphone.configuration = .filled(); microphone.configuration?.cornerStyle = .capsule
        microphone.tintColor = .label; microphone.accessibilityIdentifier = "keyboardDictate"
        microphone.addTarget(self, action: #selector(speak), for: .touchUpInside)
        microphone.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        modeButton.titleLabel?.font = .preferredFont(forTextStyle: .subheadline)
        modeButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        modeButton.addTarget(self, action: #selector(toggleMode), for: .touchUpInside)
        cancel.setImage(UIImage(systemName: "xmark.circle"), for: .normal); cancel.accessibilityLabel = "Cancel dictation"
        cancel.addTarget(self, action: #selector(cancelPressed), for: .touchUpInside)
        cancel.widthAnchor.constraint(equalToConstant: 44).isActive = true
        cancel.heightAnchor.constraint(equalToConstant: 44).isActive = true
        waveform.widthAnchor.constraint(equalToConstant: 34).isActive = true
        waveform.heightAnchor.constraint(equalToConstant: 24).isActive = true
        waveform.accessibilityElementsHidden = true
        header.addArrangedSubview(statusLabel); header.addArrangedSubview(waveform); header.addArrangedSubview(cancel); header.addArrangedSubview(microphone); header.addArrangedSubview(modeButton)
        root.addArrangedSubview(header)
        voicePanel.axis = .vertical; voicePanel.alignment = .center; voicePanel.distribution = .equalSpacing; voicePanel.spacing = 10
        voiceButton.configuration = .filled(); voiceButton.configuration?.cornerStyle = .capsule
        voiceButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 88).isActive = true
        voiceButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 72).isActive = true
        voiceButton.addTarget(self, action: #selector(speak), for: .touchUpInside)
        voiceButton.accessibilityIdentifier = "voiceModeDictate"
        voiceHint.font = .preferredFont(forTextStyle: .subheadline); voiceHint.adjustsFontForContentSizeCategory = true
        voiceHint.numberOfLines = 2; voiceHint.textAlignment = .center
        let nextKeyboard = UIButton(type: .system)
        nextKeyboard.setTitle("Next keyboard", for: .normal); nextKeyboard.setImage(UIImage(systemName: "globe"), for: .normal)
        nextKeyboard.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        nextKeyboard.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        voicePanel.addArrangedSubview(voiceButton); voicePanel.addArrangedSubview(voiceHint); voicePanel.addArrangedSubview(nextKeyboard)
        root.addArrangedSubview(voicePanel)
        keys.axis = .vertical; keys.spacing = 6; keys.distribution = .fillEqually
        root.addArrangedSubview(keys)
        renderKeys(); renderStatus()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        caps = false; shift = TextPolicy.sentenceStart(before: textDocumentProxy.documentContextBeforeInput)
        if !hasFullAccess { showTyping = true }
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
    private var documentIdentifier: UUID? {
        guard let object = textDocumentProxy as? NSObject else { return nil }
        return EditorIdentity.document(from: object)
    }
    private var snapshot: EditorSnapshot? {
        guard let documentIdentifier else { return nil }
        return .init(document: documentIdentifier, before: textDocumentProxy.documentContextBeforeInput,
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
        if let target, snapshot.map({ target.matches($0) }) != true { abandon(); localMessage = "Editor changed. Tap Speak to start again."; renderStatus() }
    }
    private func poll() {
        if hasFullAccess, let next = bridge.read("status.json", as: KeyboardStatus.self) { status = next }
        else { status = KeyboardStatus() }
        if request != nil && (!status.available || sensitive || !hasFullAccess) { abandon() }
        if pending.shouldAbandon(parentRequest: status.request) {
            abandon(); localMessage = "Dictation ended. Tap Speak to start again."
        }
        if let request, let owner = status.owner, hasFullAccess, status.available {
            do { try bridge.renewLease(request: request, owner: owner) }
            catch { abandon(); localMessage = "Keyboard connection was lost. Open WoVoice to reconnect." }
        }
        if let request, status.request == request, status.phase == "result", let text = status.result, status.resultExpiresAt ?? .distantPast > .now {
            guard let target, let current = snapshot, target.matches(current), status.available, !sensitive else {
                abandon(); localMessage = "Editor changed. Dictation is available in WoVoice History."; renderStatus(); return
            }
            // Clear the request before insertText can synchronously notify textDidChange.
            pending.reset(); self.target = nil
            textDocumentProxy.insertText(field == .literal ? text : TextPolicy.spaced(text, before: target.before, after: target.after, selected: target.selected))
            send(.acknowledge, request: request)
            if status.learning, field == .text { correction = (text, target.document, .now.addingTimeInterval(30), status.recordID) }
            feedback(); localMessage = "Inserted"
        }
        renderStatus()
    }
    private func renderStatus() {
        let phase = pending.awaitingAcknowledgement ? "starting" : request != nil && status.request == request ? status.phase : "idle"
        keys.isHidden = !showTyping; voicePanel.isHidden = showTyping; microphone.isHidden = !showTyping
        modeButton.setTitle(showTyping ? "Voice" : "ABC", for: .normal)
        modeButton.accessibilityLabel = showTyping ? "Show dictation controls" : "Show typing keyboard"
        cancel.isHidden = request == nil
        waveform.isHidden = phase != "recording" || !status.waveform
        waveform.update(level: status.level, animated: status.animate && !UIAccessibility.isReduceMotionEnabled)
        if !hasFullAccess { statusLabel.text = "Typing works offline. Allow Full Access in Settings for dictation." }
        else if !status.available { statusLabel.text = localMessage ?? "Open WoVoice → Start keyboard session, then return here." }
        else { statusLabel.text = localMessage ?? (phase == "recording" ? "Listening · \(Int(status.elapsed))s / 60s" : phase == "processing" ? "Thinking…" : phase == "error" ? status.message : "WoVoice · Microphone session active") }
        microphone.isEnabled = hasFullAccess && status.available && !sensitive && phase != "processing" && phase != "starting"
        let label = phase == "starting" ? "Starting…" : phase == "recording" ? "Finish" : phase == "processing" ? "Thinking" : phase == "error" && status.canRetry ? "Retry" : "Speak"
        microphone.configuration?.title = label
        microphone.configuration?.image = UIImage(systemName: phase == "recording" ? "stop.fill" : "mic.fill")
        microphone.configuration?.imagePadding = 6
        microphone.configuration?.baseBackgroundColor = phase == "recording" ? .systemRed : .label
        microphone.configuration?.baseForegroundColor = .systemBackground
        voiceButton.isEnabled = microphone.isEnabled
        voiceButton.configuration?.title = label
        voiceButton.configuration?.image = microphone.configuration?.image
        voiceButton.configuration?.imagePadding = 8
        voiceButton.configuration?.baseBackgroundColor = microphone.configuration?.baseBackgroundColor
        voiceButton.configuration?.baseForegroundColor = .systemBackground
        voiceButton.accessibilityLabel = phase == "recording" ? "Finish dictation" : phase == "starting" ? "Starting dictation" : phase == "processing" ? "Processing dictation" : phase == "error" && status.canRetry ? "Retry dictation upload" : "Start dictation"
        voiceHint.text = phase == "recording" ? "Tap Finish when you’re done" : phase == "processing" ? "Turning speech into text…" : !hasFullAccess ? "Typing works without Full Access" : !status.available ? "Start a keyboard session in WoVoice" : "Tap to speak · up to 60 seconds"
        if phase != lastPhase {
            if status.animate && !UIAccessibility.isReduceMotionEnabled {
                microphone.transform = CGAffineTransform(scaleX: 0.96, y: 0.96)
                voiceButton.transform = microphone.transform
                UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
                    self.microphone.transform = .identity; self.voiceButton.transform = .identity
                }
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
            guard let current = snapshot, current.exposesContext else { localMessage = "This editor does not expose a safe insertion target. Dictate in WoVoice and copy your text."; renderStatus(); return }
            let id = UUID(); pending.begin(id: id); target = current
            try? bridge.renewLease(request: id, owner: status.owner ?? "")
            send(.start, request: id, context: DictationContext.capture(preferences: status.preferences, field: field, before: current.before, after: current.after))
        }
        feedback()
    }
    private func send(_ action: KeyboardCommand.Action, request: UUID, context: DictationContext? = nil) {
        guard hasFullAccess, let owner = status.owner else { return }
        do { try bridge.enqueue(KeyboardCommand(id: UUID(), request: request, owner: owner, action: action, createdAt: .now, context: context,
                                             sentenceStart: TextPolicy.sentenceStart(before: target?.before))) }
        catch { localMessage = error.localizedDescription; pending.reset(); target = nil; renderStatus() }
    }
    private func abandon() {
        if let request { send(.cancel, request: request); bridge.removeLease(request: request) }
        pending.reset(); target = nil
    }
    private func learnCorrection() {
        guard let correction, correction.expires > .now, status.learning, status.available, hasFullAccess, !sensitive, field == .text,
              correction.document == documentIdentifier, let before = textDocumentProxy.documentContextBeforeInput,
              let term = CorrectionLearning.suggestion(generated: correction.text, before: before), let owner = status.owner else { return }
        self.correction = nil
        var command = KeyboardCommand(id: UUID(), request: UUID(), owner: owner, action: .suggest, createdAt: .now, context: nil, sentenceStart: false)
        command.suggestion = term; command.recordID = correction.recordID
        try? bridge.enqueue(command)
    }
    @objc private func cancelPressed() { abandon(); localMessage = "Cancelled"; renderStatus() }
    @objc private func toggleMode() {
        abandon(); localMessage = nil; showTyping.toggle(); renderStatus(); feedback()
    }
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

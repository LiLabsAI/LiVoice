import SwiftUI
import WoVoiceCore

struct DashboardView: View {
    @Bindable var model: AppModel
    @State private var tab = 0
    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView(model: model) }.tabItem { Label("Home", systemImage: "waveform") }.tag(0)
            NavigationStack { HistoryView(model: model) }.tabItem { Label("History", systemImage: "clock") }.tag(1)
            NavigationStack { DictionaryView(model: model) }.tabItem { Label("Dictionary", systemImage: "text.book.closed") }.tag(2)
            NavigationStack { SettingsView(model: model) }.tabItem { Label("Settings", systemImage: "gearshape") }.tag(3)
        }
        // Recreate navigation and pending dialogs when the signed-in account changes.
        .id(model.owner)
        .tint(.primary)
        .safeAreaInset(edge: .bottom) {
            if model.undoRecord != nil {
                HStack { Text("Deleted"); Spacer(); Button("Undo") { model.undoDelete() }.fontWeight(.semibold) }.padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
            }
        }
        .alert("WoVoice", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) { model.message = nil }
        } message: { Text(model.message ?? "") }
    }
}

struct HomeView: View {
    @Bindable var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var period = AnalyticsPeriod.today
    private var metrics: Metrics { Metrics(records: model.analytics, since: period.since()) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                #if DEBUG
                if DevelopmentFixture.enabled {
                    NavigationLink(DevelopmentFixture.microphoneEnabled ? "Local microphone test · Test editors" : "Sample workspace · Test editors") { FixtureEditorsView() }.font(.caption).foregroundStyle(.orange)
                    if DevelopmentFixture.microphoneEnabled { Text("Audio stays on this computer. The test service returns a fixed sentence; it does not recognize your speech.").font(.caption).foregroundStyle(.secondary) }
                }
                #endif
                if model.owner == nil || !dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Your words.\nA little clearer.").font(.system(.largeTitle, design: .rounded, weight: .semibold)).tracking(-1)
                        Text("Speak naturally. WoVoice handles the grammar and punctuation.").foregroundStyle(.secondary)
                    }
                }
                if model.owner == nil {
                    VStack(alignment: .leading, spacing: 16) {
                        Label("One account, across your devices", systemImage: "person.crop.circle").font(.headline)
                        Text("Sign in securely with your email to dictate and sync your vocabulary. Audio is sent only when you choose to dictate.").foregroundStyle(.secondary)
                        Button { Task { await model.signIn() } } label: { Text(model.busy ? "Opening sign-in…" : "Sign in to WoVoice").foregroundStyle(Color(uiColor: .systemBackground)).frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent).disabled(model.busy).accessibilityIdentifier("signIn")
                    }.card()
                } else {
                    VStack(spacing: 20) {
                        adaptiveRow {
                            Label {
                                Text(model.microphoneActive ? "Microphone active" : "Microphone off")
                            } icon: { Circle().fill(model.microphoneActive ? .orange : Color.secondary.opacity(0.4)).frame(width: 7, height: 7) }.font(.subheadline)
                            if !dynamicTypeSize.isAccessibilitySize { Spacer() }
                            if model.keyboardSessionActive { Text(model.status.expiresAt, style: .timer).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary) }
                        }
                        Button {
                            if model.haptics { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
                            Task {
                                if !model.keyboardSessionActive { await model.startSession(keyboard: false) }
                                model.toggleRecording()
                            }
                        } label: {
                            ZStack {
                                Circle().fill(model.recording ? Color.red : Color.primary).frame(width: 100, height: 100)
                                if model.status.phase == "processing" { ProgressView().tint(Color(uiColor: .systemBackground)) }
                                else { Image(systemName: model.recording ? "stop.fill" : "mic.fill").font(.system(size: 34)).foregroundStyle(Color(uiColor: .systemBackground)) }
                            }
                        }.buttonStyle(.plain).disabled(model.busy || model.status.phase == "processing" || model.accountRestriction != nil)
                            .accessibilityLabel(model.recording ? "Finish dictation" : "Start dictation").accessibilityIdentifier("dictate")
                        if model.recording && model.waveform {
                            HStack(spacing: 5) {
                                ForEach(0..<5) { i in Capsule().fill(.primary).frame(width: 4, height: 8 + min(32, model.status.level * 400) * (i == 2 ? 1 : 0.65)) }
                            }.frame(height: 40).animation(reduceMotion || !model.animations ? nil : .easeOut(duration: 0.12), value: model.status.level).accessibilityHidden(true)
                        }
                        Text(model.recording ? "Listening · \(Int(model.status.elapsed))s / 60s" : model.status.phase == "processing" ? "Turning speech into text…" : model.accountRestriction != nil ? "Voice input unavailable" : "Tap to speak").font(.headline)
                        if let restriction = model.accountRestriction {
                            Text(restriction).font(.subheadline).foregroundStyle(.red)
                            Button(model.refreshingProfile ? "Checking account…" : "Check account status") { Task { await model.refreshProfile() } }
                                .disabled(model.refreshingProfile || model.busy)
                        }
                        if model.status.phase == "error" {
                            Text(model.status.message).font(.subheadline).foregroundStyle(.secondary)
                            if model.status.canRetry { Button("Retry upload") { model.retry() }.buttonStyle(.bordered) }
                        }
                        if model.recording || model.status.phase == "processing" { Button("Cancel", role: .cancel) { model.cancelRecording() } }
                    }.frame(maxWidth: .infinity).card()
                    if !model.lastText.isEmpty {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("Latest dictation").font(.caption).foregroundStyle(.secondary)
                            Text(model.lastText).textSelection(.enabled)
                            HStack { Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = model.lastText }; Spacer(); ShareLink(item: model.lastText) }
                        }.card()
                    }
                    keyboardSetup
                    if dynamicTypeSize.isAccessibilitySize { periodPicker.pickerStyle(.menu) }
                    else { periodPicker.pickerStyle(.segmented) }
                    adaptiveRow {
                        metric("Words", value: metrics.words.formatted())
                        metric("Dictations", value: metrics.dictations.formatted())
                        if let quota = model.profile?.quota { metric("Minutes left", value: String(format: "%.1f", quota.remainingAudioSeconds / 60)) }
                    }
                    adaptiveRow { metric("Speaking pace", value: "\(metrics.wpm) wpm"); metric("Dictation time", value: "\(Int(metrics.audioMs/1000)) sec") }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("AI insights").font(.headline)
                        LabeledContent("Median processing", value: String(format: "%.1f sec", metrics.medianProcessingMs/1000))
                        LabeledContent("Polished results", value: "\(metrics.polishedPercent)%")
                        LabeledContent("Correction rate", value: "\(metrics.correctedPercent)%")
                        if model.costEstimates {
                            LabeledContent("Estimated AI usage", value: String(format: "$%.4f", metrics.cost))
                            Text(String(format: "%.1f neurons · ASR %.1f · Cleanup %.1f", metrics.asrNeurons + metrics.polishNeurons, metrics.asrNeurons, metrics.polishNeurons)).font(.caption).foregroundStyle(.secondary)
                            Text("WoVoice estimates in USD, not a Cloudflare invoice.").font(.caption).foregroundStyle(.secondary)
                        }
                    }.font(.subheadline).card()
                }
                Text("English (India) · Up to 60 seconds per dictation").font(.caption).foregroundStyle(.secondary)
            }.padding(24).frame(maxWidth: 720, alignment: .leading).frame(maxWidth: .infinity)
        }.navigationTitle("WoVoice").toolbar {
            if model.owner != nil { Button { Task { await model.refreshProfile(); await model.sync() } } label: { if model.syncing || model.refreshingProfile { ProgressView() } else { Image(systemName: "arrow.triangle.2.circlepath") } }.disabled(model.syncing || model.refreshingProfile).accessibilityLabel("Refresh account and sync") }
        }
    }
    private var adaptiveRow: AnyLayout {
        dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(spacing: 16))
    }
    private var periodPicker: some View {
        Picker("Period", selection: $period) { ForEach(AnalyticsPeriod.allCases, id: \.self) { Text($0.title).tag($0) } }
    }
    private var keyboardSetup: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Speak in other apps", systemImage: "keyboard").font(.headline)
            Text("Add WoVoice in Settings → General → Keyboard → Keyboards, then allow Full Access so the keyboard can communicate with this app.").font(.subheadline).foregroundStyle(.secondary)
            Text("Start a five-minute keyboard session here, return to your app, then use the globe key to choose WoVoice. Tap its microphone to start and stop dictating.").font(.subheadline).foregroundStyle(.secondary)
            Text("During the session the microphone stays active. Audio between dictations is discarded. You can stop the session at any time; iOS’s microphone indicator remains visible.").font(.caption).foregroundStyle(.secondary)
            Button(model.keyboardSessionActive ? "Stop keyboard session" : "Start keyboard session") {
                if model.keyboardSessionActive { model.stopSession() } else { Task { await model.startSession() } }
            }.buttonStyle(.bordered).disabled(model.busy || (model.accountRestriction != nil && !model.keyboardSessionActive)).accessibilityIdentifier("keyboardSession")
        }.card()
    }
    private func metric(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 5) { Text(value).font(.title2.weight(.semibold)); Text(title).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct HistoryView: View {
    @Bindable var model: AppModel
    @State private var search = ""
    @State private var clear = false
    private var records: [CloudRecord] { model.history.filter { search.isEmpty || ($0.payload?["text"]?.string ?? "").localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            ForEach(records) { record in
                NavigationLink {
                    HistoryDetailView(model: model, recordID: record.id)
                } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(record.payload?["text"]?.string ?? "")
                        HStack {
                            Text(Date(timeIntervalSince1970: (record.payload?["createdAtMs"]?.number ?? 0)/1000), style: .date)
                            Spacer(); Text(record.payload?["polished"]?.bool == true ? "Polished" : "Transcribed")
                        }.font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 8)
                }
                    .accessibilityHint("Open dictation details")
                    .contextMenu { Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = record.payload?["text"]?.string }; ShareLink(item: record.payload?["text"]?.string ?? ""); Button("Delete", role: .destructive) { model.delete(record) } }
                    .swipeActions { Button("Delete", role: .destructive) { model.delete(record) } }
            }
        }.overlay { if records.isEmpty { ContentUnavailableView(search.isEmpty ? "Your words will appear here" : "No matching dictations", systemImage: "clock", description: Text("Only your final dictated text is saved. Nearby editor context is never added to History.")) } }
            .navigationTitle("History").searchable(text: $search, prompt: "Search your dictations")
            .toolbar { if !model.history.isEmpty { Button("Clear", role: .destructive) { clear = true } } }
            .confirmationDialog("Clear history? This also deletes synced history from your other devices.", isPresented: $clear, titleVisibility: .visible) { Button("Clear history", role: .destructive) { model.clear(.history) } }
    }
}
struct DictionaryView: View {
    @Bindable var model: AppModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var term = ""
    @State private var editing: CloudRecord?
    @State private var editTerm = ""
    @State private var search = ""
    private var entries: [CloudRecord] { model.dictionary.filter { search.isEmpty || ($0.payload?["term"]?.string ?? "").localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            Section {
                entryLayout {
                    TextField("A name, word, or phrase", text: $term).font(.body).textInputAutocapitalization(.never).autocorrectionDisabled().frame(minHeight: 44)
                    Button("Add") { model.addTerm(term); term = "" }.frame(minHeight: 44).disabled(term.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.owner == nil)
                }
            } footer: { Text("Confirmed vocabulary helps WoVoice recognize names and words you use. Up to 100 terms are included with each dictation.") }
            Section("Your vocabulary") {
                ForEach(entries) { entry in
                    entryLayout {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.payload?["term"]?.string ?? "")
                            if entry.payload?["status"]?.string == "suggested" { Text("Suggested from a correction").font(.caption).foregroundStyle(.secondary) }
                        }
                        if !dynamicTypeSize.isAccessibilitySize { Spacer() }
                        if entry.payload?["status"]?.string == "suggested" {
                            Button("Confirm") { model.updateTerm(entry, term: entry.payload?["term"]?.string ?? "") }
                                .buttonStyle(.bordered).accessibilityLabel("Confirm \(entry.payload?["term"]?.string ?? "word")")
                        } else { Image(systemName: "checkmark.circle").foregroundStyle(.secondary).accessibilityLabel("Confirmed") }
                    }
                        .swipeActions { Button("Delete", role: .destructive) { model.delete(entry) } }
                        .contextMenu {
                            Button("Edit") { editTerm = entry.payload?["term"]?.string ?? ""; editing = entry }
                            if entry.payload?["status"]?.string == "suggested" { Button("Confirm word") { model.updateTerm(entry, term: entry.payload?["term"]?.string ?? "") } }
                        }
                }
            }
        }.navigationTitle("Dictionary").searchable(text: $search, prompt: "Search vocabulary")
            .alert("Edit vocabulary", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
                TextField("Word or phrase", text: $editTerm)
                Button("Save") { if let editing { model.updateTerm(editing, term: editTerm) }; editing = nil }
                Button("Cancel", role: .cancel) { editing = nil }
            }
    }
    private var entryLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(spacing: 8))
    }
}

struct SettingsView: View {
    @Bindable var model: AppModel
    @State private var nearbyConsent = false
    @State private var deleting = false
    @State private var historyOff = false
    @State private var resetAnalytics = false
    @State private var clearLocal = false
    @State private var signingOut = false
    @State private var export: HistoryExport?
    var body: some View {
        Form {
            Section("Account") {
                if let user = model.account.user {
                    LabeledContent("Email", value: user.email)
                    NavigationLink("Signed-in devices") { SessionsView(model: model) }
                    if let restriction = model.accountRestriction { Text(restriction).foregroundStyle(.red) }
                    Button("Sign out") { signingOut = true }.disabled(model.busy)
                } else { Button("Sign in") { Task { await model.signIn() } }.disabled(model.busy) }
            }
            if model.owner != nil {
                Section {
                    Toggle("Automatic context", isOn: Binding(get: { model.preferences.level != .off }, set: { model.preferences.level = $0 ? .app : .off; model.savePreferences() }))
                    if model.preferences.level != .off {
                        Toggle("Include nearby text", isOn: Binding(get: { model.preferences.level == .nearby }, set: { if $0 { nearbyConsent = true } else { model.preferences.level = .app; model.savePreferences() } }))
                        Picker("Writing style", selection: $model.preferences.style) { ForEach(ContextStyle.allCases, id: \.self) { Text($0 == .auto ? "Automatic" : $0.rawValue.capitalized).tag($0) } }.onChange(of: model.preferences.style) { model.savePreferences() }
                    }
                } header: { Text("Automatic context") } footer: { Text("iOS exposes field type and limited cursor text, not the app or recipient identity. Unclear situations keep your natural tone. Nearby text is optional and never saved in history or sync.") }
                Section("Keyboard & feedback") {
                    Toggle("Haptic feedback", isOn: $model.haptics).onChange(of: model.haptics) { model.savePreferences() }
                    Toggle("Animations", isOn: $model.animations).onChange(of: model.animations) { model.savePreferences() }
                    Toggle("Live waveform", isOn: $model.waveform).onChange(of: model.waveform) { model.savePreferences() }
                    Button("Open iOS Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
                Section {
                    Toggle("Sync history", isOn: Binding(get: { model.cloudSettings.historySyncEnabled }, set: { enabled in
                        if enabled { Task { await model.updateCloud(history: true, retention: model.cloudSettings.historyRetentionDays) } } else { historyOff = true }
                    }))
                    Picker("Auto-delete history", selection: Binding(get: { model.cloudSettings.historyRetentionDays ?? 0 }, set: { days in Task { await model.updateCloud(history: model.cloudSettings.historySyncEnabled, retention: days == 0 ? nil : days) } })) {
                        Text("Never").tag(0); Text("30 days").tag(30); Text("90 days").tag(90); Text("1 year").tag(365)
                    }
                    Button(model.syncing || model.refreshingProfile ? "Syncing…" : "Sync now") { Task { await model.refreshProfile(); await model.sync() } }.disabled(model.syncing || model.refreshingProfile)
                    if let lastSyncedAt = model.lastSyncedAt {
                        Text("Last synced \(lastSyncedAt.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                    } else { Text("Not synced yet on this device").font(.caption).foregroundStyle(.secondary) }
                    if let warning = model.syncWarning { Text(warning).font(.callout).foregroundStyle(.orange) }
                } header: { Text("Cloud sync") } footer: { Text("Vocabulary and analytics sync with your account. History follows your choice on every device. The server encrypts stored records.") }
                Section("Data") {
                    Toggle("Keep local history", isOn: $model.keepHistory).onChange(of: model.keepHistory) { model.savePreferences() }
                    Toggle("Show cost estimates", isOn: $model.costEstimates).onChange(of: model.costEstimates) { model.savePreferences() }
                    Toggle("Correction suggestions", isOn: $model.learning).onChange(of: model.learning) { model.savePreferences() }
                    Button("Export history", systemImage: "square.and.arrow.up") { export = HistoryExport(text: historyExport) }
                        .disabled(model.history.isEmpty)
                    Button("Reset analytics", role: .destructive) { resetAnalytics = true }
                    Button("Delete account", role: .destructive) { deleting = true }.disabled(model.busy)
                }
            }
            Section("Local data") {
                Button("Clear all local data", role: .destructive) { clearLocal = true }.disabled(model.busy)
            }
            Section("About") {
                Link("Privacy policy", destination: URL(string: "https://wovoice.aliahad.com/privacy")!)
                Link("Terms of use", destination: URL(string: "https://wovoice.aliahad.com/terms")!)
                Link("Service status", destination: URL(string: "https://wovoice.aliahad.com/status")!)
                LabeledContent("Version", value: "1.0.0 (1)")
            }
        }.navigationTitle("Settings")
            .sheet(item: $export) { item in
                HistoryShareSheet(text: item.text).presentationDetents([.medium, .large])
            }
            .confirmationDialog("Allow nearby editor text?", isPresented: $nearbyConsent, titleVisibility: .visible) {
                Button("Allow nearby text") { model.preferences.level = .nearby; model.savePreferences() }
                Button("Cancel", role: .cancel) { }
            } message: { Text("For each dictation, WoVoice may send up to 512 characters before and 128 after your cursor to improve grammar and tone. This text is temporary and is never saved in History or cloud sync. You can turn it off at any time.") }
            .confirmationDialog("Turn off history sync?", isPresented: $historyOff, titleVisibility: .visible) { Button("Delete cloud history and turn off", role: .destructive) { Task { await model.updateCloud(history: false, retention: model.cloudSettings.historyRetentionDays) } } } message: { Text("Cloud history will be deleted. Your local copies remain.") }
            .confirmationDialog("Delete your WoVoice account?", isPresented: $deleting, titleVisibility: .visible) { Button("Verify email and delete", role: .destructive) { Task { await model.deleteAccount() } } } message: { Text("This permanently deletes your account and its cloud data. You will verify your email again first.") }
            .confirmationDialog("Sign out of WoVoice?", isPresented: $signingOut, titleVisibility: .visible) { Button("Sign out", role: .destructive) { Task { await model.logout() } } } message: { Text("Voice input and sync will stop. Your local history and vocabulary remain on this device.") }
            .confirmationDialog("Reset analytics on all synced devices?", isPresented: $resetAnalytics, titleVisibility: .visible) { Button("Reset analytics", role: .destructive) { model.clear(.analytics) } }
            .confirmationDialog("Clear all local data and sign out?", isPresented: $clearLocal, titleVisibility: .visible) { Button("Clear local data", role: .destructive) { Task { await model.clearLocalData() } } } message: { Text("Removes this device’s history, vocabulary, analytics, settings, and secure sign-in. Your cloud data remains.") }
    }
    private var historyExport: String { model.history.compactMap { $0.payload?["text"]?.string }.joined(separator: "\n\n") }
}
private struct HistoryExport: Identifiable {
    let id = UUID()
    let text: String
}
private struct HistoryShareSheet: UIViewControllerRepresentable {
    let text: String
    @Environment(\.dismiss) private var dismiss
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in
            Task { @MainActor in dismiss() }
        }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
struct SessionsView: View {
    @Bindable var model: AppModel
    @State private var selected: DeviceSession?
    var body: some View {
        List(model.sessions) { device in
            VStack(alignment: .leading, spacing: 6) {
                Text(device.deviceName).font(.headline)
                Text(device.current == 1 ? "This device" : "Last active \(Date(timeIntervalSince1970: device.lastSeenAt/1000).formatted())").font(.caption).foregroundStyle(.secondary)
                Button("Sign out this device", role: .destructive) { selected = device }
            }.padding(.vertical, 6)
        }.navigationTitle("Signed-in devices").task { await model.loadSessions() }
            .confirmationDialog("Sign out this device?", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), titleVisibility: .visible) {
                Button("Sign out device", role: .destructive) { if let selected { Task { await model.revoke(selected) } }; selected = nil }
            }
    }
}
private extension View {
    func card() -> some View { padding(20).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20)).overlay(RoundedRectangle(cornerRadius: 20).stroke(.primary.opacity(0.08), lineWidth: 1)) }
}

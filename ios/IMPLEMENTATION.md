# iOS implementation and acceptance

The objective is Android feature parity wherever public iOS APIs permit it. This checklist remains open until the features and their real device/simulator behavior have been verified. A successful build alone is not acceptance.

- [ ] Native iPhone and iPad app: Home, History, Dictionary, Settings, onboarding, permissions, accessible and reduced-motion UI.
- [ ] 16 kHz PCM WAV dictation, silence gate, metering, 60-second limit, cancellation, temporary failure retry, audio deletion and interruption recovery.
- [ ] Keyboard extension: offline manual keyboard, globe key, dictation controls, selection-aware insertion, original-document/cursor guards, no password capture, rich-text host insertion, stale-result rejection.
- [ ] Explicit, bounded background microphone session owned by the main app; clear microphone status and stop control. No silent audio to keep the app alive.
- [ ] Email OTP + Turnstile in system authentication session, PKCE/state, Keychain refresh tokens, single-flight refresh, logout, account restrictions, session management, fresh verification before deletion.
- [ ] Account-partitioned local history, vocabulary, correction suggestions, dashboard analytics, export/deletion/undo.
- [ ] Existing v2 history/dictionary/analytics sync: settings before upload, durable outbox, server-wins conflicts, local delete rebasing, edit-during-upload protection, retention and history-off behavior.
- [ ] Automatic context with separate account/device consent, bounded nearby snippets, conservative field classification and no invented host identity or recipient.
- [ ] Settings parity: haptics, animation, waveform, voice/text modes, privacy, retention, data controls, status and quota.
- [ ] Swift tests, Worker auth regression tests, ad-hoc signed simulator builds, iPhone/iPad runtime checks, keyboard and rich-text integration tests, CI and setup documentation.

## Platform boundaries

iOS does not expose Android's accessibility service or a systemwide floating overlay. A keyboard extension cannot access the microphone or identify its host app. Apps can refuse third-party keyboards, and secure/phone fields use the system keyboard. We must not promise universal insertion or guess a recipient. The containing app owns recording, and the keyboard uses `UITextDocumentProxy` to insert at the user's selection.

Background dictation requires a real, user-started audio session. The initial implementation will use a bounded keyboard session with a visible microphone indicator; audio between utterances is discarded. It must explain that the microphone remains active during that session. It must stop on timeout, sign-out, interruption, cancellation, or an explicit Stop action. Force-quitting ends it. No private API, responder-chain URL-opening workaround, persistent silent playback, or hidden screen inspection is permitted.

Apple's public `NSExtensionContext.open` API does not support the keyboard extension point. The keyboard therefore gives setup guidance when the host audio session is unavailable. A host-app shortcut can start the session, and the user returns to the desired app. Investigate smoother public API workflows without hiding these constraints.

Sources checked November 3, 2026:

- [Custom keyboard limitations](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html)
- [Keyboard Full Access](https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard)
- [Text document proxy](https://developer.apple.com/documentation/uikit/uitextdocumentproxy)
- [Document identity](https://developer.apple.com/documentation/uikit/uitextdocumentproxy/documentidentifier) and [public key-value property access](https://developer.apple.com/documentation/objectivec/nsobject-swift.class/value(forkey:))
- [Extension URL opening](https://developer.apple.com/documentation/foundation/nsextensioncontext/open(_:completionhandler:))
- [Background recording](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record)
- [System web authentication](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession)
- [Apple DTS: background audio activation restrictions](https://developer.apple.com/forums/thread/816408)

## Current evidence — November 3, 2026

The implementation is present in the app, keyboard and WidgetKit targets, with a local Swift package. The broad acceptance boxes above remain open where live integration or device behavior has not been proven.

- Ten shared Swift tests pass for context consent and Unicode budgets, audience clues, text spacing, original-editor guards, WAV/silence rules, correction suggestions, transactional SQLite state, account partitioning, edits during upload, duplicate vocabulary, and Undo holds.
- Nineteen tests pass inside the iPhone simulator, including real Keychain round trips, native AVAudioConverter conversion from 48 kHz to 16 kHz, the 60-second sample cap, token refresh/revocation, history-off uploads, local-delete conflict rebasing, account-switch cleanup, synthetic dictation completion, ordered start/finish/cancel commands, actual Live Activity registration plus its Stop intent, monotonic capture/session deadlines, keyboard eviction/recreation recovery, safe handling of a missing editor ID during teardown, and retryable malformed transcription responses.
- All 103 Worker/admin tests pass (92 Worker and 11 browser UI tests), including three new functional tests for iOS callback routing and unchanged Android/Mac routing. Worker/admin builds pass.
- Debug simulator builds and unsigned Release builds for a real iOS device architecture pass. Production binaries exclude the synthetic account/service/audio fixture. No Apple signing team or provisioning profile has been selected.
- With the user's explicit permission, WoVoice and Full Access were enabled in the **iPhone 17 Pro / iOS 26.5 simulator**. The real extension inserted synthetic dictation into a native attributed text view and a WebKit contenteditable field, preserving existing bold formatting. The native test caught and verified a fix for missing space before existing text. Moving the cursor cancelled dictation, and the password field switched to the system keyboard.
- With Full Access temporarily disabled, letters, spaces, numbers, deletion, held backspace, and the globe switcher worked offline. Dictation remained disabled. Full Access was then restored under the user’s existing approval. The voice panel inserted synthetic dictation with formatting preserved; switching to typing cancelled a recording without inserting text.
- Simulator crash-log review found UIKit returning a missing document ID during editor teardown. The public Objective-C property is now read through KVC as an optional value; insertion and correction learning stop when identity is unavailable. A native regression test covers valid, missing, and detached identity.
- The native iPad Home layout was inspected in the **iPad Pro 13-inch / iPadOS 26.5 simulator**. The project is open in Xcode, with the tested iPhone 17 Pro selected as the run destination.

## Remaining acceptance

- Live email/Turnstile/PKCE sign-in and fresh account-deletion verification need the new fixed iOS callback in `worker/public/auth.js` deployed to the service. This implementation has not deployed or changed production.
- Verify real microphone recording, background dictation in another app, interruption/Bluetooth recovery, timeout, and retry on an actual audio route. Synthetic audio and converter tests do not prove those OS behaviors or model quality.
- Verify the actual system Shortcut and Dynamic Island/Lock Screen Stop UI; the underlying Live Activity registration and intent code pass simulator tests.
- Complete VoiceOver/large-text checks and broader editor/app compatibility. Do not claim universal text insertion.
- Review iOS distribution/signing and App Store privacy declarations before a TestFlight/App Store release. CI is configured locally and has not run on GitHub yet.

## Build

The Xcode project is generated from `project.yml` with XcodeGen. Do not commit signing credentials or select an unrelated developer team. Simulator development does not require an Apple account. A physical iPhone build with the shared App Group requires a team and matching provisioning profiles.

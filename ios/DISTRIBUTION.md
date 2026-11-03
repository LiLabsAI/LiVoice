# iOS signing and privacy review

This is a development build, not an App Store or TestFlight release. The simulator app and unsigned device Release targets build successfully. Physical iPhone audio behavior, live authentication, and distribution provisioning remain acceptance gates in `IMPLEMENTATION.md`.

## Signing

The app, keyboard and Live Activity extension use these identifiers:

| Target | Bundle identifier |
| --- | --- |
| App | `com.aliahad.wovoice.ios` |
| Keyboard | `com.aliahad.wovoice.ios.keyboard` |
| Live Activity | `com.aliahad.wovoice.ios.widgets` |
| Shared App Group | `group.com.aliahad.wovoice.ios` |

Select the product owner's Apple development team in Xcode for all three targets. Provision the shared App Group for that team. A valid certificate alone does not provision the identifiers or entitlements. Keep certificates, private keys, provisioning profiles and account-specific signing overrides out of Git.

The committed simulator configuration uses ad-hoc signing and supports Keychain, the keyboard and local Live Activities. Do not disable simulator code signing. A custom shortcut created in Apple's Shortcuts app successfully ran **Start WoVoice keyboard session**, opened the host app and started the synthetic session. The automatic App Shortcut tile separately failed with `Couldn't find AppShortcutsProvider`. The extracted metadata and executable contain the provider, and testing a normal executable layout did not fix tile execution. Signing diagnostics were inconclusive; do not describe this as a proven signing requirement or claim the automatic tile works. A manually certificate-signed simulator test bundle was rejected at launch, and the supported ad-hoc build was restored.

For a device archive, configure the owner's team and matching profiles in Xcode, choose a generic iOS device, then use Product → Archive. Review signing and privacy in Organizer before uploading. This development work has not created an App Store Connect record, accepted agreements, provisioned distribution profiles or uploaded a build. The user has explicitly deferred production deployment and App Store distribution.

The server must deploy the fixed `platform=ios` authentication callback before live sign-in can complete. This is separate from client distribution. No AI model or prompt publication is required for iOS support.

## Data declarations

The bundled `Shared/PrivacyInfo.xcprivacy` describes linked data used for app functionality, with no tracking:

| Declaration | Owning behavior |
| --- | --- |
| Email address | Email sign-in and account management |
| User ID | Account tokens, account-partitioned sync and quota |
| Other user content | Synced final dictation text and vocabulary |
| Product interaction | Dictation counts, words, duration and vocabulary-use metrics |
| Performance data | Synced ASR, cleanup and total processing timings |

`Core/Sources/WoVoiceCore/Models.swift` constructs history and analytics payloads. Analytics removes transcript text. `App/SyncService.swift` applies cloud settings before uploading. History sync can be disabled and cloud history deleted; local history has a separate setting. Nearby text is separately consented and excluded from history and sync.

Audio is held in memory in the main app, uploaded for a requested transcription and discarded on success, cancellation or retry expiry. Idle microphone-session audio is discarded rather than accumulated. The keyboard's App Group does not contain audio, account tokens or the history database. A pending insertion result expires. The client does not collect contacts, advertising identifiers, location, browsing history or whole conversations.

Apple distinguishes request-time processing from data retained beyond servicing the request. Before submitting App Store Connect privacy answers, verify the deployed AI provider's current retention behavior and the production service's logging configuration. Audio and nearby text can be excluded from retained-data declarations only if that complete processing chain actually discards them after the request. Do not infer provider behavior from the client manifest. The current declarations do not assert that no data is collected.

No direct required-reason API use was found in the current Swift sources: settings use SQLite rather than UserDefaults, and local file operations set protection and backup flags rather than reading file timestamps or free disk capacity. Recheck required-reason API use when dependencies or storage behavior change. The app has no third-party runtime SDKs. Xcode's archive privacy report and App Store validation still need review before distribution.

## Review behavior

For App Review, explain the public iOS workflow: the containing app starts a bounded, explicit microphone session; the keyboard requests dictation and inserts through `UITextDocumentProxy`. The main app owns audio capture. iOS does not permit the Android floating accessibility bubble. A shortcut queues its request until the app's scene is active; it does not activate recording from a suspended app.

Provide reviewers a working sign-in path and the keyboard setup steps. They should be able to type offline without Full Access, change keyboards with the globe key, and stop the microphone session in the app or its Live Activity. Secure fields and apps that reject custom keyboards use the system keyboard. Do not claim insertion into every editor or automatic identification of the host app or recipient.

Sources reviewed November 3, 2026:

- [Apple privacy data definitions](https://developer.apple.com/app-store/app-privacy-details/)
- [Privacy manifest files](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files)
- [Collected data types](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatype)
- [App Review Guidelines, including keyboard and recording rules](https://developer.apple.com/app-store/review/guidelines/)
- [Keyboard Full Access](https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard)
- [Live Activity intents](https://developer.apple.com/documentation/appintents/liveactivityintent)

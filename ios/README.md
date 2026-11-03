# WoVoice for iOS

Native SwiftUI iPhone/iPad application and UIKit keyboard extension. Swift 6 data-race checking is enabled. The local `WoVoiceCore` package implements the existing Cloudflare API, context/text/audio policies, and account-partitioned SQLite storage. No third-party runtime dependencies.

This implementation is in development. `IMPLEMENTATION.md` tracks acceptance and remaining parity work; a simulator build is not a production release.

## Build and run

```sh
brew install xcodegen
cd ios
xcodegen generate
swift test --package-path Core
xcodebuild -project WoVoice.xcodeproj -scheme WoVoice \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual build
```

Open `WoVoice.xcodeproj` in Xcode, choose an installed iPhone/iPad simulator and Run. Xcode 27 calls the Simulator interface **Device Hub**. Keep ad-hoc simulator signing enabled: `CODE_SIGNING_ALLOWED=NO` produces an app that cannot use Keychain. No Apple account is needed for simulator development.

For isolated development checks, launch a Debug build with `--fixture`. The visible **Sample workspace** uses an in-memory account/database, generated audio, and a URLProtocol fake service. It cannot contact the production API and is excluded from Release builds. Its native/WebKit/password editors exercise the real keyboard extension, including formatting preservation and cursor-change cancellation. Simulator keyboard Full Access still requires an explicit user choice.

`--fixture-microphone` uses the same local-only server and in-memory account with the **real microphone engine**. The visible banner identifies the test mode. Use it only after obtaining microphone-test permission: audio stays in memory, the server does not perform speech recognition, and all audio is discarded afterward. This mode is also excluded from Release builds.

For a physical device select your own Apple development team for all targets, provision the `group.com.aliahad.wovoice.ios` App Group, and enable the same App Group on the app and keyboard. Never put signing keys or profiles in this repository. The default identifiers are `com.aliahad.wovoice.ios` and `com.aliahad.wovoice.ios.keyboard`; customize `WOVOICE_APP_GROUP` consistently if those identifiers belong to another team.

## Dictation in other apps

1. Sign in through the secure system authentication window. The website must include the fixed `platform=ios` callback introduced with this client; production deployment is separate.
2. In iOS Settings → General → Keyboard → Keyboards, add WoVoice and allow Full Access. Offline typing and the globe key work without Full Access.
3. In WoVoice, start a five-minute keyboard session. The microphone remains active for the duration, and audio between dictations is discarded in memory.
4. Return to the desired app, choose WoVoice with the globe key, and tap Speak/Finish. The extension inserts only if the original document, cursor context and selection still match.
5. Stop the session in WoVoice, or let it expire. Audio interruptions and force-quit end it. Expired or unavailable sessions show setup guidance.

iOS has no Android accessibility bubble. Password fields, phone pads, and apps that reject third-party keyboards use the system keyboard. The keyboard cannot identify WhatsApp/Mail or read whole conversations. Automatic context uses exposed field traits and, only with consent, bounded cursor snippets. No unsupported URL-opening or private APIs are used.

## Data boundary

- Main app: tokens in private, device-only Keychain; history/vocabulary/analytics in a protected SQLite database excluded from backup.
- App Group: expiring session status, queued keyboard commands, bounded opted-in context, and an expiring insertion result. No account tokens, audio, full editor snapshots, or history database.
- Keyboard commands use individually named atomic files, drained in creation order. A rapid Finish/Cancel sequence cannot overwrite an earlier command. Expired commands are rejected and cleared.
- Keyboard: original editor snapshot only in memory. It clears pending work on editor changes or dismissal, and rejects stale results.
- Worker: unchanged audio quotas and v2 encryption contract. iOS does not retire an account's old recovery-key vault, since it has no local vault key to import. Existing Android/Mac clients can migrate that vault.

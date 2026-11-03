# Automatic App Shortcut provider reproduction

Development-only diagnostic. It is not included in any WoVoice target and has no microphone, network, account, keyboard, App Group or storage code. `ProbeIntent.perform()` increments an on-screen counter. The automatic **Run probe** tile must open the app and show a counter greater than zero.

## Observed November 3, 2026

Toolchain: Xcode 27.2 (27B52), Swift 6.4.2, iPhone Simulator 27.2 SDK, deployment target iOS 17.0. Simulator signing was ad-hoc. No production service or Apple account change was made.

| Variant | Runtime | Actual automatic-tile result |
| --- | --- | --- |
| Baseline | Existing iOS 26.5 simulator | `Unable to run App Shortcut` |
| Baseline | Newly created, clean iOS 26.5 simulator | Same failure |
| Launch calls `updateAppShortcutParameters()` | iOS 26.5 | Same failure |
| Provider explicitly `nonisolated` | iOS 26.5 | Same failure |
| Intent and provider explicitly `nonisolated` | iOS 26.5 | Same failure |
| Provider and its getter explicitly public | iOS 26.5 | Same failure |
| Baseline, byte-identical installed executable | iOS 27.2 | Opens the app; `Shortcut runs: 1` |

The installed baseline executable SHA-256 was `6fbd716ba10dfddaf07d600d40ce99177150f197f04112bb5d730da59f5b294c` on both the clean iOS 26.5 simulator and iOS 27.2. A rebuild may have a different hash; compare the installed artifacts for each comparison rather than expecting this historical hash.

The actual WoVoice automatic **Start dictation session** tile also opened the app and started its synthetic five-minute session on iOS 27.2. The session was stopped afterward. On iOS 26.5, a saved custom shortcut invoking the direct **Start WoVoice keyboard session** action works, while the automatic tile fails. This distinguishes the provider path from the underlying intent and foreground audio handoff.

The iOS 26.5 log reports `LNActionForAutoShortcutPhraseFetchError`, code 1, `Couldn't find AppShortcutsProvider`. Its missing signing-team diagnostic is not sufficient to identify the cause: ad-hoc signing also works on iOS 27.2. Provider metadata and symbols are present, and a normal executable layout did not repair the actual app's older-runtime failure. The evidence narrows the problem to a runtime/toolchain compatibility path; it does not establish an Apple-confirmed cause or prove behavior on physical iPhones.

## Repeat

Generate and build this separate project, selecting an installed simulator UDID:

```sh
xcodegen generate --spec ios/diagnostics/shortcut-provider/project.yml
PROBE_SIMULATOR_ID='<simulator UDID>'
xcodebuild -project ios/diagnostics/shortcut-provider/ShortcutProbe.xcodeproj \
  -scheme ShortcutProbe -sdk iphonesimulator \
  -destination "id=$PROBE_SIMULATOR_ID" \
  -derivedDataPath /tmp/wovoice-shortcut-probe-build build
xcrun simctl install "$PROBE_SIMULATOR_ID" \
  /tmp/wovoice-shortcut-probe-build/Build/Products/Debug-iphonesimulator/ShortcutProbe.app
xcrun simctl launch "$PROBE_SIMULATOR_ID" com.aliahad.wovoice.shortcutprobe
xcrun simctl launch "$PROBE_SIMULATOR_ID" com.apple.shortcuts
```

In Shortcuts, open the WoVoiceProbe app collection and run **Run probe**. In iOS 26.5 its tile also appears in All Shortcuts; iOS 27.2 puts app collections under Library → Apps. A successful build or direct invocation of `perform()` cannot establish that this automatic tile works.

For an agent with Computer Use, select this simulator in Device Hub, bind its app as `deviceHub`, then evaluate `check.js` in `cua_repl`. Call `await checkAutomaticShortcut(deviceHub, 'Run probe', /Shortcut runs: [1-9]/)`. It reads the actual UI, presses the named automatic tile and reports PASS, FAIL or INCONCLUSIVE. It does not automate UI through shell commands or read private system state. The first baseline run returned FAIL; iOS 27.2 showed the counter increment after the app transition.

The failure requires the system provider lookup. A unit test of the intent would miss it. Keep this diagnostic separate from shipping code; no speculative actor, visibility, signing or debug-layout workaround has been applied to WoVoice. Repeat the cross-runtime check after a toolchain update and before distribution. No feedback report has been submitted to Apple.

References:

- [Apple: verifying App Intents implementations](https://developer.apple.com/documentation/appintents/verifying-your-app-intents-implementation)
- [Apple: Xcode build product layouts](https://developer.apple.com/documentation/xcode/understanding-build-product-layout-changes)

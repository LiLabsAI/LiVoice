# CLAUDE.md

This file provides guidance to coding agents when working with code in this repository.

## What this is

LiVoice is a speech-first dictation product (formerly WoVoice; repository `aliahadmd/LiVoice`) with three client apps around one Cloudflare Worker backend:

| Part | Path | Stack |
| --- | --- | --- |
| Android keyboard (IME), floating bubble & dashboard app | `app/` | Kotlin, Views built in code (no XML layouts/Compose), Room, AccessibilityService |
| Platform-free Kotlin core | `shared/` | Kotlin/JVM 11: Room DAO, account/session, sync engine, text & context policies. **Consumed only by `app/`** |
| macOS app | `desktop/` | Electron + React (`electron-vite`), `node:sqlite`, native C/ObjC helpers (`native/tapd.c`, `native/contextd.m`) |
| iOS app & keyboard extension (in development) | `ios/` | Swift 6 / SwiftUI, `ios/Core/` SPM library, XcodeGen (`ios/project.yml`), Live Activities |
| Backend + website + admin console | `worker/` | Cloudflare Worker (TypeScript), D1, Workers AI, Email Service, Turnstile; admin SPA in `worker/admin-ui/` |
| Shared cross-platform wire contracts | `contracts/` | Sync v2 and payload compatibility fixtures verified by client tests |
| Accuracy benchmark | `benchmark/` | `node benchmark/run.mjs <audio dir>` against production with an access token |
| Marketing video | `marketing-video/` | Remotion |

`README.md` is end-user documentation (install/use), not developer docs. `CHANGELOG.md` is written for users; add an entry for user-visible changes. Versions live in `app/build.gradle.kts` (`versionName`/`versionCode`) and `desktop/package.json`; desktop releases are tagged `desktop-v<version>`.

## Compatibility-sensitive identifiers (do not blindly rename)

Several internal identifiers retain the legacy `wovoice` / `com.aliahad.wovoice` name to preserve in-place upgrades, OS permissions, active sessions, and encrypted data:
- Android `applicationId` / `namespace` (`com.aliahad.wovoice`), Room DB (`wovoice-local.db`), Keystore alias (`wovoice_account_secrets_v2`), SharedPrefs (`wovoice_settings`), notification channels.
- macOS `appId` (`com.aliahad.wovoice.desktop`), `wovoice-desktop.db`, `wovoice://` deep-link scheme (supported alongside `livoice://`).
- Worker D1 AAD prefixes (`wovoice-data-key|`, `wovoice-record|`) and legacy v1 Vault HKDF salt (`"WoVoice recovery v1"`, `WV1`).
- Production Worker/D1 names (`wovoice-transcription`, `wovoice-accounts-production`) and domain (`wovoice.aliahad.com`) until dual-bound migration.

## Commands

CI (`.github/workflows/ci.yml` & `.github/workflows/ios.yml`) runs these checks; use JDK 21 and Node 22.

```bash
# Android + shared
./gradlew :app:testDebugUnitTest :shared:test :app:lintDebug assembleDebug
./gradlew :shared:test --tests 'com.aliahad.wovoice.sync.SyncCoordinatorTest'          # one class
./gradlew :shared:test --tests 'com.aliahad.wovoice.sync.SyncCoordinatorTest.someName' # one test

# Worker (run inside worker/)
npm ci
npm test                 # vitest on the Workers pool + admin-ui jsdom tests
npx vitest run -t "name" # single Worker test by name (admin-ui: add --config admin-ui/vitest.config.ts)
npm run build            # builds admin SPA into public/admin/ (gitignored) + tsc checks
npm run types            # regenerate worker-configuration.d.ts after binding changes
npm run dev              # wrangler dev; needs .dev.vars (copy .dev.vars.example)
npm run deploy           # builds admin SPA, typechecks, and deploys via wrangler

# Desktop (run inside desktop/)
npm ci
npm run build:native     # compile native/tapd and native/contextd on macOS
npm run typecheck && npm test && npm run lint && npm run build
node --import tsx --test test/sync.test.ts   # single file
node --import tsx --test --test-name-pattern="name" test/sync.test.ts
npm run build:mac        # DMG in dist/

# iOS core tests (macOS with Swift 6.2+)
swift test --package-path ios/Core

# Marketing video (inside marketing-video/)
npm run lint
```

Worker tests are fully local: `worker/wrangler.test.jsonc` + Miniflare apply the real `migrations/` to a test D1, and tests call `createHandler(services, authServices, adminServices)` with fake AI/email/Turnstile services. Never point tests at production.

Deploying the Worker (only when asked): `npx wrangler d1 migrations apply wovoice-accounts-production --remote`, then `npm run deploy`. Required secrets: `AUTH_MASTER_KEY`, `PII_KEY`, `TURNSTILE_SECRET`, `SYNC_KEK` (losing `SYNC_KEK` makes all synced records unreadable).

## Architecture

### Dictation pipeline (shared contract across Android, macOS, and iOS)

1. Client records **16 kHz mono 16-bit PCM WAV, <= 60 s**. Android: `voice/WavRecorder.kt` inside `VoiceCaptureService` (IME) or `BubbleAccessibilityService` (floating bubble). Mac: transparent overlay (`renderer/src/overlay/overlay.ts`) captures Web Audio, downsamples, and encodes (`lib/wav.ts`). iOS: `ios/App/` audio session + Live Activity.
2. A permissive silence gate (`SpeechSignalDetector` in Kotlin/TS/Swift with identical thresholds) drops near-silent recordings locally.
3. `POST /v1/transcriptions` (multipart: `audio` + JSON `options` with `locale`, `polish`, `sentenceStart`, `commands`, `<= 100` glossary terms capped at `<= 4,000` joined characters, and optional `context`). `worker/src/handler.ts`: rate limit -> `validateWav` -> `reserveQuota` -> ASR (`models.ts`: Whisper / Nova-3 configured via `ai-config-store.ts`) -> optional cleanup (`gpt-oss-20b` / `llama` via `ai-config.ts`, skipped when `polish == "off"` or literal/code/search context) -> `chooseSafePolish` (`validation.ts`) -> `completeQuota` (or release in `finally`).
4. Insertion: Android IME commits via `InputConnection` guarded by `EditorSessionGuard`; Android floating bubble uses node actions plus API 33+ `AccessibilityService.InputMethod` (`BubbleInputMethod.kt`) for custom editors like WhatsApp `MentionableEntry`. Mac validates editor state via `contextd.m`, writes transient clipboard data, pastes via native `CGEventPost` (`osascript` fallback), and safely restores the prior clipboard (`main/insert.ts`).
5. Success is recorded locally (history + analytics) and triggers cloud sync.

### Worker

- `handler.ts` chains route handlers in order: downloads -> admin -> auth (`/v1/auth/*`) -> account (`/v1/me*`) -> legacy v1 sync (`sync.ts`, `/v1/sync*`) -> v2 cloud sync (`records.ts`, `/v2/sync*`) -> transcription & live health (`/v1/status`). Errors are `ApiError` -> `{requestId, error:{code, retryable, message}}`; clients branch on `code`.
- **Auth**: email OTP + Turnstile -> authorization code bound to a PKCE challenge -> `/v1/auth/token`. Account deletion (`DELETE /v1/me`) also verifies the PKCE `codeVerifier` against the stored challenge. Access tokens 15 min, rotating refresh tokens 30 d (reuse revokes the session), 180-day absolute session. Only HMAC hashes of tokens/codes are stored; emails are AES-GCM encrypted under `PII_KEY` with an HMAC lookup column.
- **Quotas and budgets** are enforced by D1 triggers (`USER_QUOTA_EXCEEDED`, `SERVICE_DAILY_LIMIT_REACHED`, `EMAIL_RATE_LIMITED`) in migrations 0001/0003/0005; `limits.ts` mirrors them. Pricing constants in `pricing.ts` must change together with `PRICING_VERSION`.
- **AI configuration** (`ai-config.ts`, `ai-config-store.ts`, `admin-ai.ts`): runtime ASR/cleanup model selection, prompts, timeouts, and maintenance switch persisted in D1 (`0007_ai_configuration.sql`) and managed from the Admin SPA AI workspace.
- **Automatic context** (`context.ts`): shared pure classification policy (used by Worker, Admin preview, and Mac client; mirrored in Kotlin `DictationContext.kt` and Swift `ios/Core`). Off by default; `app` sends coarse enums only; `nearby` requires explicit opt-in and bounds snippets to 512 before / 128 after (`<= 2,048` UTF-8 bytes).
- **Cloud sync v2** (`records.ts`): per-account AES-GCM data key wrapped by `SYNC_KEK`, optimistic concurrency via `baseVersion`, cursor feed in `sync_record_changes`, per-account caps (history 10k / analytics 20k / dictionary 1k).
- **Admin** (`admin.ts`, `/admin*`, `/login`): separate HttpOnly cookie session, OTP + Turnstile, CSRF token + origin/fetch-site checks, atomic audit logging. Never expose dictated text, glossary terms, or sync record contents.
- **Observability**: `wrangler.jsonc` enables both Workers Logs (`observability.enabled`) and Traces (`observability.traces.enabled`).
- `index.ts` `scheduled` (every 5 min) runs maintenance steps independently; one failing step must not stop the others.

### Clients: parity across Kotlin, TypeScript, and Swift

Desktop (`desktop/src/`) and iOS (`ios/Core/`) re-implement the shared policies from `shared/`. When changing sync, auth, context, glossary limits, or capture behavior, update all three:

| Concern | Android (`shared/` + `app/`) | macOS (`desktop/src/`) | iOS (`ios/`) |
| --- | --- | --- | --- |
| Sync engine | `sync/SyncCoordinator.kt` + `SyncClient.kt` | `main/sync.ts` + `main/worker.ts` | `App/SyncService.swift` + `Core/RecordStore.swift` |
| Session / PKCE | `account/SessionManager.kt` | `main/session.ts`, `main/auth.ts` | `App/AccountSession.swift` |
| Local DB | Room (`data/`, schemas in `shared/schemas/`) | `main/db.ts` (`node:sqlite`) | `Core/RecordStore.swift` (`CSQLite`) |
| Context policy | `context/DictationContext.kt` | imports `worker/src/context.ts` + `native/contextd.m` | `Core/DictationContext.swift` |
| Silence gate | `voice/SpeechSignalDetector.kt` | `renderer/src/lib/speech.ts` | `Core/WAV.swift` (`SpeechSignal`) |

All local data is partitioned by `ownerAccountId`; never mix records across accounts, and never log dictated transcript text.

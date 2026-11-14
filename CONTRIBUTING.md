# Contributing to LiVoice

Contributions, bug reports, forks, and pull requests are welcome on [GitHub](https://github.com/LiLabsAI/LiVoice).

## Reporting Issues

- Open an issue describing the platform (Android, macOS Desktop, iOS, or Worker/Web), OS version, steps to reproduce, and expected behavior.
- For security-sensitive issues, avoid posting credentials, tokens, or private user data in public issues.

## Development & Verification

Before opening a pull request, run the checks for the platform(s) you modified:

- **Android & Shared Kotlin**:
  ```bash
  ./gradlew :shared:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  ```
- **macOS Desktop**:
  ```bash
  npm --prefix desktop ci
  npm --prefix desktop run build:native
  npm --prefix desktop run typecheck
  npm --prefix desktop test
  npm --prefix desktop run lint
  ```
- **Cloudflare Worker & Admin UI**:
  ```bash
  npm --prefix worker ci
  npm --prefix worker test
  npm --prefix worker run build
  ```
- **iOS (`Core` & Xcode targets)**:
  ```bash
  swift test --package-path ios/Core
  ```
- **Marketing Video**:
  ```bash
  npm --prefix marketing-video ci
  npm --prefix marketing-video run lint
  ```

## Licensing & Attribution

By submitting a contribution to **LiVoice**, you agree that your contribution will be licensed under the **GNU General Public License v3.0 (`GPL-3.0`)** (see [LICENSE](LICENSE)). Please preserve existing copyright and attribution notices as described in [NOTICE](NOTICE).

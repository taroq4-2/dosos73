# Release validation — 1.1.2

Validated 2026-10-27. Application source: `e63a4a32e6c8959707e28dde5d8d76db9d6cda23`; subsequent release changes are documentation/screenshots only. Version 1.1.2, code 8. No claim of universal website/device support.

## Source and cleanup

The clean local 0f6b14d matched origin/main and origin/feat/neon-manager. Archives 0.1.0-dev and 0.2.0-preview were inspected and rejected as older implementations. Dependabot branches are unmerged proposals. The authorized workspace search found no other complete checkout; unrelated inaccessible directories were not treated as sources. Work proceeded on maintenance/verified-cleanup without resetting history.

62 obsolete tracked files removed: unused old branding/resources, superseded screenshots and historical/generated reports. Exact paths and reasons: [CLEANUP_MANIFEST.csv](CLEANUP_MANIFEST.csv). Previous reports remain available in tag v1.1.1. All Kotlin implementation, regression/diagnostic tests, Gradle infrastructure and applicable licenses retained. Android 10+ uses the adaptive vector launcher. [FILE_INVENTORY.csv](FILE_INVENTORY.csv) classifies every current tracked file.

## Results

| Check | Result |
|---|---|
| testDebugUnitTest | 39 tests, 0 failures |
| lintDebug / lintRelease | Passed |
| assembleDebug / assembleDebugAndroidTest | Passed |
| Signed assembleRelease for ARM64 and x86_64 | Passed; both signatures verified |
| CI API 29 / 33 / 35 / 36 | All four device jobs and build passed |
| Local API35 instrumentation | OK (28 tests); opt-in network/diagnostic cases are skipped in this suite |
| Opt-in direct download | Passed separately: W3C Sintel MP4 saved and Android MediaPlayer advanced playback |
| Exact signed x86_64 release | Installed as update; native extracted packages removed before installation to exercise fresh engine extraction |
| YouTube release acceptance | User-provided oexYI9IGKkM analyzed, 720p downloaded/merged/verified, saved 29.39 MiB, opened with rendered video in external player |
| Release crash log | No AndroidRuntime fatal entry during acceptance |
| Arabic/English navigation | Home, downloads, file library and settings captured from signed 1.1.2 |
| Maven advisories | Final resolved 85-coordinate graph returned no OSV IDs; native/Python dependencies excluded |
| Tracked-text secret pattern scan | No matches; not a comprehensive secret audit |

[CI run for tested source](https://github.com/taroq4-2/dosos73/actions/runs/40351801710).

Local commands: `gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease -PtargetAbi=x86_64`; `gradlew.bat assembleRelease -PtargetAbi=arm64-v8a`; `adb shell am instrument -w com.os73.dosos73.test/androidx.test.runner.AndroidJUnitRunner`; separate opt-in `LiveDownloadTest#directDownloadAndRealScreenshots` with `-e live true`. Signing uses the existing protected release key, never committed.

Initial local attempts were invalid: overlapping instrumentation sessions and an asleep emulator caused missing Compose hierarchy failures. After serializing execution, waking/unlocking the emulator and keeping its display on, the complete suite and separate live check passed. No assertion was weakened. CI ran independently on clean emulators.

## Exact APK identity

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| dosos73-1.1.2-arm64-v8a.apk | 59344182 | 56e4c90d7fb38940f272c06adab10c8970a0fa06732d1d074c019114cd2e5fd0 |
| dosos73-1.1.2-x86_64.apk | 62336181 | 44774472db70e5176d4030f8b0c805c2198ddea138edcc5db7a69fad983ea21d |

Signer SHA-256: `bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9`. ARM64 is approximately 47% smaller than the previous 111,978,356-byte universal APK. No first-paste library fetch and no permanent watchdog were added. Dependency security updates add about 0.48 MB compared with 1.1.1 ARM64.

## Evidence and limits

The five `screenshots/current-*.png` files are authentic signed 1.1.2/API35 captures, not mockups. `youtube-completed-1.1.1.png` and RELIABILITY_1.1.1.md are explicitly historical. Full-duration playback, physical Samsung S23 Ultra, ARM64 execution, broad OEM battery behavior, complete native license/vulnerability inventory and 16 KB certification were not tested here. Process-death recovery's 1.1.1 experiment is not relabeled as a 1.1.2 experiment. Durable journal regression tests pass; force-stop still requires reopening, retries start from zero, and publication/acknowledgement and orphan-document hardening remain documented in SECURITY.md.

No fixed media-size cap exists, but available storage, transfer timeouts and provider restrictions remain. This report records tested behavior, not a zero-defect or security certification.

# Remaining issues and release gates

Decision: **NO-GO for public production release**. The public default branch and stable release are separate from this validation branch.

| Gate | State | Closure evidence |
|---|---|---|
| Source-built runtime provenance | PASS in the recorded scope | Two independent [core runs](https://github.com/taroq4-2/dosos73/actions/runs/42029898208) reproduced the same raw launcher and core archive for all four ABIs. [QuickJS and Cryptodome](NATIVE_SOURCE_UPDATES_20261105.md) are now built from checked sources. Cross-host whole-ELF equality is not assumed. |
| Current revision’s device verification | Android 10–16 matrix and strict Android 16 suite passed | All eight x86-64 device jobs in the [current runtime matrix](https://github.com/taroq4-2/dosos73/actions/runs/42035105249) passed. The independent strict 16 KiB Android 16 runner reported `OK (185 tests)`. The interrupted Android 15 suite and remaining physical ARM/OEM scope are recorded in the [latest evidence](NATIVE_SOURCE_UPDATES_20261105.md). |
| Platform behavior and media availability | Live YouTube MP4 passed. Live MP3 remains open. | The exact signed release downloaded a verified 1920 × 818 MP4 with audio. Local MP3 conversion passed. The short-link live MP3 attempt encountered unavailable formats, and the full-link retry prompted for authentication or human verification. |
| Accessibility and large form factors | PARTIAL emulator verification | Navigation and selected states were exercised in Arabic at 200% font scale in phone portrait, phone landscape and tablet-sized windows. TalkBack bound on the emulator; speech, full TalkBack navigation, Switch Access and physical tablets/foldables remain open. |
| Toolchain dependency upgrades | BLOCKED / NOT VERIFIED | Complete the coordinated SDK, AGP, R8, Kotlin and Gradle migration on a separate branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| Distribution and signing | Signed APK install and launch verified on Android 15 and 16 | Current candidate is a 60,851,254-byte universal APK with the existing certificate, an APK-bound SBOM and corresponding-source supplement. The verified installations span 4 KiB and strict 16 KiB pages. Play delivery and physical OEM acceptance remain open. |
| Power and performance | Current APK size measured; hardware load/energy NOT VERIFIED | Current signed APK is 1,200,128 bytes smaller than the previous signed candidate. The [performance report](PERFORMANCE_REPORT.md) contains older launch/PSS measurements. Those measurements do not apply to the new artifact. |

The previous signed candidate and its historical results are documented in [QA_TEST_MATRIX.md](../../QA_TEST_MATRIX.md). The [compatibility matrix](COMPATIBILITY_MATRIX.md) distinguishes SDK coverage from device-specific compatibility.

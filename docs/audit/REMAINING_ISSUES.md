# Remaining issues and release gates

Decision: **controlled validation on RC.3**. Physical device acceptance remains open before public production publication.

| Gate | State | Closure evidence |
|---|---|---|
| Source-built runtime provenance | PASS in the recorded scope | Two independent [core runs](https://github.com/taroq4-2/dosos73/actions/runs/42029898208) reproduced the same raw launcher and core archive for all four ABIs. [QuickJS and Cryptodome](NATIVE_SOURCE_UPDATES_20261105.md) are now built from checked sources. Cross-host whole-ELF equality is not assumed. |
| Current revision’s device verification | Android 10–16 matrix and strict Android 16 suite passed | All eight x86-64 device jobs in the [RC.3 runtime matrix](https://github.com/taroq4-2/dosos73/actions/runs/42102988681) passed. The strict 16 KiB Android 16 runner reported `OK (186 tests)`. Physical ARM/OEM acceptance remains open. |
| Platform behavior and media availability | Live YouTube MP3 and MP4 passed for the test fixture | The [RC.3 report](MEDIA_RESPONSE_RECOVERY_20261105.md) records a fully decoded MP3 and a verified 1920 × 818 MP4 with audio from the exact signed release. Broader live provider coverage remains open. |
| Accessibility and large form factors | PARTIAL emulator verification | Navigation and selected states were exercised in Arabic at 200% font scale in phone portrait, phone landscape and tablet-sized windows. TalkBack bound on the emulator; speech, full TalkBack navigation, Switch Access and physical tablets/foldables remain open. |
| Toolchain dependency upgrades | BLOCKED / NOT VERIFIED | Complete the coordinated SDK, AGP, R8, Kotlin and Gradle migration on a separate branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| Distribution and signing | Signed RC.3 installed and opened on strict Android 16 | The 60,810,294-byte universal APK uses the existing certificate, an APK-bound SBOM and matching native sources. RC.2 installation evidence covers 4 KiB and strict 16 KiB pages. Play delivery and physical OEM acceptance remain open. |
| Power and performance | Current APK size measured; hardware load and energy remain open | RC.3 is 40,960 bytes smaller than RC.2. The [performance report](PERFORMANCE_REPORT.md) contains older launch and memory measurements. |

The previous signed candidate and its historical results are documented in [QA_TEST_MATRIX.md](../../QA_TEST_MATRIX.md). The [compatibility matrix](COMPATIBILITY_MATRIX.md) distinguishes SDK coverage from device-specific compatibility.

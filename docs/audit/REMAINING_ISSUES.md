# Remaining issues and release gates

Decision: **NO-GO for public production release**. The public default branch and stable release are separate from this validation branch.

| Gate | State | Closure evidence |
|---|---|---|
| Source-built core provenance | PASS in the recorded scope | [Producer 7989327](https://github.com/taroq4-2/dosos73/actions/runs/41730382903) passed four ABIs; source receipts, config, header, NDK payload, source/notices supplement and final selected bytes were checked. Inherited Cryptodome native/QuickJS Android build histories and a second identical native compiler build remain NOT VERIFIED. |
| Current revision’s device verification | PASS in recorded emulator scope | The candidate passed the 185-test runner on strict API35 16 KiB x86-64 (180 passes, five optional skips), focused 4 KiB checks and the [updated Python API29–36 matrix](https://github.com/taroq4-2/dosos73/actions/runs/41932324505). Physical ARM/OEM acceptance remains NOT VERIFIED. |
| Platform behavior and media availability | Latest YouTube attempt FAILED with authentication challenge | The latest diagnostic invocation returned YouTube sign-in/bot verification; W3 returned an HTTP403 challenge. Public GitHub video download passed. A requested audio variant was absent from that fixture's metadata and the optional audio test failed selection; local PCM-to-MP3 encoding passed. Earlier successful live YouTube trials remain historical. |
| Accessibility and large form factors | NOT VERIFIED | Run manual TalkBack, Switch Access, large text/RTL and landscape on real windows, including tablets/foldables. |
| Toolchain dependency upgrades | BLOCKED / NOT VERIFIED | Complete the coordinated SDK, AGP, R8, Kotlin and Gradle migration on a separate branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| Distribution and signing | Signed APK static and install/open PASS on API35 x86-64 with both 4/16 KiB pages | Current candidate is a 62,051,382-byte universal APK with the existing certificate, an APK-bound SBOM and corresponding-source supplement. Play delivery/account acceptance and physical OEM installation remain NOT VERIFIED. |
| Power and performance | Launch/PSS measured on emulator; hardware load/energy NOT VERIFIED | Ten signed-APK trials each showed median launch 4069.5→2930.5 ms and PSS 29078→30704.5 KiB. Device variation and the open hardware/energy scope remain in the [performance report](PERFORMANCE_REPORT.md). |

The previous signed candidate and its historical results are documented in [QA_TEST_MATRIX.md](../../QA_TEST_MATRIX.md). The [compatibility matrix](COMPATIBILITY_MATRIX.md) distinguishes SDK coverage from device-specific compatibility.

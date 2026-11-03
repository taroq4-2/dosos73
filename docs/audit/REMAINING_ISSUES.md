# Remaining issues and release gates

Decision: **NO-GO for public production release**. The public default branch and stable release are separate from this validation branch.

| Gate | State | Closure evidence |
|---|---|---|
| Producer provenance and reproducible four-ABI runtime | BLOCKED | The last run passed x86 and ARMv7. Confirm [the corrected four-ABI run](https://github.com/taroq4-2/dosos73/actions/runs/41715765777), compare the resulting payload against the packaged runtime, and verify native advisories, GPL obligations and source archives for the exact binaries. |
| Current revision’s device verification | Emulator matrix PASS; hardware NOT VERIFIED | [All API 29–36 x86-64 CI jobs passed](https://github.com/taroq4-2/dosos73/actions/runs/41712135377). Exercise physical ARM and actual 16 KiB page mode independently. |
| Platform behavior and media availability | Scoped live PASS; broader coverage NOT VERIFIED | Signed APK passed a direct HTTPS fixture and a YouTube fixture as MP4 and MP3. Test other authorized formats and providers, physical reconnect, Doze, Wi-Fi/mobile handover, denied permission, provider ejection and real disk exhaustion separately. |
| Accessibility and large form factors | NOT VERIFIED | Run manual TalkBack, Switch Access, large text/RTL and landscape on real windows, including tablets/foldables. |
| Toolchain dependency upgrades | BLOCKED / NOT VERIFIED | Complete the coordinated SDK, AGP, R8, Kotlin and Gradle migration on a separate branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| Distribution and signing | Signed APK static and update tests PASS on x86-64 API 35; full distribution NOT VERIFIED | Verify clean installation on physical devices and 16 KiB hardware. A new signed Play delivery artifact and account acceptance remain separate gates. |
| Power and performance | NOT VERIFIED beyond recorded fixtures | Measure on target hardware under load and retain all outliers. See [performance report](PERFORMANCE_REPORT.md). |

The previous signed candidate and its historical results are documented in [QA_TEST_MATRIX.md](../../QA_TEST_MATRIX.md). The [compatibility matrix](COMPATIBILITY_MATRIX.md) distinguishes SDK coverage from device-specific compatibility.

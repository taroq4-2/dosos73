# Remaining issues and release gates

Decision: **NO-GO for public production release**. The public default branch and stable release are separate from this validation branch.

| Gate | State | Closure evidence |
|---|---|---|
| Producer provenance and reproducible four-ABI runtime | BLOCKED | Isolated native build must pass on four ABIs, with a pinned producer and comparison against the packaged runtime. Review native advisories, GPL obligations and source archives for the exact binaries. |
| Current revision’s complete device verification | NOT VERIFIED | Link the exact CI revision and all API 29–36 results. Exercise physical ARM and actual 16 KiB page mode independently. |
| Platform behavior and media availability | NOT VERIFIED | Test authorized live formats, downloads, reconnect, 429 and optional providers. Test Doze, Wi-Fi/mobile handover, denied permission, provider ejection and real disk exhaustion separately. |
| Accessibility and large form factors | NOT VERIFIED | Run manual TalkBack, Switch Access, large text/RTL and landscape on real windows, including tablets/foldables. |
| Toolchain dependency upgrades | BLOCKED / NOT VERIFIED | Complete the coordinated SDK, AGP, R8, Kotlin and Gradle migration on a separate branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| Distribution and signing | NOT VERIFIED for a new release | Build a signed candidate tied to the remediation SHA. Verify identity, checksums, clean install, migration, ABI contents, minimum SDK and Play acceptance. |
| Power and performance | NOT VERIFIED beyond recorded fixtures | Measure on target hardware under load and retain all outliers. See [performance report](PERFORMANCE_REPORT.md). |

The previous signed candidate and its historical results are documented in [QA_TEST_MATRIX.md](../../QA_TEST_MATRIX.md). The [compatibility matrix](COMPATIBILITY_MATRIX.md) distinguishes SDK coverage from device-specific compatibility.

# Test evidence

Reference SHA `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. This file distinguishes reference, remediation and external evidence.

| Run | Evidence and verdict |
|---|---|
| Reference Gradle build | PASS: JVM tests, debug and release lint, APKs, Android test APK, AAB, runtime inventory and no-KAPT check. |
| First remediation build | FAIL: `ReviewLayoutTest.kt` was missing the Compose `testTag` import. The import was corrected before the integrated rerun. |
| Python tooling | PASS: 29 tests for packaging, signing, advisories and measurement parsing. |
| Advisory queries | PASS, scoped: 87 Maven/Python queries with no matches. Four build inputs queried; one reviewed KAPT finding with expiry and guard conditions. |
| Source secret scan | PASS, scoped: 0 findings in scanned source. Historical scan of 117 commits flagged three entries at `af7fcb8e6cc99e93ee8d68ba3ee701484dde95d4`, lines 15–17 of `docs/audit/apk-signature.txt`. All three are printed public-key digests. |
| Prior Android API matrix | Historical PASS on the earlier source SHA. See [compatibility matrix](COMPATIBILITY_MATRIX.md). |
| Isolated native rebuild | FAIL in all four jobs on `db9bf1998c1766a51ed754b65b0b39bd12880e61`. [GitHub Actions](https://github.com/taroq4-2/dosos73/actions/runs/41641954791) |
| Final remediation rerun | In progress when this checkpoint was written. Record final SHA and results after completion. |

Reference and remediation build commands use the Gradle wrapper. Device installation and instrumentation commands are in the [compatibility matrix](COMPATIBILITY_MATRIX.md). Hardware and environmental limitations are listed under [remaining issues](REMAINING_ISSUES.md).

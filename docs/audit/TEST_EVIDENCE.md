# Test evidence

The [5 November source runtime verification](NATIVE_SOURCE_UPDATES_20261105.md)
describes the current APK, the passing Android 10–16 matrix and the full
strict 16 KiB Android 16 run. It also records a byte-exact public video
download and local MP3 conversion on the exact signed release.

## Source-built runtime acceptance on 4 November 2026

App code/resources remain at `96337091f968ee36714e1adcb9b215d43191cd77`.
Packaging and inventory tools are at `5838095749af15e1e8553545b02cddca885e6c23`.
The four-ABI native producer `79893270b85278b71d02a7aae0f4143921c2f6ac`
passed [run 41730382903](https://github.com/taroq4-2/dosos73/actions/runs/41730382903).
The following results describe the new signed candidate, separate from the earlier audit below.

| Check | Executed result |
|---|---|
| Signed universal APK | 62,051,382 bytes, SHA-256 `195bb74228ebeb59519513dd49790075d7fd12b9906c4ac470a8c013b53c741d`. Existing signing certificate retained. |
| Producer and selected payload | All four artifact digests, member digests, ELF architecture, safe symlinks and static dependency closure independently checked. Each source receipt verifies 27 source/tool/config records, host/target Python input, target config/header and the exact NDK libc++ member. |
| Packaging consistency | A second local assembly with the checked tools produced the identical unsigned APK, SHA-256 `c145ac534a3b8f67ea4b2d9deebdd662022734568ca1ceeb5ecffe358f6c1c9e`. Dynamic symbols, loader identity and load segments were checked before and after symbol removal. This demonstrates local packaging consistency. |
| Static alignment | 608 ELF files checked, zero 64-bit alignment failures. |
| Strict 16 KiB emulator | Android API 35 x86-64, `getconf PAGE_SIZE=16384`. Both linker and package-manager compatibility modes disabled. Runner reported `OK (185 tests)`: 180 passed and five optional live fixtures skipped. The source-built debug test package was followed by install/open of the exact signed release APK. |
| Standard 4 KiB emulator | API 35 x86-64. Five focused engine/shared-runtime/MP3 tests passed. Exact signed release APK installed and opened. |
| Loaded component versions | Confirmed under the app UID on both emulators: CPython 3.12.16, OpenSSL 3.5.9, Expat 2.9.0, SQLite 3.54.1 and XZ 5.8.4. LZMA round trip, SQLite FTS5, inherited AES round trip, GDBM import and the selected CA store passed. |
| Packaging tools | 44 Python tests passed, including invalid source receipts, native file changes, missing ABIs, ZIP path rejection and changed inherited app/vendor bytes. |
| Linux packaging follow-up, 5 November | Initial source-built matrix 41844483616 failed because resolving `llvm-readelf` to `llvm-readobj` changed LLVM's output mode. Alias-preserving invocation, explicit GNU output and direct binary program-header comparison were added at 24bfa90. All 48 tooling tests passed locally, including the symlink and binary-header regressions. Reassembly after the fix produced the same unsigned APK hash. |
| SBOM | 704 CycloneDX components bound to the signed APK hash. Selected inherited CA and producer-build CA are recorded separately. |
| Supplemental sources | 189,067,594-byte source/notices ZIP, SHA-256 `18d393c195ff4de9bbed50f329f1b26906865df5771b68b9afc47bd67a129a84`. 264 manifest entries checked, including effective recipes, receipts, source inputs, inherited notices and matching packaging tools. |
| Latest live YouTube attempt | FAILED / external authentication challenge. A diagnostic invocation returned `EXTRACT:AUTHENTICATION_REQUIRED` with YouTube's sign-in/bot-verification message. UI trials first reported unavailable formats. No cookies, login or challenge workaround was used. Earlier successful fixtures below remain historical results. |
| W3 live fixture | FAILED / HTTP 403 Cloudflare challenge. No impersonation workaround was used. |
| Public GitHub native video fixture | PASS: the existing optional `LiveDownloadTest.mediaExtractorAndNativeDownload` downloaded and validated the repository's default public MP4. A second invocation requesting audio failed because that fixture's metadata had no matching audio choice. This is retained as a failed optional fixture selection. The local MP3 encoding test passed. |
| New runtime API matrix | PASS: [run 41932324505](https://github.com/taroq4-2/dosos73/actions/runs/41932324505) at 24bfa90 passed packaging and every API 29–36 x86-64 device job. It uses one same-run signed debug test pair with the pinned rebuilt Python core; its upstream FFmpeg test input differs from the compact release FFmpeg checked locally. |
| Regular Android verification | PASS: [run 41932324600](https://github.com/taroq4-2/dosos73/actions/runs/41932324600) at 24bfa90 passed the regular build and eight API jobs. |
| Comparative emulator measurement | Ten process-cold launches per signed APK on the same API35 x86-64 test device, two-second settling, app data retained. Previous/current median TotalTime: 4069.5/2930.5 ms. Median TOTAL PSS: 29078/30704.5 KiB. All samples and outliers retained. See [performance report](PERFORMANCE_REPORT.md). |

Remaining acceptance and provenance limits are in [REMAINING_ISSUES.md](REMAINING_ISSUES.md).

Reference SHA `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. This file distinguishes reference, remediation and external evidence.

Remediation source: `96337091f968ee36714e1adcb9b215d43191cd77`. [Android verification run 41712135377](https://github.com/taroq4-2/dosos73/actions/runs/41712135377) passed the build job and all eight x86-64 emulator jobs.

| Run | Evidence and verdict |
|---|---|
| Reference Gradle build | PASS: JVM tests, debug and release lint, APKs, Android test APK, AAB, runtime inventory and no-KAPT check. |
| First remediation build | FAIL: `ReviewLayoutTest.kt` was missing the Compose `testTag` import. The import was corrected before the integrated rerun. |
| Remediation JVM suite | PASS: 121 tests, zero failed or skipped. Includes the binary response, 429 fallback, resume and prior network/queue fixtures. |
| Python tooling | PASS: 29 tests for packaging, signing, advisories and measurement parsing. |
| Advisory queries | PASS, scoped: 87 Maven/Python queries with no matches. Four build inputs queried; one reviewed KAPT finding with expiry and guard conditions. |
| Source and commit secret scans | PASS, scoped: zero findings in scanned source and in the `1e46c56..9633709` commit range. Historical scan of 117 commits flagged three entries at `af7fcb8e6cc99e93ee8d68ba3ee701484dde95d4`, lines 15–17 of `docs/audit/apk-signature.txt`. All three are printed public-key digests. |
| Prior Android API matrix | Historical PASS on the earlier source SHA. See [compatibility matrix](COMPATIBILITY_MATRIX.md). |
| Remediation Android API matrix | PASS: build and API 29, 30, 31, 32, 33, 34, 35, 36 on GitHub Actions. The API 35 job log reported `OK (185 tests)`. Optional live fixtures were outside the default CI invocation. |
| Isolated native rebuild | FAIL in all four jobs on `db9bf1998c1766a51ed754b65b0b39bd12880e61`. [GitHub Actions](https://github.com/taroq4-2/dosos73/actions/runs/41641954791) |
| Isolated native follow-up | PARTIAL: `da14c10a2a6464e06f7ace92a43b25e19e4508b4` passed x86 and ARMv7; ARM64 and x86-64 compiled and then failed collection when a Python relocatable development object was passed to the 16 KiB load-segment gate. All four passed input preparation and built the matching host CPython 3.12.16 and Android OpenSSL. [GitHub Actions](https://github.com/taroq4-2/dosos73/actions/runs/41712501784) |
| Native collection regression | PASS, scoped on isolated SHA `490c5ba3be12d770edc8d3361c7d0453ed2f1534`: the baseline failed on unlinked `python.o`; the fixed collector excluded the CPython static development directory, retained the inherited Android `_ssl` binary, and rejected its deliberately mutated 4 KiB load segment. [New four-ABI run](https://github.com/taroq4-2/dosos73/actions/runs/41715765777) pending at evidence capture. |
| Integrated lint and build | PASS: `testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease exportRuntimeInventory verifyNoKapt` completed successfully in 11m 2s, 143 tasks (43 executed). |
| Lint reports | Debug: zero errors/fatals and 77 warnings. Release: zero errors/fatals and 78 warnings. |
| Dependency isolation negative control | PASS: a temporary Gradle init script injected `mockwebserver:4.12.0` into `implementation`. The offline task failed with the expected `MockWebServer must be present only on the test runtime, not releaseRuntimeClasspath` diagnostic. No project files changed for the injection. |
| Focused local instrumented suite | PASS: `OK (27 tests)` on API 35 x86-64, covering `ReviewLayoutTest`, `UiFlowTest`, `BatchHandoffTest` and `RuntimeInventoryTest` with the compact debug app and matched instrumentation signatures. |
| Signed universal APK static checks | PASS: existing production certificate, APK v3, zip alignment, Android API 29 minimum and four ABIs. 608 ELF inputs scanned with zero 64-bit alignment failures. This is static evidence on the artifact; a real 16 KiB kernel remains a separate gate. |
| Signed universal APK installed acceptance | PASS: exact signed APK installed as an update and resumed on API 35 x86-64, 4096-byte pages. Six existing MediaStore files retained the same byte counts and SHA-256. APK SHA-256 `8a70dc46ab8f17291587613ae35768854b436730ff3eda9412b08a26ef491983`. |
| Signed APK live direct download | PASS on one fixture: share the [Sintel trailer](https://durian.blender.org/sharing/) HTTPS MP4, analyze, explicitly accept the duplicate confirmation, download and publish a new 4,372,373-byte MediaStore entry. SHA-256 `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254` matched the prior saved fixture. A separate host fetch reset its connection. Sintel attribution: Blender Foundation, Durian Open Movie Project. |
| Signed APK local MP3 conversion | PASS on the downloaded sample: UI converted the MP4 to MP3 V0. MediaStore reported `audio/mpeg`, the output starts with a valid MPEG Layer III frame after its ID3 tag, and 1,456,964 bytes matched the prior conversion SHA-256 `ed0d40dd6dee6a4a8ac81a057a9ea92e527035e95f65201a7b919ae7b51d3141`. The input MP4 hash remained identical. |
| Signed APK live YouTube video | PASS on the Blender Foundation [Sintel trailer](https://www.youtube.com/watch?v=HOfdboHvshg) on API 35 x86-64. The signed app analyzed the link and saved a 12,061,707-byte MP4. Parsing the MP4 verified video and audio tracks and 1920×818 dimensions. SHA-256 `6e8298e7010c79a356fce45d2dbdb9f477f27b64ccdc093110ee1256581cf0fc`. |
| Signed APK live YouTube audio | PASS on the same trailer via its [short URL](https://youtu.be/HOfdboHvshg). The signed UI selected MP3 V0, saved a 1,483,268-byte `audio/mpeg` MediaStore entry, and the file contained an MPEG Layer III frame after its ID3 tag. SHA-256 `cb98f94d7f53f28d9c1fba252114027d6403adaa5f73544362f6f7dd75c34306`. |
| SBOM and AAB validation | PASS, scoped: CycloneDX inventory with 699 components from the signed compact APK and resolved inputs. Bundletool validated the Gradle AAB identity and mapping. The Gradle AAB is an unsigned verification input. |

Reference and remediation build commands use the Gradle wrapper. Device installation and instrumentation commands are in the [compatibility matrix](COMPATIBILITY_MATRIX.md). Hardware and environmental limitations are listed under [remaining issues](REMAINING_ISSUES.md).

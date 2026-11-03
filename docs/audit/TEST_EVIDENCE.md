# Test evidence

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

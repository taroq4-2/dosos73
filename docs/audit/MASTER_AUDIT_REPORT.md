# DOSOS73 engineering audit

Baseline readiness SHA `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. Public default SHA `2efeb7f1271516b13c3fc2ab767fe81753df81ac`, published `v1.2.0` at `852f269be743e394159cbbba4e465a2e80244801`. Decision: **NO-GO** for production publication.

The latest signed validation APK is **62,051,382 bytes** at SHA-256
`195bb74228ebeb59519513dd49790075d7fd12b9906c4ac470a8c013b53c741d`.
Its application code/resources remain at `96337091f968ee36714e1adcb9b215d43191cd77`;
the native producer is `79893270b85278b71d02a7aae0f4143921c2f6ac`.
Packaging tools were added at `5838095749af15e1e8553545b02cddca885e6c23`
and their Linux LLVM alias handling was corrected at
`24bfa90f19fa777bd6a21fee46716b93cd6c5ad6`.
The updated core passed local strict 16 KiB and standard 4 KiB checks, with a
source/notices supplement and a 704-component SBOM. The corrected rebuilt-core
workflow passed packaging and all API29–36 x86-64 device jobs in
[run 41932324505](https://github.com/taroq4-2/dosos73/actions/runs/41932324505).
See [test evidence](TEST_EVIDENCE.md) for exact scopes and [performance](PERFORMANCE_REPORT.md)
for the package-size comparison.

The app, runtime and presentation scopes were investigated independently by virtual Codex workstreams and integrated on the readiness branch. The audit keeps observed failures, deterministic regressions, historical CI and unresolved checks separate.

| Review area | Record |
|---|---|
| Security architecture, network boundaries and storage | [Security audit](SECURITY_AUDIT.md), [download engine](DOWNLOAD_ENGINE_REVIEW.md) |
| Architecture, lifecycle, UX, RTL and accessibility | [Architecture review](ARCHITECTURE_REVIEW.md), [compatibility](COMPATIBILITY_MATRIX.md) |
| Runtime, Android ABI, compatibility and signing | [Compatibility](COMPATIBILITY_MATRIX.md), [native security](../../SECURITY_REVIEW.md) |
| Dependencies, advisories, CI/CD and release quality | [Dependency review](DEPENDENCY_REVIEW.md), [test evidence](TEST_EVIDENCE.md) |
| Throughput, memory and APK size | [Performance report](PERFORMANCE_REPORT.md) |
| Confirmed corrections and release gates | [Remediation log](REMEDIATION_LOG.md), [remaining issues](REMAINING_ISSUES.md) |

Confirmed remediation covers a false-success response that could alter staged
output, an immediate fallback request after origin throttling, inaccessible
pending-share recovery, a dependency graph mismatch, blocked native test output,
and a source-built core with updated native security dependencies. The current
candidate has been selected for controlled testing. Live YouTube verification
on the latest candidate encountered an authentication challenge. Remaining
release gates are tracked in [REMAINING_ISSUES.md](REMAINING_ISSUES.md).

## Engineering appendix

| Review function | Evidence and next gate |
|---|---|
| Technical architecture | `DownloadQueue` owns durable leases and `MainActivity` owns intent delivery. Review scope and implementation records are linked above. |
| Kotlin engineering | The view model keeps staged share state while editing, and the Compose dialog waits for draft recovery. See [UI-01](REMEDIATION_LOG.md). |
| Software architecture and code quality | Baseline source ownership and I/O boundaries were examined. The focused UI extraction keeps service and journal ownership intact. |
| Application security | See [security audit](SECURITY_AUDIT.md) for SSRF, redirect, DNS, process and publication controls and their limits. |
| Download engineering | See [DL-01–03](DOWNLOAD_ENGINE_REVIEW.md) for response validation, fallback, staging and resume evidence. |
| YouTube and external sources | The signed APK analyzed and downloaded one live authorized YouTube trailer as MP4 with verified video and audio tracks, and as MP3 using its short URL. Wider provider, account and format coverage remains open. |
| Networking | Validated address policy and Android network binding are covered in the [security audit](SECURITY_AUDIT.md). Hostile real DNS, DNS64 and radio handover are separate checks. |
| Native libraries and FFmpeg | The four-ABI rebuilt core and source receipts passed. The selected source/notices supplement and actual byte correspondence were checked. Inherited producer scope and independent native compiler repeatability remain open. |
| Storage and integrity | [Download review](DOWNLOAD_ENGINE_REVIEW.md) records journal, hashing, staging, MediaStore and SAF boundaries. OEM and real removable-storage failure testing remains open. |
| Performance | The [performance report](PERFORMANCE_REPORT.md) separates prior APK and PSS measurements from the current controlled transfer test. |
| Android compatibility | [Compatibility matrix](COMPATIBILITY_MATRIX.md) covers API 29–36 and four ABI configurations, with device-specific gaps. |
| UX and accessibility | [Architecture review](ARCHITECTURE_REVIEW.md) records share recovery and RTL/touch-target evidence. Manual assistive-technology checks are separate. |
| QA | [Test evidence](TEST_EVIDENCE.md) distinguishes executed checks, historical results and pending runs. |
| CI and release engineering | [Dependency review](DEPENDENCY_REVIEW.md) records action pins, failing PR and coordination requirements. |
| Licensing and supply chain | [Native security review](../../SECURITY_REVIEW.md) records existing provenance and redistribution limitations. See advisory and secret scope in [test evidence](TEST_EVIDENCE.md). |

The implementation touches direct transfer and representation validation, pending-share presentation and state, localized strings, verification workflows and their regressions. Inspect the exact changed paths with `git diff --name-status 1e46c56...HEAD` on the readiness branch. Reproduction commands are linked in the scoped reports. The release remains blocked on the evidence in [remaining issues](REMAINING_ISSUES.md).

## Decision gates

| Gate | State |
|---|---|
| Build, JVM tests and lint | PASS on the remediation revision |
| CI | PASS for both regular verification and the rebuilt Python runtime matrix on API29–36 at 24bfa90 |
| Dependency isolation | PASS for the resolved OkHttp fixture graph, including the intentional failure control. Coordinated toolchain upgrade remains BLOCKED. |
| Security | Application regressions, selected native core updates and source receipts passed in scope. Inherited Cryptodome native/QuickJS producer history and wider device/provider acceptance remain open. |
| Download and integrity | PASS for automated range, recovery and verification fixtures, one signed direct HTTPS download, local MP3 conversion, and MP4/MP3 from one signed YouTube fixture. Broader live provider and physical interruption testing remains NOT VERIFIED. |
| Android compatibility | PASS on the rebuilt Python API29–36 x86-64 matrix and local strict 16 KiB emulator. Physical ARM/OEM acceptance remains open. |
| Performance | Package size, controlled transfers and signed-APK launch/PSS comparison measured. Memory median increased about 1.59 MiB in the latest emulator trial. Physical load and energy acceptance remain open. |
| Accessibility | Automated RTL, touch target and share flow checks passed. Manual assistive technology and form-factor checks NOT VERIFIED. |
| Documentation | Results distinguish source, artifact, scope and unresolved gates. |
| Release | Signed validation artifact passed static checks and an update/install/open on API 35 x86-64 with six unchanged retained media hashes. Independent native reproducibility and Play distribution remain BLOCKED / NOT VERIFIED. |

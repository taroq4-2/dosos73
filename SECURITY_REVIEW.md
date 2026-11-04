# Release-readiness security review

**4 November source-built candidate update.** The signed universal candidate
SHA-256 `195bb74228ebeb59519513dd49790075d7fd12b9906c4ac470a8c013b53c741d`
uses the verified four-ABI producer `79893270b85278b71d02a7aae0f4143921c2f6ac`,
with CPython 3.12.16, OpenSSL 3.5.9, Expat 2.9.0, XZ 5.8.4 and SQLite 3.54.1.
It passed standard and strict 16 KiB emulator checks. A matching source/notices
supplement and current SBOM were generated. The old-core version findings and
build failures below describe the earlier candidate. Current evidence and
remaining limits are in [the audit](docs/audit/TEST_EVIDENCE.md) and
[open gates](docs/audit/REMAINING_ISSUES.md).

**Source rebuild follow-up (3 November):** The separate
[`runtime/security-20261102`](https://github.com/taroq4-2/dosos73/tree/runtime/security-20261102)
branch pins CPython 3.12.16, OpenSSL 3.5.9, Expat 2.9.0, the Android NDK,
the builder image, and the source recipes. The build-order error from an empty
optional Tk dependency was corrected. An unrelated `foot` terminal description
archive changed checksum; the experiment omits that auxiliary terminfo input
and retains checksum enforcement and the ncurses library. See the
[four-ABI build trial](https://github.com/taroq4-2/dosos73/actions/runs/41637410970).
ARM64 progressed to fetching the pinned Readline patches, but the mirror reset
the connection. The next trial uses the GNU upstream host after confirming
the exact failed patch has the expected SHA-256; verification remains enabled.
The signed rc.2 artifacts retain the previously tested runtime byte for byte;
the experimental core must still build, pass module/ELF and application tests,
and establish matching source/notice evidence before it can replace it.

Reviewed 2 November 2026 on `release/production-readiness`, based on `bcfcdbdf46ba0f9e18dcfad50fa8fb8220312b9e` plus the changes identified in the release report. This is a source/component review by virtual specialist functions, not a penetration-test certificate. Final build identity and executed device-test counts belong in `QA_TEST_MATRIX.md` and `RELEASE_READINESS_REPORT.md`.

## Decision

**A05: retained with an explicit, tested application-layer policy. A06: not closed. A15: intentional restriction retained. A16: owner/Play review pending.** A clean scoped OSV lookup does not clear the inherited native runtime for production. Current native security fixes and the missing exact CPython producer lock must be resolved or formally dispositioned with reproducible evidence before claiming a production GO.

## Threat model and controls reviewed

| Boundary | Untrusted input / threat | Current control and reviewed locations | Limit |
|---|---|---|---|
| URL and network | Private/literal addresses, DNS rebinding, redirects, TLS downgrade | `Safety.url/publicAddress/resolve`, `DirectDownloader.request/secureClient`, `NetworkSettings.client` validate each target; public-only DNS and checked socket addresses; redirects are manual and reject HTTPS downgrade | OEM/network integration is not proved by mocked sockets |
| Native transport | Extractor-selected destinations; proxy misuse | `PublicProxy` binds 127.0.0.1, uses random per-instance authentication, permits CONNECT:443 to checked public IPs, limits sockets/threads and header/origin deadlines; `MediaEngine.request` retains TLS verification and uses fixed structured options | TLS is performed by the bundled native runtime; see native advisories below |
| Native execution | Shell/path injection, orphan helpers, uncontrolled downloads of code | `NativeProcessSession`, `Safety.fileName`, fixed task staging, bundled pinned extractor; no user-built shell command, login/cookie import, DRM bypass or remote executable-component fetch | Does not sandbox every parser/codec bug in a subprocess |
| Private state | Corrupt/downgraded journals, unbounded allocations, replay | `PrivateJournal` AES-GCM, migration markers, bounded reads/writes; owned durable queue/checkpoint/publication records | Compromised application data and whole-device rollback remain outside this guarantee |
| Output publication | Provider failures and unrelated rename-return URIs | `PublicationReceipt.publish` now rejects foreign authority/tree/noncanonical content-URI returns while preserving the recovery plan; cross-reviewed with storage functions 18–20 | A fully malicious provider in the granted tree can misrepresent its own data; this is not provider certification |
| Privacy | Background clipboard reads, leaked source URLs, excessive permissions | Foreground, unlocked, consented one-shot clipboard route; local encrypted metadata; bounded redacted diagnostics; no analytics/account SDK found; manifest has no all-files, accessibility-service, sensor or cookie permission | Source servers necessarily receive requested paths, IP/network information and request headers |

### A05: why the manifest flag remains

`usesCleartextTraffic=true` is not treated as an exploit by itself. The app supports arbitrary public HTTP **only through an explicit setting**, default false, and an authenticated loopback proxy. A static Network Security Configuration cannot express a runtime opt-in for arbitrary user-selected domains. Restricting the manifest to localhost would silently remove that existing HTTP function. Therefore the dynamic policy stays in `NetworkSettings.allowCleartext` and the direct-client interceptors; native media remains HTTPS-only and the CONNECT proxy only accepts port 443.

Regression locations: `SafetyTest`, `LiteralNetworkBoundaryTest`, `PublicProxyTest`, `ProxyLifecycleTest`, `ProxyTransportTest`, `TlsVersionTest`, `DirectDownloaderTest`, and `NetworkRecoveryTest`. They must be executed against the new build; their existence alone is not a pass. The root release QA record supplies the actual results. No trust-all verifier or SSL-check disabling was introduced.

### A15: nonstandard ports

Retain the existing 80/443 URL policy and native CONNECT:443 restriction. There is no demonstrated product requirement for 8443 and no consent/threat-model implementation to justify expanding reachable services during release hardening. This is a documented compatibility limitation, not a claimed vulnerability fix.

## Supply-chain checks actually executed

Commands run from the repository root:

```text
python tools/test_dependency_advisories.py
python tools/check_dependency_advisories.py --inventory app/build/reports/audit/runtime-components.json --output docs/audit/readiness-osv.json
python tools/test_build_advisories.py
python tools/check_build_advisories.py --output docs/audit/readiness-build-advisories.json
./gradlew verifyNoKapt --console=plain
```

Results: **7 runtime-advisory gate regression tests passed**; **87 distinct Maven/PyPI coordinate queries, zero OSV matches**. The exact inventory and runtime-scope hashes, request identities, UTC time and result are in `docs/audit/readiness-osv.json`. This comprises 85 Maven coordinates and two explicitly identified Python distributions (yt-dlp 2026.10.21 and Mutagen 1.47.0). This runtime lookup does not scan arbitrary ELF bytes, all Python modules, unknown backports or every vulnerability database. New findings, API failures, incomplete batches and pagination fail the tool rather than passing silently.

### Build-host findings and fixes

The separate build-tool scanner reads the actual wrapper and root plugin pins instead of assuming that `releaseRuntimeClasspath` covers them. **6 build-pin/source-guard regression tests passed**. Its four exact Maven-coordinate queries cover Gradle core, AGP, Kotlin Gradle plugin and Compose compiler plugin; they do not cover all transitive build-host libraries.

The initial API36-compatible Gradle 8.11.1 was affected by October 2026 upstream high-severity advisories about repository error handling and unauthenticated daemon deserialization. The wrapper now pins **Gradle 8.14.6**, the fixed 8.x release, with the official distribution SHA-256 `7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24`. Root must run the complete build with this wrapper. Primary records: [repository/TLS handling](https://github.com/gradle/gradle/security/advisories/GHSA-j5m7-59rp-24f5), [client/daemon deserialization](https://github.com/gradle/gradle/security/advisories/GHSA-xwqc-3h47-hg64), [worker/daemon deserialization](https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8). This fixes build-tool exposure; it does not change APK runtime dependencies.

The fresh build-tool lookup has **one reviewed finding, zero unreviewed findings**: Kotlin 2.0.21 CVE-2026-53914 / GHSA-r937-wjx7-w2jp. The [upstream fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6) changes **KAPT incremental-cache** deserialization. This application does not apply KAPT or annotation processors. The exact package/version is therefore dispositioned `not_affected` for this project path, with an owner, evidence and expiry of 2 December 2026. The source scanner rejects KAPT declarations/catalog entries; `verifyNoKapt` checks resolved plugin/task/configuration names. Enabling KAPT invalidates this disposition and requires review or a patched Kotlin upgrade. This does not assert that the Kotlin library itself is patched, and is not an accepted-risk exception for native code. See `docs/audit/advisory-dispositions.json` and `docs/audit/readiness-build-advisories.json`.

SBOM generation remains artifact-bound using `tools/generate_sbom.py`, the refreshed Gradle inventory, runtime proof and native correspondence evidence. The release report must identify the regenerated SBOM and final APK hash; an older SBOM count is not substituted for that artifact.

## Native findings and release blockers

These are **version-based security hypotheses/compliance gaps**, not reproduced application exploits. The inherited strings identify CPython 3.12.11, OpenSSL 3.5.2 and Expat 2.7.3. No producer-supplied backport attestation has been established. Relevant upstream material was fetched during this review and its retrieval digests retained in `work/readiness-security/sources.json` outside distribution.

| Component / finding | Assessment of this product path | Required disposition |
|---|---|---|
| OpenSSL CVE-2026-84782 (High; DTLS), CVE-2026-45447 (High; PKCS7), CVE-2025-15467 (High; CMS) | Upstream affected ranges include 3.5.2. Current download routes use HTTPS over TCP, not DTLS, PKCS7 message verification or CMS decryption. No product-path exploit was reproduced; no claim that the packaged library is patched | Rebuild/update the native runtime, or independently prove the relevant interfaces unreachable and record an owner-approved, expiring disposition. Current record does not grant that approval |
| OpenSSL CVE-2026-35189 (Low upstream) | Certificate-processing memory pressure is plausibly relevant to native HTTPS; the public-IP proxy does not fix parser allocation inside TLS | Update to a fixed supported release and test TLS/engine loading across the four ABIs |
| CPython security fixes after 3.12.11 | 3.12.16 includes a compromised-intermediate TLS denylist (CVE-2026-115557) and cookie policy fixes. A bundled trust store may differ from Android's updated trust store. No imported browser cookies are used; redirect/cookie behavior still deserves regression coverage | Produce a pinned patched 3.12 runtime with extension/standard-library correspondence; rerun TLS, extraction, MP3, offline conversion and process lifecycle tests |
| Expat fixes after 2.7.3 | Official later changes include malformed-XML and denial-of-service fixes. XML can be parsed by extractors. Exploitability and exact build flags were not dynamically established | Update with ABI/SONAME/exports validation, current source notices and actual parser tests; do not mark complete based only on a Maven lookup |
| Exact native producer lock | Existing wrapper/AAR binary hashes and source archives prove origin at several layers, but historical `BUILD_PYTHON.md` does not reproduce shipped Python 3.12.11 | Obtain/rebuild from an exact recipe/toolchain lock; preserve all required modules and source/license obligations |

Primary sources checked: [OpenSSL 3.5 advisories](https://openssl-library.org/news/vulnerabilities-3.5/), [CPython 3.12 changelog](https://docs.python.org/3.12/whatsnew/changelog.html), [Expat 2.8.5 changes](https://github.com/libexpat/libexpat/blob/R_2_8_5/expat/Changes). Full native-advisory coverage is **not** claimed; these findings keep A06 open.

An update path was investigated rather than guessed: the official [Termux repository](https://packages.termux.dev/apt/termux-main/) offers OpenSSL 3.6.5 packages for all four ABIs and a newer Expat. Its available Python 3.14.8 package changes Python ABI and dependencies (including zstd), so it is not a safe replacement for 3.12.11 extensions. Neither package presence nor SONAME similarity proves safe loading in the application's relocated prefix. No untested binaries were substituted. Next acceptance work includes digest/provenance, exports/DT_NEEDED closure, prefix/provider configuration, 16KB checks, source/notice updates and all-ABI runtime tests.

## Licensing and redistribution

Preserve `LICENSE`, `THIRD_PARTY_NOTICES.md`, `docs/launch/NATIVE_SOURCES.md`, the source-only release assets and FFmpeg/LAME build configurations. The inherited wrapper, Readline/History, GDBM and Mutagen carry copyleft obligations; FFmpeg/LAME obligations depend on the actual built configuration, not on a generic FFmpeg label. Current compact FFmpeg configuration evidence requires GPL=0, NONFREE=0 and LIBMP3LAME=1. This does not erase the separate GPL obligations elsewhere in the application. Exact source correspondence, written notices and continued source availability require maintainer/legal review before Play distribution.

## Virtual review functions and cross-review

These functions were performed by one AI security workstream, not 10 independent humans or test runs. The shared release matrix owns final acceptance.

| Function | Concrete contribution / reviewed evidence | Disposition and cross-review |
|---|---|---|
| 06 App security | Threat boundaries above, manifest permissions, structured native execution | Preserve controls; storage-return fix cross-reviewed with 18–20 |
| 07 URL/SSRF | `Safety`, public-only DNS and per-redirect validation | Retain ports/address restrictions; root executes regression matrix |
| 08 TLS/DNS/redirect | `DirectDownloader`, `PublicProxy`, `MediaEngine` and native advisory scope | TLS version gap stays open; no certificate bypass |
| 09 Privacy | `PRIVACY.md`, encrypted journals, one-shot clipboard, redacted diagnostics | Owner must reconcile Play Data safety with actual remote requests |
| 10 Supply chain | Exact OSV lookup, fail-closed scanner tests, source correspondence | Scoped lookup passed; native upgrade/producer-lock blocked |
| 36 CI/CD | Provided advisory command and failure semantics to root workflow owner | Root owns actual CI execution and pinned build tooling |
| 37 Signing/provenance | Reviewed external signing boundary and inherited binary/source records | Root verifies final certificates; no credential changes by this workstream |
| 38 Open source | Readline/GDBM/Mutagen/FFmpeg source notices and CPython limitations | Source availability must accompany the final exact artifact |
| 39 Play policy | Current API, Data safety, FGS, account-testing and content-rights requirements | Submission checklist prepared; owner/Google approval pending |
| 40 Independent acceptance function | Challenged broad “clean scan/100% safe” claims | NO-GO for production until native and external gates are closed; not an independent human approval |

## Unexecuted security work

Physical OEM networking/Doze, malicious-certificate reproduction, comprehensive native fuzzing, hardware key protection/rollback assessment, full cloud-provider trust evaluation, independent penetration testing and Play review are **NOT RUN** by this workstream. Do not infer those results from emulator/JVM success or from the seven scanner-unit tests.

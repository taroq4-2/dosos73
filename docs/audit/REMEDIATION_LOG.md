# Remediation log

Baseline `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. App and CI changes and their regression fixtures are in `96337091f968ee36714e1adcb9b215d43191cd77`. See [TEST_EVIDENCE.md](TEST_EVIDENCE.md) for results.

## Source-built core follow-up

Producer `79893270b85278b71d02a7aae0f4143921c2f6ac` passed all four ABIs.
The candidate and corresponding-source supplement are bound by SHA-256 in
[TEST_EVIDENCE.md](TEST_EVIDENCE.md).

| ID | Confirmed defect and correction |
|---|---|
| NATIVE-03 | A wrong build-cache path and a masked pipeline failure produced empty source-input hash files. SHA 1f0faa9 adds fail-closed receipt checks for actual inputs, target config/header and NDK libc++ correspondence. |
| NATIVE-04 | The first rebuilt core still carried XZ 5.8.1 and SQLite 3.50.4. SHAs 590e0a8 and 7989327 update the verified source inputs and reviewed Android patches to XZ 5.8.4 and SQLite 3.54.1 while retaining configured runtime features. |
| CI-02 | An interrupted official NDK download failed with curl92. SHA ec52d38 adds bounded transport retries and keeps checksum verification. |
| PACK-01 | The raw build tree was too large for the universal APK. SHA 5838095 selects the inherited runtime feature set, follows rebuilt stable-library aliases, verifies symbol removal and shares identical Python files. Signed size is 62,051,382 bytes. |
| CI-03 | Resolving LLVM's readelf symlink selected readobj's structured output, causing a Linux-only packaging failure. SHA 24bfa90 preserves invocation aliases, requests GNU symbol output and compares binary ELF headers, with focused regression tests. |

| ID / severity | Confirmed behavior | Remediation and regression |
|---|---|---|
| DL-01 / Medium | Successful binary download responses could carry explicit textual error envelopes. The baseline staging checks could publish those bytes under a binary filename. | Use the selected representation at the transfer boundary. Reject explicit incompatible text MIME before modifying staging, including a resumed range. JVM fixtures cover the false success, checkpoint preservation and legitimate text downloads. |
| DL-02 / Low | HTTP error status was obscured by inspecting the error body's MIME and encoding first. | Classify unsuccessful responses before checking the representation. JVM fixtures cover HTTP 403, 404, 429 and 503. |
| DL-03 / Medium | A parallel probe or worker could receive HTTP 429 and cause an immediate full GET. | Treat observed HTTP 429 as terminal for that attempt, preserve the typed failure and clean temporary parts. Deterministic parallel fixtures cover both probe and worker throttling and check request headers, staging and cleanup. |
| UI-01 / Medium | A non-dismissible pending-share dialog could cover batch-draft recovery while its only action was disabled by state. | Wait until review is possible. Provide Cancel and Back, preserving the current result and draft. Tests exercise recovery, UI dismissal and preservation during manual edits. |
| CI-01 / Medium | A unilateral MockWebServer upgrade pulled an incompatible Android transport onto the SDK 36 build path. | Keep test and app versions aligned and verify the resolved dependency graphs. Update independent CI action pins on the readiness branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| QA-01 / Medium | The native instrumentation probe waited for exit before draining a pipe. A child with a full pipe could block until the parent timeout. | Drain concurrently with a 4 KiB retention cap and a shared timeout. Add deterministic tests for a 1 MiB child stream and a sleeping child. The JVM reproduction establishes the pipe issue independently of earlier Python startup timeouts. |
| NATIVE-01 / High release blocker | The OpenSSL 3.5.9 rebuild failed when applying stale context in a Termux patch. After correction, all four jobs got past OpenSSL and failed configuring unused X11 dependency `xcb-proto` with the wrong host Python. | Isolated commit `db9bf1998c1766a51ed754b65b0b39bd12880e61` updates the patch. Follow-up `da14c10a2a6464e06f7ace92a43b25e19e4508b4` prunes the unused Tk dependency edges and provisions the host CPython from the verified 3.12.16 source. Native integration remains gated by [the new four-ABI run](https://github.com/taroq4-2/dosos73/actions/runs/41712501784). |
| NATIVE-02 / High release blocker | x86 and ARMv7 passed on `da14c10a`. ARM64 and x86-64 failed after compilation because collection applied the load-segment gate to an unlinked CPython development object, `config-3.12/python.o`. | Isolated SHA `490c5ba3be12d770edc8d3361c7d0453ed2f1534` excludes only the static development directory, which is absent from the inherited archive on all four ABIs. The alignment gate still rejects nonconforming executable/shared load segments. See [local regression and new run](TEST_EVIDENCE.md). |

Each scope and remaining risk is documented in the [master audit](MASTER_AUDIT_REPORT.md).

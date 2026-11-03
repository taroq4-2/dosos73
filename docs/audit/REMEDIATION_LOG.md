# Remediation log

Baseline `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. The code changes and their regression fixtures share the implementation commit in this branch. See [TEST_EVIDENCE.md](TEST_EVIDENCE.md) for the tested revision.

| ID / severity | Confirmed behavior | Remediation and regression |
|---|---|---|
| DL-01 / Medium | Successful binary download responses could carry explicit textual error envelopes. The baseline staging checks could publish those bytes under a binary filename. | Use the selected representation at the transfer boundary. Reject explicit incompatible text MIME before modifying staging, including a resumed range. JVM fixtures cover the false success, checkpoint preservation and legitimate text downloads. |
| DL-02 / Low | HTTP error status was obscured by inspecting the error body's MIME and encoding first. | Classify unsuccessful responses before checking the representation. JVM fixtures cover HTTP 403, 404, 429 and 503. |
| DL-03 / Medium | A parallel probe or worker could receive HTTP 429 and cause an immediate full GET. | Treat observed HTTP 429 as terminal for that attempt, preserve the typed failure and clean temporary parts. Deterministic parallel fixtures cover both probe and worker throttling and check request headers, staging and cleanup. |
| UI-01 / Medium | A non-dismissible pending-share dialog could cover batch-draft recovery while its only action was disabled by state. | Wait until review is possible. Provide Cancel and Back, preserving the current result and draft. Tests exercise recovery, UI dismissal and preservation during manual edits. |
| CI-01 / Medium | A unilateral MockWebServer upgrade pulled an incompatible Android transport onto the SDK 36 build path. | Keep test and app versions aligned and verify the resolved dependency graphs. Update independent CI action pins on the readiness branch. See [dependency review](DEPENDENCY_REVIEW.md). |
| QA-01 / Medium | The native instrumentation probe waited for exit before draining a pipe. A child with a full pipe could block until the parent timeout. | Drain concurrently with a 4 KiB retention cap and a shared timeout. Add deterministic tests for a 1 MiB child stream and a sleeping child. The JVM reproduction establishes the pipe issue independently of earlier Python startup timeouts. |
| NATIVE-01 / High release blocker | The OpenSSL 3.5.9 rebuild failed when applying stale context in a Termux patch. | Isolated commit `db9bf1998c1766a51ed754b65b0b39bd12880e61` updates the context. The next four-ABI run still failed and native integration remains blocked. See [run 41641954791](https://github.com/taroq4-2/dosos73/actions/runs/41641954791). |

Each scope and remaining risk is documented in the [master audit](MASTER_AUDIT_REPORT.md).

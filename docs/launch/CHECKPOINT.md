# Launch verification checkpoint — 31 October 2026

This branch prepares 1.2.0/code12. It is **not yet an official release**. The public RC5 download remains available until replacement acceptance is complete. User authorized emulator verification; physical S23 Ultra acceptance is deferred to the owner.

## Actual review work

60 distinct AI review tasks completed source reviews (reports01–60). These are AI review tasks, not human hiring, independent certification or proof of 100% device coverage. Original findings are retained under reviews/ and distinguish static inspection from executed tests. Triage and residual limitations are recorded in REVIEW_DISPOSITIONS.md; completing the review count does not itself clear release acceptance.

## Implemented changes

- Literal IP validation before transport socket creation, including redirect requests; reject ambiguous IPv4 and non-public IPv6 destinations.
- Preserve the durable pause/resume state against a stale startup snapshot; handle Resume/Cancel after the service has stopped. Pausing releases the worker and foreground service.
- Authenticated native process-group identity and bounded cancellation cleanup, including helpers surviving their parent. Unverified group ownership fails closed and records a diagnostic rather than signaling an unknown group.
- Durable batch acceptance IDs, queue receipts and serialized/CAS draft writes prevent a crash or concurrent screen from enqueuing the same accepted row twice. Analysis cancellation closes the direct/native transport; draft errors retain pending shared input.
- Check all Content-Encoding values and contradictory representation headers, preserve validator-bound resume and account for retained partial bytes in space preflight.
- Preserve the committed URI through cancellation; recover normalized provider names only with destination membership and full content verification. Check cancellation immediately before publication; remove a fallible post-commit reopen.
- Retain renamed provider URIs when optional metadata fails, migrate hidden-history/collection references, serialize history updates, hide orphan collection chips and disable operations on confirmed missing files.
- Bound private-journal reads to16MiB without overwriting an oversized file. Legacy plaintext migration remains an explicitly private-file trust boundary, not a claim of tamper detection for arbitrary legacy input.
- Treat extractor format IDs as literals. Converted audio is labeled MP3 V0 with unknown converted size rather than claiming source size or Original.
- Save the typed/pending URL and file-action text across activity restoration, retain expected hash input, accept CharSequence share payloads, canonicalize URL schemes and exclude unmatched prose delimiters from shared links.
- Add on-demand SHA-256 computation/comparison without a new runtime dependency. Narrow-screen shortcuts scroll rather than collapse; URL semantics, button roles and status announcements improved.
- Replace8 Readline/History ELFs with pinned official8.3.6 packages, preserving four ABIs/symlinks/functions. APK-install identity invalidates only managed runtime caches, avoiding upstream size-only update detection.
- Validate ELF ABI before packaging. Assembly emits an artifact-bound engine proof. SBOM generation rejects mismatched APK/engine evidence and verifies actual Readline/GDBM/Mutagen content. Maven archive hashes remain build-input evidence, not causal DEX attestation.

## Completed checks at this checkpoint

-106 JVM tests:0 failures,0 errors,0 skipped, including proxy deadlines/half-close, network observation/retry, runtime partial-init retry and maximum shared-input regressions.
- Debug/Release full lint and debug/test/minified Release assembly passed after network/history/destination integrations. An API30-only network builder call was replaced with an API29-compatible VPN-filter removal.
- Current-source compact debug device suite:116 reported cases,0 failures on the final run. Optional live-network/TLS diagnostic cases were not enabled in this default suite. The preceding run had1 about/contact semantic-node readiness failure; its focused retry passed, then a bounded readiness wait preserved the reachability assertions and the full116-case rerun passed. New failure-retention, tombstone pruning, directory-create and repeated network-callback registration cases passed.
- Previous committed e625ee7 source passed the API29–36 matrix in GitHub run41152259709; this does not cover the newer working-tree integrations. A fresh source-pinned matrix remains required.
- Previously accepted a8e4d87 source / signed candidate SHA-25695ea86067e2dcc73bbca85f631566e2ed6474d4e4e0309ba045fd5aead7969dd:62,018,329bytes, all4ABIs and unchanged signing certificate. Fresh-profile actual YouTube720p video, MP3 and W3C direct downloads plus video playback passed. Those results are not reassigned to a newer artifact.
- Prior candidate SBOM/schema, wrong-payload rejection, ELF and deterministic unsigned packaging checks passed. SBOM contains699components,608ELF records and85Maven input components. Final integration artifact checks must be regenerated.

## Additional integrated fixes from reviews41–60

- Bound unauthenticated CONNECT headers by an absolute deadline and origin read inactivity without breaking half-close response delivery or long receiving-only downloads.
- Observe loss of a transfer's original bound network after default-route handover. Register all observers before transport start, clean up partial registration failures, and bound only policy/acquisition recovery; ordinary cancellation and unrelated failures are not retried.
- Preserve successfully initialized Python/FFmpeg files when a later runtime stage fails and initialization retries in the same process.
- Dispose evicted failed-task partials only after durable journal commit while queue ownership is locked; preserve queued, leased and active partials. Prune hidden-history URI metadata on explicit delete/confirmed-missing forget.
- Build share ClipData from known MIME metadata without synchronous provider discovery on the UI thread. Strip long shared-link prose delimiters in linear bounded scans.
- Require directory-create capability before downloading to SAF, including delete-only/write-only destination regressions, retaining the API29 same-UID compatibility fix.
- Retain artifact-bound runtime-proof JSON in compact workflow uploads and correct the current rebuild commands.

## Still required before official publication

Finish finding dispositions and the corrected current-source local suite and API29–36 CI; validate the final minified APK on a clean/update emulator with real YouTube/video/MP3/direct downloads; verify deterministic packaging, signature and payload/source evidence; capture current Arabic/English UI; update final verification/README/source offers. Only then replace obsolete download assets and publish a single official universal APK.

Native source evidence is materially improved, not represented as a completed legal audit: exact CPython producer build lock remains unavailable. See NATIVE_SOURCES.md. OS-scheduled wakeups, multiple simultaneous active files and subtitle selection remain separate unimplemented features, as recorded in the requirements ledger. A paused queue barrier/strict priority is deliberate behavior. Source-specific restrictions and physical-device coverage are not guaranteed.

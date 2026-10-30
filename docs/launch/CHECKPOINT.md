# Launch verification checkpoint — 31 October 2026

This branch prepares 1.2.0/code12. It is **not yet an official release**. The public RC5 download remains available until replacement acceptance is complete. User authorized emulator verification; physical S23 Ultra acceptance is deferred to the owner.

## Actual review work

40 distinct AI reviewers completed source reviews (reports01–40). Reviewer41 and42 stopped at the service usage limit without reports. Reviews41–60 are incomplete. These are AI review tasks, not human hiring, independent certification or proof of 100% device coverage. The original reports are retained under reviews/; each states whether it ran tests. Later fixes do not rewrite earlier evidence.

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

-88 JVM tests:0 failures,0 errors,0 skipped.
- Debug lint, minified Release build and Release vital lint passed. Full lintRelease remains a separate required check.
- First emulator suite:100 tests,3 failures in newly stale input-test readiness assertions; native cancellation, MP3, updated Python/runtime inventory, journal, queue, storage and publication cases passed. Readiness assertions were corrected and a102-case rerun is pending. Do not count the failed run as overall success.
- Signed universal candidate:62,018,329bytes (62.02MB), all4 ABIs, same signing certificate. Candidate hash is4da0e218921e328a28e8fca2a4b58ad8b8ad4460dd43efc7e0d92a652b9018b7.
- Candidate SBOM contains699 components,608ELF records and85Maven input components; schema validation/negative-case checks remain pending.

## Still required before official publication

Complete reviews41–60 and triage any findings; finish the corrected local suite and API29–36 CI; validate the final minified APK on a clean/update emulator with real YouTube/video/MP3/direct downloads; verify deterministic packaging, signature and payload/source evidence; capture current Arabic/English UI; update final verification/README/source offers. Only then replace obsolete download assets and publish a single official universal APK.

Native source evidence is materially improved, not represented as a completed legal audit: exact CPython producer build lock remains unavailable. See NATIVE_SOURCES.md. OS-scheduled wakeups, multiple simultaneous active files and subtitle selection remain separate unimplemented features, as recorded in the requirements ledger. A paused queue barrier/strict priority is deliberate behavior. Source-specific restrictions and physical-device coverage are not guaranteed.

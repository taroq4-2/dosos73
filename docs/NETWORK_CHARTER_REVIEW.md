# Direct transfer integrity

Current status: [remaining requirements](REMAINING_REQUIREMENTS.md); signed release evidence: [verification](VERIFICATION.md).

## Safe resume contract

The downloader preserves its public function signature. The service must pass the same private destination for the same task UUID on retry. The sidecars are `<destination>.resume` and `<destination>.resume.tmp`. They contain version, SHA-256 source/final-target URL bindings, strong ETag, total length and durable offset, never raw URLs. Data is synchronized before an atomically replaced checkpoint. Any tail after the committed offset is truncated before append. Checkpoints occur at 4 MiB or 2 seconds, plus completion/failure; the buffer is 64 KiB. This is not a measured power-consumption claim.

Resume is enabled only with a strong ETag and known positive total. Weak/missing ETag or date-only validators restart safely; Last-Modified alone is not assumed strong. A 206 must match original final-target hash, ETag, exact requested start, original total, and expected terminal byte; nonidentity encoding, multipart or incorrect content length is rejected before append. A 200 after Range replaces the partial body, never appends. A 416 triggers at most one full request and never marks a partial file complete solely from local size. Changed or malformed 206 responses fail safely and retain the original checkpoint for explicit retry or cancellation. Success removes the sidecar; explicit user cancellation can delete the stable task directory; transient errors/system interruption should retain it.

The implementation follows [RFC 9110 If-Range](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-range) and [Content-Range](https://www.rfc-editor.org/rfc/rfc9110.html#name-content-range): weak validators and invalid ranges cannot be used to combine representations.

## Acceptance cases added

- Disconnect midbody, instantiate a new downloader, resume and compare SHA-256 with fixture.
- Commit prefix plus uncommitted tail, then resume exactly at durable offset.
- Ignored Range / changed representation 200 replaces without duplicated bytes.
- 416 restarts once; never accepts the local file as completed.
- Wrong range start/end/total, overflow, unknown total, weak/changed/missing validator reject append.
- Wrong range Content-Length, gzip response and URL-binding mismatch.
- Corrupt sidecar is rejected; journal roundtrip does not expose raw secret query.
- Existing unknown-length, >2 GiB advertisement, truncation, HTML, unsafe redirect and pause tests retained.

## Optional parallel transfer

Two independently validated byte ranges may be used on compatible sources. Failure/cancellation prevents unsafe merging, and sequential fallback is bounded. Parts currently restart after interruption. Adaptive measured selection, durable segment journals and a broader resource comparison remain open; do not describe this as complete adaptive acceleration.

# Network and storage review for the development charter

Review scope: full DirectDownloader, Safety, PublicProxy, OutputStore, StoragePolicy, DownloadService, Models and LibraryRepository; complete DirectDownloaderTest, SafetyTest, PublicProxyTest, StoragePolicyTest, OutputSafetyTest and ProxyTransportTest. This is one actual specialist review, not a claim of 30 independent reviewers. Initial findings refer to v1.1.2 source before edits.

| ID / role | Evidence in v1.1.2 | Effect / priority | Implementation / verification |
|---|---|---|---|
| NET-01 / HTTP + resume | DirectDownloader.download line 135 truncates; DownloadService.processTask lines 130,225 uses random cache directory and always deletes it | Cannot resume verified bytes after interruption; high user value | Strong-ETag resume engine and durable sidecar implemented; service integration owned separately. Adversarial tests added; execution results belong in final test report. |
| NET-02 / security | secureClient lines 166-179 overrides DNS but inherits system proxy selection | Configured HTTP proxy can resolve the origin itself, outside public-address DNS validation; potential policy bypass | Explicit Proxy.NO_PROXY. Unit assertion added. This intentionally does not implement system HTTP-proxy support; VPN routing is OS-managed. |
| NET-03 / file integrity | download lines 124-130 asks for identity but never validates Content-Encoding | Unexpected content encoding could be saved as generic file bytes under a decoded-content extension | Reject nonidentity encoding and multipart ranges; test added. |
| STORE-01 / storage integrity | OutputStore.save lines 134-137 only checks statSize if >=0 | Provider returning unknown size could silently truncate without detection | Readback byte/hash verification recommended to storage owner; not claimed fixed by network patch. |
| STORE-02 / crash recovery | OutputStore creates pending row/document without persistent publication journal; service acknowledges afterward | Process death may leave partial documents or redeliver already-published task | Publication transaction journal recommended; not claimed fixed by network patch. |
| NET-04 / cancellation | Single active call and synchronous reads; coroutine polling occurs between reads | Coroutine cancellation alone cannot interrupt blocked socket read immediately | Service must call cancel(); cancel also guards call-registration race and pre-read boundary. 30-second read deadline remains. |

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

## Adaptive parallel download assessment

A truthful two-connection implementation needs separate durable segment journals, per-call cancellation ownership, exact range/validator checks for every segment, a streamed verified merge, and a sequential fallback transaction. Current recovery represents a single contiguous durable prefix and service ownership is single-flight; simply launching two copies would corrupt checkpoint ownership and cancel only one socket. Therefore parallel mode is not exposed by this change. No speed improvement is claimed. The resume integrity tests are the prerequisite; the subsequent parallel work must include two independent staged segments and deterministic throttled-server benchmarks, including a no-improvement result. This is a documented unimplemented feature, not silently labeled adaptive acceleration.

Remaining limits: no checksum reference is assumed from arbitrary servers; exact fixture hashing proves transfer/test correctness only. Full OS power-loss durability, physical-device flash behavior, native extractor range recovery, hostile storage-provider fault injection and parallel benchmarks require additional evidence.

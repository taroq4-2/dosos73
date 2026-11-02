# Download, media, storage and recovery review

Review date: 2026-11-02. Baseline: `bcfcdbdf46ba0f9e18dcfad50fa8fb8220312b9e`.
This document records source review and added tests. Execution results and the final
candidate SHA belong in `QA_TEST_MATRIX.md` and `RELEASE_READINESS_REPORT.md`; test
existence is not a passing test result. Roles below are virtual review functions
performed by an AI reviewer, not 15 independent people or 15 independent approvals.

## Findings and decisions

| ID | Classification / priority | Evidence and user impact | Exact intervention | Acceptance and remaining limits |
|---|---|---|---|---|
| A04-URI | Verified defect / P1 defensive correctness | `PublicationReceipt.publish` accepted a provider's rename return as the committed destination without checking its scheme, authority, selected tree or canonical URI shape. A faulty provider could return an unrelated URI after renaming the verified file. | Check content scheme, same authority and tree ID, tree-scoped canonical document URI before updating the ready receipt. On failure retain the durable plan and file for recovery. | `PublicationUriBoundaryTest`: five invalid return forms must be rejected, with zero deletion and recovery of the actual verified file; a valid changed document ID still commits when the provider becomes unreadable immediately after rename. A malicious provider controlling the same authority and its reported contents remains outside the guarantee. |
| A04-SOAK | Test-coverage gap / P1 | Existing targeted publication tests did not provide a 100-run, per-run integrity artifact. | Add `StorageRecoverySoakTest`, deterministic seed 7301, 100 runs across ten fault boundaries. | Hash equality, unchanged source and unrelated sibling, exactly one publication and idempotent receipt replay each run. Device execution is required before calling these runs passed. This is controlled fault injection and receipt reconstruction, not 100 actual OS process kills. |
| A04-REAL | Test-coverage gap / P1 | No attached physical ARM device or real cloud/removable-storage fault fixture. | Preserve this as an unverified release gate. Do not fill the user's disk or delete user media to simulate failure. | True ENOSPC, USB removal, cloud-provider normalization/outage, permission revocation during real transfer, reboot and physical process-death matrix remain NOT RUN in this review. Existing fixture coverage does not close this gate. |
| A09 | Optional feature / P2, deferred | `DownloadService` has one worker, one current task, one direct/native transfer owner, one pause/cancel state and one progress notification. Increasing the worker count would race those shared owners. | Preserve the serial durable queue. No new simultaneous-file claim. | A future design must introduce independent persisted per-task ownership, staging, progress and cancellation before 2–3 active jobs can be accepted. Existing optional two connections download ranges of one direct file; they are not multiple concurrent files. |
| A10 | Optional capability / P2, deliberately limited | `DeferredTransferScheduler` has one persisted JobScheduler reminder, network constraints, earliest eligibility and an immutable pending intent opening the app. It does not auto-start a background transfer. | Preserve honest reminder semantics and foreground user ownership. | `DeferredTransferTest` covers one constrained job, replacement, pause/removal/cancel, expired-wait finalization. Exact-time or background auto-start is not advertised. OEM/Doze timing remains unverified. |
| A11 | Test-coverage gap / P2 | Direct files use a strong ETag, source/redirect target hashes, full Content-Range/length/encoding checks and durable flush-before-checkpoint. Extracted streams may safely restart; there is no claim that every media source can resume. | Preserve the existing restart, rejection and cancellation paths; no speculative segmented-media rewrite. | JVM `DirectDownloaderTest`, `HeaderRepresentationTest`, `ResumeJournalTest`, `ParallelDownloaderTest`; Android `NativeCancellationTest`, `SharedRuntimeTest`, `OfflineAudioTest`, `Mp3EncodingTest`, `PublicationCommitRaceTest`. Real flaky/mobile network and long-running source/OEM matrix remain separate gates. |

## Fault laboratory

`StorageRecoverySoakTest.oneHundredControlledRecoveriesPreserveHashesAndUnrelatedFiles`
loops ten times through each of:

1. Durable plan before document creation.
2. Empty document created before its URI was journaled.
3. Recorded document with a partial body.
4. Complete document body before its URI was journaled.
5. Verified document before publication.
6. Provider renamed the document and then reported failure.
7. Provider rejected rename before mutation.
8. Provider temporarily refused read access.
9. Durable ready-receipt replay.
10. Unknown-size pipe-backed document.

Each run creates a unique receipt and local source, a random deterministic body
between 4 KiB and approximately 132 KiB, and an unrelated sibling. SHA-256 and byte
count are checked after recovery; the source and sibling must remain unchanged.
The recovered URI is replayed once more and must not cause a second rename.
The report records every run, seed, scenario, bytes and source/output hashes.
An incomplete run leaves `state: running`, rather than a fabricated pass summary.

The fixture uses only `com.os73.dosos73.recovery-tests`, a debug-only provider,
UUID-named cache sources and UUID-owned encrypted publication journals. Cleanup
does not query, clear or delete MediaStore downloads or the user's chosen trees.
The deterministic report filename is owned by the test harness and is overwritten
only by that harness. Tests run serially because the debug provider has shared
fault controls; running parallel instrumentation against the same app is unsupported.

Run after building/installing the shared-runtime debug APK and its matching test APK:

```text
adb -s emulator-5560 shell am instrument -w -e class com.os73.dosos73.PublicationUriBoundaryTest,com.os73.dosos73.StorageRecoverySoakTest com.os73.dosos73.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell run-as com.os73.dosos73 cat files/readiness-storage-soak.json
```

Expected new test-method count: **3** (two boundary tests and one 100-run soak).
This count is not an execution result. Retain instrumentation output and the JSON
report with the exact candidate commit. Also run existing publication/rename/commit
race tests and the full regression suite before accepting the patch.

## Virtual review functions 11–25

| Role / accountable function | Concrete reviewed location or contribution | Evidence / cross-review disposition |
|---|---|---|
| 11 HTTP Range / ETag | `DirectDownloader.request/download`, `HttpRepresentation`, `DirectDownloaderTest` | Checked 200 replaces rather than appends; 206 binds offset/end/total, target and strong ETag; 416 restarts once. Preserve; root reruns JVM suite. |
| 12 Durable checkpoints | `ResumeJournal`, `DirectDownloader.persist`, `PublicationReceipt.recover` | Checkpoint follows file sync; receipt replay requires exact bytes. Added 100 reconstructed-boundary runs; execution pending. |
| 13 Connection concurrency | `ParallelDownloader`, `ParallelDownloaderTest`, single-worker `DownloadService` | Two ranges are bounded to one representation with safe fallback. Multi-file expansion deferred under A09. |
| 14 Queue / cancellation races | `DownloadQueue`, `TransferProcessor`, `PublicationCommitRaceTest`, `BatchHandoffTest`, `PauseStartupRaceTest` | Preserve commit truth after cancellation and durable batch handoff. No shared-owner parallel-worker change. Root reruns tests. |
| 15 Background service | `DownloadService`, `DeferredTransferScheduler`, `ColdServiceControlTest`, `DeferredTransferTest` | Visible dataSync ownership, bounded network wait and OS reminder retained. Physical Doze/OEM matrix NOT RUN. |
| 16 yt-dlp integration | `MediaEngine`, `NativeProcessSession`, `SharedRuntimeTest`, `NativeTransportComparisonTest` | Native/session ownership and existing format-selection/TLS behavior preserved. Public-source availability is external; no new universal-source claim. |
| 17 FFmpeg / codecs | `OfflineAudio`, `offline_audio.py`, `OfflineAudioTest`, `Mp3EncodingTest` | Local MP3 V0/source retention remains in regression scope. No binary or codec removal. Supply-chain reviewer owns native advisories/licensing. |
| 18 SAF / MediaStore | `PublicationReceipt`, `OutputStore`, `PublicationUriBoundaryTest` | Added rename-return boundary validation; publication plan preserves ambiguity for recovery. Security/root review requested before acceptance. |
| 19 ENOSPC / atomic publish | `StoragePolicy`, `OwnedProviderPreflightTest`, `PublicationRecoveryTest`, soak fixture | Added deterministic fault coverage; explicitly no true disk-full or real cloud/USB claim. |
| 20 SHA-256 integrity | `StreamIntegrity`, new `StorageRecoverySoakTest` | Per-run source/output hashes and unchanged sibling/source assertions. Final run artifact required. |
| 21 JVM quality | `DirectDownloaderTest`, `ResumeJournalTest`, `ParallelDownloaderTest`, `StoragePolicyTest` | Reviewed regression intentions; execution delegated to root's matched build, not invented here. |
| 22 Android instrumentation | New `PublicationUriBoundaryTest` and `StorageRecoverySoakTest` | Three new test methods; debug-only fault injection. Root runs on owned emulator and CI. |
| 23 Generated inputs | Seeded random bodies and five invalid URI forms | Deterministic variation, not a comprehensive fuzzer or proof against all providers. |
| 24 Stress / soak | New 100-run provider recovery fixture | Tests publication integrity under injected boundaries. Not multi-hour transfer/battery soak and not actual OS-kill certification. |
| 25 Compatibility / regression | Existing API 29–36 matrix plus new tests | Root owns execution after target-36 migration. Physical ARM/OEM and unsupported API versions remain unverified. |

## Cross-review and release limits

The root reviewer must inspect the rename URI checks and rerun the existing
post-rename commit-race tests. The security reviewer should confirm that the new
check narrows accepted URIs without treating the same-authority provider as a
trusted security oracle. The no-post-commit-reopen behavior is intentionally
preserved: a provider going offline immediately after a successful rename must
not retroactively turn a committed output into a failed job or delete it.

No real-device approval, ENOSPC certification, source availability guarantee,
multi-file support, exact scheduling guarantee or production GO is asserted here.

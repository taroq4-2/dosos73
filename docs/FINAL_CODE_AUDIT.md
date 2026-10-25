# First-party code audit

## V4 investigation review

Scope: BundledEngine, PipelineError, MediaEngine, DownloadService, OutputStore, MainViewModel, MainActivity, Models and added tests. Confirmed generic-error reporting defect corrected with stage-specific messages. Version-pinned asset replaces stale cached engine atomically after hash verification. No network updater introduced. Cancellation exceptions remain cancellations rather than being wrapped into download failures. Expected audio and height now participate in output verification. Native error-message strings are classified in memory, never written to app diagnostics. No significant duplicate subsystem removed; no independent review claimed. YouTube end-to-end and the requested 20-review framework remain incomplete. UI redesign is not claimed complete.

Review type: separate self-review pass by the implementing assistant, **not an independent human/agent audit**. Date: 2026-10-25. Verdict: development preview only; production release blocked by the outstanding items in SECURITY.md and TEST_RESULTS.md.

## Files inspected

All first-party Kotlin files: Models, Safety, DirectDownloader, PublicProxy, MediaEngine, OutputStore, DososApp, DownloadService, MainViewModel, MainActivity; SafetyTest, DirectDownloaderTest, OutputSafetyTest, LiveDownloadTest. Also inspected app/build.gradle.kts, root build/settings/gradle.properties, AndroidManifest.xml, proguard-rules.pro, values/values-ar resources, adaptive icon and android.yml workflow.

## Findings and corrections

| Finding | Severity / user effect | Action / evidence |
|---|---|---|
| OkHttp Dns is not a Kotlin SAM interface | Build blocker | Explicit interface implementation; subsequent debug compilation and DNS tests passed. |
| Kotlin daemon startup failed on this Windows installation | Build reliability | Compile in-process, no source semantics changed. |
| Jackson optional desktop classes missing from Android | Release build blocker | Narrow optional-class exclusions documented; no broad warning suppression added. Rebuild evidence recorded in TEST_RESULTS. |
| extractNativeLibs source manifest warning | Maintainability | Removed redundant manifest attribute; Gradle useLegacyPackaging controls extraction. |
| Process-death output recovery incomplete | High, orphan partial output | Documented release blocker; cancellation in normal service lifetime deletes newly created output. Durable recovery still needed. |
| Native proxy compliance not proven for every helper | High, network-policy assurance | Not claimed solved; SECURITY.md gate retained. |
| Native wrapper buffers stdout and writes metadata before size check | High, resource exhaustion | Parser cap is only partial mitigation; requires upstream/adapter hardening. |
| Native process cancellation registration race | High, possible orphan process | Documented, stress testing required. |
| Optional conversions/resume not implemented | Functional scope | UI does not advertise either. Restart is explicit. |

## Simplification and duplication

Shared Safety functions own URL, DNS, filename and progress rules. A single OutputStore owns MediaStore/SAF commits. Phase-to-text mapping is reused by notification and screen. No second FFmpeg distribution or Aria2 engine included. No application framework/DI/Room added. No claim of a large duplicate-code removal: this is a new codebase, and no established duplicated subsystem was deleted.

Media and HTTP remain distinct adapters because their cancellation/progress/extraction semantics differ. Combining them into one generic framework was rejected as unnecessary. Unit tests exercise failure behavior rather than mirroring statements.

Critical ownership: service owns transfer coroutine and temporary directory; use blocks own HTTP responses/streams, sockets and metadata retriever; Activity owns presentation only. Direct cancellation closes the OkHttp call; native cancellation closes proxy and calls wrapper termination. Cancellation during storage copy deletes the newly created destination. Post-commit cancellation edge cases and process death require additional tests.

No performance improvement numbers are claimed. Raw build/test evidence and real device tests determine verified results; unperformed tests remain pending.

## Follow-up evidence

Generic yt-dlp metadata without a formats array caused an observed failure. Added single-original-format mapping and regression test; no invented resolution. Moved transfer status above choices, corrected URL LTR direction and added explicit backup exclusions. All 14 JVM and 5 device tests passed after fixes. Release build succeeded. CI setup failure traced to retired SDK tools package and corrected.

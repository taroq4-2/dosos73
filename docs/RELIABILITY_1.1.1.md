# dosos73 1.1.1 — crash repair, smaller packages and recovery

## Release crash

The signed, minified 1.1.0 APK crashed after receiving a YouTube URL on the API 35 emulator. The stack was `ExceptionInInitializerError` from `YoutubeDL.initPython`, caused by Commons Compress reporting that its reflected ZIP extra-field implementation was not a concrete class. The release mapping identified `AsiExtraField` and `ExtraFieldUtils`. Debug-only tests did not exercise this R8 failure.

Keep rules now preserve concrete `ZipExtraField` implementations. Initialization linkage failures are also converted to a visible runtime error instead of an uncaught application crash. On the signed fixed release, the same link passed initialization and produced a handled TLS/network error in this emulator instead of terminating. This is evidence for the crash fix, not a successful YouTube download on every device/network. Samsung S23 Ultra verification remains a real-device acceptance step.

## Size decision

The old universal APK was 111,978,356 bytes. Its compressed ARM64 FFmpeg and Python archives alone occupied about 49.5 MB; it also carried roughly 52.4 MB of corresponding x86 components. Architecture-specific packages remove the second copy without changing the engine or introducing a first-use download. ARM64 is intended for modern phones including S23 Ultra; x86_64 is separate.

Alternatives were evaluated:

| Approach | Decision |
|---|---|
| Download runtimes when pasting | Not adopted: first-use latency, availability/integrity requirements and Android executable-location restrictions. It reduces installer size, not the eventual runtime footprint. |
| NewPipeExtractor | Not a feature-equivalent replacement: its supported-service list is narrower than yt-dlp, notably lacking the same Instagram/TikTok/X coverage. |
| Remote extraction/merge server | Not adopted: adds server cost, privacy implications and a permanent network-service dependency. |
| Custom remux-focused FFmpeg | Promising next optimization, not shipped: bundled FFmpeg includes codec/encoding libraries such as AOM, SVT-AV1, x264/x265 and many shared dependencies. Removing dependent libraries from a prebuilt archive breaks loading; a reproducible rebuild and MP4/WebM/HLS/DASH/merge validation are required. No untested replacement binary is included. |
| One APK per ABI | Implemented: immediate size reduction, same pinned runtime, no first-use runtime download. Build with `-PtargetAbi=arm64-v8a` or `-PtargetAbi=x86_64`; omitting the property retains the universal developer build. |

Primary references: [Android APK configuration](https://developer.android.com/build/configure-apk-splits), [Android executable restrictions](https://developer.android.com/privacy-and-security/security-best-practices), [NewPipeExtractor supported services](https://github.com/TeamNewPipe/NewPipeExtractor#supported-sites), [FFmpeg packaging source](https://github.com/yausername/youtubedl-android/blob/0.18.1/ffmpeg/src/main/java/com/yausername/ffmpeg/FFmpeg.kt).

## In-flight recovery without a resident watchdog

Waiting and active tasks now share an atomic private journal. Taking a task persists active ownership before transfer. Terminal handling acknowledges it after publication or failure recording. A normal service start uses `START_REDELIVER_INTENT`; redelivered pause commands resume recovery instead of silently leaving the service idle. Opening the app also recovers a saved in-flight task. No polling worker, extra runtime library or battery lock was added.

An API 35 test started a real W3C Sintel transfer, verified its active journal, moved to Home and killed process 9134 with SIGKILL. Android started process 9339 without reopening the Activity. That process verified and published 4,372,373 bytes, then cleared active ownership. The network was temporarily slowed to keep the test transfer in progress and restored to full speed immediately after the kill.

Recovery restarts transfer from byte zero; it is not Range/ETag resume. Android/OEM restrictions still govern scheduling, and Settings → Force stop prevents automatic restart until the user opens the app. There is still a narrow crash window between publishing a file and acknowledging the journal that can cause a duplicate retry. A durable output transaction journal remains outstanding. No exactly-once guarantee is claimed.

## Priority and medium-item status

| Item | This change / remaining scope |
|---|---|
| H1 | Deterministic bitrate/frame-rate ordering before deduplication; highest standalone audio chosen as well as merged audio. Audio labels still use format names. |
| H2 | SAF create/write/delete preflight and rotation-aware height validation added. Provider free-space, native estimated sizes and rename fallback remain. |
| H3 | Independent engine updater not added; signed APK updates retain the pinned engine. |
| H4 | Content-Disposition including UTF-8 names, MIME extension mapping and additional file types added. Untrusted paths are stripped. |
| H5 | HTTPS direct-probe I/O failure now allows extractor fallback, respecting coroutine cancellation and existing public-address checks. |
| M1 | Standard NAT64 /96 prefix checks the embedded IPv4 address; private targets still rejected. Custom NAT64 prefixes and a real IPv6-only network test remain. |
| M2 | Tries validated DNS answers within one bounded connection budget. System-proxy chaining and parallel Happy Eyeballs remain. |
| M3 | Per-stream native progress remains; no weighted combined progress. |
| M4 | Saving now reports copied bytes; staging and sync retained for verification. |
| M5 | Active-task journal and process-death retry implemented and exercised. Byte-resume remains absent. |
| M6 | Analysis has a two-minute coroutine timeout and cancellation calls. Metadata size/count guards remain; upstream buffering still needs resource-limit work. |
| M7 | SAF rename limitations remain. |
| M8 | Explicit public HTTP support remains; no silent HTTPS migration. |
| M9 | IDN and pasted trailing punctuation work remains. |
| M10 | Existing foreground lifecycle fixes retained; recovery policy extended. |
| M11 | Completion notification retained; separate failure channel/actions remain. |
| M12 | No new wake/Wi-Fi locks, metered policy or network callback. Recovery is not a substitute for OEM power tests. |
| M13 | Cancellation job access stays on the main dispatcher. Busy-analysis input and re-analysis on retry remain. |
| M14 | Active-task durability added; orphan output transaction recovery remains. |
| M15 | Added filename tests, NAT64 address tests, standalone audio assertion and active-journal test. Broader device/API coverage remains. |
| M16 | Dependabot configuration added for Gradle and Actions; SHA pinning, SBOM, verification metadata and advisory audit remain. |
| M17 | No further asset-rights audit or archive cleanup in this repair. |
| M18 | Root security-reporting guidance and clearer audit scope added; privacy text now describes queue/history storage. |
| M19 | ABI-specific release packages implemented; no 32-bit support. |
| M20 | Error text now announces a polite accessibility live region; full TalkBack/dropdown audit remains. |

## Verification scope

- 39 JVM tests, zero failures; release lint and optimized signed builds passed locally.
- Device instrumentation runner completed with `OK (28 tests)`; opt-in network tests are separate and were not silently counted as network acceptance.
- The first live direct-download run timed out. Subsequent real UI transfers verified and published the exact 4,372,373-byte sample, including process-death recovery.
- A repeat of the opt-in end-to-end direct test passed (`OK (1 test)`): saved media duration 52,209 ms, 854×480, Android MediaPlayer position advanced to 47 ms. This checks playback startup, not full-duration viewing.
- Release initialization was explicitly exercised after reproducing the old release-only crash. Do not substitute debug tests for this check in future releases.

No claim is made that all forty review lenses are closed or that all network sources work under all restrictions.

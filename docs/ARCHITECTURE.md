# Architecture

One Android application module uses Kotlin, Compose, lifecycle and coroutines. The flow is UI → MainViewModel → direct probe or MediaEngine → DownloadService → validation → OutputStore publication → content URI.

## Task ownership and lifecycle

DownloadQueue persists waiting/active ownership through an authenticated encrypted journal. The foreground dataSync service is started for requested work and stops at a terminal state. START_REDELIVER_INTENT and the journal allow recovery after process death; opening the app also checks interrupted work. There is no periodic watchdog. Force-stop requires reopening; no automatic boot restart is promised. One file is active at a time, with explicit queue priorities/pause/retry.

DirectDownloader streams through bounded buffers and can retain a durable verified prefix with strong ETag/target/length binding. Incorrect ranges or changed representations never append to existing bytes. Optional two-range transfer has separate per-call cancellation and a checked merge; its parallel parts are not durably resumed. Native extraction may restart after interruption.

## Network and engines

Public-address validation, network-bound DNS/sockets, redirects and timeouts protect direct requests. A locally authenticated HTTPS CONNECT proxy validates native target addresses without terminating TLS. Full helper/protocol isolation and physical network transitions remain verification work. Media metadata has distinct size/count guards; media files have no fixed size ceiling. Storage reserves bookkeeping space and rejects known insufficient space without integer overflow.

MediaEngine initializes bundled pinned yt-dlp/Python/QuickJS/FFmpeg when needed. No runtime fetch happens on paste. Actual metadata supplies formats and language/source bitrate. MP3 conversion uses LAME V0; source bitrate is not presented as an output guarantee. [Compact packaging](COMPACT_ENGINE.md) explains the four-ABI universal delivery.

## Publication and library

OutputStore checks content, duration, tracks and requested dimensions. An encrypted plan is saved before destination creation; known temporary/final names and verification receipts support interrupted publication and SAF rename URI changes. Recovery hashes candidates and preserves ambiguous/verified content. Provider-specific limitations remain in [PUBLICATION_RECOVERY.md](PUBLICATION_RECOVERY.md).

Saved-file metadata is indexed separately from user bytes. Bounded access checks distinguish proven local absence from denied/unavailable storage. Missing-record removal is explicit, reconfirms absence and removes metadata only. Collections are metadata labels; deleting a collection does not delete media.

## Privacy and diagnostics

Queue, failure, batch, receipt and timeline journals are private/encrypted. TaskTimeline records allowlisted stage/result/code fields, at most 32 tasks × 24 events, with no URL/title/native stderr inputs. Its selected-day retention prunes on read/write, without a periodic worker. Completed-library visibility and failed-task retention have separate limits; full metadata retention cleanup remains open.

Android's foreground execution/time limits apply, including API35 restrictions. [Verification](VERIFICATION.md) and [remaining work](REMAINING_REQUIREMENTS.md) separate measured outcomes from unimplemented scope.

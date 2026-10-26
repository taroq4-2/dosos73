# Architecture

One Android application module uses Kotlin, Compose, lifecycle and coroutines. The flow is UI → MainViewModel → direct HTTP probe or MediaEngine → DownloadService → validation → OutputStore publication → content URI.

DownloadQueue atomically persists waiting and active task ownership. Transfers exposes process-local UI state. The foreground dataSync service runs for requested downloads and stops at terminal state; START_REDELIVER_INTENT and the active journal allow retry after process death. Opening the app recovers an interrupted active task. There is no periodic watchdog. Recovery starts from byte zero, not a byte-range resume. Force stop prevents automatic restart until opening the app. A crash between publication and journal acknowledgement can cause a duplicate retry; an output transaction journal remains future work.

DirectDownloader streams with 64 KiB buffers, checked DNS, bounded redirects and timeouts. There is no fixed media-size ceiling. StoragePolicy retains a 32 MiB bookkeeping reserve and checks known sizes without overflow. MediaEngine lazily initializes pinned yt-dlp/Python/QuickJS/FFmpeg; HTTPS CONNECT tunnels validate public DNS answers, authenticate locally and preserve end-to-end TLS. Metadata has separate size/count guards. No engine download occurs on paste.

Format choices come from actual source metadata; separate compatible streams merge with FFmpeg. Forced MP3 conversion, playlists and arbitrary command options are not advertised. OutputStore checks duration, audio/video presence and requested displayed height, accounting for rotation. MediaStore remains pending until verification; SAF uses a unique temporary document, then rename. Provider free-space/rename limits and orphan output recovery remain documented.

The app uses Android's foreground dataSync policy across API 29+. Android 15 time limits still apply. No battery, OEM scheduling, broad extractor compatibility or complete native-isolation guarantee is made. See SECURITY.md and RELEASE_VALIDATION.md.

# Dependency decisions

Research date: 2026-10-25. Versions are pinned. Not every selected version is the newest upstream version; the initial build uses the locally available AGP 8.7.2 / Gradle 8.9 toolchain, Kotlin 2.0.21 and SDK 35. Migration to newer stable Android tooling is a follow-up gate, not a claimed completed optimization.

| Candidate | Decision and reason | Primary source |
|---|---|---|
| yt-dlp | Selected via Android wrapper; broad extraction without implementing site parsers. Python/native size overhead is real. | https://github.com/yt-dlp/yt-dlp |
| youtubedl-android | 0.18.1 library + FFmpeg, no Aria2. Upstream tags are prereleases, explicitly recorded. Includes QuickJS and earlier 16 KB changes; device validation still required. | https://github.com/yausername/youtubedl-android/releases |
| Seal | Reference architecture, no application code copied. Broader product surface than needed. | https://github.com/JunkFood02/Seal |
| YTDLnis | Reference native integration, no application code copied. | https://github.com/deniscerri/ytdlnis |
| Ketch | Not selected: additional queue/concurrency abstractions unnecessary for one transfer. | https://github.com/linroid/Ketch |
| Fetch | Not selected: another persistence/download engine duplicates needed HTTP responsibility. | https://github.com/tonyofrancis/Fetch |
| Downpour | Not selected: extra manager layer without measured benefit for this scope. | https://github.com/AlirezaJavan/Downpour |
| DownloadManager | Not selected: cannot share the same custom URL/DNS policy and native processing pipeline. | https://developer.android.com/reference/android/app/DownloadManager |
| OkHttp | 4.12.0: streaming, call cancellation, custom DNS and explicit redirect handling. | https://square.github.io/okhttp/ |
| FFmpegKitNext | Evaluated but not packaged: duplicates wrapper FFmpeg and requires another native/license audit. | https://github.com/arthenica/ffmpeg-kit-next |
| Retired FFmpegKit | Excluded. | https://github.com/arthenica/ffmpeg-kit |
| Media3 Transformer/platform codecs | Not selected for conversion: device codec variability and duplicate pipeline; platform retriever used only for validation. | https://developer.android.com/media/media3/transformer |

No benchmark advantage is claimed. Exact native inventory, dependency advisories, codec availability, memory and APK size must be recorded from built artifacts. The bundled engine is not silently updated at runtime. This may limit current website support; secure, reproducible updates require a new audited application build.

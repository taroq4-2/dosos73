# Neon redesign implementation

Baseline: e79fe4f, Compose/Kotlin, min29/target35, current yt-dlp/FFmpeg unchanged. The earlier 1.0.1 reliability fixes are retained. Native source requests remain behind the authenticated public-address proxy. No extra runtime dependency or permission is planned.

Reference mapping: deep navy canvas, D mark/cyan-blue-purple wordmark, rounded URL pill, source shortcuts, four tool tiles, fixed four-item navigation; compact headers in downloads/files; real filtered task and file lists. No mock phone frame or fabricated transfer list.

Implementation: shared theme/icons/components; home; task queue and direct-stream pause/resume while app process lives; persistent completed-file index and scoped-storage discovery; library actions; settings/about/contact; screenshot/device verification. Native extractor tasks retain cancel/retry, because safe resumable pausing is not supplied by that engine. UI must not advertise unsupported native resume.

Acceptance: baseline and final JVM/lint/build; queue/file-action device tests; Arabic/English navigation and screenshots; actual direct transfer; signed APK from GitHub installed as an update; README/screenshots/release updated after verification. Process-death interrupted transfers require explicit restart; no automatic background network on launch.

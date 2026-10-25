# V4 baseline — 2026-10-25

Existing project retained. Baseline commit 5354664 on main, recoverable branch baseline/v0.1.0-before-v4. Work branch fix/youtube-v4. Version 0.1.0-dev, Kotlin/Compose, wrapper+FFmpeg 0.18.1, QuickJS explicitly injected by wrapper. MediaEngine selects format IDs, stages files privately, then OutputStore validates and publishes using MediaStore/SAF. DownloadService reduces native errors to generic messages. Prior tests verify direct/generic-file downloads, NOT YouTube.

Reproduction on installed API35 x86_64 APK using Blender Big Buck Bunny YouTube aqz-KE-bpKQ: extraction succeeded; download failed after 40.981s with `HTTP Error 403: Forbidden` from MediaEngine.download. Merge/storage not reached. This establishes the failing stage for this test; it does not prove why the server refused the stream or that the user's particular URL has the same cause.

Original layout inspected against supplied references. No UI redesign started before reproducing functional failure. Initial investigation: wrapper includes QuickJS, but engine freshness/EJS and exact native versions require inspection. No missing-JavaScript-runtime claim is made solely from the screenshot.

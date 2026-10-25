# YouTube investigation — V4

Status: **NOT FIXED**. A complete authorized YouTube download has NOT passed on the Android test device. This report distinguishes reproduced facts from hypotheses.

## Baseline

Commit 5354664, API35 x86_64 emulator, wrapper 0.18.1. Test: Blender Big Buck Bunny (`aqz-KE-bpKQ`). Actual installed APK extracted formats successfully, entered native download, then returned `HTTP Error 403: Forbidden` after 40.981 seconds. Merge, output validation and MediaStore publication were not reached. The user's screenshot URL was not downloaded; this test reproduces its failure class, not its exact media.

Confirmed application defect: native transfer exceptions were collapsed into a generic download-or-verification message. That hid the observed HTTP403 and incorrectly suggested storage might have been reached. The cause of the remote 403 is not conclusively established by this evidence. No claim is made that storage or missing FFmpeg caused the user's failure.

## Controlled engine update

Pinned official yt-dlp 2026.10.21 zipapp, SHA256 `b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14`. It contains EJS 0.8.0. Wrapper 0.18.1 already supplies QuickJS via --js-runtimes and FFmpeg path; they were not simply missing. Existing Python is 3.12.11, observed inside Android during runtime inventory.

The APK supplies the engine resource; BundledEngine verifies SHA256 and atomically installs the APK asset before wrapper initialization, including on upgrades from an old cached engine. No executable network updater and no remote EJS components are enabled. Wrapper/native versions remain unchanged to isolate the experiment.

Same YouTube test with the updated engine returned TLS record errors during webpage retrieval and then an explicit sign-in/human-verification requirement. This is an unresolved external restriction and/or network compatibility condition; we did not supply cookies, spoof tokens, disable TLS validation, change identities or bypass the challenge. Updating the engine alone is NOT a verified fix.

## Implemented diagnostic and integrity improvements

- PipelineError: stage-specific classification from actual exceptions. HTTP403/429, login-required, format-unavailable, timeout, merge, verification and write failures are separated.
- Diagnostics: debug-only allowlisted JSON events; no raw stderr, URL, URI, filename, cookies or signed query values are logged.
- MediaEngine: preserves interrupted/cancellation exceptions, tracks native transfer versus merge stage, bundles pinned engine/EJS.
- DownloadService: distinguishes validation and publication; never reports success at a 100% transfer callback.
- OutputStore: validates expected audio and source-declared height in addition to duration/video presence.
- MainViewModel/MainActivity: clear stale format selections after failure; show specific failure messages in Arabic/English.
- Regression tests: exact HTTP403 classification, no inference from arbitrary '403' in text, stage preservation, authentication/rate-limit/format errors, unchanged direct/generic downloads and native component execution.

## Gate and next required evidence

A permitted environment where YouTube serves the authorized test stream is needed to establish the before/after download result, audio/video merge, saved output and external playback. Android16, physical ARM devices and the full 20-review matrix remain pending. Under the requested strict phase order, UI/About/Contact redesign remains pending until the YouTube gate passes or the user authorizes parallel work. No stable release or YouTube-success claim is authorized by the available evidence.

Sources: https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.21 and https://github.com/yt-dlp/yt-dlp/wiki/EJS. See docs/evidence/v4-* for actual recorded test output. Errors from prior failed experiments are retained and not replaced by success from unrelated endpoints.

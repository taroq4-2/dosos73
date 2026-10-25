# YouTube investigation — V4

Status: **NOT FIXED**. A complete authorized YouTube download has NOT passed on the Android test device. This report distinguishes reproduced facts from hypotheses.

## Current preview update

The user subsequently requested removal of the size ceiling and completion of the remaining prompt. Version 0.2.0-preview therefore includes the light UI and About/Contact pages. The former 2GiB guards were removed from DirectDownloader, yt-dlp arguments, and OutputStore. Nonempty/media integrity checks remain. Historical size-limit observations below describe the earlier implementation, not the preview.

Two new API35 live suites still encountered `[SSL] record layer failure` on the non-YouTube generic video fixture, after earlier runs of that fixture had passed. This demonstrates that TLS failures are not exclusive to YouTube in this environment; it does not establish their exact cause. The generic failure is recorded rather than discarded. A specific NETWORK_TLS message was added, with authentication taking precedence when both warnings and an explicit sign-in challenge are present. Direct download, MediaStore, UI and deterministic tests passed separately. YouTube remains NOT FIXED and no stable approval is issued.

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

### Follow-up transport isolation

The user explicitly retained the YouTube-first gate. No UI/About/Contact work was started.

`ProxyTransportTest` passed on API35 x86_64: a public video fixture downloaded directly over HTTPS, through three Java CONNECT transfers, and through the bundled Python urllib CONNECT path. All five copies had identical byte counts and SHA256. TLS certificate verification remained enabled. No YouTube challenge was retried, cookies supplied, or production proxy restrictions removed. See `evidence/v4-proxy-transport.txt` and `evidence/v4-proxy-test.txt`.

This rules out reproducible transport corruption for that fixture, but does not establish compatibility with YouTube's TLS endpoints or identify the cause of its 403/challenge. The user has not yet checked whether their failing video plays in a signed-out private browser on their phone. That manual observation remains useful to distinguish a general source access requirement from an app-specific failure; browser success alone would not establish downloader success.

### File-size hypothesis checked

The application does enforce `Safety.MAX_BYTES = 2147483648` (2 GiB, approximately 2.15 decimal GB). DirectDownloader checks both declared Content-Length and streamed byte count; the native extractor receives `--max-filesize` with that value; OutputStore rejects a final file above it. Separate audio/video streams can each pass the native per-stream limit and still exceed the final merged-file limit. Unknown source sizes also cannot be assumed to pass until transfer/validation completes.

Two deterministic HTTP tests were added: a declared 2147483649-byte response is rejected before writing; a declared 2147483648-byte response passes the size guard and then correctly fails as truncated when the fixture sends only one byte. These are boundary tests, not claims of an actual 2 GiB successful download. All 20 JVM tests passed.

The recorded YouTube baseline failed with HTTP403 during transfer, and the updated engine encountered an extraction-time sign-in challenge. Neither log reports a max-filesize rejection. The user's original video's selected stream sizes are not known, so size cannot be excluded for that distinct case. No size limit was raised: doing so would not repair the observed server refusal. The successful transport fixture was 5510872 bytes and is not evidence of large-file acceptance.

A permitted environment where YouTube serves the authorized test stream is needed to establish the before/after download result, audio/video merge, saved output and external playback. Android16, physical ARM devices and the full 20-review matrix remain pending. Under the requested strict phase order, UI/About/Contact redesign remains pending until the YouTube gate passes or the user authorizes parallel work. No stable release or YouTube-success claim is authorized by the available evidence.

Sources: https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.21 and https://github.com/yt-dlp/yt-dlp/wiki/EJS. See docs/evidence/v4-* for actual recorded test output. Errors from prior failed experiments are retained and not replaced by success from unrelated endpoints.

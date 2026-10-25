# 0.2.0-preview test results — 2026-10-25

## Verdict

PARTIALLY FIXED overall; YouTube is **NOT FIXED**. User-requested removal of the fixed media-size cap, light UI, About and contact form are implemented. No stable release approval.

## Executed evidence

- Local Gradle: testDebugUnitTest, lintDebug, assembleDebug, assembleDebugAndroidTest and assembleRelease passed. 23 JVM tests, zero failures (10 direct, 5 classification, 6 safety, 2 contact).
- CI [40214261668](https://github.com/taroq4-2/dosos73/actions/runs/40214261668), application source c8ca504: build, Android15/API35 and Android16/API36 x86_64 jobs passed. Device suite has 12 discovered tests, 3 opt-in live tests skipped and 9 executed. This is not evidence of YouTube downloading on either OS.
- Local API35 full live suite: 12 tests reported, one live transport test skipped, one native generic download failed with TLS record-layer errors. Other executed tests passed: direct download/publication, seven storage/format/single-flight cases, runtime inventory and contact navigation/draft retention. Failures preserved in v4-preview-device-*.txt.
- Focused direct download + playback and runtime suite passed: 2 tests, 38.539 seconds. Public W3 Sintel trailer saved as 4,372,373 bytes through MediaStore; Android MediaPlayer opened it with a real display surface and playback advanced. Exact dimensions/duration/position are in v4-direct-playback.txt. This is not a YouTube file or external-player UI test.
- First playback attempt used no display surface and a fixed one-second wait; it failed to observe advancement. The test was corrected to use a real surface and bounded condition wait. Failure retained in v4-playback-first-attempt.txt.
- Size behavior: HTTP declared size above 2GiB enters transfer (fixture then fails intentionally as truncated). A sparse 2,147,483,649-byte file passes generic-file verification. No multi-gigabyte network download is claimed.
- Proxy integrity fixture: 5,510,872 identical bytes via direct HTTPS, three Java CONNECT transfers and Python CONNECT. Does not eliminate intermittent endpoint/network/native TLS incompatibility.
- Native ELF LOAD alignment checked for all ten APK lib/*.so files: at least 16KiB. Extracted runtime package dependencies and a physical 16KiB device remain unverified.
- Six actual screenshots captured from the installed API35 application in screenshots/. Direct download content is identified accurately, not presented as YouTube success.

## Environment and artifact

Android15 x86_64 Medium_Phone_API_35 emulator; Android16 x86_64 GitHub emulator. minSdk29/targetSdk35. Wrapper0.18.1, Python3.12.11, yt-dlp2026.10.21, EJS0.8.0, FFmpeg7.1.1, packaged QuickJS execution passed. Runtime initialization measurement refers to already installed files; see v4-runtime-preview.txt.

Installable APK: 137,162,099 bytes, Android Debug-signed; SHA256 `4b861d5edcc9d76075a52b9f2668349a6f19bb69c88ce7bdec92dbee7149d1ef`. Optimized unsigned release artifact is not the installable download. No production signing key was introduced.

## Open gates

YouTube standard video/audio/1080p/merge/share-to-completion and external playback acceptance; generic native TLS reliability; physical ARM; complete SAF/provider and process-death recovery; independent review; comprehensive native license/source audit; TalkBack and large-font matrix; sustained download/merge performance. No email was sent by tests. The contact intent/fallback has source and input/navigation tests, not a full installed-mail-client matrix.

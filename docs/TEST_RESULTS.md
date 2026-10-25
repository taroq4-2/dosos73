# Verification evidence

Status recorded during development on 2026-10-25. This report is updated after checks finish.

## VERIFIED — COMPLETED

- Debug APK compilation completed locally with Gradle 8.9, AGP 8.7.2, Kotlin 2.0.21 and JDK 21.
- 14 JVM tests passed (SafetyTest: 6; DirectDownloaderTest: 8), zero failures/errors. These include real MockWebServer streaming, unknown-length responses, interrupted transfers, partial-response rejection, HTML/empty output rejection and HTTPS downgrade rejection.
- Public GitHub repository created at https://github.com/taroq4-2/dosos73.

## FAILED — REQUIRES FIX

- First compilation found an OkHttp DNS interface incompatibility; fixed using an explicit Dns implementation and debug build passed.
- First release shrink failed because Jackson references optional desktop JavaBeans/XML classes. Narrow optional-class rules added; release rebuild is pending evidence.

## NOT TESTED — EVIDENCE MISSING

- Social platform matrix (YouTube, TikTok, Instagram, Facebook, X, Snapchat, Telegram).
- SAF provider behavior, full-disk failures, process-death recovery, service stop/timeout, large fonts, dark mode, multiple physical devices and 16 KB memory-page execution.
- Release signing and native binary/license/source-correspondence audit.

## In progress

Android 15 / API 35 x86_64 emulator instrumented checks, lint, release build and opt-in live downloads.

Test invocation: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease connectedDebugAndroidTest`.
Live cases are opt-in (`-e live true`, `-e liveMedia true` when invoking instrumentation), because external site behavior must not be disguised as deterministic CI coverage.

Raw reports are generated in app/build/reports and uploaded by CI; selected evidence is retained under docs/evidence.

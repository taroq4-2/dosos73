# Charter baseline and execution record

Reference: v1.1.2 / b50564692f2c902a9cf7b051ab5736613272b59a. Work branch: feature/reliable-download-platform. No reference tag replacement. Proposed feature release: 1.2.0 (code 9), subject to validation, following the existing major/minor/patch changelog.

## Existing features

Direct downloads, extracted actual media formats, sequential persistent queue, transient direct pause, active-task retry after process death, file library/search/sort/rename/delete, external open/share, Arabic/English UI, signed architecture-specific APKs. Those are baseline capabilities, not new feature claims.

## Measured baseline

ARM64 APK: 59,344,182 bytes. ZIP entries: native compressed 50,151,628 bytes; DEX 4,239,252; resources/other 4,880,623 (ZIP overhead separate). Native binaries dominate; resource cleanup alone cannot remove most of the package.

API35 x86_64 emulator, installed signed 1.1.2: three force-stop cold activity launches via am start -W: 1355, 454, 666 ms. Single immediate memory snapshot: TOTAL PSS 38,575 KiB, RSS 124,616 KiB. These are small diagnostic samples, not a benchmark guarantee. CPU, battery and population failure rate: not measured. Earlier attempt found no running device and was not counted. Baseline test evidence is in RELEASE_VALIDATION.md.

## Compatibility decision

Android 10/API29 remains the minimum: existing MediaStore Downloads and scoped-storage implementation depends on it. The native wrapper actually contains armeabi-v7a, arm64-v8a, x86 and x86_64 libraries. New default package includes all four; individual -PtargetAbi builds remain available for smaller downloads. Universal means these supported CPU architectures on Android10+, not every Android version/device/TV or a guarantee of OEM behavior. Verify native execution where hardware/emulators exist; do not relabel packaging checks as execution tests.

## Review workstreams

The requested 30 roles are covered as engineering review domains, not 30 real people or 30 independent runs. Three delegated implementation/review workstreams cover transport, lifecycle/storage, and UI; coordinating review covers cryptographic journals, publication integrity, media metadata, build, compatibility and acceptance. No measured improvement claim before comparison.

See CHARTER_FEATURES.md and NETWORK_CHARTER_REVIEW.md for design/status; remaining final acceptance is pending.

## Integration checks, 2026-10-28

First compile found context-receiver/type visibility/Dns-interface errors and mixed UTF-8/Windows-1256 Arabic resource encoding. Corrected without disabling checks. Second device pass caught canonical duplicate URLs (explicit :443 versus default port); canonicalized validated HttpUrl before deduplication. Re-run: 58 JVM tests, zero failures; local API35 instrumentation OK(37 tests), including skipped opt-in diagnostics (not 37 live-network checks). Debug lint/build passed. Release build and live acceptance still pending.

Controlled MockWebServer comparison: 4,194,304 bytes; per-connection limit262144 bytes/50ms applied equally; sequential [794,781,784] ms, parallel [376,450,378] ms; SHA256 matched each run. Initial comparison accidentally left sequential unthrottled and was rejected, then corrected. This artificial per-connection-limited result does not predict internet performance or battery use.

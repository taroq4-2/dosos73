# Verification — 2026-10-25

VERIFIED: Debug and optimized unsigned release builds succeeded (Gradle 8.9, AGP 8.7.2, Kotlin 2.0.21, JDK21). 14 JVM tests passed. Five instrumented tests passed on Android15 API35 x86_64, including both opt-in live downloads, in 22.133 seconds. Android Lint: zero errors, 12 warnings (dependency/branding and compatibility advisories remain).

Direct download: https://media.w3.org/2010/05/sintel/trailer.mp4 downloaded through foreground service, validated, saved to MediaStore and opened for reading via content URI.

Native download: https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4 analyzed and downloaded by yt-dlp and validated by Android metadata parser. This generic-file success does NOT prove social-platform support or separate-stream merging.

Three additional device tests cover malformed/empty media, format filtering and generic-original metadata. Four real Arabic captures are in screenshots/. Completion screenshot visually inspected. Evidence: docs/evidence/.

FIXED: Kotlin DNS interface compile error; optional Jackson desktop-class R8 error; generic metadata without formats array rejected; status card below controls; Arabic URL direction. Release build now passes.

FAILED/UNVERIFIED: W3Schools native test TLS error, W3 native test HTTP403 challenge, Vimeo login-required response. No bypass attempted. Initial GitHub build failed on retired SDK tools package; explicit platform-tools configuration fixes the cause, remote rerun pending.

NOT TESTED: social-platform matrix, physical ARM devices, 16KB execution, separate-stream merge, conversions, SAF provider/error matrix, process death, timeout, cancellation stress, TalkBack, large fonts, external-player Open File action. Readable saved URI is verified, external player is not.

BLOCKED for production: signing, complete native license/corresponding-source audit, advisory scan, independent QA and SECURITY.md issues. No v1.0 release published.

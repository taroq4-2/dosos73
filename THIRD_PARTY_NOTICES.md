# Licensing inventory — development preview

V4 investigation branch pins the official yt-dlp 2026.10.21 zipapp with EJS 0.8.0 inside the APK. Original yt-dlp/EJS code uses Unlicense; the zipapp additionally includes ISC/MIT components. Upstream notices are preserved in docs/licenses/. SHA256 is recorded in BundledEngine.kt and YOUTUBE_DOWNLOAD_ROOT_CAUSE.md. This does not complete the native wrapper/FFmpeg audit.

First-party Kotlin, resources and build scripts are offered under GPL-3.0-or-later; see LICENSE. This does not relicense dependencies or third-party artwork.

| Component | Version | Declared upstream license / source |
|---|---|---|
| youtubedl-android library + ffmpeg | 0.18.1 | GPL-3.0, https://github.com/yausername/youtubedl-android/tree/0.18.1 |
| yt-dlp bundled by wrapper | bundled version requires on-device inventory | Unlicense for original project; bundled components may differ, https://github.com/yt-dlp/yt-dlp |
| FFmpeg bundled by wrapper | native binary inventory pending | LGPL/GPL depending on build configuration; https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md |
| AndroidX/Compose | pinned in app/build.gradle.kts | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin/coroutines | 2.0.21 / 1.9.0 | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp/Okio | 4.12.0 / transitive | Apache-2.0, https://github.com/square/okhttp |
| JUnit | 4.13.2, tests only | EPL-1.0, https://github.com/junit-team/junit4 |
| Gradle Wrapper | 8.9 | Apache-2.0, https://github.com/gradle/gradle |

The full transitive/native source-correspondence audit is not complete. Public production binary distribution remains blocked until the exact bundled native licenses, notices and corresponding-source obligations are verified. A linked upstream repository alone does not establish complete compliance for every binary.

## User-supplied branding

`branding/original.jpg` is preserved byte-for-byte from the supplied artwork. `logo.png` is a deterministic crop; Android assets are resized from that crop. No replacement logo was generated. The artwork contains third-party social platform marks. Those marks are not licensed under the application code license and do not indicate affiliation or endorsement. Rights and brand-guideline clearance for public binary distribution have not been established. No unrestricted reuse grant is made for branding.

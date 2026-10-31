# Third-party notices and source inventory

The application pins the official yt-dlp 2026.10.21 zipapp with EJS 0.8.0 inside the APK. Original yt-dlp/EJS code uses Unlicense; the zipapp additionally includes ISC/MIT components. Upstream notices are preserved in docs/licenses/. SHA256 is recorded in BundledEngine.kt. This does not complete the native wrapper/FFmpeg audit.

First-party Kotlin, resources and build scripts are offered under GPL-3.0-or-later; see LICENSE. This does not relicense dependencies or third-party artwork.

| Component | Version | Declared upstream license / source |
|---|---|---|
| youtubedl-android library + ffmpeg | 0.18.1 | GPL-3.0, https://github.com/yausername/youtubedl-android/tree/0.18.1 |
| yt-dlp / EJS pinned by application | 2026.10.21 / 0.8.0, verified on API35 | Unlicense for original projects; ISC/MIT bundled notices in docs/licenses, https://github.com/yt-dlp/yt-dlp |
| FFmpeg in universal delivery | 9.0.2, commit 946fcce07b6dcd0331c8cc609192aeff5e1924f8 | LGPL-2.1-or-later configuration, CONFIG_GPL=0 and CONFIG_NONFREE=0; [pinned recipe](tools/build_compact_ffmpeg.sh). Untransformed developer APKs retain wrapper FFmpeg 7.1.1. |
| Python / QuickJS bundled by wrapper | Python 3.12.11; QuickJS execution verified | Native/transitive license source correspondence remains under review; wrapper source above |
| AndroidX/Compose | pinned in app/build.gradle.kts | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin/coroutines | 2.0.21 / 1.9.0 | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp/Okio | 4.12.0 / transitive | Apache-2.0, https://github.com/square/okhttp |
| Jackson core/databind / annotations | 2.22.4 / 2.22 | Apache-2.0, https://github.com/FasterXML/jackson |
| Commons Compress / IO / Lang | 1.27.1 / 2.19.0 / 3.18.0 | Apache-2.0, https://commons.apache.org/ |
| JUnit | 4.13.2, tests only | EPL-1.0, https://github.com/junit-team/junit4 |
| Gradle Wrapper | 8.9 | Apache-2.0, https://github.com/gradle/gradle |

The full transitive/native source-correspondence audit is not complete. The inventory does not claim a completed legal audit. Exact upstream build recipes are linked below; component licenses remain applicable independently of the first-party license.

## User-supplied branding

The current neon artwork was supplied by the project owner. The source image is retained in branding/neon-reference.png as a reference. Both the UI and launcher now draw the corresponding vector mark; no full-resolution bitmap is decoded for the in-app logo. Artwork is not covered by the code license; no unrestricted third-party reuse or independent trademark clearance is asserted.

Tajawal (Regular/Medium/Bold), copyright 2018 Boutros International, SIL Open Font License 1.1. Unmodified fonts from https://github.com/google/fonts/tree/main/ofl/tajawal ; full license in docs/licenses/Tajawal-OFL.txt and the APK raw resources.

## Compact engine candidate with MP3

The compact candidate replaces the wrapper FFmpeg binaries with FFmpeg 9.0.2 (commit 946fcce07b6dcd0331c8cc609192aeff5e1924f8), LGPL-2.1-or-later configuration, and LAME 3.100, LGPL-2.0-or-later. The pinned source URLs, SHA256 and cross-build recipe are in tools/build_compact_ffmpeg.sh. LAME license: docs/licenses/LAME-3.100.txt. Official source: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz . Native build artifacts retain configurations and licenses. This candidate remains subject to the documented acceptance gates.

## Artifact-bound inventory

RC5 release assets include a CycloneDX 1.6 SBOM with resolved Maven build-input hashes and every ELF hash from the actual APK. Unknown/inherited/native license correspondence is explicitly marked. The corresponding FFmpeg and LAME source archive, per-ABI configurations, notices and pinned recipe are supplied separately from the APK. No native codecs, CPU targets or source restrictions are changed by sharing 642 identical regular Python files; per-ABI symlinks and ELF payloads are preserved.

## 1.2.0 preparation: Python native correspondence

The next universal candidate replaces Readline/History with pinned8.3.6 official packages. GDBM1.26-1 ELF and Mutagen1.47.0 Python sources were matched byte for byte to official package/source inputs. Readline/History and GDBM are GPL-3.0-or-later; Mutagen is GPL-2.0-or-later. Source-only archives, licenses, pinned recipes, patches and limits are described in [native evidence](docs/launch/NATIVE_SOURCES.md). CPython producer build provenance remains incomplete. These materials are distributed separately and add no APK download size. The source-only archive must accompany an official replacement release.

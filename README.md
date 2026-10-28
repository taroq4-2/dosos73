> Development branch: **1.2.0-rc.1** adds verified direct resume, optional two-connection downloads, batch review, privacy controls and universal Android10+ packaging. [Scope, tests and remaining work](docs/CHARTER_DELIVERY.md). Stable download below remains1.1.2.

<div align="center">
<img src="branding/neon-reference.png" width="160" alt="dosos73 logo" />

# dosos73
**Download open-source OS73**

An open-source Android media downloader with an Arabic-first, bilingual interface.

[العربية](README-ar.md) · [Download ARM64 APK](https://github.com/taroq4-2/dosos73/releases/download/v1.1.2/dosos73-1.1.2-arm64-v8a.apk) · [All releases](https://github.com/taroq4-2/dosos73/releases) · [Website](https://os73.com)

[![Android verification](https://github.com/taroq4-2/dosos73/actions/workflows/android.yml/badge.svg)](https://github.com/taroq4-2/dosos73/actions/workflows/android.yml)
</div>

## Download and install

**Version 1.1.2 · Android 10+ · no ads or accounts.**

| Package | Device |
|---|---|
| [ARM64 APK](https://github.com/taroq4-2/dosos73/releases/download/v1.1.2/dosos73-1.1.2-arm64-v8a.apk) | Modern ARM64 phones, including Samsung S23 Ultra |
| [x86_64 APK](https://github.com/taroq4-2/dosos73/releases/download/v1.1.2/dosos73-1.1.2-x86_64.apk) | Compatible Intel/AMD devices and emulators |
| [Checksums](https://github.com/taroq4-2/dosos73/releases/download/v1.1.2/SHA256SUMS.txt) | SHA-256 identities of published APKs |

Open the APK and allow installation from your selected browser/file manager when Android asks. Production updates use the same signing certificate and can install over previous official releases without clearing data. Older debug-signed previews cannot update in place. Choose the correct architecture; 32-bit devices are not supported. No runtime-library download is required when pasting a link.

## Features

- Paste or share one public URL; analyze actual available formats and qualities.
- Download supported video, audio and direct files; merge compatible video/audio streams.
- Sequential queue, saved failed tasks and retry; real progress, speed and file size when available.
- Pause direct downloads within the current session; cancel/restart native downloads.
- Durable active-task recovery after process death, without a permanent monitoring service.
- App file library with search, filters, sorting, thumbnails, open, share, rename and confirmed deletion.
- MediaStore Downloads or a user-authorized folder; storage checks and verification before success.
- Arabic RTL and English LTR, dark navy interface, Tajawal typography and local language settings.
- No fixed media-size cap; storage capacity, timeouts and source restrictions still apply.

## Screenshots

Authentic captures from the running app. The release validation report identifies the build and environment; older release evidence is labeled as such.

<img src="screenshots/current-home-ar.png" width="230" alt="Arabic home" /> <img src="screenshots/current-downloads-ar.png" width="230" alt="Downloads" /> <img src="screenshots/current-files-ar.png" width="230" alt="Files" />

<img src="screenshots/current-settings-ar.png" width="230" alt="Settings" /> <img src="screenshots/current-home-en.png" width="230" alt="English home" />

## Use

1. Paste a URL or share it to dosos73.
2. Wait for analysis, then select media type, quality and format.
3. Start the download; monitor it in Downloads.
4. Open the verified saved file from its notification or the file library.

Recovery retries from the beginning, not the last byte. Android Settings → Force stop prevents automatic restart until reopening the app. Failed jobs stop the queue for user review. Removing history is distinct from deleting a file. Download only content you are authorized to save.

## Technical details

| Setting | Value |
|---|---|
| Application ID | `com.os73.dosos73` |
| Version / code | `1.1.2` / `8` |
| Minimum / target / compile SDK | `29` / `35` / `35` |
| UI | Kotlin 2.0.21, Jetpack Compose, Material 3 |
| Network | OkHttp 4.12.0; public-address validation |
| Media engine | yt-dlp 2026.10.21 + EJS 0.8.0, wrapper 0.18.1 |
| Processing | Bundled Python, QuickJS and FFmpeg |
| Distribution | Signed ARM64 and x86_64 APKs |

## Build and test

Use JDK 17/21, Android SDK platform/build-tools 35 and the included wrapper. Set your SDK path locally; never commit `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease -PtargetAbi=arm64-v8a
./gradlew assembleRelease -PtargetAbi=x86_64
```

Windows: use `gradlew.bat`. Set `DOSOS73_KEYSTORE` and `DOSOS73_STORE_PASSWORD` only in the authorized signing environment. Without these, the release build is unsigned and must not be published as a production APK. Required CI covers APIs 29, 33, 35 and 36. Live-network tests are separate opt-in checks, not implied by unit-test success. Always smoke-test the exact minified release artifact.

[Validation and limitations](docs/RELEASE_VALIDATION.md) · [Architecture](docs/ARCHITECTURE.md) · [Cleanup manifest](docs/CLEANUP_MANIFEST.csv) · [Changelog](CHANGELOG.md)

## Privacy, permissions and limitations

No analytics, advertising, accounts, cookies or automatic message sending. Clipboard access occurs only on Paste. Queue/history URLs and destination metadata are stored privately on the device; backup is disabled. Source websites see network requests and IP addresses. Permissions cover internet, foreground data transfer and optional notifications; no all-files, accessibility or overlay access. Public HTTP files are supported, so do not use sensitive HTTP links.

Website restrictions may prevent downloads. No promise of universal platform support, DRM bypass, playlists, forced MP3 conversion or persistent byte-range resume. No real Samsung-device or complete native supply-chain/16 KB certification is claimed. [Privacy](PRIVACY.md) · [Security](SECURITY.md) · [Known hardening work](docs/SECURITY.md).

## Contribute and license

[Contribution guide](CONTRIBUTING.md) · [Issues](https://github.com/taroq4-2/dosos73/issues) · [Source](https://github.com/taroq4-2/dosos73)

First-party code: **GPL-3.0-or-later**, see [LICENSE](LICENSE). Dependencies, fonts and supplied artwork retain their own terms; see [third-party notices](THIRD_PARTY_NOTICES.md). Complete native license/source correspondence remains under review.

### About dosos73

**Easier downloads. More control. Open source.**

dosos73 is an open-source download manager for Android, designed to provide a simple, practical experience for downloading video and audio and managing files in one place.

We are developing an app that combines ease of use with good performance, while caring about privacy and transparency and giving the community room to contribute to its development.

An open project, evolving through everyone's contributions.

**Project developer:** Tariq

**Official website:** [os73.com](https://os73.com)

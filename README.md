<div align="center">
<img src="branding/repository-banner.svg" width="100%" alt="dosos73 — Download open-source OS73" />

# dosos73
**Download open-source OS73**

[![Android verification](https://github.com/taroq4-2/dosos73/actions/workflows/android.yml/badge.svg)](https://github.com/taroq4-2/dosos73/actions/workflows/android.yml)

An open-source Android media downloader with an Arabic-first interface and English support.

[Download universal APK](https://github.com/taroq4-2/dosos73/releases/download/v1.2.0-rc.4/dosos73-1.2.0-rc.4-universal.apk) · [Releases](https://github.com/taroq4-2/dosos73/releases) · [العربية](README-ar.md)
</div>

## Download and compatibility

**1.2.0-rc.4** is a release candidate delivered as **one APK for Android 10+**, containing ARM32, ARM64, x86 and x86_64. No phone-specific package selection is required. Published checksums identify the exact artifact.

Official updates retain the existing signing certificate. Allow installation from your chosen browser/file manager when Android requests it. Pasting a link does not download additional runtime libraries.

| Package | Details |
|---|---|
| Current download | **1.2.0-rc.4** · release candidate |
| APK size | **72.76 MB** (69.39 MiB) |
| Supported Android | **10+** (API 29+) |
| Architectures | ARM32 · ARM64 · x86 · x86_64, in one file |
| Audio output | **MP3 V0** |

[SHA-256 checksums](https://github.com/taroq4-2/dosos73/releases/download/v1.2.0-rc.4/SHA256SUMS.txt) · [Verification](docs/VERIFICATION.md) · [Report a bug](https://github.com/taroq4-2/dosos73/issues/new/choose)

## Features

- Paste or share a public link and select actual available formats and qualities.
- Video downloads, **MP3 V0** audio extraction and compatible stream merging.
- Direct files without a fixed size cap; available storage and source restrictions apply.
- Validator-bound direct resume, a durable queue, priorities and retry.
- Review up to 20 links with per-link format/folder choices and encrypted draft recovery.
- Hash-verified publication and recovery across supported destination-creation/rename interruptions.
- Search, filter and preview saved files; distinguish confirmed local absence, denied access and unavailable storage.
- A bounded encrypted task timeline without source URLs, filenames or credentials.
- Arabic RTL, English, Tajawal typography and a dark interface, without ads or analytics.

## Use

1. Paste a public URL or share it to dosos73.
2. After analysis, select the media type, quality, format and destination.
3. Start the download and follow its real progress in Downloads/the notification.
4. Open the verified saved file from the library.

If publication needs recovery, reconnect storage and retry **the same task**. Recovery checks existing output before transferring again. Native extraction may restart after interruption; byte-range resume applies only to direct sources whose representation can be validated.

## Authentic screenshots

<img src="screenshots/home-ar.png" width="230" alt="Arabic Home" /> <img src="screenshots/downloads-ar.png" width="230" alt="Downloads" /> <img src="screenshots/media-library-ar.png" width="230" alt="Files" />

<img src="screenshots/charter-task-timeline-ar.png" width="230" alt="Actual task timeline with a successful transfer and controlled failure cases" />

Home, Downloads, Files and Settings were captured from the signed RC4 APK on an Android 15 emulator. Timeline/batch/privacy captures come from the corresponding instrumentation build; see [image provenance](screenshots/README.md).

<img src="screenshots/home-en.png" width="230" alt="English Home" /> <img src="screenshots/settings-ar.png" width="230" alt="Arabic Settings" />

## Release status

RC4 is a candidate. OS-backed scheduling, multiple active files, subtitle selection and the complete physical-device/provider matrix remain open. Emulator checks do not certify every device, codec or website.

[Remaining requirements](docs/REMAINING_REQUIREMENTS.md) · [Publication recovery](docs/PUBLICATION_RECOVERY.md) · [Task timeline](docs/TASK_TIMELINE.md) · [File availability](docs/FILE_AVAILABILITY.md)

## Questions

**Why four architectures?** Android runs on different processor families. One universal APK includes the native engines for each, and Android uses the matching set. It is not a separate edition for a phone model.

**Why is the file about 73 MB?** Offline extraction and MP3 conversion require bundled native engines. The compact FFmpeg build reduces their size; libraries are ready when you paste a link. No first-use engine download is required.

**Can every video be downloaded?** Availability depends on the source. Login, private content, DRM and regional/source restrictions are not bypassed. A source error is shown without marking an incomplete file successful.

## Build and contribute

Use JDK 17/21, Android SDK 35 and a local SDK path that is not committed.

```sh
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

Windows uses `gradlew.bat`. Ordinary Gradle builds produce the base APK. The [pinned compact-engine workflow](.github/workflows/compact-engine.yml) and `tools/` scripts build all four native targets and assemble the universal delivery. Align, sign and verify the assembled APK before publication. Signing keys/password files are excluded from the repository and source archive.

## Privacy and license

No in-app accounts, ads or analytics. Clipboard access occurs on Paste. Private task journals are encrypted on-device and backup is disabled. Downloads and library metadata follow the user's selected storage. External sources see network requests and IP addresses.

First-party code is **GPL-3.0-or-later**. Dependencies, fonts and artwork retain their own terms. Download only content you are allowed to save.

[License](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md) · [Privacy](PRIVACY.md) · [Contributing](CONTRIBUTING.md)

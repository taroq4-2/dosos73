# RC4 verification / التحقق من RC4

**Date / التاريخ:** 2026-10-29
**Artifact / الملف:** `dosos73-1.2.0-rc.4-universal.apk`
**Application source / مصدر التطبيق:** `162b2be29abe422b455d3cb9f50f523c29d59d16`
Documentation and screenshots were refreshed afterward without application-code changes.

| Property | Verified value |
|---|---|
| Bytes | 72,758,008 (72.76 MB; 69.39 MiB) |
| SHA-256 | `b4da6a414cd3ec47db66f789a10e3a19b31ac5d1f3bf11a479d6dd7d3a32e7f1` |
| Signing certificate SHA-256 | `bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9` |
| Package | `com.os73.dosos73` |
| Version | `1.2.0-rc.4` / code `10` |
| Android | min API29; target API35 |
| Native architectures | armeabi-v7a, arm64-v8a, x86, x86_64 |
| Release safety | Minified release; debug fault-injection provider absent |

## Automated source checks

- 62 JVM tests, Android lint and release assembly passed locally.
- Full local API35 x86_64 instrumentation: OK (58 tests); optional live network tests are separate.
- GitHub build plus device suites on APIs **29, 33, 35 and 36** all succeeded for the exact application commit: [run 40945911520](https://github.com/taroq4-2/dosos73/actions/runs/40945911520).
- Compact FFmpeg/LAME native build succeeded for all four ABIs: [run 40928395879](https://github.com/taroq4-2/dosos73/actions/runs/40928395879). Native source/build recipe is unchanged in RC4.
- ELF scan covered 608 files, including nested native archives: zero 64-bit PT_LOAD 16KB alignment/congruence failures. 286 smaller-aligned 32-bit entries are informational. This is not physical 16KB-device certification.

## Signed release acceptance on API35 x86_64

| Scenario | Observed result |
|---|---|
| Official stable setup | Published 1.1.2 x86_64 installed on an independent fresh AVD; signature and published checksum matched. English selected in Settings. |
| Real file before update | Downloaded the W3C Sintel trailer, 4,372,373 bytes; SHA-256 matched reference below. |
| Update to RC4 | Signed universal APK installed with replacement, without uninstalling or clearing data. Version code 10 confirmed. |
| Preservation | English setting, prior library entry and saved file retained; prior file SHA-256 unchanged. |
| New download after update | New W3C file published and indexed; 4,372,373 bytes and the same reference SHA-256. |
| Local playback | App preview rendered frames; platform player reported H264 video 854×480 and AAC audio. |
| Fresh app state | RC4 opened in an independently created Android test profile with no prior app data. This is a fresh-profile check, not a separate APK uninstall/reinstall test. |
| YouTube analysis | Public video oexYI9IGKkM analyzed after fresh-profile native initialization; actual choices through 2160p displayed. |
| YouTube transfer | Selected MP4 720p completed, about 29.39 MiB. Preview reported VP9 1280×720 plus AAC audio and rendered frames. |
| YouTube MP3 | Completed real MP3 V0 output, about 7.45 MiB; app playback reported audio/mpeg and c2.android.mp3.decoder. Foreground service list was empty after completion. |
| Public release re-download | Downloaded the APK from the public RC4 release URL; all 72,758,008 bytes and SHA-256 matched. Installed that downloaded file as an update, opened the app and rechecked the preserved trailer hash. |
| Crash log | No AndroidRuntime crash in the observed release acceptance steps. |

W3C fixture: https://media.w3.org/2010/05/sintel/trailer.mp4
SHA-256: `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`.

## Recovery and safety evidence

- Direct-transfer process kill and separate emulator reboot/user relaunch tests produced one final file per task, reference hash preserved and private staging cleared. These earlier source-increment tests did not capture whether the origin answered 206; do not infer byte-range reuse from completion alone.
- Publication fault injection covers the create-before-URI-record gap, changed SAF rename URI after reported failure, rejected rename, unknown reported size, denied read/query, modified verified output and unverified temporary cleanup. See [publication recovery](PUBLICATION_RECOVERY.md).
- Timeline encryption, bounded retention, safe field set and tamper preservation tested. A live direct transfer asserted its durable stage events; [details](TASK_TIMELINE.md).
- Missing local file, denied access and unknown/unavailable provider states tested; metadata-only removal rechecks absence and preserves content; [details](FILE_AVAILABILITY.md).
- Batch review persistence survives an actual process kill without auto-enqueue; independent choices and retry state have regression coverage.

## Scope / النطاق

هذه أدلة للملف والمسارات المذكورة، وليست ضمانًا لكل الأجهزة أو المواقع أو الترميزات. RC4 إصدار مرشّح؛ لا تُعد البنود المتبقية مكتملة لمجرد نجاح البناء. راجع [قائمة المتبقي](REMAINING_REQUIREMENTS.md).

Physical Samsung/OEM power behavior, cellular/Wi-Fi transitions, all ARM runtimes/codecs, 16KB runtime, native process cancellation race, scheduling, multiple active files and subtitle sidecars remain open. No permanent watchdog, cloud extraction engine or first-paste runtime download was added.

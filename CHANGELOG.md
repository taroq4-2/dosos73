# Changelog

## 1.2.0-rc.4 — 2026-10-29
- One signed universal APK, 72,758,008 bytes, Android 10+, four architectures and real MP3 V0 conversion.
- Recover destination-creation gaps and changed SAF rename URIs using encrypted pre-creation plans and content verification.
- Persist batch review choices with storage-error retry; preserve navigation when an older draft exists.
- Add a bounded encrypted task timeline and access-aware library rows without deleting unavailable records.
- Close the proxy late-socket shutdown race; retain the native process race as open.
- Test source on Android APIs 29, 33, 35 and 36; verify signed APK upgrade from 1.1.2 on API35.
- Refresh Arabic/English documentation and current screenshots; remove superseded reports from the current tree.
- Scope and evidence: [Verification](docs/VERIFICATION.md), [remaining work](docs/REMAINING_REQUIREMENTS.md).

## 1.2.0-rc.1
- Verified direct HTTP resume, opt-in two-range download and encrypted durable queue with priorities.
- Network-bound Wi-Fi/unmetered controls, batch review, audio quality metadata, local preview, collections and privacy controls.
- Universal four-ABI package plus smaller per-ABI APKs for Android10+.
- Beta scope and remaining charter work: [current status](docs/REMAINING_REQUIREMENTS.md).

## 1.1.2
- Cleaned superseded assets, old screenshots and historical generated reports from the current tree; history and license notices retained.
- Equivalent English and Arabic project pages, contribution guidance and explicit release evidence.
- Expanded required CI device matrix to Android APIs 29, 33, 35 and 36.
- Preserves 1.1.1 runtime repair, architecture-specific packaging and active-task recovery.

## 1.1.1
- Fixed release-only runtime initialization crash caused by optimized reflective ZIP classes.
- Added ARM64/x86_64 packages, active-task retry after process death, safer format ordering, server filenames and destination preflight.
- See [historical release](https://github.com/taroq4-2/dosos73/releases/tag/v1.1.1) for tests and remaining scope.

## 1.1.0
- Neon Arabic/English interface, persistent queue and failure history, file library and direct-session pause.

Earlier sources, release notes and evidence remain available in Git history and [GitHub Releases](https://github.com/taroq4-2/dosos73/releases).

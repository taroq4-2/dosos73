# Release notes

## Unreleased · vNext (branch `feature/dosos73-vnext-overhaul`)

### Phase B: link analysis and file downloads (P0)
- Direct links to APK, XAPK, APKS, AAB, ZIP, RAR, 7Z, PDF, Office, EPUB, EXE/MSI/DMG/ISO, images, audio and video are recognised as files. Previously anything outside a short MIME list (including `application/vnd.android.package-archive`) was sent to the media engine and usually failed.
- The type is proven from the file's first bytes, not just its headers or name. An HTML page is never saved as an APK, and a declared APK whose bytes are another format is refused. The check runs again after the download, before publishing.
- Analysis uses one bounded ranged request instead of HEAD followed by fallbacks. It shows visible stages (checking, connecting, reading, fetching media), can be cancelled, and has its own timeouts.
- Failures are specific and immediate: not found (404), expired link (410), sign-in required, rate limited with Retry-After, a web page instead of a file, or a wrong file type.
- GitHub release and repository pages list their release files through the documented GitHub REST API. F-Droid package pages resolve to the suggested APK through F-Droid's public API.
- Saved Android packages are published as `application/vnd.android.package-archive` and are never installed automatically. The result card says so.
- Server file names are stripped of bidi/format characters, which blocks extension spoofing; RFC 5987 ISO-8859-1 names are supported.

## 1.3.0-rc.2 · readiness candidate · 2 November 2026

- Target/compile Android 16 while retaining Android 10 minimum and four ABIs.
- Validate AAB identity, mapping, signatures and compact runtime correspondence; retain the universal APK.
- Reject malformed/foreign SAF rename-return URIs without discarding recovery evidence.
- Extract navigation/review presentation and protect horizontal display insets; scroll long transfer details.
- Add a deterministic 100-run storage recovery lab, large-text/landscape UI regression and host-side performance evidence.
- Pin CI actions and enforce Release lint, AAB and scoped dependency/build-tool advisory checks.
- Retain offline MP3 conversion, named presets and duplicate-download review from rc.1.
- This is a controlled-test candidate. Native provenance/security, physical OEM acceptance and owner-controlled Play gates remain open.

## 1.2.0 · 1 November 2026

- One universal Android10+ APK with four CPU architectures and bundled local engines.
- Video downloads, real MP3 V0 conversion, direct-file resume and optional parallel ranges.
- Durable queue controls, idempotent batch handoff, bounded analysis cancellation and network recovery.
- A bounded network wait releases the foreground service; Android schedules an eligibility reminder that opens the app for resume.
- Verified publication, safe write-ahead SAF rename recovery and retention cleanup for inactive failed-download partials.
- One-time authenticated legacy migration for historical queue/failure records; bounded encrypted journal reads and writes.
- SHA-256 file comparison, restored input/dialog state, improved accessibility semantics and adaptive controls.
- Pinned Readline/History8.3.6, artifact-bound runtime proof, ELF validation, CycloneDX inventory and native source/notice downloads.

Measured acceptance, exact size/hash, current images and coverage limits are recorded in [verification](docs/VERIFICATION.md). Older release packages are superseded by this universal delivery; source history remains available through Git commits.

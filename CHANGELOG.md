# Release notes

## Unreleased · vNext (branch `feature/dosos73-vnext-overhaul`)

### Phase E: widget and notifications
- Home-screen widget (reference: right phone) wired to the same `DownloadBoard` as the app. It shows idle, active, paused, waiting, completed and failed states, with real progress, size and speed and two buttons (New download, View downloads). It has a compact layout for small sizes, follows the app language and RTL, and uses light or dark with the system. There's no polling: it redraws when the state changes, at most every 1.5 s during a transfer. After a reboot it reads the durable queue and resume checkpoint, so a paused download keeps its real progress.
- Privacy: by default the widget shows the file type, not the name (a home screen is public). Showing names is a setting.
- Notifications are split into a silent "Download progress" channel, used only while transferring, and a "Download results" channel.
  - Progress shows name, downloaded / total, speed, time left and percentage, with Pause and Cancel.
  - Pausing leaves a notification with Resume and Cancel; previously nothing remained.
  - New completion notification has Open and Downloads; failures now get a notification with the reason.
  - The lock screen shows a generic version without the name. All actions are explicit, immutable PendingIntents.
- Notifications and the widget use the language chosen in the app on Android 10–12 too.
- After a restart, a paused download shows its real progress from the resume checkpoint in the app and the widget.

### Phase D: redesign
- New design system matching the reference: deep navy `#0B1929`, cards `#14273A`, interactive teal `#42D9CC`, light text `#E4F5F4`. A WCAG-AA light theme was added, with System / Dark / Light choices and optional wallpaper colors on Android 12+. Every screen reads one palette, and contrast is checked by tests.
- Home: centered brand, a "New download" card with paste inside the link field, a teal "Analyze link" button, and Video / Audio / Files / Downloads shortcuts. Quick settings, supported links and more tools are all real actions. The menu and crown icons from the concept were left out because nothing would sit behind them.
- Downloads: filter chips with real counts, collapsible sections, and cards with a file-type badge, downloaded / total, speed, percentage, honest ETA and state-aware actions, built on `DownloadBoard`.
- Library: video, audio, apps, documents, images, archives, programs and other categories; empty categories are hidden.
- Settings grouped into downloads, network, notifications, appearance and language, privacy and security, tools, and about. A new page lists supported links honestly, including what is not supported.
- Proper RTL: the bottom bar, headers and rows follow the language; Home sits on the right in Arabic.
- Fixed (present in 1.3.2): paused or interrupted GitHub release downloads could never resume, and every retry failed, because CDN redirects carry a fresh signed query. Resume identity is now host + path + strong ETag + exact length. Verified on a device with byte-identical SHA-256 after pause, process death and resume.
- Fixed: icon buttons announced no name to TalkBack; a paused card lost its progress; long file names broke mid-word.

### Phase C: architecture
- `MainViewModel` keeps only link/analysis orchestration. The encrypted multi-link draft actor moved, unchanged, into `BatchDraftSession`. State transitions are pure functions (`AnalysisReducer`). Engines are reached through the `LinkInspector` and `MediaExtractor` contracts.
- New `DownloadBoard` read-model merges the active transfer, queue, failures and library into one stream (status, category, real progress, honest ETA) for the redesigned screens, the widget and notifications.
- Saved files get readable names (`Report.pdf`, then `Report (2).pdf`) instead of `Report-<uuid>.pdf`. Names stay unique among the destination's files and other unfinished publications, so crash recovery remains exact. If either can't be listed, the 1.3.2 scheme is used. Existing files are not renamed.
- See `ARCHITECTURE.md` for layers and the link-to-file flow.

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

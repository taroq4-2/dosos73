# Release notes

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

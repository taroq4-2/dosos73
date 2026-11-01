# Release notes

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

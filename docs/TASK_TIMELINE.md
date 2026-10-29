# Durable task timeline — 2026-10-29

The download service records transfer start, verification start/completion, publication start/completion, publication recovery and terminal interruption/failure. These are task-bound checkpoints, not a full extractor/internal network trace. Metadata is committed through the existing authenticated encrypted AtomicFile journal. URLs, titles, destination URIs, credentials and native stderr cannot be passed to this recorder.

A single file retains at most 32 tasks and 24 events per task. It has no periodic task, polling loop or new dependency. Writes occur at stage changes, not for byte progress. Optional diagnostic write failures do not fail a download or erase an unreadable journal. A process death can omit an event that had not yet committed, but prior committed events remain readable.

The Privacy page exposes localized stages, timestamps and failure explanations. Its existing explicit history-clear action also clears this timeline, without deleting media. The selected history-day policy physically prunes expired timeline events on read/write; it is not a periodic erasure promise and does not change the existing completed-file visibility policy or failed-task retention.

Evidence:
- Three isolated instrumentation tests passed: bounded encrypted storage/allowed fields, retention rewriting/clear preserving a sentinel file, and authenticated tamper detection without overwrite.
- Full local API35 suite passed OK(55 tests), with optional network checks separate.
- An opt-in live W3C download/publication/MediaPlayer test passed and asserted durable transfer, verification and publication events for the actual task.
- Debug lint/unit build passed; the dialog now waits for loaded data in its UI test. State publication is dispatched explicitly to Main and applied through a mutable Snapshot, preventing a stale loading state after IO completion. The library availability badge has an analogous UI regression test.

Compatibility follow-up: the prior publication commit passed CI device suites on API35 and36, but API29 exposed an owner-provider permission precheck and API33 exposed a batch-navigation test timing/visibility issue. Recovery now relies on real provider queries rather than the redundant canRead check. Batch model tests have isolated storage and proper lifecycle cleanup; navigation clears keyboard focus and the regression test scrolls the actual lazy list. The corrected implementation passed the complete CI matrix on API29/33/35/36 in run40942151899 (commit6ee7bff). Later explicit Main/Snapshot UI-publication and loading assertions passed local UI tests and await their updated CI run.

![Actual timeline, including a successful live transfer and controlled failed service tests](../screenshots/charter-task-timeline-ar.png)

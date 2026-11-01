# Verified publication and rename recovery

OutputStore writes an encrypted task-specific plan before destination creation. Temporary/final names are deterministic for that task. After writing, full source/destination byte counts and SHA-256 must match before publication. A commit callback preserves COMPLETE across cancellation; a later metadata/read failure cannot misreport a committed file as an unsuccessful download.

PublicationReceipt checks the intended parent/owned MediaStore directory, recorded URI, verified bytes and planned identities. Normalized names can be accepted through a verified recorded URI within the original destination. Ambiguous, changed or unavailable verified output is preserved with its receipt; reconnect storage and retry the same task. Cleanup deletes only proven task-owned incomplete output. SAF preflight requires directory-create capability without creating an unjournaled disposable probe.

## Library renames

RenameRecovery uses one encrypted write-ahead transition under the library mutex. It records the original fingerprint, destination membership, pre-existing child IDs, requested name and collection/history metadata before invoking a SAF rename. Returned URIs pass tree/authority/identity validation before entering live state; the returned identity is durably recorded before index/preference updates. Recovery replays metadata updates without repeating the provider rename.

When a provider fails before returning its new URI, recovery accepts only a unique new identity with the requested name and exact original fingerprint, excluding every pre-existing sibling. It never chooses between identical ambiguous candidates. Cancellation closes owned verification streams and signals blocked provider queries. Cold library refresh retains unrelated durable rows, and new publications remain indexed while a different rename awaits recovery.

## Measured scope and limits

The14 RenameRecoveryRegressionTest cases plus publication race/recovery and output-safety tests exercise controlled creation, cancellation, URI changes, write failure, unavailable providers, metadata replay and byte integrity. Their results are included in the current [verification](VERIFICATION.md). The fault provider/hooks exist only in Debug and are absent from minified Release.

Provider rename and the private index cannot form one cross-provider atomic transaction. An ID-changing rename that also normalizes its name and crashes before returning may remain ambiguous. Unenumerable standalone destinations are rejected before that SAF rename path; MediaStore stable-URI rename remains separate. Full fingerprinting streams bounded buffers on IO but adds read time for large SAF rename operations. Uncooperative providers, physical removal and real ENOSPC remain broader acceptance areas; evidence/files are preserved instead of guessed identities.

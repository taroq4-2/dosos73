# Publication recovery increment — 2026-10-29

Publication now writes an encrypted, task-specific plan before destination creation. Temporary and final names are deterministic for that task. The URI is attached after creation and the verified state is committed before a provider rename/update. Recovery queries the intended parent/owned MediaStore directory, checks the expected byte count and SHA256, and finalizes the existing file. A changed SAF URI after rename can be rediscovered through the planned final name.

A verified or ambiguously renamed file is never deleted by the failure cleanup path. Incomplete, unverified output is removed only when its task-specific temporary name matches. Missing access, multiple matching destinations, or changed verified contents retain the receipt/file and produce a localized recovery-required message. The user can reconnect storage and retry the same task. SAF preflight no longer creates an unjournaled disposable probe.

MediaStore normalizes leading-dot names on the tested API35 device; the planned temporary name therefore uses a regular task-specific name while IS_PENDING keeps it unpublished. Queries explicitly include pending MediaStore rows. SAF rename uses DocumentsContract.renameDocument and its returned URI; SingleDocumentFile.renameTo is unsupported. Official API references: https://developer.android.com/reference/android/provider/MediaStore#setIncludePending(android.net.Uri) and https://developer.android.com/reference/android/provider/DocumentsContract#renameDocument(android.content.ContentResolver,android.net.Uri,java.lang.String).

Validation:
- 62 JVM tests; debug lint/build; release lint/minified universal build passed.
- 17 focused API35 x86_64 device tests passed (7 publication fault cases plus 10 output safety cases).
- Complete local device suite reported OK(52 tests); optional external-network checks are separate.
- Fault cases: MediaStore creation before URI recording, SAF creation before URI recording, rename changes URI then reports failure, rejected rename retry with unknown descriptor size, unreadable verified output, changed verified content, and cleanup of only an incomplete unverified temporary document.
- The fault provider exists only in src/debug and is protected by MANAGE_DOCUMENTS. It is absent from the release manifest. No runtime dependency was added.

Limits: Tested provider behavior is controlled and the API35 MediaStore case is real. Providers that silently normalize names, return incomplete child listings, or violate rename semantics can still require manual recovery; no claim covers every cloud/removable provider. Physical storage removal, real ENOSPC, all API/OEM variants, and real process death during each provider transaction remain additional acceptance gates. Legacy receipts preserve their earlier conservative handling. This increment does not implement scheduling, multiple active files or subtitle selection.

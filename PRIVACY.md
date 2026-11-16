# Privacy

No accounts, advertising SDKs, analytics or telemetry are implemented. URLs are sent to their destination servers and media extraction may contact the source site's public media hosts. Those services can observe the device IP and requests. The local media proxy does not inspect TLS content or persist request logs.

The app reads the clipboard when you press Paste or use the optional Quick download entry. If you separately enable the launcher fallback, opening the app from its launcher can trigger the same quick entry. Quick download reads one clipboard snapshot after the device is unlocked and the app has focus. It shows the link for review and contacts the source only after you choose Analyze. Saving the file requires you to press Start. The app has no background clipboard monitor.

Links shared by another app wait for Review by default. Settings contains a separate, optional automatic shared-link analysis switch. Enabling it allows source requests immediately after a shared link is received; it does not authorize saving a download without Start. Invalid text is rejected locally. Explicit Paste and Analyze are user-initiated source requests.

Validated native HTTP continuation stores only URL digests, strong ETags, byte counts and a prefix digest beside private no-backup partial files. It does not store signed media URLs in those checkpoint files. Changed links, validators or redirects cause a safe restart; segmented streams restart. Paused playback keeps a process-local file/position bookmark after its player and service are released; the bookmark is not sent anywhere and does not survive process termination.

The selected destination URI is stored locally. Transfers use private temporary files, then MediaStore or a user-authorized folder. Backup is disabled. The project does not collect cookies or passwords, and cannot access private account content.

The operating system and externally selected file-opening apps have their own privacy behavior. Public GitHub bug reports should not contain private links, credentials or personal files.

The waiting queue and failed-download history store source URLs, titles, selected formats and destination URIs in the app's private storage so users can review or retry tasks. Saved-file records also store local output URIs and file metadata. These records are not uploaded to us and are excluded from Android backup. Removing queue/history entries removes their corresponding local records; clearing app storage removes all app-private records but does not automatically delete completed downloads.

## Local controls

Queue, failure, batch-review and publication journals are encrypted with Android Keystore-backed authenticated encryption. Their content is needed for recovery/retry. A bounded encrypted task timeline stores stage/result/time/error codes rather than URLs or filenames. Timeline day retention physically prunes its old events when the timeline is read/written; no periodic background erasure runs.

Completed-history day selection is currently a visibility filter, not complete metadata erasure. Clearing visible history is distinct from deleting saved media. Active/resumable task files must not be erased by history visibility changes. The timeline clear action removes its journal; thumbnail cleanup removes only the app thumbnail cache. No forensic-erasure guarantee is made.

لا يجمع التطبيق حسابات أو إعلانات أو تحليلات استخدام. يقرأ الحافظة عند الضغط على «لصق» أو استخدام اختصار «التنزيل السريع» بعد تفعيله. إذا فعّلت خيار الفتح البديل من المشغّل، فقد يقرأ الحافظة عند فتح التطبيق من أيقونته أيضًا. يقرأ الاختصار الحافظة مرة واحدة بعد فتح قفل الجهاز وظهور التطبيق في الواجهة، ويعرض الرابط للمراجعة. يتصل التطبيق بالمصدر بعد اختيار «تحليل»، ويبدأ حفظ الملف بعد الضغط على «بدء». لا يراقب التطبيق الحافظة في الخلفية.

تبقى بيانات الاسترداد الخاصة مشفّرة محليًا. ترى المواقع الخارجية طلباتك وعنوان الشبكة. تصفية السجل حسب الأيام لا تمحو كل بياناته ولا تحذف ملفات التنزيل.

تنتظر الروابط المشتركة من تطبيق آخر اختيار «مراجعة» افتراضيًا. يمكن تفعيل تحليلها تلقائيًا من إعداد مستقل وواضح؛ عند تفعيله يتصل التطبيق بالمصدر عند الاستلام، ويظل بدء حفظ الملف بحاجة إلى تأكيد. يُرفض النص غير الصالح محليًا. يحتفظ الاستئناف بمعرّفات تحقق وبصمات للروابط والأجزاء في التخزين الخاص المستثنى من النسخ الاحتياطي، ولا يكتب الروابط الموقّعة نفسها في ملفات التحقق. الملفات الجزئية نفسها وبيانات فهرس المكتبة محمية بعزل تخزين أندرويد، ولا نزعم تشفير كل بايت منها.

# أدوات الوسائط / Media tools

## فيديو محفوظ إلى MP3

من الصفحة الرئيسية اختر **استخراج الصوت**، أو من قائمة ملف فيديو اختر **تحويل الفيديو إلى MP3**. اختر فيديو من الجهاز، راجع وجهة الحفظ، ثم ابدأ. يبقى الفيديو الأصلي دون تعديل. تعمل العملية محليًا، وتستخدم طابور المهام والتقدم والإلغاء والتحقق من المخرجات الموجود في التطبيق.

تحتاج العملية مساحة للنسخة المؤقتة وملف الصوت، وتستهلك المعالجة أثناء التشغيل فقط. جودة MP3 لا تزيد جودة الصوت الأصلي. الملفات المحمية أو الترميزات غير المدعومة قد لا تقبل التحويل، وملفات المزود السحابي تحتاج أن يكون محتواها متاحًا للقراءة.

## إعدادات جاهزة

بعد تحليل رابط، اختر **حفظ الإعداد الحالي** واكتب اسمًا. يُحفظ النوع والصيغة والجودة ومجلد الحفظ محليًا داخل سجل مشفر. طبّق الإعداد على مصدر جديد ثم راجع الخيارات قبل البدء. لا تُستبدل الجودة أو الصيغة بصمت عند غيابها، وتُراجع صلاحية المجلد قبل التطبيق. يمكن تعديل إعداد بالحفظ بالاسم نفسه أو حذفه من القائمة. الحد20 إعدادًا.

## التنزيل السابق

تُحفظ إيصالات خاصة للتنزيلات الجديدة المكتملة منذ إضافة هذه الميزة. عند محاولة تنزيل المصدر نفسه بالصيغة والجودة نفسيهما، يعرض التطبيق ملفًا سابقًا متاحًا مع خيارات فتحه أو التنزيل مجددًا أو الإلغاء. لا يستطيع استنتاج مصدر الملفات القديمة التي لم يكن مصدرها محفوظًا.

يستخدم التطابق معرف المصدر والصيغة والجودة، وليس اسم الملف وحده؛ وهو تنبيه مساعد وليس برهانًا على تطابق البايتات أو أن المصدر لم يتغير. الملفات المحذوفة أو المخفية أو غير المتاحة لا تُعرض. يُزال الإيصال عند الإخفاء أو الحذف، ويشمله مسح السجل. تبقى بصمة SHA-256 الموجودة متاحة لفحص المحتوى عند الطلب.

## English

**Offline video → MP3:** select a local video from the extraction tool or its library menu, review the output folder, and start. The original is read-only. Conversion uses the bundled FFmpeg/LAME and the existing durable queue, cancellation and output verification. It needs temporary storage and processing time; it cannot improve source quality or bypass protected/unsupported media. Cloud document providers must make the source bytes readable.

**Named presets:** after analyzing a link, save the current media type, format, quality and folder under a name. Apply it to another supported source and review before starting. Unavailable choices are reported instead of silently substituted. Folder permissions are checked. Up to20 presets are stored locally with authenticated encryption; saving the same name updates that preset.

**Downloaded-before warning:** completed new network downloads receive a bounded private source/variant receipt. A later matching request can offer Open saved file, Download again, or Cancel. Old files without source receipts cannot be inferred. Source matching is advisory, not proof of byte identity or remote immutability. Missing, hidden and inaccessible files do not produce warnings; hiding/deleting/clearing history removes the associated receipts.

All three reuse the existing application and native payload. There is one universal APK, with no new heavy dependency, background clipboard monitor, global file scan or additional persistent service.

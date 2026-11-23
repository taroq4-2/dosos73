# التقرير الختامي — dosos73 vNext (الإصدار 1.4.0، versionCode 23)

> الفرع: `feature/dosos73-vnext-overhaul` (محلي، لم يُدفع). نقطة الأساس: `df233de` (الإصدار 1.3.2 المنشور).
> كل نتيجة هنا مرتبطة بدليل في المستودع. ما لم يُنفذ أو لم يُختبر مكتوب صراحة. لا يدّعي هذا التقرير أن التطبيق خالٍ من العيوب أو الثغرات.

## 1) ملخص تنفيذي: ما تغيّر فعلًا

- **روابط الملفات والبرامج تعمل:** روابط APK وZIP وPDF وغيرها تُعرف كملفات بدل إرسالها لمحرك الوسائط. يُثبت نوع الملف من أول بايتاته قبل التنزيل وبعده، فلا تُحفظ صفحة HTML على أنها APK. صفحات إصدارات GitHub وحزم F-Droid تُحل عبر واجهاتها الرسمية.
- **التحليل لم يعد حاجبًا:** مراحل ظاهرة (تحقق، اتصال، قراءة، جلب وسائط) مع إلغاء، ومهل منفصلة، وأسباب فشل محددة. ظهور أول حالة بعد الضغط: P50 52 ms وP95 105 ms.
- **الاستئناف صحيح:** تنزيلات GitHub الموقّعة (CDN) تُستأنف بدل الفشل الدائم. انقطاع الشبكة أثناء التنزيل صار انتظارًا ثم استئنافًا بدل فشل نهائي (كلاهما خلل موجود في 1.3.2).
- **تصميم جديد مطابق للمرجع:** Compose وMaterial 3، داكن وفاتح، وRTL عربي وإنجليزي، وشاشات الرئيسية والتنزيلات والمكتبة والإعدادات.
- **ودجت حقيقي وإشعارات كاملة:** ودجت مربوط بحالة التنزيل الفعلية، وإشعارات تقدم/إيقاف/استئناف/إلغاء/اكتمال/فشل، مع خصوصية افتراضية.
- **حزمة ARM64 أخف بنسبة 61.8%** (22.31 MB مقابل 58.42 MB)، مع بقاء الحزمة العالمية لبقية الأجهزة.
- **مراجعة أمنية دفاعية** بستة إصلاحات واختبارات SSRF تثبت عدم الاتصال بالعناوين الداخلية.

## 2) المشكلة ← السبب المؤكد ← الملف المعدّل ← اختبار الإصلاح

| المشكلة | السبب المؤكد | الملف المعدّل | اختبار الإصلاح |
|---|---|---|---|
| روابط APK/البرامج لا تعمل | قائمة MIME قصيرة لا تشمل `application/vnd.android.package-archive`، فيُرسل الرابط لـ yt-dlp | `FileTypes.kt`، `DirectDownloader.kt` | `FileTypesTest`، `DirectDownloaderTest`، تجربة F-Droid على المحاكي |
| صفحة HTML تُحفظ كملف | لا فحص للمحتوى، والاعتماد على `Content-Type` فقط | `FileTypes.verify`، `OutputStore.validate` | `SecurityPhaseGTest.aDisguisedPage…`، `FileTypesTest` |
| التطبيق يبدو عالقًا بعد اللصق | حالة `busy` واحدة، ومهلة 120 s، وطلبات متسلسلة | `LinkAnalyzer.kt`، `AnalysisReducer.kt`، `HomeScreen.kt` | `LinkAnalyzerTest`، `StaleAnalysisTest`، `UiResponseBenchmark` |
| صفحات GitHub/F-Droid تفشل | تُرسل لمحرك الوسائط | `SourceResolvers.kt` | `SourceResolversTest` + تجارب حقيقية |
| تنزيل GitHub المتوقف لا يُستأنف أبدًا | هوية الاستئناف مربوطة برابط CDN موقّع يتغير مع كل طلب | `DirectDownloader.kt` | `SignedRedirectResumeTest` + SHA-256 مطابق على الجهاز |
| انقطاع الشبكة = فشل نهائي | انقطاع المقبس قبل إشعار النظام بفقد الشبكة لا يُعامل كانقطاع | `NetworkRecovery.kt`، `DownloadService.kt` | `NetworkRecoveryTest` (+2) + 3 تجارب وضع طيران |
| مهمة تنتظر الشبكة بلا «استئناف» | تراجع في التصميم الجديد (المرحلة D) | `DownloadsScreen.kt` | `NetworkWaitResumeTest` |
| إشعار يحذف آخر بصمت | تصادم المعرّفات 75 و76 (المرحلة E) | `DownloadNotifications.kt` | `SurfacesPhaseETest.everyNotificationOwnsADistinctId` |
| أسماء ملفات `Report-<uuid>.pdf` | المعرّف جزء من الاسم لضمان الاستعادة | `PublicationNames.kt` | `ReadablePublicationNameTest` + 42 اختبار نشر/استعادة |
| `MainViewModel` ضخم | التحليل والدفعات والمسودة المشفرة في صنف واحد | `BatchDraftSession.kt`، `EngineContracts.kt` | `ArchitecturePhaseCTest` |
| الإيقاف المؤقت يُفقد من الإشعار | الإشعار فيه «إلغاء» فقط | `DownloadNotifications.kt`، `DownloadService.kt` | `SurfacesTest` + تجارب الإشعار |
| الحزمة 58 MB لكل الأجهزة | أربع معماريات في حزمة واحدة | `tools/assemble_universal.py --abi` | `tools/test_assemble_abi.py` (5) + فحص ELF |

## 3) لقطات الواجهة الفعلية مقابل المرجع

المرجع: `docs/vnext/screens/reference.png`. لقطات من التطبيق المثبّت على المحاكي:
- الرئيسية: `docs/vnext/screens/ar-dark-home.png` (وفاتح: `ar-light-home.png`، إنجليزي: `en-light-home.png`)
- التنزيلات: `ar-dark-downloads-active.png`، `ar-dark-downloads-paused.png`
- المكتبة والإعدادات: `ar-dark-library.png`، `ar-dark-settings.png`
- الودجت: `widget-active.png`، `widget-paused-after-restart.png`، `widget-compact.png`، `widget-light.png`
- الإشعارات: `notification-progress.png`، `notification-paused.png`
- المقاسات (المرحلة G): `docs/vnext/screens/phaseG/` (خط 200%، 360dp، لوحي، أفقي)

التفاصيل والفروق المقصودة عن المرجع في `UX_IMPLEMENTATION_REPORT.md`.

## 4) مخرجات البناء

| الملف | ABI | الحجم | التغير عن 1.3.2 | SHA-256 | نتيجة الاختبار |
|---|---|---:|---:|---|---|
| `dosos73-1.4.0-arm64-v8a.apk` (موقّعة) | arm64-v8a فقط | 22,319,830 B (22.32 MB) | **−61.8%** | `72dc7c402e8c1313076482bca3893cf5e3f7edbc885503f35d5d11b9576fd196` | توقيع v3 بشهادة 1.3.2، محاذاة 16 KB ✓، ELF 143 / 0 إخفاق. محرك الوسائط على هاتف ARM64 **BLOCKED** (لا جهاز) |
| `dosos73-1.4.0-universal.apk` (موقّعة، = `dosos73.apk`) | arm64-v8a، armeabi-v7a، x86، x86_64 | 58,566,863 B (58.57 MB) | +0.25% | `466bb6301a3ca4b44c11a0fe1fc636c4267e99594926888b5d946a269baf2f29` | توقيع v3 بشهادة 1.3.2، محاذاة 16 KB ✓، ELF 576 / 0 إخفاق 64-bit. تحديث 1.3.2 ← 1.4.0 في مكانه على المحاكي PASSED |
| `dosos73-1.4.0.aab` | الكل (مخرج Gradle) | 203,307,989 B | — | `030b590bd6aa4d748c5d410a92dd149b36fc559e98ef95adb05ce0553f64328f` | `verify_bundle.py` (versionCode 23، 1.4.0) PASSED. حجم التسليم عبر Play غير مُقاس |
| خط الأساس 1.3.2 Universal (منشور) | الأربعة | 58,422,468 B | — | `19c5c5f3d69d635472a2d0fd6cfc1528250e2aba3f62e32a291fd3ea324d431e` | — |

الملفات في `build-evidence/release-1.4.0/` (خارج git).

> بُنيت الحزمتان من الـ commit `5f2ff11` (CI على GitHub ناجح عليه) ووقّعهما صاحب المشروع بمفتاح 1.3.2 نفسه (شهادة SHA-256 `bf0bcb1b…0ca9`)، ومحتواهما مطابق ملفًا بملف للبناء الذي اختُبر. لم يتعامل المساعد مع كلمة المرور. ملف AAB لم يُوقّع ولم يُرفع إلى أي متجر.

## 5) الفحوص الأمنية

| الفحص | النتيجة | الدليل |
|---|---|---|
| SSRF عبر إعادة التوجيه (10.0.0.5، 127.0.0.1، 169.254.169.254، [::1]، 192.168.1.1، localhost) | مرفوض **دون الاتصال بالعنوان** | `SecurityPhaseGTest` (عدد الطلبات = 1) |
| خفض HTTPS إلى HTTP | مرفوض | `SecurityPhaseGTest.httpsDowngrade…` |
| استجابة API ضخمة | مرفوضة قبل التحليل (> 2 MiB) | `SecurityPhaseGTest.oversizedApiResponse…` |
| أسماء ملفات خبيثة (traversal، bidi، NUL) | تُنظَّف، ولا فواصل مسار | `SecurityPhaseGTest.serverFileNames…`، `DirectFileNameTest` |
| صفحة متنكرة كـ APK | تُصنَّف صفحة لا ملفًا | `SecurityPhaseGTest.aDisguisedPage…` |
| المكوّنات المُصدَّرة | كلها محمية أو مقصودة | `SECURITY_REVIEW.md` (جدول من `aapt2 dump`) |
| الاعتماديات (OSV.dev) | 89 مكوّن تشغيل، 0 ثغرات معروفة | `tools/check_dependency_advisories.py` |
| أدوات البناء | ثغرة واحدة في إضافة Kotlin Gradle، غير مؤثرة (KAPT غير مستخدم) | `tools/check_build_advisories.py`، `verifyNoKapt` |
| السجلات | لا روابط ولا أسماء ملفات ولا نصوص أخطاء | تدقيق `grep` |
| تثبيت APK | لا تثبيت تلقائي أبدًا؛ الحفظ فقط مع تنبيه | `FileTypes`، بطاقة النتيجة |

القيود: لا اختبار اختراق لخوادم خارجية، ولا اعتراض TLS على جهاز، ولا fuzzing للمحركات الأصلية في هذه المراجعة.

## 6) جدول المتطلبات (بنود الطلب)

الحالة: **مكتمل** · **جزئي** · **لم ينفذ**

| # | البند (من المواصفة) | الحالة | الدليل | الخطر الباقي / الخطوة التالية |
|---|---|---|---|---|
| 3 | جرد وخط أساس (`AUDIT_BASELINE.md`، P0/P1/P2، قياسات) | مكتمل | `AUDIT_BASELINE.md` | — |
| 4 | معمارية موحدة (MVVM + UDF، عقود محرك، read-model موحد) | جزئي | `ARCHITECTURE.md`، `ArchitecturePhaseCTest` | حالات الاستعمال (`AnalyzeUrl`، `StartDownload`…) ليست أصنافًا مستقلة؛ المنطق في عقود ومخفضات. لا حقن تبعيات بإطار (مقصود) |
| 5 | تحليل غير حاجب بمراحل وإلغاء ومنع الاستجابات القديمة | مكتمل | `LinkAnalyzer.kt`، `StaleAnalysisTest`، P50 52 ms / P95 105 ms | القياس على محاكٍ لا على جهاز متوسط |
| 5 | 50 حالة موثقة وP50/P95 | جزئي | `PerformanceBenchmarks.kt`: 93 تحليلًا على 6 أنواع | لا 50 عينة لكل نوع (YouTube 3 فقط بسبب الزمن) |
| 6 | تنزيل أي ملف معقول + تحقق المحتوى | مكتمل | `FileTypes.kt`، `FileTypesTest`، تجارب حقيقية | — |
| 6 | GitHub Releases وF-Droid | مكتمل | `SourceResolvers.kt` + اختبارات | SourceForge وGoogle Drive غير مدعومة (موثق في «الروابط المدعومة») |
| 6 | استئناف آمن (Range + ETag + الطول)، 416، روابط منتهية، نفاد المساحة، traversal | مكتمل | `SignedRedirectResumeTest`، `DirectDownloaderTest`، `ResumeSpaceTest`، `OutputSafetyTest` | ملف ≥ 1 GB لم يُختبر |
| 6 | تنزيلات متزامنة قابلة للضبط وحد سرعة | لم ينفذ | `AUDIT_BASELINE.md` (P1-1) | يمس عقد الخدمة والطابور والاستعادة؛ يحتاج تصميمًا واختبارات تزامن مخصصة |
| 6 | اختيار الاسم قبل البدء، أسماء مقروءة، تعارض الأسماء | مكتمل | `PublicationNames.kt`، حوار «تم تنزيله سابقًا» | — |
| 7 | الحفاظ على الفيديو والصوت | مكتمل | `LiveDownloadTest#mediaExtractorAndNativeDownload` (Universal)، YouTube حتى 2160p | محركات الوسائط في حزمة ARM64 **BLOCKED** على المحاكي (تحتاج جهاز ARM64) |
| 8 | تصميم مطابق للمرجع (الرئيسية، التنزيلات، المكتبة، الإعدادات) | مكتمل | `UX_IMPLEMENTATION_REPORT.md` + لقطات | الشعار أُبقي (قرار هوية للمالك) |
| 8 | داكن/فاتح/النظام، RTL/LTR | مكتمل | `DesignContrastTest`، لقطات ar/en | — |
| 8 | إتاحة الوصول: 48dp، TalkBack، خط 200%، 360dp، لوحي، أفقي | جزئي | لقطات `phaseG/`، `ReviewLayoutTest`، `LandscapeReviewLayoutTest` | لا اختبار TalkBack يدوي كامل؛ لا `NavigationRail` في الأفقي |
| 9 | ودجت حقيقي (حالات، أحجام، RTL، سمات، خصوصية، إعادة التشغيل) | مكتمل | `DownloadWidget.kt`، `SurfacesTest`، 11 تجربة على Pixel Launcher | مشغّلات أخرى لم تُختبر. على Android 10–12 نجحت الاختبارات الآلية في CI فقط |
| 10 | إشعارات: قنوات، تقدم، إيقاف/استئناف/إلغاء، نتيجة، شاشة القفل، إذن مرفوض | مكتمل | `DownloadNotifications.kt`، `SurfacesTest`، تجارب الإذن المرفوض | — |
| 10 | استئناف بعد قتل العملية/إعادة التشغيل | مكتمل | تجربة إعادة التشغيل (المرحلة E)، `PauseStartupRaceTest`، `ColdServiceControlTest` | — |
| 11 | حزمة ARM64 أساسية + Universal، تقرير أحجام، 16 KB، SHA-256 | مكتمل | `PERFORMANCE_AND_SIZE_REPORT.md`، القسم 4 أعلاه | AAB عبر bundletool لم يُقس |
| 11 | التشغيل البارد والذاكرة | جزئي | `PERFORMANCE_AND_SIZE_REPORT.md` §3–4 | **تراجع قائم** بعد التثبيت مباشرة (+38 إلى +107 ms P50)؛ يحتاج جهازًا فعليًا وMacrobenchmark |
| 12 | مراجعة أمنية دفاعية بصيغة الخطورة/الدليل/الإصلاح/الاختبار | مكتمل | `SECURITY_REVIEW.md`، `SecurityPhaseGTest` | انظر «القيود» في القسم 5 |
| 13 | مشاركة الروابط عبر Sharesheet | مكتمل (منذ 1.3.2) | `MainActivity` (`SEND text/plain`)، استُخدم في تجارب المرحلة G | — |
| 13 | Wi-Fi فقط والشبكات غير المحدودة | مكتمل (منذ 1.3.2) | الإعدادات ← الشبكة | — |
| 13 | جودة الفيديو والصوت والحجم المتوقع | مكتمل (منذ 1.3.2) | بطاقة النتيجة | — |
| 14 | اختبارات وحدة وLint وبناء وinstrumented | مكتمل | `TEST_RESULTS.md`؛ CI على GitHub: 210 اختبارات جهاز نجحت على كل إصدار من API 29 إلى 36 | — |
| 14 | شهادة غير صحيحة، DNS rebinding على الجهاز | جزئي | JVM: `SafetyTest`، `LiteralNetworkBoundaryTest`؛ الجهاز: `TlsVersionTest` (مقارنة إصدارات TLS المتحقق منها) | لا تجربة شهادة خاطئة يدوية على الجهاز |
| 14 | ملف ≥ 1 GB | لم ينفذ | — | يحتاج موردًا مستقرًا ومساحة أكبر على المحاكي |
| 16 | ملفات التسليم (ARCHITECTURE، PERFORMANCE، SECURITY، UX، TEST_RESULTS، CHANGELOG، هذا الجدول) | مكتمل | جذر المستودع | — |

## 7) نقاط عمل متبقية وسبب عدم إنجازها

1. **التنزيلات المتزامنة (P1-1):** لم تُنفذ. التصميم الحالي يضمن مهمة واحدة نشطة بعقد صارم بين `Transfers` والخدمة والطابور والنشر واستعادة الأعطال. تغييره بلا تصميم واختبارات تزامن مخصصة يهدد صحة التنزيلات، وهي الأولوية الأولى في المواصفة.
2. **جهاز ARM64 فعلي:** لم يتصل أي هاتف. محركات الوسائط في حزمة ARM64 لم تُثبت (المحاكي لا يربط ملفًا تنفيذيًا ARM64 مترجمًا)، والتشغيل البارد يحتاج قياسًا حقيقيًا.
3. **تراجع التشغيل البارد بعد التثبيت مباشرة:** موثق وغير مُعالَج. أثر Baseline Profile غير مُثبت على المحاكي.
4. **ملفات ≥ 1 GB، ونفاد المساحة الفعلي، وشهادة TLS خاطئة على الجهاز:** لم تُختبر يدويًا (المنطق مغطى باختبارات JVM).
5. **مشغّلات غير Pixel، والتجربة اليدوية على Android 10–12:** لم تُنفذ. أما الاختبارات الآلية للجهاز (210) فنجحت كلها في CI على محاكيات API 29 إلى 36 (Android 10 إلى 16).
6. **اختبار الحافظة الحساسة (`QuickLaunchTest`):** متذبذب على المحاكي. الشيفرة لم تتغير منذ 1.3.2، والسبب المرجّح مزامنة حافظة المحاكي مع المضيف (غير مُثبت).
7. **النشر:** نُشر الإصدار v1.4.0 على GitHub بموافقتك، مع الحزمتين الموقّعتين وملف البصمات، وحُدّث موقع التنزيل. لم يُرفع شيء إلى Google Play.

# TEST_RESULTS — dosos73 vNext

سجل حيّ لنتائج الاختبارات الفعلية على فرع `feature/dosos73-vnext-overhaul`. كل سطر نتيجة تشغيل حقيقي في البيئة المذكورة. ما لم يُشغَّل مكتوب بوضوح.

**الحالات:** PASSED · FAILED · BLOCKED · NOT RUN

## البيئة

| البند | القيمة |
|---|---|
| المضيف | Windows 11 Pro، JDK 21.0.3 (JBR من Android Studio)، Gradle 8.14.6، AGP 8.10.1 |
| المحاكي | `dosos73_vnext`: Android 15 (API 35)، x86_64 مع ترجمة arm64، قرص بيانات 12 GB، ذاكرة 4 GB (3 GB و4 أنوية منذ المرحلة F لاستقرار المضيف). أُنشئ لهذا العمل لأن المحاكي الأصلي `Medium_Phone_API_35` لم يكن فيه سوى 214 MB حرة، ولم يُحذف منه شيء |
| الشبكة | اتصال المضيف الفعلي بالإنترنت (غير مُحاكى) |
| جهاز فعلي | غير متصل حتى الآن: كل اختبارات ARM64 الحقيقية **BLOCKED** |

## المرحلة A — خط الأساس (`df233de`)

| الأمر | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | PASSED | 126/126 |
| `lintDebug` | PASSED | 81 ملاحظة (Warning/Hint)، صفر Error |
| `assembleDebug` / `assembleRelease` / `bundleRelease` | PASSED | الأحجام وقيم SHA-256 في `AUDIT_BASELINE.md` |
| اختبارات instrumented (193) | NOT RUN | لم يكن هناك جهاز متصل |

## المرحلة B — إصلاحات P0

### البوابة الآلية

```
gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease compileDebugAndroidTestKotlin
```

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | PASSED | **162/162** (126 سابقة، منها 4 اختبارات HEAD استُبدلت، + 40 جديدة) |
| `lintDebug` | PASSED | 81 ملاحظة، مطابقة لخط الأساس (لا ملاحظات جديدة)، صفر Error. تحذيران ظهرا في ملفاتي الجديدة (`PluralsCandidate` ومورد غير مستخدم) أُصلحا قبل الالتزام |
| `lintRelease` | PASSED | 82 ملاحظة، صفر Error (لم يُشغَّل في خط الأساس للمقارنة) |
| `assembleDebug` / `assembleRelease` | PASSED | — |
| `compileDebugAndroidTestKotlin` | PASSED | اختبارات الجهاز تُترجم مع التغييرات |
| تشغيل instrumented (193) | NOT RUN | يُجدول في المرحلة G على المحاكي والجهاز الفعلي |

### الاختبارات الجديدة/المحدّثة

| الملف | العدد | ما يثبته |
|---|---:|---|
| `FileTypesTest` | 6 | أنواع MIME الخاصة بـ APK، وتواقيع ZIP/RAR/7Z/PDF/PNG/JPEG/MP4/EXE، وكشف HTML عبر BOM والمسافات، وحكم Match/Mismatch/WebPage/Unverifiable |
| `DirectDownloaderTest` (فحص الرابط) | 14 جديدة ضمن 39 | APK مع `vnd.android` وoctet-stream وبلا MIME وZIP عام. `Content-Disposition` RFC 5987. HTML برابط `.apk` يُرفض حتى لو ادّعى نوعًا ثنائيًا. APK محتواه PDF يُرفض. RAR/7Z/EXE/DOCX/XAPK/APKS/EPUB. حالة 416 تُعاد بطلب GET عادي مرة واحدة دون تكرار. 404/410/403/401/503/429 مع `Retry-After`. سلسلة تحويلات 302→307. قراءة رأس محدودة لملف 1 MB في طلب واحد. JSON محدود الحجم وحد معدل GitHub |
| `DirectFileNameTest` | +2 | امتداد APK/XAPK محفوظ، واسم ISO-8859-1، ورفض انتحال الامتداد بحرف U+202E |
| `SourceResolversTest` | 7 | مطابقة روابط GitHub Releases وF-Droid، ونقاط API الرسمية، ورفض أصول خارج نفس الإصدار، وترتيب APK حسب ABI الجهاز |
| `LinkAnalyzerTest` | 11 | الملف المباشر لا يشغّل محرك الوسائط. الصفحة العامة تذهب للمحرك. الصفحة بدل الملف تفشل فورًا بسبب محدد. 404/410/عدم تطابق النوع لا تشغّل المحرك. مهلة الفحص مستقلة. اختيار أصول الإصدار. F-Droid |

### تجارب حقيقية على المحاكي (تطبيق Debug مثبّت، روابط حقيقية)

زمن «الجاهزية» يقيسه التطبيق نفسه (`DososPipeline` → `millis`) من الضغط على «تحليل» حتى ظهور النتيجة. عدد التشغيلات صغير (2–3 لكل رابط)، فهذه ليست P50/P95.

| الحالة | الرابط | النتيجة | الزمن | الحكم |
|---|---|---|---:|---|
| APK مباشر من GitHub (تحويلات حتى خادم الأصول) | `…/releases/download/v1.3.2/dosos73.apk` | ملف APK، «تم التحقق من المحتوى (APK)»، الحجم 55.72 MiB (يطابق 58,422,468 بايت)، تنبيه عدم التثبيت التلقائي | 1,935 / 1,048 / 1,265 ms | PASSED |
| صفحة إصدار GitHub | `…/releases/latest` | قائمة «اختر ملفًا من هذا الإصدار» بـ 3 ملفات APK أولًا ثم بقية الأصول مع أحجامها | 1,014 / 990 ms | PASSED |
| صفحة تطبيق F-Droid | `f-droid.org/packages/org.fdroid.fdroid/` | APK الإصدار المقترح `org.fdroid.fdroid_2000053`، تم التحقق، 12.18 MiB | 666 / 542 ms | PASSED |
| ملف غير موجود | `…/releases/download/v1.3.2/no-such-file.apk` | رسالة HTTP 404 محددة، دون تشغيل محرك الوسائط | فوري (~1 s) | PASSED |
| تنزيل كامل حتى الحفظ | APK F-Droid أعلاه | 12,774,751 بايت، VERIFY ثم PUBLISH، حُفظ في `Download/dosos73/` بنوع `application/vnd.android.package-archive`. SHA-256 على الجهاز: `dac55a0d17b1edf7e7286b48cfbd70c7c52fbb32a00af062c68830a437c47959` | ~5 s | PASSED |

لقطات الشاشة (`direct-apk.png`, `github-release-page.png`, `fdroid-page.png`, `missing-apk.png`) محفوظة محليًا في `build-evidence/phaseB-device/` وغير مرفوعة. أُعيد التقاطها في التشغيل الثاني بعد إصلاح خطأ في مسار الحفظ بسكربتي، كان يكتب كل اللقطات فوق ملف واحد.

**للمقارنة:** في 1.3.2 كانت هذه الروابط الأربعة كلها تُحال إلى محرك الوسائط (Python/yt-dlp)، لأن نوع APK لم يكن في القائمة ولأن صفحات GitHub/F-Droid صفحات HTML. لم يُقس زمن 1.3.2 على هذا المحاكي: **غير مُقاس**.

## المرحلة C — إعادة الهندسة

### البوابة الآلية

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | PASSED | **170/170** (+8 في `ArchitecturePhaseCTest`: المختزل، ولوحة التنزيلات، وأسماء النشر) |
| `lintDebug` / `lintRelease` | PASSED | 81 / 82 ملاحظة، مطابقة لما قبل المرحلة، صفر Error |
| `assembleDebug` / `assembleRelease` | PASSED | — |
| `connectedDebugAndroidTest` (19 صنفًا متأثرًا) | **PASSED** | **103/103** على المحاكي `dosos73_vnext` (API 35) في 1 د 43 ث. النتائج الخام في `build-evidence/phaseC-device-results.xml` |

### الأصناف المشغّلة على الجهاز

| الصنف | العدد | لماذا شُغّل |
|---|---:|---|
| `StaleAnalysisTest` (جديد) | 2 | نتيجة تحليل أُلغي لا تظهر أبدًا، ولا تُطبَّق نتيجة على رابط آخر |
| `ReadablePublicationNameTest` (جديد) | 2 | `اسم.bin` ثم `اسم (2).bin` مع بقاء الاستعادة صحيحة، وحجز اسم خطة نشر غير مكتملة |
| `BatchHandoffTest`, `BatchDraftTest`, `BatchChoiceUiTest` | 20 | نقل منطق المسودة المشفرة إلى `BatchDraftSession` |
| `UiFlowTest`, `ClipboardInputTest`, `QuickLaunchTest`, `ReviewLayoutTest`, `RotationStateTest`, `LandscapeReviewLayoutTest`, `AppResourcesTest` | 24 | `MainViewModel` والواجهة |
| `OutputSafetyTest`, `PublicationRecoveryTest`, `PublicationCommitRaceTest`, `PublicationUriBoundaryTest`, `RenameRecoveryRegressionTest` | 42 | تغيير أسماء النشر وفحص المحتوى في `validate` |
| `LibraryQueueTest`, `LibraryMutationTest` | 13 | المكتبة والطابور |

بقية الاختبارات (~92 من 193 القديمة) لم تُشغَّل في هذه المرحلة: **NOT RUN**، وتُشغّل كاملة في المرحلة G.

## المرحلة D — إعادة التصميم

### البوابة الآلية

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | PASSED | **173/173** (+3: اختبار التباين للوضعين، و`SignedRedirectResumeTest` ×2) |
| `lintDebug` / `lintRelease` | PASSED | 83 / 84 ملاحظة، صفر Error. الزيادة +2 تلميحات `UseKtx` في كتابتي `SharedPreferences` الجديدتين، اتباعًا لنمط `check(commit())` القائم. أُزيلت 16 سلسلة يتيمة بالعربية والإنجليزية |
| `assembleDebug` / `assembleRelease` | PASSED | Release غير موقّع: 202,585,394 بايت (+127,680 بايت ≈ 125 KB منذ خط الأساس، لكل المراحل B–D) |
| `connectedDebugAndroidTest` (20 صنفًا) | **84/87 ثم 86/87** | التفاصيل أدناه |

### نتائج الجهاز

| الاختبار | النتيجة | السبب والإجراء |
|---|---|---|
| `UiFlowTest.primaryNavigationKeepsRealEmptyStatesAndInput` | FAILED ثم **PASSED** | تراجع حقيقي مني: عبارة «لا توجد تنزيلات نشطة» اختفت حين توجد ملفات مكتملة. أُعيدت كبطاقة أعلى القائمة |
| `AppResourcesTest.systemSplashUsesTheSameNavyCanvas` | FAILED ثم **PASSED** | الاختبار كان يثبّت الكحلي القديم `#070F1E`. حُدِّث ليقارن بتوكن الهوية `DososPalettes.Dark.background` |
| `QuickLaunchTest.unsafeClipboardItemsAreRejectedWithoutOpeningAContentProvider` | **FLAKY (بيئة)**: فشل 3 من 6 | يفشل دائمًا مع العنصر `sensitive` فقط: يُقرأ الرابط بدون علامة `IS_SENSITIVE`. التفسير الأرجح أن مزامنة حافظة المحاكي مع Windows تعيد النص كمقطع عادي فتُسقط العلامة. `QuickLaunch.kt` لم يتغير منذ خط الأساس. أُضيفت رسالة تسمّي العنصر والقيمة المقروءة. **يحتاج تأكيدًا على جهاز فعلي** |
| بقية 84 اختبارًا (Batch، Publication، Library، Output، Stale، Readable، Timeline...) | PASSED | — |

### تجارب حقيقية على المحاكي

| الحالة | النتيجة | الحكم |
|---|---|---|
| تنزيل 268,141,364 بايت من GitHub مع إيقاف مؤقت، ثم قتل العملية (`am force-stop`)، ثم إعادة الفتح والاستئناف | توقف عند 73.32 MiB واستُؤنف من نقطة الاستئناف حتى الاكتمال. **SHA-256 للنسخة المستأنفة = النسخة غير المنقطعة = بصمة GitHub المنشورة** `2080a4d490de9d64fc13f31868fab3f9c7215e06138c602d5df31611502e7260` | PASSED (بعد الإصلاح) |
| نفس السيناريو قبل الإصلاح | 3 محاولات فشلت `DOWNLOAD_FAILED` («Changed representation») | خلل موجود في 1.3.2، أُصلح وأعيد إنتاجه باختبار JVM |
| تسمية مقروءة على الجهاز | `dosos73.apk` ثم `dosos73-1.3.2-native-sources.zip` ثم `dosos73-1.3.2-native-sources (2).zip` | PASSED |
| تحليل APK مع خنق الشبكة إلى 8000 kbps | 15,559 ms مقابل 1–2 ث بلا خنق؛ والتنزيل أبطأ بكثير من القيمة المطلوبة | خنق المحاكي غير موثوق. **اختبار الاتصال البطيء الحقيقي: NOT RUN** |
| السمات: داكن/فاتح، العربية/الإنجليزية | لقطات حقيقية في `docs/vnext/screens/` | PASSED (بصريًا) |

### ملاحظة على البيئة

`connectedDebugAndroidTest` يزيل التطبيق بعد التشغيل (سلوك Gradle الافتراضي). لهذا فقد ملف F-Droid المحفوظ في المرحلة B مالكه في MediaStore واختفى من المكتبة. هذا أثر لطريقة الاختبار وليس خللًا في المنتج.

## المرحلة E — الودجت والإشعارات

### البوابة الآلية

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | PASSED | **182/182** (+9 في `SurfacesPhaseETest`: نموذج الودجت، والخصوصية، والإيجار المتوقف بعملية باردة، ونص الإشعار، والوقت المتبقي) |
| `lintDebug` / `lintRelease` | PASSED | 84 / 85 ملاحظة، صفر Error. خطأ واحد (`RtlCompat` في تخطيط الودجت) أُصلح. علامتا `UnusedAttribute` و`UseCompatTextViewDrawableXml` مُعلَّلتان في XML لأن RemoteViews لا تدعم سمات AppCompat |
| `assembleRelease` | PASSED | 202,585,394 بايت |
| `connectedDebugAndroidTest` (12 صنفًا: الخدمة، والطابور، والتأجيل، والنشر، والواجهة، و`SurfacesTest`) | **36/50 ثم 50/50** | التشغيل الأول فشل في 14 اختبارًا، معظمها عند `check(lease.id == task.id)` في `DownloadQueue.rememberActive`. السبب تنزيل متوقف تركتُه في سجل الطابور على المحاكي أثناء الاختبار اليدوي، والاختبارات تفترض طابورًا فارغًا. بعد أن أزال Gradle التطبيق في نهاية التشغيل الأول، نجحت الأصناف نفسها كلها دون أي تغيير في الشيفرة. وفشلان في `SurfacesTest` كانا من خطئي (مقارنة بلغة النظام بدل لغة التطبيق) وأُصلحا |

### `SurfacesTest` على الجهاز (7/7)

ودجت نشط بتقدم حقيقي واسم مخفي افتراضيًا. ودجت خامل بلا تقدم وهمي وأزراره قابلة للنقر. اتجاه الودجت يتبع لغة التطبيق (RTL/LTR). إشعار التقدم: العنوان، والنص، والنسبة، وإجراءان، و`PendingIntent` غير قابل للتعديل، و`VISIBILITY_PRIVATE` مع نسخة عامة لا تكشف الاسم. إشعار الإيقاف يُستبدل بإشعار الفشل. قائمة الصفحات المسموح بفتحها من الخارج.

### تجارب حقيقية على المحاكي (Pixel Launcher، Android 15)

| الحالة | النتيجة | الحكم |
|---|---|---|
| إضافة الودجت من منتقي الودجت | يظهر «dosos73، 4×2» مع الوصف. أُضيف وعرض «لا يوجد تنزيل نشط» بالعربية وRTL | PASSED |
| تنزيل حقيقي 268 MB | الودجت: «التنزيل النشط»، وأرشيف (الاسم مخفي)، و22%، والحجم، والسرعة. الإشعار: الاسم، و22%، و«57.69 MiB / 255.72 MiB • 3.78 MiB/ث • متبقٍ 0:53» | PASSED |
| «إيقاف مؤقت» من الإشعار | أُزيل إشعار الخدمة، وظهر إشعار «متوقف مؤقتًا» (142.54 MiB) مع «استئناف» و«إلغاء» | PASSED |
| «استئناف» من الإشعار | استمر من النقطة نفسها (142.5 ← 203 ← 212 MiB) دون إعادة البدء | PASSED |
| الاكتمال | إشعار الاكتمال (id 74) مع «فتح» و«التنزيلات»، والودجت «اكتمل» | PASSED |
| إعادة تشغيل الجهاز أثناء الإيقاف | بعد الإقلاع (عملية باردة) عرض الودجت «متوقف مؤقتًا» وأرشيف و**47.11 / 255.72 MiB، 18%** من نقطة الاستئناف المحفوظة | PASSED |
| زر «عرض التنزيلات» | يفتح التطبيق على صفحة التنزيلات، وفيها المهمة المتوقفة بالتقدم الحقيقي | PASSED |
| زر «تحميل جديد» | يفتح الرئيسية مع حقل الرابط | PASSED |
| تغيير الحجم | التخطيط المضغوط: الحالة، و18%، وشريط RTL | PASSED |
| الإزالة | أُزيل دون أي `FATAL` في السجل | PASSED |
| الوضع الداكن للنظام | ألوان المرجع. الوضع الفاتح للنظام: بطاقة فاتحة | PASSED |

### ما لم يُختبر في هذه المرحلة

| البند | الحالة | السبب |
|---|---|---|
| إشعار الفشل على تنزيل حقيقي | NOT RUN يدويًا | لم يتوفر فشل طبيعي قابل للإعادة. المحتوى مغطى في `SurfacesTest` |
| رفض إذن الإشعارات (Android 13+) | NOT RUN | الشيفرة تتجاهل `notify` بأمان؛ يُختبر في المرحلة G |
| مشغّلات أخرى (Samsung One UI، إلخ) وAndroid 10–12 | NOT RUN | لا جهاز. عرض المقاسات على Android أقل من 12 يعتمد على `OPTION_APPWIDGET_MIN_WIDTH` |
| عرض الإشعار بعد إعادة التشغيل | لا يُعاد نشر إشعار «متوقف» بعد الإقلاع | الودجت والتطبيق يعرضان المهمة؛ لا خدمة تعمل عند الإقلاع (مقصود) |

### ما لم يُختبر بعد

| البند | الحالة | السبب |
|---|---|---|
| 50 تحليلًا لكل نوع لحساب P50/P95 | NOT RUN | المرحلة F |
| شهادة TLS غير صحيحة / DNS rebinding / رابط داخلي على الجهاز | NOT RUN | مغطاة جزئيًا باختبارات JVM القائمة (`SafetyTest`, `LiteralNetworkBoundaryTest`)؛ تجربة الجهاز في المرحلة G |
| ملفات ≥ 1 GB، انقطاع/استئناف، نفاد المساحة | NOT RUN | المرحلة G (منطق الاستئناف لم يتغير في هذه المرحلة) |
| ARM64 على جهاز فعلي | BLOCKED | لا جهاز متصل |

## المرحلة F — الحجم والأداء

النتائج الكاملة (الأحجام، وELF، والتشغيل البارد، والذاكرة، وP50/P95 للتحليل) في `PERFORMANCE_AND_SIZE_REPORT.md`. ملخص الاختبارات:

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `tools/test_assemble_abi.py` | PASSED | 5/5: تجميع ARM64 يرفض التلاعب بأي عضو، ويرفض تطبيقًا مبنيًا لكل الـ ABIs |
| `SpeedMeterTest` | PASSED | تنعيم السرعة والوقت المتبقي |
| `PerformanceBenchmarks` (اختياري `-e dosos73.benchmark true`) | PASSED | 93 تحليلًا على 6 أنواع روابط، و30 قياس ضغط ← ظهور المرحلة (P50 52 ms، P95 105 ms) |
| حزمة ARM64 على المحاكي: تثبيت وتنزيل مباشر | PASSED | SHA-256 مطابق |
| حزمة ARM64: محرك الوسائط | **BLOCKED** | المحاكي لا يربط ملفًا تنفيذيًا ARM64 مترجمًا؛ يحتاج جهاز ARM64 |
| تشغيلا جهاز كاملان (عادي) | PASSED | 208 اختبارًا مميزًا: 191 نجح، 0 فشل، 17 تخطٍّ (اختبارات تحتاج runtime المقوّى أو مفعّلة اختياريًا) |

## المرحلة G — ضمان الجودة والإصدار

### البوابة الآلية (الشيفرة النهائية)

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | PASSED | **196/196** (+7 `SecurityPhaseGTest`، +2 `NetworkRecoveryTest`، +1 `SurfacesPhaseETest`) |
| `lintDebug` / `lintRelease` | PASSED | 85 / 86 ملاحظة، **صفر Error** |
| `assembleRelease` + `bundleRelease` (Universal و`-PtargetAbi=arm64-v8a`) | PASSED | الحزم وقيم SHA-256 في `FINAL_REPORT.md` §4 |
| `tools/verify_bundle.py --version-code 23 --version-name 1.4.0` | PASSED | نفس فحص CI |
| `zipalign -c -P 16 4` + `tools/audit_elf_alignment.py` | PASSED | Universal: 576 ELF، 0 إخفاق 64-bit. ARM64: 143 ELF، 0 إخفاق |
| OSV.dev (`check_dependency_advisories.py`) | PASSED | 89 مكوّن تشغيل، 0 ثغرات |

### الجهاز: التشغيل الكامل بطريقة CI (APK مشترك مع runtime المقوّى)

APK الـ debug مُجمّع مع `dosos73-1.3.2-runtime-hardened.zip` ومُحاذى وموقّع مع APK الاختبار بالمفتاح نفسه، كما في `.github/workflows/android.yml`. هذا يشغّل الاختبارات التي تُتخطى في التشغيل العادي لأنها تحتاج المحركات المقوّاة.

| التشغيل | النتيجة | التفاصيل |
|---|---|---|
| الشيفرة النهائية، كل الأصناف (211) | **200 PASSED، 1 FAILED، 10 تخطٍّ** | التخطي: 10 اختبارات مفعّلة اختياريًا (قياسات وشبكة حية). الفشل: `UiResponseBenchmark` عند إغلاق النشاط (`Activity never becomes requested state "[DESTROYED]"`)، وهو مُتخطى أصلًا بالافتراض؛ أُعيد منفردًا **3/3 PASSED**. يُصنَّف تذبذب دورة حياة تحت حمل المحاكي |
| الاختبارات المفعّلة اختياريًا على الشيفرة النهائية | **5/5 PASSED** | `LiveDownloadTest#mediaExtractorAndNativeDownload` (`-e liveMedia true`، كما في CI على API 35)، و`#directDownloadAndRealScreenshots`، و`ProxyTransportTest`، و`TlsVersionTest`، و`NativeTransportComparisonTest` |
| `NetworkWaitResumeTest` + `SurfacesTest` + `DeferredTransferTest` | **3 × 11/11 PASSED** | بعد إصلاحات المرحلة G |
| `QuickLaunchTest#unsafeClipboardItemsAreRejected…` | **متذبذب (FAILED في 4 من 6 تشغيلات في هذه المرحلة)** | الحافظة الحساسة تُقبل أحيانًا لأن علامة `IS_SENSITIVE` تصل فارغة. شيفرة `QuickLaunch.copiedUrl` لم تتغير منذ 1.3.2 وتفحص العلامة صحيحًا. السبب المرجّح مزامنة حافظة المحاكي مع المضيف. **غير مُثبت**؛ يحتاج جهازًا فعليًا |

### CI على GitHub (طلب الدمج taroq4-2/dosos73#20، 21 نوفمبر 2026)

سير العمل `.github/workflows/android.yml` على الشيفرة النهائية (التشغيل `44902027759`):

| المهمة | النتيجة | التفاصيل |
|---|---|---|
| `build` (اختبارات الوحدة، Lint Debug/Release، AAB، `verify_bundle`، فحوص الاعتماديات) | **PASSED** | — |
| `device-tests` على API 29، 30، 31، 32، 33، 34، 35، 36 (x86_64) | **PASSED على الثمانية** | `OK (210 tests)` في كل إصدار، بالـ APK المشترك مع runtime المقوّى. الاختبارات المفعّلة اختياريًا مستثناة كما في التشغيل المحلي |
| API 35: تنزيل وسائط أصلي من مورد MP4 عام | **PASSED** | `OK (1 test)` |
| API 36: تكرار اختبار التكرار والإلغاء 3 مرات | **PASSED 3/3** | — |

تشغيل آخر للدفع نفسه أظهره GitHub «ملغى»: ألغاه سير العمل لأن تشغيل طلب الدمج بدأ بعده، وليس فشلًا في الاختبارات.

### تجارب حقيقية على المحاكي

| الحالة | النتيجة | الحكم |
|---|---|---|
| رفض إذن الإشعارات (Android 15) ثم تنزيل APK | ظهر طلب النظام عند البدء، واختير «لا تسمح». اكتمل التنزيل، ولم يُنشر إشعار نتيجة، ولا تعطل | PASSED |
| وضع الطيران أثناء تنزيل 268 MB (قبل الإصلاح) | مرة انتظر، ومرتين **فشل نهائي** (`DOWNLOAD_FAILED`)؛ والمهمة المنتظرة بلا زر استئناف | **FAILED** ← أُصلح |
| وضع الطيران أثناء التنزيل (بعد الإصلاح)، 3 تجارب | «بانتظار الشبكة المسموح بها» مع «استئناف»؛ بعد عودة الشبكة استُؤنف من النقطة المحفوظة (مثلًا 42.6 MiB بعد 1.5 ث)؛ **SHA-256 مطابق لـ GitHub** (`2080a4d4…7260`) في الثلاث | PASSED |
| انقطاع > 15 ث (الخدمة تتوقف وتُسلّم للنظام) | عند عودة الشبكة ظهر تذكير «بدء قائمة الانتظار» **بالعربية** (كان بالإنجليزية قبل الإصلاح)، ثم استُؤنف من التطبيق واكتمل بـ SHA-256 مطابق | PASSED |
| خط 200%، عرض 360dp، لوحي 1280×800dp، أفقي | لقطات في `docs/vnext/screens/phaseG/`. ثلاث مشكلات قص أُصلحت (انظر `UX_IMPLEMENTATION_REPORT.md` §8) | PASSED بعد الإصلاح |
| بناء Release (Universal) على المحاكي | تنزيل APK متحقق منه، وتحليل YouTube خلال ~4 ث بجودات حتى 2160p | PASSED |

### عيوب وُجدت وأُصلحت في هذه المرحلة

| العيب | المصدر | الإصلاح | الاختبار |
|---|---|---|---|
| انقطاع المقبس قبل إشعار فقد الشبكة يُسجَّل فشلًا نهائيًا | 1.3.2 | `recoverNetwork(lostNetwork=…)`: يُعامل كانقطاع فقط إن اختفت الشبكة المرتبطة فعلًا خلال 3 ث | `NetworkRecoveryTest` (+2)، 3 تجارب |
| مهمة تنتظر الشبكة بلا «استئناف»، و«بدء قائمة الانتظار» معطّل | تراجع من المرحلة D | زر «استئناف» للحالة `WAITING` ونص «بانتظار الشبكة المسموح بها» | `NetworkWaitResumeTest` |
| تصادم معرّفات الإشعارات (75 مع التذكير، 76 مع التشغيل المحلي) | تراجع من المرحلة E | 77 و78 | `SurfacesPhaseETest.everyNotificationOwnsADistinctId` |
| تذكير «بدء قائمة الانتظار» يتجاهل لغة التطبيق | 1.3.2 | `DososLocale.wrap` + قناة التقدم نفسها | تجربة المحاكي |
| اختبار إشعار الإيقاف/الفشل يقرأ قبل أن ينشر النظام الإشعار (سباق) | اختباري (المرحلة E) | انتظار حتى 5 ث | 3 × PASSED |
| قص النصوص عند خط 200% وعرض مفرط على اللوحي | المرحلة D | `FitText`، أسطر أكثر للأسماء، عرض أقصى 840dp | لقطات |

### ما لم يُختبر

| البند | الحالة | السبب |
|---|---|---|
| ملف ≥ 1 GB | NOT RUN | لا مورد مستقر بهذا الحجم ومساحة المحاكي محدودة |
| نفاد المساحة الفعلي على الجهاز | NOT RUN يدويًا | مغطى بـ `ResumeSpaceTest` و`StoragePolicyTest` (JVM) |
| شهادة TLS خاطئة / DNS rebinding يدويًا على الجهاز | NOT RUN | مغطى جزئيًا بـ `SafetyTest` و`LiteralNetworkBoundaryTest` (JVM) و`TlsVersionTest` (جهاز) |
| اتصال بطيء حقيقي | NOT RUN | خنق شبكة المحاكي غير دقيق (المرحلة D) |
| TalkBack يدويًا على كل الشاشات | NOT RUN | الأسماء مُتحقق منها برمجيًا فقط |
| Android 10–12 يدويًا | NOT RUN | الاختبارات الآلية للجهاز نجحت في CI على API 29–32 (انظر أعلاه) |
| مشغّلات غير Pixel، وجهاز ARM64 فعلي | BLOCKED | لا جهاز |

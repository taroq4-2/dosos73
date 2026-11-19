# AUDIT_BASELINE — dosos73 (المرحلة A)

| البند | القيمة |
|---|---|
| تاريخ الفحص | 2026-11-19 |
| النسخة المفحوصة | `main` @ `df233de42754aadb2976d54513799dab5d38353e` (الإصدار 1.3.2، versionCode 22) |
| فرع العمل | `feature/dosos73-vnext-overhaul` (محلي، غير مرفوع) |
| بيئة البناء | Windows 11 Pro، JDK 21.0.3 (JBR المرفق مع Android Studio)، Android SDK: platforms 34/35/36، build-tools 34/35، Gradle 8.14.6، AGP 8.10.1، Kotlin 2.0.21 |
| ملاحظة منهجية | كل رقم هنا مُقاس فعلًا في هذه البيئة أو منقول مع مصدره. ما لم يُقس مكتوب «غير مُقاس». |

## 1) الجرد

### الشيفرة
- **وحدة Gradle واحدة** (`:app`) وحزمة واحدة مسطّحة `com.os73.dosos73`، فيها 74 ملف Kotlin رئيسي (~10.5 ألف سطر) بلا فصل طبقات (presentation/domain/data كلها في نفس الحزمة).
- الواجهة Jetpack Compose + Material 3 (BOM 2024.10.01)، وسمة داكنة فقط (`NeonTheme.kt` يعرّف `darkColorScheme` فقط ولا يوجد `lightColorScheme`).
- **126 اختبار JVM** (`app/src/test`) و**193 اختبار instrumented** (`app/src/androidTest`)، بالإضافة إلى اختبارات Python في `tools/`.
- CI: سبعة workflows؛ الرئيسي `android.yml` يشغّل `testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease bundleRelease` ومصفوفة محاكيات API 29–36.

### الوحدات المطلوب فحصها
| الوحدة | الملف | المسؤولية الفعلية |
|---|---|---|
| MainViewModel | `MainViewModel.kt` (481 سطر) | حالة الإدخال والتحليل + مسودة الدفعات المشفرة + طابور الدفعات: **God ViewModel** |
| DirectDownloader | `DirectDownloader.kt` (322) | HEAD/Range probe + تنزيل مباشر مع استئناف + عميل OkHttp الآمن |
| MediaEngine | `MediaEngine.kt` (329) | تشغيل yt-dlp عبر Python أصلي + بروكسي CONNECT محلي + FFmpeg |
| AppRoot | `AppRoot.kt` (706) | الهيكل والتنقل والحوارات والآثار الجانبية |
| Transfers | `Models.kt` (كائن `Transfers`) | **مالك حالة واحد لتنزيل واحد** (single-flight) |
| DownloadQueue | `DownloadQueue.kt` (326) | طابور مُدوّن في journal مشفر، أولويات، إيقاف، تأجيل |
| PublicationReceipt / OutputStore | `PublicationReceipt.kt`, `OutputStore.kt` | نشر ذرّي إلى MediaStore/SAF مع إيصالات استعادة |
| Safety / NetworkSettings | `Safety.kt`, `NetworkSettings.kt` | تحقق URL، حظر العناوين الخاصة (SSRF)، سياسة الشبكة |

### المحركات الأصلية
- `io.github.junkfood02.youtubedl-android:library` و`:ffmpeg` 0.18.1 (Python + yt-dlp + FFmpeg + QuickJS) لأربع ABIs: `armeabi-v7a, arm64-v8a, x86, x86_64` (`app/build.gradle.kts:19-22`).
- **مهم:** APK الإصدار المنشور ليس مخرَج Gradle مباشرة؛ `tools/assemble_universal.py` يستبدل `lib/**` و`assets/dosos73/**` بحزمة runtime مقوّاة ومثبّتة بالـ hash (`dosos73-1.3.2-runtime-hardened.zip`، 51,457,132 بايت). لذلك أي نسخة ARM64 حقيقية تحتاج تصفية **تلك الحزمة** لا مجرد `-PtargetAbi`.
- R8 و`isShrinkResources` **مفعّلان مسبقًا** في Release (`app/build.gradle.kts:38-39`) — لن يُحتسبا إنجازًا.

## 2) نتائج البناء والاختبار الأساسي

الأمر (من جذر المستودع، Windows):

```
gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --console=plain --continue
```

| الخطوة | النتيجة | التفاصيل |
|---|---|---|
| `testDebugUnitTest` | **PASSED** | 126 اختبارًا، 0 فشل، 0 أخطاء، 0 متجاهل |
| `lintDebug` | **PASSED** | 81 ملاحظة، كلها Warning/Hint ولا Error. الأكثر: UseKtx 55، UnusedResources 10، GradleDependency 9 |
| `assembleDebug` | **PASSED** | `app-debug.apk` |
| `assembleRelease` | **PASSED** | `app-release-unsigned.apk` (غير موقّع، كما اتُّفق) |
| `bundleRelease` | **PASSED** | `app-release.aab` |
| الزمن الكلي | 111 ثانية | تشغيل بارد للـ daemon |
| `lintRelease` | NOT RUN | لم يُطلب في هذا التشغيل؛ يُضاف في المرحلة G |
| اختبارات instrumented (193) | NOT RUN | لا يوجد جهاز متصل بعد؛ المحاكي متاح، والجهاز الفعلي ينتظر توصيله |
| اختبارات Python في `tools/` | NOT RUN | تُشغَّل في المرحلة F مع أدوات التجميع |
| تحذيرات | — | ميزات Gradle مهملة تمنع الترقية إلى Gradle 9 |

> المحاولة الأولى فشلت بسبب خطأ مني في `local.properties` (مسار Windows بشرطة مائلة عكسية غير مهرّبة)، وليس بسبب المشروع. صُحّح الملف وأُعيد التشغيل.

## 3) الأحجام

### المخرجات المحلية (Gradle مباشرة، بدون runtime المقوّى)

| الملف | الحجم (بايت) | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 233,039,299 | `76005c1c2523e2e85ecaf5e1b29aecb988eb78ea341380dd562bca3c2a9d3981` |
| `app-release-unsigned.apk` | 202,457,714 | `757a7b20da7f294f5fa9fc5cb20a95265f4456756d3649d7ee8f37359cb41573` |
| `app-release.aab` | 203,146,965 | `8528d924c1d317994a67cf03566cfcfcc3b4edde84fb6df05048c35bcc94f1ac` |

### تفصيل `app-release-unsigned.apk` (مضغوط داخل ZIP)

| المكوّن | مضغوط | خام |
|---|---:|---:|
| `lib/x86_64` | 53.14 MB | 54.46 MB |
| `lib/arm64-v8a` | 50.15 MB | 51.41 MB |
| `lib/x86` | 48.74 MB | 50.02 MB |
| `lib/armeabi-v7a` | 43.50 MB | 44.35 MB |
| `res/` | 3.27 MB | 3.43 MB |
| DEX | 3.01 MB | 3.01 MB |
| `resources.arsc` | 0.41 MB | 0.41 MB |

أكبر مكتبات `arm64-v8a`: `libffmpeg.zip.so` 35.44 MB، و`libpython.zip.so` 14.05 MB، و`libqjs.so` 0.43 MB، و`libffmpeg.so` 0.14 MB، و`libffprobe.so` 0.08 MB.

**الاستنتاجات:**
- المكتبات الأصلية تمثل **~96%** من حجم APK. الـ DEX في Release 3 MB فقط (R8 يعمل)، بينما يبلغ 32.9 MB في Debug.
- ملف FFmpeg الذي تجلبه المكتبة من Maven (35 MB لكل ABI) هو أكبر عنصر. الإصدار المنشور يستبدله بمحرك مدمج أصغر عبر `assemble_universal.py`. لذلك **حجم 202 MB ليس حجم المستخدم الحقيقي**، والمقارنة العادلة تكون مع APK المنشور.
- حذف ثلاث ABIs من مخرج Gradle الحالي يعطي تقديريًا ~57 MB لنسخة ARM64 (50.15 + 3.27 + 3.01 + 0.41). هذا رقم محسوب، ولم يُبنَ بعد.

### الإصدار المنشور (من GitHub API، دون تنزيل)

| الأصل | الحجم (بايت) |
|---|---:|
| `dosos73-1.3.2-universal.apk` | 58,422,468 (يطابق `docs/RELEASE_1_3_2.md`) |
| `dosos73-1.3.2-runtime-hardened.zip` | 51,457,132 |

تفصيل الإصدار المنشور حسب كل ABI: **غير مُقاس**. يحتاج تنزيل الملفين أعلاه (~110 MB) من إصدارك على GitHub، وهذا ينتظر موافقتك.

### ملاحظات أخرى
- AAB موجود ويقسّم حسب ABI عند التوزيع عبر Play، لكن قياس الحجم المسلَّم يحتاج `bundletool`: **غير مُقاس**.
- 16 KB page size: وثائق 1.3.2 تذكر اختبارًا على Android 16 بصفحات 16 KiB. لم يُعَد التحقق هنا: **NOT RUN**.

## 4) قائمة المشكلات P0 / P1 / P2

### P0 — تمنع الاستخدام الأساسي

**P0-1 روابط APK والبرامج لا تُعامل كملفات مباشرة.**
- الدليل: `DirectDownloader.analyze()` يقبل فقط: `video/*`, `audio/*`, `application/octet-stream`, `pdf`, `zip`, `jpeg`, `png`, `gzip`, `7z` (`DirectDownloader.kt:95-110`). لا يوجد `application/vnd.android.package-archive` ولا RAR ولا DOCX/XLSX/PPTX ولا EXE/MSI/DMG/ISO ولا EPUB.
- الأثر: أي نوع خارج القائمة يُرجع `null` ⇒ `MainViewModel.analyzeSource()` يمرره إلى `MediaEngine.analyze()` (تشغيل Python/yt-dlp)، وهو مسار بطيء ومصمم للوسائط فيفشل غالبًا بخطأ عام. هذا يفسّر البلاغين معًا: «روابط البرامج لا تعمل» و«الانتظار الطويل».
- كذلك `Content-Type` فارغ أو `text/*` يُرسل للمحرك نفسه، فلا يوجد مسار «ملف مباشر بلا MIME».

**P0-2 لا تحقق من محتوى الملف الفعلي (magic bytes).**
- الدليل: `OutputStore.validate()` لا يفحص شيئًا لـ `Kind.FILE` سوى أن الحجم > 0 (`OutputStore.kt:64-71`). الفحص الوحيد ضد HTML هو ترويسة `Content-Type` في `HttpRepresentation.requireIdentity()`.
- الأثر: صفحة HTML تُقدَّم بـ `application/octet-stream` ورابطها ينتهي بـ `.apk` تُحفظ وتُعرض «مكتملة» كـ APK.
- أيضًا `DirectFileName.binaryExtensions` لا يضم `apk`، فلا يُفعَّل قيد `expectsBinary` على APK.

**P0-3 التحليل حاجب ومعتم.**
- الدليل: حالة الواجهة `ScreenState.busy: Boolean` واحدة (`MainViewModel.kt:14`) مع `LinearProgressIndicator` غير محدد (`HomeScreen.kt:71-72`). المهلة الكلية `withTimeout(120_000)` (`MainViewModel.kt:216`)، والمراحل متسلسلة: HEAD ⇒ (403/405/501 ⇒ Range GET) ⇒ (416 ⇒ GET) ⇒ عند الفشل تشغيل المحرك.
- لا مهلة منفصلة للمرحلة الشبكية في التحليل: العميل يستخدم `connectTimeout 20s` و`readTimeout 30s` (`DirectDownloader.kt:308-309`)، وقد يتكرر ذلك حتى 3 طلبات قبل الانتقال للمحرك (`--socket-timeout 25`, `--retries 2`).
- لا معلومات أولية تُعرض قبل اكتمال كل شيء، ولا تمييز «صفحة تنزيل» عن «ملف مباشر».

### P1 — جودة وتجربة ومعمارية

| المعرّف | المشكلة | الدليل |
|---|---|---|
| P1-1 | تنزيل واحد فقط في كل وقت؛ لا تنزيلات متزامنة | `Transfers.reserve()` يرفض أي مهمة أثناء أخرى نشطة (`Models.kt:96-103`) |
| P1-2 | إشعار التقدم فيه «إلغاء» فقط؛ لا Pause/Resume من الإشعار | `DownloadService.kt:317-328` |
| P1-3 | لا ودجت للشاشة الرئيسية | لا يوجد `AppWidgetProvider` في Manifest |
| P1-4 | سمة داكنة فقط؛ لا وضع فاتح ولا اتباع النظام | `NeonTheme.kt:52` |
| P1-5 | شريط التنقل مجبر على LTR حتى بالعربية، وأقسامه «الرئيسية/التنزيلات/الملفات/المزيد» لا تطابق «الرئيسية/التنزيلات/المكتبة/الإعدادات» | `AppNavigation.kt:19` |
| P1-6 | ألوان الهوية الحالية (`#070F1E`/`#19D7F5`) تختلف عن المرجع (`#0B1929`/`#14273A`/`#42D9CC`/`#E4F5F4`)، وبعض الألوان مكتوبة مباشرة خارج التوكنات | `NeonTheme.kt:27-38`, `AppNavigation.kt:22` |
| P1-7 | God ViewModel: التحليل والدفعات والمسودة المشفرة في صنف واحد | `MainViewModel.kt` |
| P1-8 | لا محوّلات لمصادر GitHub Releases / F-Droid؛ صفحة الإصدار تُرسل لـ yt-dlp | `MainViewModel.analyzeSource()` |
| P1-9 | الإعدادات بلا مظهر/إشعارات/عدد تنزيلات متزامنة؛ بعض إعدادات الشبكة موزعة | `SettingsScreen.kt` |

### P2 — تحسينات وتنظيف

| المعرّف | المشكلة | الدليل |
|---|---|---|
| P2-1 | `Safety.url()` يرفض أي منفذ غير 80/443 (قرار أمني مقصود يمنع بعض خوادم الملفات الشرعية) — يُبقى، مع رسالة سبب واضحة | `Safety.kt:12` |
| P2-2 | `usesCleartextTraffic="true"` على مستوى التطبيق (مبرر بالبروكسي المحلي وخيار HTTP الصريح؛ العميل يرفض HTTP العام افتراضيًا) — يُستبدل بـ `networkSecurityConfig` يسمح بـ loopback فقط إن أمكن | `AndroidManifest.xml:20` |
| P2-3 | `DeferredTransferJobService` مُصدَّر لكنه محمي بـ `BIND_JOB_SERVICE` — سليم، يُوثق فقط | `AndroidManifest.xml:42-43` |
| P2-4 | قائمة امتدادات MIME مكررة بين `DirectDownloader` و`DirectFileName` | الملفان |
| P2-5 | تحذيرات Gradle 9 (ميزات مهملة) | سجل البناء |

## 5) تقييم المواد السابقة (`previous_review/`)

| العنصر | النتيجة |
|---|---|
| `git apply --check` | **فشل**: قطعة Manifest لا تطابق السياق الحالي (`patch failed: AndroidManifest.xml:35`). باقي القطع تُطبَّق نصيًا. |
| قطعة MIME في `DirectDownloader` | الاتجاه صحيح لكنها **ناقصة**: تضيف 3 أنواع APK فقط، ولا تعالج RAR/Office/EXE، ولا `binaryExtensions`، ولا فحص المحتوى (P0-2). |
| الودجت | غير موصول بمصدر الحالة (يقرأ `Transfers.state` عند الطلب فقط)، بلا إجراء «تحميل جديد»، مستقبِل مُصدَّر يقبل إجراء refresh من أي تطبيق، وتصميم لا يطابق المرجع. |
| القرار | **لن تُطبّق الرقعة كما هي.** تُستخدم كمرجع، ويُعاد تنفيذ الإصلاحين في المرحلتين B وE مع اختبارات. |

## 6) مقاييس الأداء

| المقياس | القيمة |
|---|---|
| زمن التشغيل البارد | غير مُقاس (يحتاج جهازًا/محاكيًا — المرحلة F) |
| أول إطار | غير مُقاس |
| زمن ظهور حالة بعد اللصق | غير مُقاس؛ بالتحليل الساكن: لا حالة وسيطة أصلًا، فقط `busy` |
| زمن تحليل رابط مباشر / فيديو (P50/P95) | غير مُقاس (يحتاج خادم اختبار محلي + جهاز — المرحلة B/F) |
| الذاكرة / jank | غير مُقاس |

## 7) خطة التغيير القصيرة (المرحلة B — P0)
1. مُصنّف ملفات موحد (`FileTypeRegistry`): MIME + امتداد + magic bytes لـ APK/XAPK/APKS/AAB/ZIP/RAR/7Z/PDF/Office/EXE/MSI/DMG/ISO/EPUB/صور/صوت/فيديو، يحل محل القائمتين المكررتين.
2. `DirectDownloader.analyze()`: قبول أي تمثيل ثنائي، رفض HTML حتى لو انتهى الرابط بـ `.apk`، قراءة أول بايتات عبر `Range: bytes=0-N` للتحقق، تمييز «ملف مباشر» عن «صفحة تنزيل».
3. فحص المحتوى بعد التنزيل لـ `Kind.FILE` قبل النشر.
4. آلة حالات تحليل: `IDLE → VALIDATING → CONNECTING → PROBING → EXTRACTING → READY / FAILED / CANCELED` مع مهل منفصلة لكل مرحلة، وعدم تشغيل المحرك إلا لروابط صفحات/وسائط.
5. محوّلات GitHub Releases وF-Droid عبر الروابط/الواجهات الرسمية.
6. اختبارات MockWebServer لكل حالة في §14 من المواصفة.

# TEST_RESULTS — dosos73 vNext

سجل حيّ لنتائج الاختبارات الفعلية على فرع `feature/dosos73-vnext-overhaul`. كل سطر نتيجة تشغيل حقيقي في البيئة المذكورة. ما لم يُشغَّل مكتوب بوضوح.

**الحالات:** PASSED · FAILED · BLOCKED · NOT RUN

## البيئة

| البند | القيمة |
|---|---|
| المضيف | Windows 11 Pro، JDK 21.0.3 (JBR من Android Studio)، Gradle 8.14.6، AGP 8.10.1 |
| المحاكي | `dosos73_vnext`: Android 15 (API 35)، x86_64 مع ترجمة arm64، قرص بيانات 12 GB، ذاكرة 4 GB. أُنشئ لهذا العمل لأن المحاكي الأصلي `Medium_Phone_API_35` لم يكن فيه سوى 214 MB حرة، ولم يُحذف منه شيء |
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
| `DirectDownloaderTest` (فحص الرابط) | 15 جديدة ضمن 39 | APK مع `vnd.android` وoctet-stream وبلا MIME وZIP عام. `Content-Disposition` RFC 5987. HTML برابط `.apk` يُرفض حتى لو ادّعى نوعًا ثنائيًا. APK محتواه PDF يُرفض. RAR/7Z/EXE/DOCX/XAPK/APKS/EPUB. حالة 416 تُعاد بطلب GET عادي مرة واحدة دون تكرار. 404/410/403/401/503/429 مع `Retry-After`. سلسلة تحويلات 302→307. قراءة رأس محدودة لملف 1 MB في طلب واحد. JSON محدود الحجم وحد معدل GitHub |
| `DirectFileNameTest` | +2 | امتداد APK/XAPK محفوظ، واسم ISO-8859-1، ورفض انتحال الامتداد بحرف U+202E |
| `SourceResolversTest` | 7 | مطابقة روابط GitHub Releases وF-Droid، ونقاط API الرسمية، ورفض أصول خارج نفس الإصدار، وترتيب APK حسب ABI الجهاز |
| `LinkAnalyzerTest` | 11 | الملف المباشر لا يشغّل محرك الوسائط. الصفحة العامة تذهب للمحرك. الصفحة بدل الملف تفشل فورًا بسبب محدد. 404/410/عدم تطابق النوع لا تشغّل المحرك. مهلة الفحص مستقلة. اختيار أصول الإصدار. F-Droid |

### تجارب حقيقية على المحاكي (تطبيق Debug مثبّت، روابط حقيقية)

زمن «الجاهزية» يقيسه التطبيق نفسه (`DososPipeline` → `millis`) من الضغط على «تحليل» حتى ظهور النتيجة. كل تجربة نُفذت **مرة واحدة**، فهذه ليست P50/P95.

| الحالة | الرابط | النتيجة | الزمن | الحكم |
|---|---|---|---:|---|
| APK مباشر من GitHub (تحويلات حتى خادم الأصول) | `…/releases/download/v1.3.2/dosos73.apk` | ملف APK، «تم التحقق من المحتوى (APK)»، الحجم 55.72 MiB (يطابق 58,422,468 بايت)، تنبيه عدم التثبيت التلقائي | 1,935 ms ثم 1,048 ms | PASSED |
| صفحة إصدار GitHub | `…/releases/latest` | قائمة «اختر ملفًا من هذا الإصدار» بـ 3 ملفات APK أولًا ثم بقية الأصول مع أحجامها | 1,014 ms | PASSED |
| صفحة تطبيق F-Droid | `f-droid.org/packages/org.fdroid.fdroid/` | APK الإصدار المقترح `org.fdroid.fdroid_2000053`، تم التحقق، 12.18 MiB | 666 ms | PASSED |
| ملف غير موجود | `…/releases/download/v1.3.2/no-such-file.apk` | رسالة HTTP 404 محددة، دون تشغيل محرك الوسائط | فوري (~1 s) | PASSED |
| تنزيل كامل حتى الحفظ | APK F-Droid أعلاه | 12,774,751 بايت، VERIFY ثم PUBLISH، حُفظ في `Download/dosos73/` بنوع `application/vnd.android.package-archive`. SHA-256 على الجهاز: `dac55a0d17b1edf7e7286b48cfbd70c7c52fbb32a00af062c68830a437c47959` | ~5 s | PASSED |

لقطات الشاشة محفوظة محليًا في `build-evidence/phaseB-device/` (غير مرفوعة).

**للمقارنة:** في 1.3.2 كانت هذه الروابط الأربعة كلها تُحال إلى محرك الوسائط (Python/yt-dlp)، لأن نوع APK لم يكن في القائمة ولأن صفحات GitHub/F-Droid صفحات HTML. لم يُقس زمن 1.3.2 على هذا المحاكي: **غير مُقاس**.

### ما لم يُختبر بعد

| البند | الحالة | السبب |
|---|---|---|
| 50 تحليلًا لكل نوع لحساب P50/P95 | NOT RUN | المرحلة F |
| شهادة TLS غير صحيحة / DNS rebinding / رابط داخلي على الجهاز | NOT RUN | مغطاة جزئيًا باختبارات JVM القائمة (`SafetyTest`, `LiteralNetworkBoundaryTest`)؛ تجربة الجهاز في المرحلة G |
| ملفات ≥ 1 GB، انقطاع/استئناف، نفاد المساحة | NOT RUN | المرحلة G (منطق الاستئناف لم يتغير في هذه المرحلة) |
| ARM64 على جهاز فعلي | BLOCKED | لا جهاز متصل |

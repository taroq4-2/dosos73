# dosos73
### Download open-source OS73

<img src="branding/logo.png" width="112" alt="شعار dosos73" />

تطبيق أندرويد مفتوح المصدر لتنزيل الملفات والوسائط المدعومة، بواجهة عربية وإنجليزية، وحفظ الملفات في التنزيلات أو المجلد الذي تختاره.

## المميزات

- استقبال الروابط بالمشاركة أو اللصق.
- اختيار الصيغ والجودات التي يوفرها المصدر فعلًا.
- تنزيل الملفات دون سقف ثابت للحجم، مع مراعاة المساحة المتاحة.
- التحقق من سلامة الوسائط قبل إعلان اكتمال التنزيل.
- واجهة فاتحة، تقدم فعلي، وإشعار اكتمال يتيح فتح الملف.
- صفحة تعريف بالمشروع ونموذج تواصل يفتح تطبيق البريد.
- دون إعلانات أو حسابات أو تحليلات سلوكية أو مراقبة للحافظة.

## التنزيل والتثبيت

[تنزيل dosos73 1.0.0 لأندرويد](https://github.com/taroq4-2/dosos73/releases/download/v1.0.0/dosos73-1.0.0.apk) · [تفاصيل الإصدار](https://github.com/taroq4-2/dosos73/releases/tag/v1.0.0) · [بصمة SHA256](https://github.com/taroq4-2/dosos73/releases/download/v1.0.0/SHA256SUMS.txt)

يتطلب Android 10 أو أحدث. يدعم arm64-v8a وx86_64. الحزمة موقّعة بمفتاح إصدار مستقل.

**التثبيت لأول مرة:** افتح APK واسمح بالتثبيت من المصدر الذي تستخدمه. إذا كانت لديك نسخة Preview القديمة، احذفها أولًا لاختلاف التوقيع؛ سيؤدي ذلك إلى حذف إعدادات التطبيق، وتبقى الملفات المحفوظة في مجلد التنزيلات العام. الإصدارات الرسمية اللاحقة تستخدم المفتاح نفسه.

أكد مالك المشروع نجاح تنزيل YouTube على هاتفه في 26 أكتوبر 2026. اختبارات المحاكي المحلي رصدت أخطاء اتصال متقطعة؛ قد تتأثر الإتاحة بقيود المصدر والشبكة. [نتائج التحقق](docs/RELEASE_1.0.0.md).

## الاستخدام

1. شارك رابطًا نصيًا واحدًا أو الصقه في التطبيق.
2. اختر «تحليل»، ثم النوع والجودة والصيغة المتاحة.
3. اختر مكان الحفظ.
4. ابدأ التنزيل وافتح الملف بعد اكتماله والتحقق منه.

التنزيل يحتاج إلى مساحة للنسخة المؤقتة والملف النهائي، وقد يحتاج الدمج مساحة إضافية. التنزيل الملغى يبدأ من جديد؛ لا يوجد استئناف في النسخة الحالية. لا يضمن ظهور عنوان الفيديو إمكانية تنزيله. حمّل المحتوى الذي يحق لك حفظه فقط؛ لا يتجاوز التطبيق قيود الوصول أو DRM.

## صور التطبيق

صور فعلية من Android15. المثال المصوّر تنزيل مباشر لعينة عامة، وليس دليل نجاح YouTube.

<img src="screenshots/01-main-ar.png" width="220" alt="الرئيسية" /> <img src="screenshots/02-analyzed-ar.png" width="220" alt="الصيغ المتاحة" />
<img src="screenshots/03-download-ar.png" width="220" alt="التنزيل" /> <img src="screenshots/04-complete-ar.png" width="220" alt="اكتمال الحفظ" />
<img src="screenshots/05-about-ar.png" width="220" alt="من نحن" /> <img src="screenshots/06-contact-ar.png" width="220" alt="التواصل" />

## للمطورين

Kotlin وJetpack Compose؛ Android10+؛ minSdk29 وtargetSdk35؛ arm64-v8a وx86_64. يلزم JDK17 أو21 وAndroid SDK35.

~~~sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
~~~

على Windows استخدم gradlew.bat. اختبارات الوحدة لا تعتمد على الإنترنت؛ الاختبارات الحية منفصلة. للتوقيع اضبط DOSOS73_KEYSTORE وDOSOS73_STORE_PASSWORD في بيئة البناء. المفاتيح وبيانات الدخول غير موجودة في المستودع.

[الاختبارات](docs/V4_TEST_RESULTS.md) · [الإصلاحات](docs/REVIEW_FIXES.md) · [الأمان](docs/SECURITY.md) · [الأداء](docs/PERFORMANCE.md) · [مراجعة الكود](docs/FINAL_CODE_AUDIT.md) · [التراخيص](THIRD_PARTY_NOTICES.md)

## English

dosos73 is a native Android downloader with Arabic/English UI, source-derived format selection, scoped storage and streaming transfers without a fixed file-size ceiling. Storage and source restrictions still apply.

Version 1.0.0 uses a dedicated release signing key. The project owner confirmed working YouTube downloads on their phone. Automated build/device results and intermittent local emulator network failures are documented separately. Upgrading from the older debug-signed preview requires uninstalling that preview first.

First-party code: GPL-3.0-or-later. Dependencies and branding have separate terms. Developer: [OS73](https://os73.com). Contact: contact@os73.com.

# خريطة مشروع dosos73

الخريطة مبنية على قراءة ملفات Kotlin الحالية كاملة، بما فيها الاختبارات، وملفات البناء والموارد. لقطة جميع الملفات المتتبعة وأحجامها وبصماتها في [الجرد](audit/baseline-inventory.json). الثنائيات ليست شيفرة Kotlin: فحص جردها وتوقيعها وتشغيلها لا يساوي قراءة كل مصدر تبعية خارجية أو إثبات خلوها من العيوب.

```mermaid
mindmap
  root((dosos73))
    الواجهة والهوية
      MainActivity ودورة حياة أندرويد
      AppRoot توجيه الشاشات والحوارات
      SettingsScreen الإعدادات والمزيد
      AppUi وإدخال الروابط والاختيارات
      NeonTheme والألوان والخط والعلامة
      LibraryScreens الملفات والتنزيلات
      AdvancedScreens الخصوصية والدفعات والمعاينة
      InfoScreens التعريف والتواصل
    التحليل
      MainViewModel وScreenState
      BatchInput وBatchEntry وBatchDraft
      ChoiceSelection وModels
      DirectDownloader فحص الملف
      MediaEngine استخراج الصيغ
      EngineRuntime حاجز التهيئة الواحد
      BundledEngine المحرك المثبت
      PythonCommonRuntime ملفات مشتركة محلية
    الشبكة والأمان
      Safety الروابط والعناوين والأسماء
      PublicProxy أنفاق HTTPS المحلية
      NetworkSettings الشبكة المسموح بها
      ResumeJournal هوية المحتوى والنطاق
      DirectFileName اسم الخادم غير الموثوق
    التنزيل والخلفية
      DownloadService دورة حياة الخدمة والطابور
      TransferProcessor التحقق والنشر والاسترداد
      NativeProcessSession وProcessLease ملكية العملية وإلغاؤها
      DownloadQueue ملكية المهمة الدائمة
      DirectDownloader نقل متدفق واستئناف
      ParallelDownloader نطاقان ودمج متحقق
      MediaEngine تنزيل وتحويل ودمج
    الحفظ والمكتبة
      OutputStore تحقق وحفظ
      StreamIntegrity البصمة والحجم
      StoragePolicy المساحة دون حد حجم ثابت
      PublicationReceipt استرداد النشر
      LibraryRepository فهرس وصور مصغرة
      FileAvailability صلاحية الوصول
    البيانات والتشخيص
      PrivateJournal تشفير وتحديث ذري
      FailureJournal إعادة المحاولة
      TaskTimeline مراحل محدودة ومشفرة
      PipelineError تصنيف الأخطاء
      DisplayFormat وContactDraft
      DososApp تنظيف مؤقت محدود
    البناء والتحقق
      Gradle وR8 والموارد المترجمة
      اختبارات JVM
      اختبارات أندرويد ومزود أعطال Debug
      بناء FFmpeg وLAME المثبت
      تجميع APK موحد بأربع معماريات
      التوقيع وELF وSBOM وتقارير القبول
```

```mermaid
flowchart TD
  input[لصق أو مشاركة صريحة] --> model[MainViewModel]
  model --> direct[فحص مباشر]
  model --> native[استخراج وسائط محلي]
  direct --> review[اختيار الصيغة والمجلد]
  native --> review
  review --> queue[طابور مشفر وملكية مهمة]
  queue --> service[خدمة تنزيل أمامية أثناء العمل]
  service --> transfer[نقل مباشر أو محرك الوسائط]
  transfer --> verify[تحقق من الملف]
  verify --> plan[خطة نشر مشفرة]
  plan --> storage[MediaStore أو مجلد مصرح به]
  storage --> hash[مقارنة الحجم والبصمة]
  hash --> publish[نشر وفهرسة الملف]
  publish --> library[مكتبة ومعاينة محلية]
  service -. مراحل محدودة .-> timeline[TaskTimeline]
```

## التكرار المرصود قبل التعديل

- فحص Content-Encoding ونوع الاستجابة مكرر بين التنزيل المباشر والمتوازي.
- مسارا تحليل الرابط الواحد والدفعة يكرران اختيار الشبكة والفحص والرجوع للمحرك.
- إعدادات الشبكة تكرر صف المفتاح ومعالجة الحفظ والرسالة.
- النشاط يجمع التوجيه والإعدادات والحوار وإطلاق الأدوات؛ الخدمة تجمع الإشعارات والتنفيذ والتخزين.
- اشتقاق مجموعات المكتبة وتهيئة منسقات الأرقام والتاريخ يتكرر عند إعادة الرسم.
- العلامة الداخلية تفك صورة كاملة، بينما أيقونة التطبيق لها أصل متجهي مستقل.

الخريطة محدثة بعد فصل النشاط والخدمة وإضافة ملفات المحرك المشتركة. يسجل تقرير المراجعة التغييرات الفعلية ونتائجها بعد التنفيذ، ولا تعني الخريطة أن كل بند سابق أو جديد مغلق.

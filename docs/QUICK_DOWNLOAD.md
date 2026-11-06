# التنزيل السريع / Quick download

## الاستخدام

1. افتح **الإعدادات ← التنزيل السريع** وفعّل إجراء الرابط المنسوخ.
2. اربط إيماءة الهاتف بإجراء **تنزيل الرابط المنسوخ** إن ظهر في قائمة الهاتف. في أجهزة Galaxy المدعومة استخدم Good Lock ثم RegiStar؛ وفي Pixel المدعوم استخدم System → Gestures → Quick Tap → Open app.
3. انسخ رابطًا، وافتح قفل الهاتف، واستخدم الإيماءة. راجع الرابط واضغط «تحليل» لعرض النوع والصيغة والجودة ومكان الحفظ. يبدأ التنزيل بعد الضغط على «بدء».

يتطلب الربط اختيار المستخدم داخل إعدادات الهاتف. يتغير دعم الإيماءة وقائمة التطبيقات المتاحة حسب الجهاز والنظام والمنطقة. عند تعذر اختيار الإجراء المنفصل، يوجد خيار إضافي معطل افتراضيًا لعرض الرابط للمراجعة عند فتح التطبيق بالطريقة العادية. ويمكن استخدام اختصار الشاشة الرئيسية أو زر اللصق بدلًا من الإيماءة.

## الخصوصية والخفة

- الإجراء معطل افتراضيًا. تُقرأ لقطة نصية واحدة للحافظة بعد ظهور التطبيق وتركيزه وفتح قفل الجهاز. يبدأ الاتصال بالموقع عند اختيار «تحليل».
- لا توجد مراقبة للحافظة أو حساسات مستمرة أو خدمة وصول أو إذن للظهور فوق التطبيقات.
- تُرفض عناصر الملفات وIntent والنصوص الحساسة والمحتوى الأكبر من 8192 حرفًا، ولا يُفتح مزود ملفات من الحافظة.
- لا تؤدي استعادة التركيز أو دوران الشاشة إلى قراءة أخرى. تُرفض محاولة استبدال رابط مفتوح، وتُتجاهل بيانات التشغيل الخارجية. لا يبدأ تنزيل دون تأكيد.
- يستخدم الإجراء محرك التنزيل وخيارات الصيغ الموجودة، ولا يضيف مكتبة أو ملفات أصلية جديدة. هناك APK موحّد واحد للمعماريات الأربع.

## English

Enable **Settings → Quick download**, then select **Download copied link** in your phone's gesture configuration when available. Copy a media URL, unlock the phone and invoke the action. The app shows one clipboard snapshot for review. Choose **Analyze** to contact the site and display the format, quality and destination controls. Choose **Start** to download.

Supported Galaxy devices provide back tap through Good Lock/RegiStar, and supported Pixel devices provide it through Quick Tap. Device, OS, region and app-picker support vary. Assign the action in your phone settings. A separately enabled fallback shows copied links for review on ordinary launcher openings. Home-screen shortcuts and in-app Paste remain alternatives.

The feature adds no dependency, native payload, persistent sensor listener, clipboard monitor, accessibility service or overlay permission. Reads require an unlocked, resumed and focused activity. URI/Intent clipboard items and sensitive/oversized text are rejected without provider access. Rotation does not re-read the clipboard; busy input is preserved and rapid invocations do not stack download requests.

## حدود التحقق / Verification limits

Automated activity, clipboard, input and lifecycle tests can run on emulators. They do **not** verify physical back-tap recognition or a manufacturer's gesture picker. That final integration must be tried on the actual phone; no universal gesture reliability claim is made.

Official references: [Google Pixel gestures](https://support.google.com/pixelphone/answer/7443425?hl=en), [Samsung Good Lock / RegiStar](https://www.samsung.com/jp/explore/hint/good-lock-app/), [Android clipboard privacy](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data).

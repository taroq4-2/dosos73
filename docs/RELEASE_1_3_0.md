# dosos73 1.3.0 verification / التحقق من الإصدار

The signed universal APK has package ID `com.os73.dosos73`, version code `20`, minimum Android API `29`, and target API `36`. It contains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` in one 60,777,526-byte file. The signing certificate matches RC.6. [Release assets](https://github.com/taroq4-2/dosos73/releases/tag/v1.3.0)

الحزمة الموحّدة موقّعة بشهادة المشروع وتعمل ضمن نطاق أندرويد 10 فأحدث بحسب الحد الأدنى المعلن. تضم المعماريات الأربع في ملف واحد حجمه 60,777,526 بايت. [ملفات الإصدار](https://github.com/taroq4-2/dosos73/releases/tag/v1.3.0)

| Check / الفحص | Evidence / النتيجة |
| --- | --- |
| Project tests / اختبارات المشروع | 126 JVM cases passed and `lintRelease` passed. |
| APK / الحزمة | Release signature and 16 KiB APK alignment checks passed. The 608 native ELF files match RC.6 byte for byte. |
| RC.6 device test / تجربة المرشح | The project owner confirmed an upgrade preserving data, video download, and MP3 conversion. |
| 1.3.0 device test / تجربة الإصدار النهائي | The project owner reported successful testing of the exact 1.3.0 APK. Device model, Android version, and step logs were not supplied. |

APK SHA-256

```text
e23f7ba89c0f8d734aed79b9237c32f5acefbcae10cc2f7e319a8602040d29ba
```

The release includes an APK-bound CycloneDX SBOM and separate application and native source archives. Historical emulator matrices relate to the specific candidates identified in their reports. Physical coverage beyond the reported device and Google Play delivery remain outside this verification. [Compatibility matrix](audit/COMPATIBILITY_MATRIX.md)

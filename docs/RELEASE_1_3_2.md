# dosos73 1.3.2 — release evidence

This is the current verification record. Documents named for earlier versions are historical evidence and do not describe this APK.

## Identity

| Field | Value |
|---|---|
| Package | `com.os73.dosos73`, version 1.3.2, code 22 |
| APK | `dosos73-1.3.2-universal.apk` |
| Size | **58,422,468 bytes · 58.42 MB · 55.72 MiB** |
| Saving against 1.3.1 | 462,706 bytes, approximately 0.79% |
| Android | 10 and newer; ARM32, ARM64, x86, x86_64 in one file |
| APK SHA-256 | `19c5c5f3d69d635472a2d0fd6cfc1528250e2aba3f62e32a291fd3ea324d431e` |
| Certificate SHA-256 | `bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9` |
| Application/build input source | `1d4e0e62746c986ba4be0adcf078020d78c26de7` |
| Runtime bundle SHA-256 | `5e7625f73c72d16b9e96025c9744351c99029a6242e10e6f81cde19f7efa0532` |

Later screenshot, test-driver and documentation commits do not alter the APK. The existing certificate permits in-place updates. Engines remain bundled, including MP3 conversion. No ABI, codec or extractor was removed for this size reduction; only eight CPython test extensions per ABI were removed after import checks and runtime regression tests.

## Changes

- Failing download A no longer clears independent options for link B.
- Paused playback releases its player and service after the idle timeout while retaining a process-local bookmark. Resume reopens the file at that position. Stop or process termination clears it.
- Preview follows video dimensions; seeking exposes elapsed time and duration.
- Shared links wait for Review by default. Automatic analysis is a separate opt-in setting; Start still authorizes saving.
- Native plain HTTP media downloads retain verified partial files. Append requires matching source/final URL identities, strong ETag, complete Content-Range and prefix digest. Changed or unprovable representations restart safely. Segmented streams and changed signed links may restart; every YouTube download is not guaranteed to resume.
- Library snapshots are cached and parsed as a stream. A bounded atomic additions journal avoids rewriting large snapshots for each file. Generation/digest checks reject stale journals. Thumbnail requests support cancellation and release memory when hidden or under pressure.
- OpenSSL 3.5.9 includes five official security backports on all four ABIs, retaining exported symbols, versioned SONAMEs and hardening.
- Portable universal assembly validates a pinned runtime bundle. The device workflow tests that native payload; a separate workflow exercises the production-signed APK.

## Recorded tests

[Source and pinned-runtime matrix](https://github.com/taroq4-2/dosos73/actions/runs/43970500455) passed Android APIs **29–36**. Each runner reports **193 cases** including optional/SDK-conditioned assumptions, not 193 live network tests. API 35 separately passed public MP4 extraction/download through the native engine. API 36 repeated the earlier duplicate-review scenario three times successfully. The earlier timeout is not represented as a proven application crash or a diagnosed platform defect.

The run also passed **126 JVM tests**, **59 Python tests**, Debug/Release lint, Release assembly and bundle checks. Dependency lookup queried **85 Maven + 4 PyPI coordinates**, with no returned advisories at the recorded time; native review is separate.

[Exact signed acceptance](https://github.com/taroq4-2/dosos73/actions/runs/43972220946) binds results to the APK SHA-256 above. It exercises public MP4/MP3 downloads with exact output hashes, offline MP3 conversion, background playback, paused-resource release, bookmark resume, Arabic at 200% font, rotation captures and absence of transfer/playback services when idle. Android 16 uses **16 KiB pages with compatibility fallback disabled**. Publication requires successful final receipts.

| Incremental library fixture | Android 15 emulator | Android 16 emulator |
|---|---:|---:|
| Load snapshot, 1,000 rows | 12 ms | 9 ms |
| Add one row, 1,000 rows | 3 ms | 4 ms |
| Load snapshot, 10,000 rows | 91 ms | 70 ms |
| Add one row, 10,000 rows | 9 ms | 19 ms |

At 10,000 rows the unchanged snapshot was 1,185,565 bytes; the addition journal was 293 bytes. These are single emulator fixture measurements, not phone latency guarantees or end-to-end MediaStore benchmarks. Legacy migration still requires a full read. No database dependency was added.

## Security, package and source

Backports address `CVE-2026-88822`, `CVE-2026-88823`, `CVE-2026-95691`, `CVE-2026-95692` and `CVE-2026-107496`. The source fixes precede scheduled upstream 3.5.10; the delivered version is **patched 3.5.9**. [Official advisory](https://openssl-library.org/news/secadv/20261110.txt) · [Pinned patches](../tools/patches/openssl/manifest.json) · [Four-ABI producer](https://github.com/taroq4-2/dosos73/actions/runs/43968974251)

The CycloneDX 1.6 SBOM has **674 components and 576 deployed ELF records**. All 568 retained ELF files match 1.3.1 byte-for-byte; eight OpenSSL ELF files match the producer artifacts. The removed 32 files are CPython test extensions. All 64-bit ELF load segments pass 16 KiB alignment/congruence checks. APK signature and ZIP alignment passed.

The native source archive preserves the prior corresponding-source bundle unchanged and adds OpenSSL source, patches, recipe and configuration. Receipts do not prove every inherited build is bit-for-bit reproducible. [Packaging](COMPACT_ENGINE.md) · [Notices](../THIRD_PARTY_NOTICES.md)

## Coverage limits

Physical ARM/OEM battery, Bluetooth and power behavior; long-term CPU/battery measurements; a complete TalkBack user session; all storage providers; every live site/codec; and every process-death or real full-disk condition remain outside this matrix. Current live acceptance uses public MP4/MP3 fixtures, not a new YouTube acceptance. The owner's earlier successful YouTube report is separate evidence. Background playback consumes resources even without network access; zero consumption is not claimed.

## ملخص بالعربية

الحزمة الموحّدة **58.42 ميغابايت**، أصغر بمقدار **462,706 بايت**، وبهوية التوقيع نفسها. عولج مسح خيارات رابط عند فشل تنزيل آخر، وحُفظ موضع التشغيل عند تحرير الموارد، وأضيف استئناف متحقق منه للمصادر المتوافقة، وتحسين تدريجي لفهرس الملفات، وإلغاء المصغرات، وإعداد واضح لخصوصية المشاركة، وضبط أبعاد الفيديو ووصف شريط الوقت.

نجحت مصفوفة Android 10–16 مع المحرك المثبّت بالبصمة. عدّاد 193 يشمل الحالات الاختيارية المتجاوزة. نجح الاختبار المباشر للمحرك على Android 15 وتكرار سيناريو الرابط المكرر ثلاث مرات على Android 16. فحص الملف الموقّع نفسه مرتبط بالرابط أعلاه وببصمته؛ لا يُعتمد نجاح Debug وحده لإطلاقه.

حُدّثت OpenSSL بخمسة تصحيحات رسمية مع إبقاء حماية المكدس وواجهات المكتبات. خُفض الحجم بإزالة وحدات اختبار Python مع إبقاء المعماريات الأربع ومحركات التنزيل والتحويل. قياسات البطارية الطويلة وأجهزة ARM الفعلية وجلسة TalkBack الشاملة خارج نطاق الاعتماد. الاستئناف الآمن لا يضمن إكمال كل رابط YouTube متغيّر دون إعادة البدء.

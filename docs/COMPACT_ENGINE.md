# Compact universal delivery

## Current 1.3.2 packaging

One **58,422,468-byte** signed APK contains all four ABIs and local media engines. APK download size is distinct from installed/extracted runtime size. [Current evidence](RELEASE_1_3_2.md)

Use JDK 17 or 21, Android SDK 36/build-tools 35.0.0 and Python 3. Gradle builds the application; the portable assembler validates the runtime archive SHA-256, every member hash and expected path layout. Consuming it needs no historical APK, 7-Zip or machine-specific path.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
curl --fail --location --output universal-runtime.zip https://github.com/taroq4-2/dosos73/releases/download/v1.3.2/dosos73-1.3.2-runtime-hardened.zip
python3 tools/assemble_universal.py --app app/build/outputs/apk/release/app-release-unsigned.apk --runtime universal-runtime.zip --lock tools/universal-runtime.json --output universal-unsigned.apk
zipalign -P 16 4 universal-unsigned.apk universal-aligned.apk
# Sign with your protected key. Never commit passwords or keys.
apksigner verify --verbose --print-certs signed-universal.apk
zipalign -c -P 16 4 signed-universal.apk
python3 tools/audit_elf_alignment.py signed-universal.apk elf-alignment.json
```

Assembly requires fresh output filenames. A different signing certificate cannot update the official installation in place. Production signing stays outside CI; the output report binds Gradle, runtime and unsigned payload hashes.

`prepare_runtime_bundle.py` produced this input from hash-verified 1.3.1 plus source-built OpenSSL. It verifies retained bytes and modes, removes exactly eight CPython test extensions per ABI and replaces two OpenSSL libraries per ABI. The lock records all removals, replacements and hashes. Other native components, including patched FFmpeg 9.0.2 and LAME 3.100, are unchanged. No codec, extractor, ABI or local MP3 functionality is removed. There is no first-use engine download.

The native source archive for 1.3.2 preserves previous corresponding sources and adds the official OpenSSL base, five patches, recipe and configuration. The producer uses NDK 28.2.13676358 with stack protection and original architecture flags. [Producer](https://github.com/taroq4-2/dosos73/actions/runs/43968974251)

This is checked portable packaging with pinned provenance, not a claim of complete source-built bit-identical reproduction of all inherited components.

تبني Gradle كود التطبيق، ثم تجمع أداة Python مدخل التشغيل الحالي المثبّت بالبصمة، دون الاعتماد على APK قديم لدى مستخدم الوصفة. المصادر السابقة والتصحيحات الجديدة محفوظة في أرشيف الإصدار، حتى بعد إزالة الإصدارات القديمة من صفحة التنزيل. التوقيع محلي ومحمّي.

## Historical 1.2.0 recipe

The remainder is retained for source history; use the current commands above for 1.3.2.

Release1.2.0 is one **62,059,289-byte** signed APK for all four supported ABIs. Engines run locally; no remote processing service or first-use native download is introduced. Compared with the earlier unoptimized204,892,299-byte universal build, delivery is about69.7% smaller by file length. This preserves the features exercised by [current verification](VERIFICATION.md), without claiming equivalence for every historical codec/protocol path.

## Composition

- Standard Gradle/R8/resource-shrunk release as the application base.
- Wrapper Python/QuickJS runtime: retained entries are checked byte-for-byte; only the build-time `usr/lib/quickjs/libquickjs.a` static archive is removed.
- FFmpeg/ffprobe **9.0.2**, pinned commit `946fcce07b6dcd0331c8cc609192aeff5e1924f8`, shared build.
- LAME **3.100**, pinned archive SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`, linked into the native build for real MP3 V0 conversion.
- Native FFmpeg network protocols disabled; downloader/extractor handle tested network paths. HLS/DASH fixups and broad protocol/codec equivalence still require a larger matrix.

## Build

Use the pinned NDK/build configuration in [build_compact_ffmpeg.sh](../tools/build_compact_ffmpeg.sh), or the [native workflow](../.github/workflows/compact-engine.yml). Its unsigned artifacts are development intermediates, not installable official downloads.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
python3 tools/fetch_runtime_overrides.py runtime-overrides
python3 tools/assemble_compact_experiment.py app/build/outputs/apk/release/app-release-unsigned.apk compact-engines compact-universal-unsigned.apk --runtime-overrides runtime-overrides --ffmpeg-version 9.0.2 --lame-version 3.100
zipalign -P 16 -f 4 compact-universal-unsigned.apk compact-universal-aligned-unsigned.apk
# Sign with the owner's protected key; never commit the key or password.
apksigner verify --verbose --print-certs signed-universal.apk
python3 tools/audit_elf_alignment.py signed-universal.apk elf-alignment.json
```

`compact-engines` contains an ABI directory for each target, with ffmpeg, ffprobe and usr/lib/*.so from the native workflow. Retain the accompanying config/license files. Source code/build recipe is open; publication signing uses the existing protected key. Refer to [third-party notices](../THIRD_PARTY_NOTICES.md) for component license scope. Full Python/native source correspondence and physical 16KB runtime review remain open.

The assembler writes `compact-universal-unsigned.runtime-proof.json`. Retain this exact proof with the APK payload; the compact workflow uploads it beside the aligned unsigned artifact. `generate_sbom.py --engine-proof` requires the artifact-bound proof and rejects unrelated payloads. Alignment/signing do not replace the proof with a new, unverified declaration.

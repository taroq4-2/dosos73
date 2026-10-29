# Compact universal delivery

RC4 is one **72,758,008-byte** signed APK containing the four supported ABIs. Both extraction and MP3 conversion work locally; no remote processing service or first-use native download is introduced.

The earlier unoptimized universal measured 204,892,299 bytes. Current size is about 64.5% smaller by file length, while retaining the application features exercised in [VERIFICATION.md](VERIFICATION.md). This does not prove equivalence for every old codec/protocol path.

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
python3 tools/assemble_compact_experiment.py app/build/outputs/apk/release/app-release-unsigned.apk compact-engines compact-universal-unsigned.apk
zipalign -P 16 -f 4 compact-universal-unsigned.apk compact-universal-aligned-unsigned.apk
# Sign with the owner's protected key; never commit the key or password.
apksigner verify --verbose --print-certs signed-universal.apk
python3 tools/audit_elf_alignment.py signed-universal.apk
```

`compact-engines` contains an ABI directory for each target, with ffmpeg, ffprobe and usr/lib/*.so from the native workflow. Retain the accompanying config/license files. Source code/build recipe is open; publication signing uses the existing protected key. Refer to [third-party notices](../THIRD_PARTY_NOTICES.md) for component license scope. Full Python/native source correspondence and physical 16KB runtime review remain open.

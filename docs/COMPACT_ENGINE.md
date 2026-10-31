# Compact universal delivery

The public RC5 universal APK is **61,944,601 bytes**. The previously accepted 1.2.0 launch candidate measured **62,018,329 bytes**; newer source changes require a fresh artifact measurement and acceptance before publication. All four supported ABIs remain in one APK. Extraction and MP3 conversion run locally without a remote processing service or first-use native download.

The earlier unoptimized universal measured 204,892,299 bytes. The accepted 1.2.0 candidate was about 69.7% smaller by file length, while retaining the application features exercised in its acceptance tests. Public RC5 evidence is recorded in [VERIFICATION.md](VERIFICATION.md); in-progress launch evidence is separate in [the checkpoint](launch/CHECKPOINT.md). This does not prove equivalence for every old codec/protocol path.

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

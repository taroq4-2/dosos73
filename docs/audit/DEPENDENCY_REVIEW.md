# Dependency and CI review

The 4 November native candidate uses receipt-verified CPython 3.12.16,
OpenSSL 3.5.9, Expat 2.9.0, XZ 5.8.4 and SQLite 3.54.1. The exact source/tool
inputs, build configuration, NDK libc++ bytes and four-ABI payload hashes were
checked. [Producer run 41730382903](https://github.com/taroq4-2/dosos73/actions/runs/41730382903)
passed. This native update is separate from the Android Gradle/JVM dependency
PR decisions below.

The published default branch was at `2efeb7f1271516b13c3fc2ab767fe81753df81ac` and stable tag `v1.2.0` points to `852f269be743e394159cbbba4e465a2e80244801`. The investigation started from readiness SHA `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. The following PR decisions apply to that readiness branch.

| PR | Disposition | Reason |
|---|---|---|
| [#1](https://github.com/taroq4-2/dosos73/pull/1) AndroidX Core 1.19.1 | BLOCKED | Official Google Maven AAR metadata requires compile SDK 37 and AGP 9.1.0. |
| [#2](https://github.com/taroq4-2/dosos73/pull/2) Gradle setup action | Updated independently in this branch | Pinned v5.0.2. |
| [#3](https://github.com/taroq4-2/dosos73/pull/3) Android setup action | Updated independently | Pinned v4.0.4. |
| [#4](https://github.com/taroq4-2/dosos73/pull/4) Java setup action | Updated independently | Pinned v6.1.0 with Temurin 17. |
| [#5](https://github.com/taroq4-2/dosos73/pull/5) Checkout | Updated independently | Pinned v7.0.1 with persistence of credentials disabled. |
| [#6](https://github.com/taroq4-2/dosos73/pull/6) Upload artifact | Updated independently | Pinned v7.0.2. Manual download workflow uses pinned v8.0.2. |
| [#7](https://github.com/taroq4-2/dosos73/pull/7) Kotlin 2.4.21 | BLOCKED | [Kotlin 2.4 requires R8 9.1.29](https://developer.android.com/build/kotlin-support). Verify an AGP/Gradle/JDK migration and Compose compiler on a separate branch. |
| [#8](https://github.com/taroq4-2/dosos73/pull/8) OkHttp 5.5.1 | BLOCKED | Verified resolved Android artifact in PR #14 requires compile SDK 37. Recheck Android/JVM dependency alignment during a coordinated upgrade. |
| [#9](https://github.com/taroq4-2/dosos73/pull/9) Coroutines 1.11.0 | NOT VERIFIED | Official Maven endpoint failed TLS validation from this host. Confirm Kotlin metadata, cancellation and coroutine behavior on a compatible toolchain. |
| [#10](https://github.com/taroq4-2/dosos73/pull/10) Gradle 9.8.2 | NOT VERIFIED | Official checksum verified as `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`. Readiness uses verified 8.14.6. Evaluate compatibility with the selected AGP and Kotlin versions before updating. |
| [#14](https://github.com/taroq4-2/dosos73/pull/14) MockWebServer 5.5.1 | BLOCKED | [Run 41389319357](https://github.com/taroq4-2/dosos73/actions/runs/41389319357) failed at `:app:checkDebugAarMetadata`. The test-only bump selected `okhttp-android:5.5.1` and required compile SDK 37. |

The Gradle verification task checks resolved OkHttp and MockWebServer versions against a single declared version on both JVM-test and release runtime graphs. It requires the fixture on the JVM-test graph and rejects it on the release graph. Reproduce with:

```sh
./gradlew :app:verifyNetworkTestStack :app:dependencyInsight --configuration debugUnitTestRuntimeClasspath --dependency com.squareup.okhttp3
```

The latest three merged PRs returned at the baseline were #11, #12 and #13. The queried issue window returned no ordinary issues. All upstream PRs retain their original disposition on GitHub.

## Advisory coverage

Local OSV lookup returned zero matches across 87 queried Maven/Python coordinates. The build-tool lookup returned one scoped reviewed finding out of four queries, Kotlin KAPT cache deserialization `GHSA-r937-wjx7-w2jp`. The disposition depends on absent KAPT integration and expires on 2026-12-02. Keep both the source and resolved-task guards. The lookup covers the inventoried coordinates only. Native producer provenance and redistribution review remain separate release gates in [SECURITY_REVIEW.md](../../SECURITY_REVIEW.md).

## Rebuild and packaging entry points

The native source/notices supplement contains the effective producer recipes,
source archives, patches, configuration and receipts for 7989327. Its manifest
and SHA-256 bind it to the selected APK. The inherited third-party producer
limits are listed in its README.

The normal application build uses the Gradle wrapper. The existing
`tools/build_compact_ffmpeg.sh` and `tools/assemble_compact_experiment.py`
produce the compact FFmpeg/LAME universal input. Apply the rebuilt Python core
after that step:

```sh
python3 tools/fetch_rebuilt_runtime.py native-core
ndk_tools="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
python3 tools/assemble_rebuilt_runtime.py \
  --apk compact-universal-unsigned.apk --artifacts native-core \
  --run-id 41730382903 --producer 79893270b85278b71d02a7aae0f4143921c2f6ac \
  --strip-tool "$ndk_tools/llvm-strip" --readelf-tool "$ndk_tools/llvm-readelf" \
  --output rebuilt-output
```

The fetch command uses an existing `GH_TOKEN` with same-repository Actions
read access. It checks pinned artifact IDs, producer identity, byte counts and
SHA-256. The source-built verification workflow builds and signs one temporary
debug test pair, then uses it across the API matrix.

Release signing continues through the existing project-owned signing process.
Validate the signed APK with `zipalign -c -P 16 4`, `apksigner verify`,
`tools/audit_elf_alignment.py` and `tools/generate_rebuilt_sbom.py`.
The SBOM command requires the checked baseline APK/SBOM/engine proof, rebuilt
integration proof and native artifact directory. Recheck live sources and the
intended Android devices against the exact signed hash.

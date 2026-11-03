# Dependency and CI review

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

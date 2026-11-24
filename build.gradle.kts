// AGP 9 compiles Kotlin itself (built-in Kotlin). This pins the Kotlin Gradle plugin it
// uses to 2.4.21, the first line with the fix for GHSA-r937-wjx7-w2jp (CVE-2026-53914).
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}
plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// CVE-2026-53914 affected KAPT incremental caches (fixed in Kotlin 2.4.20+). The project
// still uses no annotation processing; keep that guard so KAPT needs a new review.
tasks.register("verifyNoKapt") {
    doLast {
        allprojects.forEach { candidate ->
            check(!candidate.pluginManager.hasPlugin("org.jetbrains.kotlin.kapt") &&
                !candidate.pluginManager.hasPlugin("kotlin-kapt")) { "KAPT requires a new security review" }
            check(candidate.tasks.names.none { it.startsWith("kapt", ignoreCase = true) } &&
                candidate.configurations.names.none { it.startsWith("kapt", ignoreCase = true) }) {
                "KAPT tasks/configurations invalidate CVE-2026-53914 disposition"
            }
        }
    }
}

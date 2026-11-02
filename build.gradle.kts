plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// CVE-2026-53914 affects KAPT incremental caches. This project does not use
// annotation processing; keep that scoped advisory disposition executable.
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

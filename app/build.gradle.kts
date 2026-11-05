import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.os73.dosos73"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.os73.dosos73"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "1.3.0-rc.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        val selectedAbi = providers.gradleProperty("targetAbi").orNull
        require(selectedAbi == null || selectedAbi in supportedAbis) { "Unsupported targetAbi" }
        ndk { abiFilters += selectedAbi?.let { listOf(it) } ?: supportedAbis }
    }
    signingConfigs {
        val keyPath = System.getenv("DOSOS73_KEYSTORE")
        if (!keyPath.isNullOrBlank()) {
            create("production") {
                storeFile = file(keyPath)
                storePassword = System.getenv("DOSOS73_STORE_PASSWORD")
                keyAlias = "dosos73"
                keyPassword = System.getenv("DOSOS73_STORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    bundle { language { enableSplit = false } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}
// The HTTP fixture must exercise the same transport version as the application.
val okhttpVersion = "4.12.0"
dependencies {
    // Override obsolete transitive archive/JSON dependencies carried by the native wrapper.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("commons-io:commons-io:2.19.0")
    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyNetworkTestStack by tasks.registering {
    group = "verification"
    description = "Checks the resolved OkHttp and MockWebServer versions in production and JVM tests."
    doLast {
        val transport = setOf("okhttp", "okhttp-android", "okhttp-jvm", "mockwebserver")
        for (name in listOf("releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath")) {
            val components = configurations.getByName(name).incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .filter { it.group == "com.squareup.okhttp3" && it.module in transport }
            check(components.any { it.module != "mockwebserver" }) { "OkHttp transport missing from $name" }
            check(components.any { it.module == "mockwebserver" } == (name == "debugUnitTestRuntimeClasspath")) {
                "MockWebServer must be present only on the test runtime, not $name"
            }
            check(components.all { it.version == okhttpVersion }) {
                "OkHttp stack in $name must use $okhttpVersion; selected ${components.joinToString()}"
            }
        }
    }
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(verifyNetworkTestStack) }

// Catch missing translations in local checks and CI without a runtime dependency.
val verifyTranslations by tasks.registering {
    inputs.files(fileTree("src/main/res/values") { include("*.xml") },
        fileTree("src/main/res/values-ar") { include("*.xml") })
    doLast {
        fun keys(directory: String): Set<String> {
            val keys = file(directory).listFiles().orEmpty().filter { it.extension == "xml" }.flatMap { source ->
                val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)
                val strings = document.getElementsByTagName("string")
                (0 until strings.length).map { (strings.item(it) as org.w3c.dom.Element).getAttribute("name") }
            }
            check(keys.size == keys.toSet().size) { "Duplicate string keys in $directory" }
            return keys.toSet()
        }
        check(keys("src/main/res/values") == keys("src/main/res/values-ar")) { "Arabic and English string keys differ" }
    }
}
tasks.named("preBuild") { dependsOn(verifyTranslations) }


// Build evidence only; this inventory is never packaged in the APK.
tasks.register("exportRuntimeInventory") {
    val runtime = configurations.named("releaseRuntimeClasspath")
    val report = layout.buildDirectory.file("reports/audit/runtime-components.json")
    outputs.file(report)
    inputs.files(runtime)
    doLast {
        val components = runtime.get().resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            mapOf("group" to artifact.moduleVersion.id.group, "name" to artifact.name,
                "version" to artifact.moduleVersion.id.version, "type" to artifact.extension,
                "file" to artifact.file.name, "sha256" to digest.digest().joinToString("") { "%02x".format(it) })
        }.sortedBy { "${it["group"]}:${it["name"]}:${it["version"]}" }
        val destination = report.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(components)) + "\n")
    }
}

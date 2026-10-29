plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.os73.dosos73"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.os73.dosos73"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "1.2.0-rc.4"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Catch missing translations in local checks and CI without a runtime dependency.
val verifyTranslations by tasks.registering {
    inputs.files("src/main/res/values/strings.xml", "src/main/res/values-ar/strings.xml")
    doLast {
        fun keys(path: String): Set<String> {
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
            val strings = document.getElementsByTagName("string")
            val keys = (0 until strings.length).map { (strings.item(it) as org.w3c.dom.Element).getAttribute("name") }
            check(keys.size == keys.toSet().size) { "Duplicate string keys in $path" }
            return keys.toSet()
        }
        check(keys("src/main/res/values/strings.xml") == keys("src/main/res/values-ar/strings.xml")) { "Arabic and English string keys differ" }
    }
}
tasks.named("preBuild") { dependsOn(verifyTranslations) }

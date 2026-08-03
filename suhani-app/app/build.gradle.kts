import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Sarvam key comes from the repo's .env - never committed, but baked into
// this debug-only spike APK, which runs on our own phones.
val envFile = rootProject.file("../.env")
val sarvamKey: String = envFile.takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("SARVAM_API_KEY=") }
    ?.substringAfter("=")?.trim()
    ?: ""

android {
    namespace = "app.suhani.spikes"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.suhani.spikes"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.2-all-phases"
        buildConfigField("String", "SARVAM_API_KEY", "\"$sarvamKey\"")
        ndk { abiFilters += "arm64-v8a" } // every modern Android phone; halves the APK
    }

    buildFeatures { buildConfig = true }

    packaging {
        jniLibs {
            // Both the Microsoft onnxruntime AAR (Silero VAD) and sherpa-onnx's
            // AAR ship a libonnxruntime.so. Sherpa's JNI statically links its
            // copy and never dlopens this file, so Microsoft's (which its Java
            // API genuinely loads) must be the one packaged.
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Speaker verification (voice recognition). The static-link-onnxruntime AAR
    // variant is deliberate: the plain AAR bundles its own libonnxruntime.so,
    // which would collide with the Microsoft onnxruntime above (Silero VAD).
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}

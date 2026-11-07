plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.portfolio.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.portfolio.screentranslator"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Phones only: drop x86 / x86_64 native libs (ML Kit ships ~30 MB per ABI)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // On-device OCR (Latin + Japanese + Chinese) and on-device fallback translation
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Korean recogniser is used only to find text that is ALREADY Korean and skip it:
    // the ja/zh recognisers read Hangul as look-alike hanzi/kana and would "translate" it.
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")

    // Gemini / Gemma REST
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Claude official Java SDK
    implementation("com.anthropic:anthropic-java:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.titipong.pdfwriter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.titipong.pdfwriter"
        minSdk = 26
        targetSdk = 34
        versionCode = 65
        versionName = "7.4"
        // 64-bit ARM only (all current Galaxy tablets/phones): keeps native libraries for other CPUs out of the APK.
        // -PallAbis (timing test on an x86 emulator) keeps every CPU type.
        if (!project.hasProperty("allAbis")) ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release key: given by CI from the repository secrets SIGNING_KEYSTORE_B64 / SIGNING_PASSWORD
    // (env SIGNING_KEYSTORE = path of the decoded .jks). Never commit the .jks.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "pdfwriter"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release key when available; otherwise the debug key (so the APK still installs).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// OCR language data: Tesseract "best" models (most accurate) for Thai + English, downloaded at build
// time (about 30 MB, so not kept in the repository).
val tessdataDir = file("src/main/assets/tessdata")
val fetchTessdata by tasks.registering {
    val langs = listOf("tha", "eng")
    outputs.files(langs.map { File(tessdataDir, "$it.traineddata") })
    doLast {
        tessdataDir.mkdirs()
        for (l in langs) {
            val f = File(tessdataDir, "$l.traineddata")
            if (f.exists() && f.length() > 0) continue
            val tmp = File(tessdataDir, "$l.tmp")
            URL("https://github.com/tesseract-ocr/tessdata_best/raw/main/$l.traineddata")
                .openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            check(tmp.length() > 1_000_000) { "Downloading $l.traineddata failed" }
            tmp.renameTo(f)
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchTessdata) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.tesseract4android)
    // Handwriting to text (on device; language models downloaded once)
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

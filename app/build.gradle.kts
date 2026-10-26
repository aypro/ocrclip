plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.ocrclip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.ocrclip"
        minSdk = 30 // AccessibilityService.takeScreenshot
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Retroid Pocket Classic (and nearly every current device) is arm64; drops the other ABIs' copies of the OCR model.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}

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
        versionCode = 14
        versionName = "0.6.2"

        // Retroid Pocket Classic (and nearly every current device) is arm64; drops the other ABIs' copies of the OCR model.
        ndk { abiFilters += "arm64-v8a" }
    }

    // libllm.so: llama.cpp (the third_party/llama.cpp submodule) for the offline translation model.
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}

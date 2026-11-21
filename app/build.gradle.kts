plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sumatradroid.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sumatradroid.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // MuPDF ships native libraries for these ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly (sideloading).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("net.timelegend.mupdf:fitz:1.24.9.2")
}

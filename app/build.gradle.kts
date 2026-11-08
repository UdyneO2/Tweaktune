import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Versi bisa di-override dari CI: -PversionName=1.2-beta -PversionCode=12
val vName = (project.findProperty("versionName") as String?) ?: "1.2-beta"
val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 3

android {
    namespace = "com.tweak.tuner"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Lokal tanpa keystore -> fallback ke debug key; di CI selalu pakai keystore rilis
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    defaultConfig {
        applicationId = "com.tweak.tuner"
        minSdk = 27
        targetSdk = 34
        versionCode = 18
        versionName = "1.9"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { aidl = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

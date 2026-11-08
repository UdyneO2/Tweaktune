import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
/ Keystore bersama untuk debug + release (diisi oleh GitHub Actions / gradle.properties lokal)
val ksPath: String? = System.getenv("KEYSTORE") ?: (findProperty("KEYSTORE") as String?)
fun secret(name: String): String? = System.getenv(name) ?: (findProperty(name) as String?)

android {
    namespace = "com.tweak.tuner"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    
       signingConfigs {
        if (ksPath != null) {
            create("shared") {
                storeFile = file(ksPath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") { if (ksPath != null) signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null) signingConfig = signingConfigs.getByName("shared")
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

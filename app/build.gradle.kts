plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ABIs to package. Phones are arm64-v8a; pass -Pparallax.abis=arm64-v8a,x86_64 for an emulator.
val abis = (findProperty("parallax.abis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

android {
    namespace = "io.github.foadsf.parallax"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.foadsf.parallax"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Optional shared debug key, as in the author's other apps: if `debug.keystore` exists next to
    // settings.gradle.kts (it is gitignored), every machine signs identically. Otherwise AGP's
    // per-machine ~/.android/debug.keystore is used.
    val sharedDebugKey = rootProject.file("debug.keystore")
    if (sharedDebugKey.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = sharedDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local and CI release builds are signed with the debug key so they install directly.
            // A store release is re-signed with the maintainer's key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    // No Google-encrypted dependency blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.opencv)
}

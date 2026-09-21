plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fourgeailabs.bpwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fourgeailabs.bpwatch"
        minSdk = 30 // Wear OS 3+
        targetSdk = 34
        // Kept in sync with :mobile (see its versioning policy comment).
        versionCode = 19
        versionName = "1.15.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    signingConfigs {
        // Pinned debug keystore shared with :mobile — see the comment there.
        // Both APKs must keep a stable signature or updates wipe user data.
        getByName("debug") {
            storeFile = rootProject.file("keystore/bpwatch-debug.keystore")
            storePassword = "android"
            keyAlias = "bpwatch-debug"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        // Needed so the watch UI can show its own version (update check).
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val wearComposeVersion = "1.3.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.wear.compose:compose-material:$wearComposeVersion")
    implementation("androidx.wear.compose:compose-foundation:$wearComposeVersion")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
}

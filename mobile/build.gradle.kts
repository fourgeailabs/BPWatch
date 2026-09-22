plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.fourgeailabs.bpwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fourgeailabs.bpwatch"
        minSdk = 26
        targetSdk = 34
        // Versioning policy (semver): MAJOR.MINOR.PATCH
        //   MAJOR — milestones / breaking changes
        //   MINOR — new features (continues our v1..v11 iteration count)
        //   PATCH — bug fixes on the current MINOR line
        // versionCode must increase by >= 1 every release for Android.
        versionCode = 33
        versionName = "2.4.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    signingConfigs {
        // Pinned debug keystore (checked into keystore/, same key on every
        // machine and CI run). CI runners generate a fresh ephemeral debug
        // key per run, which made every build's signature differ and forced
        // an uninstall (wiping all settings) on every update. With one
        // stable key, updates install over the top and data is preserved.
        // NOTE: this is a *debug* key — a proper release key (kept secret)
        // must be used if BPWatch ever ships to the Play Store.
        getByName("debug") {
            storeFile = rootProject.file("keystore/bpwatch-debug.keystore")
            storePassword = "android"
            keyAlias = "bpwatch-debug"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        // Needed so UI can read the version dynamically (no hard-coded strings).
        buildConfig = true
    }

    sourceSets {
        named("main") {
            // The watch APK bundled by bundleWearApk (built from :wear).
            assets.srcDir(layout.buildDirectory.dir("generated/wearApk"))
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
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Pinned past the compose BOM (2024.06.00 -> 1.2.1) for Material 3
    // Expressive (MaterialExpressiveTheme). Safe: 1.3.1 targets compose 1.7.0,
    // exactly what this BOM pins.
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Wireless-debugging pairing (TLS 1.3 + SPAKE2) and the AES-128-GCM peer-info
    // cipher. Conscrypt is required because Android's public TLS API does not
    // expose the TLS exporter used to derive the SPAKE2 password.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
}

/**
 * Builds the watch APK and stages it as a phone asset (bpwatch-wear.apk) so
 * the phone app can install/update the watch app over Wi-Fi debugging with
 * no PC involved. Runs before every phone build, so the bundled watch app is
 * always the current one.
 */
val bundleWearApk = tasks.register<Copy>("bundleWearApk") {
    dependsOn(":wear:assembleDebug")
    from(rootProject.file("wear/build/outputs/apk/debug/wear-debug.apk"))
    into(layout.buildDirectory.dir("generated/wearApk"))
    rename { "bpwatch-wear.apk" }
}

// Make sure the asset exists before the phone APK is assembled.
tasks.named("preBuild") { dependsOn(bundleWearApk) }

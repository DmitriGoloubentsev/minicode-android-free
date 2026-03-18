plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.minicode"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.minicode"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.4.5"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            // Clean FOSS build for F-Droid — no proprietary binary blobs
        }
        create("play") {
            dimension = "distribution"
            // Google Play Store Pro — includes sherpa-onnx for offline voice
        }
        create("playFree") {
            dimension = "distribution"
            applicationIdSuffix = ".free"
            // Google Play Free — same as foss features but with update checker
        }
        create("playDev") {
            dimension = "distribution"
            applicationIdSuffix = ".dev"
            // Direct download from minicode.app — coexists with Play Store version
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk { debugSymbolLevel = "FULL" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SSH - Apache MINA SSHD
    implementation("org.apache.sshd:sshd-core:2.12.1")
    implementation("org.apache.sshd:sshd-sftp:2.12.1")

    // EdDSA support (required by MINA SSHD for ed25519 keys)
    implementation("net.i2p.crypto:eddsa:0.3.0")

    // SLF4J (MINA SSHD logs through SLF4J - needs a binding or it crashes)
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // sora-editor (code editor with syntax highlighting)
    implementation(platform("io.github.Rosemoe.sora-editor:bom:0.23.6"))
    implementation("io.github.Rosemoe.sora-editor:editor")
    implementation("io.github.Rosemoe.sora-editor:language-textmate")

    // Sherpa-ONNX offline speech recognition (NVIDIA Parakeet TDT) — play & playDev flavors
    "playImplementation"(files("libs/sherpa-onnx-1.12.28.aar"))
    "playDevImplementation"(files("libs/sherpa-onnx-1.12.28.aar"))

    // Apache Commons Compress for tar.bz2 model extraction (play & playDev flavors — model download)
    "playImplementation"("org.apache.commons:commons-compress:1.26.1")
    "playDevImplementation"("org.apache.commons:commons-compress:1.26.1")
}

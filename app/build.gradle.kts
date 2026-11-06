plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.im6705.ardogy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.im6705.ardogy"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("0.1.0-dev").get()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // This intentionally PUBLIC development key preserves updates between prereleases.
    // It is not a production identity. Never use it to sign a Play Store release.
    signingConfigs {
        create("development") {
            storeFile = rootProject.file("signing/development.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("development") }
        release {
            signingConfig = signingConfigs.getByName("development")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    androidResources { noCompress += listOf("glb", "filamat", "ktx") }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("io.github.sceneview:arsceneview:2.3.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.asern.reelsaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.asern.reelsaver.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
    }

    // Fixed signing key so each new version installs over the previous one
    signingConfigs {
        getByName("debug") {
            storeFile = file("reelsaver.keystore")
            storePassword = "reelsaver"
            keyAlias = "reelsaver"
            keyPassword = "reelsaver"
        }
    }

    // One APK per CPU type to keep size down (most phones are arm64-v8a)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

val ytdl = "0.18.1"
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.asern.reelsaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.asern.reelsaver"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // مفتاح ثابت باش كل نسخة جديدة تتركّب فوق القديمة
    signingConfigs {
        getByName("debug") {
            storeFile = file("reelsaver.keystore")
            storePassword = "reelsaver"
            keyAlias = "reelsaver"
            keyPassword = "reelsaver"
        }
    }

    // APK منفصل لكل معالج حتى يكون الحجم أصغر (أغلب التلفونات arm64-v8a)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

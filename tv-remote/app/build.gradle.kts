plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.lgremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.lgremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    // Ключ подписи копируется из соседнего приложения в CI (см. workflow),
    // чтобы новые сборки ставились поверх старых без удаления.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

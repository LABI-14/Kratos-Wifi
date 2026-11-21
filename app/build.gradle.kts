plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.ambaan.wifiguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.ambaan.wifiguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-part1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

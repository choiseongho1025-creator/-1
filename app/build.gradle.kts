plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base { archivesName.set("나의 도우미 beta6") }

android {
    namespace = "com.myhelper.pet"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.myhelper.pet"
        minSdk = 28
        targetSdk = 34
        versionCode = 6
        versionName = "beta6"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
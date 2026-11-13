plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base { archivesName.set("나의 도우미 beta5") }

android {
    namespace = "com.myhelper.pet"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.myhelper.pet"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "beta5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
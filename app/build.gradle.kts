plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base { archivesName.set("나의 도우미 beta4") }

android {
    namespace = "com.myhelper.pet"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.myhelper.pet"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "beta4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.petpet.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.petpet.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // JDK 25로 실행하면 릴리스 lint 분석이 버전 문자열 파싱 오류로 실패하므로 릴리스 빌드에서는 끔
        checkReleaseBuilds = false
        abortOnError = false
    }
}

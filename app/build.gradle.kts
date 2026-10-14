plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 릴리스 서명: 루트의 keystore.properties(.gitignore 대상, keystore.properties.example 참고)가 있을 때만 적용
val keystoreFile = rootProject.file("keystore.properties")

android {
    namespace = "com.petpet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.petpet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                val p = java.util.Properties().apply { keystoreFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
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

dependencies {
    // AdMob 배너 + 동의 양식(UMP, 의존성으로 포함됨). 출시 전 strings의 테스트 ID를 실제 ID로 교체
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    // 인앱 결제(커피 후원 = 광고 제거). 7.x API 기준 — Play 정책상 더 높은 버전이 요구되면 올리고 listener 시그니처 확인
    implementation("com.android.billingclient:billing:7.1.1")
}

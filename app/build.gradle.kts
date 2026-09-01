plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seoulprime.huboneagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seoulprime.huboneagent"
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "0.7.0-mode-widget"
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // 신분증 촬영 화면(foreign_contact_intake.html)의 네이티브 카메라 미리보기 브릿지
    // (HubOneCamera) — WebView가 http로 페이지를 열어 웹 getUserMedia()가 보안
    // 컨텍스트 정책에 막히는 문제를 우회한다. RECORD_AUDIO의 HubOneAudio 브릿지와 동일한 이유.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

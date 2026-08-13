plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.anglesgirl.labelscanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anglesgirl.labelscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // 固定 debug 签名（仓库内 debug.keystore）：
            // CI 每次构建用同一签名 → 覆盖安装无需卸载 → 数据不丢
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        getByName("debug") {
            // 用仓库内的固定 debug.keystore（默认配置指向 ~/.android/，CI 每次不同）
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.chinese)
    // ML Kit Document Scanner（拍照矫正 + 相册导入识别）
    implementation(libs.mlkit.doc.scanner)
    // zxing-cpp：C++ 内核（比 Java ZXing 强：密集小码 3x 放大后 10/10、15/15 全解实测），原生多码检测
    implementation(libs.zxingcpp)
    // zxing-core：二维码【生成】（zxing-cpp 只解码不编码；集成码拆分用）
    implementation(libs.zxing-core)

    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)

    // Apache POI - Excel (.xlsx) 导出
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
}

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
        versionCode = 26
        versionName = "0.1.19-box-qty"
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 固定 debug 签名（仓库内 debug.keystore）：CI 每次构建用同一签名，覆盖安装无需卸载。
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

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
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
    // zxing-cpp：原生多码检测
    implementation(libs.zxingcpp)
    // zxing-core：二维码/条码生成（集成码拆分用）
    implementation(libs.zxing.core)

    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    // USB UVC 摄像头：使用本地修复版 AAR，避免重复/不兼容 USBMonitor 类。
    implementation(":AndroidUSBCamera-nousbmonitor:2.3.4@aar")
}

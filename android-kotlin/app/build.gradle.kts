plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.networkcontrol.android.kotlin"
    compileSdk = 36

    signingConfigs {
        val sharedKeystore = rootProject.file("keystore/shared-release.jks")
        getByName("debug") {
            if (sharedKeystore.exists()) {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        val releaseKeystore = System.getenv("NC_ANDROID_KEYSTORE")
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("NC_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NC_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("NC_ANDROID_KEY_PASSWORD")
            }
        } else if (sharedKeystore.exists()) {
            create("release") {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.networkcontrol.android.kotlin"
        minSdk = 29
        targetSdk = 36
        versionCode = 70
        versionName = "0.15.7"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release 按 ABI 拆分：給一般使用者的是 arm64 安裝包（目前的實機幾乎都是 arm64），
    // x86_64 只給模擬器。Debug 保持單一通用安裝包，開發流程不變。AAB 由商店自行按 ABI 下發。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) && !it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 讓 JVM 測試中的 android.util.Log 等呼叫回傳預設值，而不是拋出「not mocked」。
        unitTests.isReturnDefaultValues = true
    }
}

baselineProfile {
    // 只在開發機上手動生成（:app:generateReleaseBaselineProfile），生成結果提交到 src/release/generated；
    // 一般構建不會連接裝置。
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    // 安裝後依 Baseline Profile 預先編譯啟動路徑（側載安裝也有效）
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("dev.chrisbanes.haze:haze:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Shizuku：讓使用者不用電腦就能授予 DUMP（關閉偵測）；Apache-2.0
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 桌面掛件（Jetpack Glance，Compose 風格的 App Widget）
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

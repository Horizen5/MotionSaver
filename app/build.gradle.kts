plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.motionsaver.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.motionsaver.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "2.4.0"

        // 只打 arm64-v8a：近五年机型全覆盖，包体最小、无 32 位兼容损耗
        ndk { abiFilters += listOf("arm64-v8a") }

        // 测试运行器仅影响 androidTest；保持默认以免引入额外依赖
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 复用 Android 默认 debug keystore 给 release 签名：
        // 与用户此前安装的包签名一致，可直接覆盖升级，无需先卸载。
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isProfileable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // 更激进的优化通道：去掉不必要的非空断言检查，减少运行时分支
        freeCompilerArgs += listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 去掉调试/校验元数据，减小包体与安装时的校验开销
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version"
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // 侧载安装（非应用商店分发）时由 ProfileInstaller 把 Baseline Profile
    // 写入 ART，触发 dexopt 预编译；Android 12+ 系统本身也会读取 assets/dexopt。
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // 预览工具库仅 debug 引入，release 不再打入
    debugImplementation("androidx.compose.ui:ui-tooling")
}

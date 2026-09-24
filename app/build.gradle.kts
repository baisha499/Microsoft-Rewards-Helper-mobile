plugins {
    id("com.android.application")
}

android {
    namespace = "com.baisha.MicrosoftRewardsHelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.baisha.MicrosoftRewardsHelper"
        minSdk = 24
        targetSdk = 36
        // 版本号后面括号中的数字：这是 3.0.1 下的第几次修改
        // 注意：versionCode 是给系统用的整数，Android 安装器会把它显示成第二个括号
        versionCode = 14
        versionName = "3.0.1(2)"
        // 只打 arm64：ML Kit 的 so 很大，多打一个架构体积就翻一倍，不为 32 位机器保留
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 离线文字识别（模型打包进 APK，运行时不联网、不依赖 Google Play 服务）
    implementation("com.google.mlkit:text-recognition:16.0.0")
}

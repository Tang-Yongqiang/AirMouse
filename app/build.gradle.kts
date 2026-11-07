plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airmouse.gyro"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.airmouse.gyro"
        minSdk = 28
        targetSdk = 36
        versionCode = 37
        versionName = "2.26"
    }

    signingConfigs {
        create("localRelease") {
            storeFile = file("airmouse.keystore")
            storePassword = "airmouse"
            keyAlias = "airmouse"
            keyPassword = "airmouse"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 离线环境跑不了 lintVital（会去拉新版本检查项），release 直接跳过
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// 离线环境：本机 Gradle 缓存里只有这些版本，强制对齐，避免解析到未缓存的构件
configurations.all {
    resolutionStrategy {
        force(
            "androidx.recyclerview:recyclerview:1.3.2",
            "androidx.viewpager2:viewpager2:1.1.0-beta02",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

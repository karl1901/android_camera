plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wheeltech.blindcam"
    // ============ 硬性版本约束（禁止修改） ============
    compileSdk = 37
    // 固定 NDK 版本（需在 SDK Manager 安装 NDK r27b 与 CMake 3.22.1）
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.wheeltech.blindcam"
        minSdk = 21          // 覆盖安卓 5.0+ 老旧后装车机
        targetSdk = 33       // 核心：停在 33，规避 Android14~17 权限/前台/USB 收紧
        versionCode = 1
        versionName = "0.1.0"

        // 同时兼容 32 位老车机与 64 位新车机
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // C++ 静态 STL；异常与 RTTI 按需开启
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-21"
                )
                // JNI 层使用 C++17
                cppFlags += "-std=c++17 -fexceptions -frtti"
                cFlags += "-std=c11"
            }
        }
    }

    // 原生构建入口
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 仅保留两个正式构建类型，调试期不做代码混淆
    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // 编译字节码目标：Java 17（与构建 JDK 17 对齐）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // Kotlin 字节码目标 17（minSdk21 由 D8 负责 desugar 脱糖）
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true   // 硬性约束：XML ViewBinding，不使用 Compose
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.amap3dmap)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

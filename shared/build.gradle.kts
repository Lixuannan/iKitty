import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

// 平台无关的业务逻辑集中在这里：Android 应用（:app）与 iOS 应用（iosApp）共用同一份代码。
// 依赖方向固定为 app → shared、iosApp → shared，shared 不允许反向依赖任何一侧。
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    // 纯 JVM target 只为了让 commonTest 能跑得最快（./gradlew :shared:jvmTest）。
    jvm()

    // iOS 真机目标。模拟器目标（iosSimulatorArm64）在 Phase 6 补齐。
    iosArm64 {
        binaries {
            framework {
                baseName = "Shared"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 只用 JsonElement API（buildJsonObject / JsonObject 读写），刻意不用 @Serializable，
            // 因此不需要 serialization 编译器插件。
            // 用 api 而不是 implementation：JsonObject 出现在 shared 的公开签名里
            //（StoredMessage.toJson 等），:app 的编译类路径必须能看到它。
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            // 进 system prompt 的时间必须两端一致；时区/夏令时交给 kotlinx-datetime，
            // 不自己算 epoch 偏移。用 api 是因为时区类型出现在公开签名里。
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            // 文件读写。okio 已经是 OkHttp 的传递依赖，所以不算新增框架；
            // FileSystem/Path 出现在公开签名里（AppPaths、两个 store），因此用 api。
            api("com.squareup.okio:okio:3.18.2")
            // 协程类型出现在公开签名里（suspend 函数与注入的 CoroutineDispatcher）。
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        androidMain.dependencies {
            // Android 侧的 HttpTransport 实现。okhttp 只出现在 androidMain，
            // 所以 iOS 侧不会被迫拖进 OkHttp。
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            // 让存储层的测试不需要真实文件系统。
            implementation("com.squareup.okio:okio-fakefilesystem:3.18.2")
        }
    }
}

android {
    namespace = "com.codingcow.ikitty.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

// 平台无关的业务逻辑集中在这里：Android 应用（:app）与 iOS 应用（iosApp）共用同一份代码。
// 依赖方向固定为 app → shared、iosApp → shared，shared 不允许反向依赖任何一侧。
kotlin {
    // 必须显式应用：下面手工创建了 okhttpMain 这个中间 source set，
    // 一旦手工配置过 source set 层级，默认层级模板就不再自动应用，
    // 结果是 iosMain 被"配置了但不属于任何编译"——框架照样链接成功，
    // 却完全不含 iOS 侧的代码。这个坑只在 Swift 报 "cannot find X in scope" 时才暴露。
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    // 纯 JVM target 只为了让 commonTest 能跑得最快（./gradlew :shared:jvmTest）。
    jvm()

    // iOS 真机与模拟器。两个目标共用 iosMain，平台层只写一份。
    iosArm64 {
        binaries {
            framework {
                baseName = "Shared"
            }
        }
    }
    iosSimulatorArm64 {
        binaries {
            framework {
                baseName = "Shared"
            }
        }
    }

    sourceSets {
        // OkHttp 不是 Android 独有的：JVM 与 Android 共用同一份实现。
        // 抽成中间 source set 之后，真的跑一次真实网络往返的集成测试就能放在 jvmTest 里，
        // 不必为了测传输层去开模拟器。
        val okhttpMain by creating {
            dependsOn(commonMain.get())
        }

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
        androidMain {
            dependsOn(okhttpMain)
            dependencies {
                // DataStore 是 Android 专有的设置存储。
                implementation("androidx.datastore:datastore-preferences:1.1.1")
            }
        }
        jvmMain {
            dependsOn(okhttpMain)
        }
        okhttpMain.dependencies {
            // OkHttp 只出现在 okhttpMain，iOS 侧不会被迫拖进它。
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
        iosMain.dependencies {
            // iOS 侧的 HttpTransport。Ktor Darwin 直接封装 NSURLSession，
            // 自己做 cinterop 实现 NSURLSessionDataDelegate 的成本高得多。
            implementation("io.ktor:ktor-client-core:3.6.0")
            implementation("io.ktor:ktor-client-darwin:3.6.0")
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

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
        commonTest.dependencies {
            implementation(kotlin("test"))
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

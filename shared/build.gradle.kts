plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Phase 0.5 探测：只声明 iosArm64，验证 Kotlin/Native 2.4.20 能否在 Xcode 27.0 下
// 编译并链接出 framework。探测通过后再在 Phase 1 补 jvm()/androidTarget()，
// Phase 6 补 iosSimulatorArm64()。
kotlin {
    iosArm64 {
        binaries {
            framework {
                baseName = "Shared"
            }
        }
    }
}

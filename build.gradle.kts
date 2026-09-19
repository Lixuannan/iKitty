plugins {
    id("com.android.application") version "8.7.3" apply false
    // KGP 必须升到 2.4.20：2.0.21 官方只支持到 Xcode 16.0，本机是 Xcode 27.0。
    // 2.4.20 支持 Gradle 7.6.3–9.7.0 / AGP 8.5.2–9.3.1，所以 AGP 8.7.3 不用动。
    // Compose 编译器插件必须与 KGP 同版本。
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

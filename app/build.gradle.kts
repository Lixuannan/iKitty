plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.codingcow.ikitty"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codingcow.ikitty"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // 沿用 v0.1.0 的 debug 签名密钥：老用户已装的包就是这把 key 签的，
            // 换 key 会让系统拒绝覆盖安装，只能卸载重装，而卸载会清空聊天记录。
            // 换成正式发布密钥时，必须同时准备一次带数据迁移的过渡方案。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// KGP 2.4.20 起 `kotlinOptions` 是编译错误，必须改用 compilerOptions DSL。
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // 读取相册/相机图片的 EXIF 方向；BitmapFactory 自己不看这个标签，
    // 不处理的话竖拍照片会被当成横图存下来。
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 只是会抛异常的桩。
    // 补一份真实现，让记忆解析这类纯逻辑不用设备就能测。
    testImplementation("org.json:json:20240303")
}

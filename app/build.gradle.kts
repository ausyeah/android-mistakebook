// 依赖版本严格锁死在 PRD 第 2 节，禁止自行替换。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// debug 预填值来自根目录 local.properties（已在 .gitignore）。
// 注意：Gradle 不会把 local.properties 自动注入 project properties，必须显式读取文件。
fun localProperty(key: String, default: String = ""): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return default
    val props = Properties()
    file.inputStream().use { input -> props.load(input) }
    return props.getProperty(key)?.takeIf { it.isNotBlank() } ?: default
}

/**
 * 是否把 API Key 预填进 APK。
 *
 * ## 为什么要这个开关
 * `buildConfigField` 注入的字符串会**明文落在 DEX 里**，任何人下载 APK
 * 都能用 `strings classes*.dex | grep sk-` 提取出来。v0.1.0~v0.1.17
 * 的公开 Release 就是这么把真实 Key 泄露出去的。
 *
 * 因此：
 * - **本地开发**（默认 true）：从 local.properties 读，省去每次手填。
 * - **CI / 公开分发**（`-PprefillKeys=false`）：注入占位符，
 *   用户首次启动后自己在设置页填。
 *
 * 绝不能因为「反正仓库里没有 local.properties」就以为安全——
 * CI 会在构建前**生成**一个带 Secrets 的 local.properties。
 */
val prefillKeys = (project.findProperty("prefillKeys") as String?)?.toBoolean() ?: true

/** 公开分发时注入的占位符。用户看到它就知道要自己去设置里填。 */
val KEY_PLACEHOLDER = "REPLACE_IN_SETTINGS"

android {
    namespace = "com.mistakebook"
    compileSdk = 35

    defaultConfig {
        // -PappIdSuffix=.probe 可构建一个 applicationId 不同的探针包，
        // 用于和 CI 正式签名包共存着调试（签名不一致时无法 -r 覆盖安装），不污染正式包的数据。
        val appIdSuffix = (project.findProperty("appIdSuffix") as String?) ?: ""
        applicationId = "com.mistakebook$appIdSuffix"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // 版本号从 v0.0.1 重新开始。
        // 之前 v0.1.0~v0.1.17 的公开 Release 里带着真实的 API Key（明文躺在 DEX 中），
        // 那批产物与 tag 已全部删除，这个编号视为作废、不再复用。
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 只有开发者自己的本地构建才注入真实 Key（prefillKeys 默认 true）。
            // CI 与公开分发传 -PprefillKeys=false，注入占位符，APK 里不含任何密钥。
            if (prefillKeys) {
                buildConfigField("String", "MINERU_API_KEY", "\"${localProperty("MINERU_API_KEY")}\"")
                buildConfigField("String", "LLM_API_KEY", "\"${localProperty("LLM_API_KEY")}\"")
            } else {
                buildConfigField("String", "MINERU_API_KEY", "\"$KEY_PLACEHOLDER\"")
                buildConfigField("String", "LLM_API_KEY", "\"$KEY_PLACEHOLDER\"")
            }
            // Base URL 与模型名不是机密，公开包也带上，减少用户首次配置量
            buildConfigField(
                "String",
                "LLM_BASE_URL",
                "\"${localProperty("LLM_BASE_URL", "https://api.deepseek.com")}\""
            )
            buildConfigField(
                "String",
                "LLM_MODEL",
                "\"${localProperty("LLM_MODEL", "deepseek-chat")}\""
            )
        }
        release {
            // 正式打包的 APK 一律为空，使用者必须自行填写，即使 local.properties 有值也不注入
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "MINERU_API_KEY", "\"\"")
            buildConfigField("String", "LLM_API_KEY", "\"\"")
            buildConfigField("String", "LLM_BASE_URL", "\"https://api.deepseek.com\"")
            buildConfigField("String", "LLM_MODEL", "\"deepseek-chat\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

ksp {
    // Room schema 导出到 app/schemas，方便后续 migration 校验
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ===== Kotlin / 基础 =====
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ===== Compose（BOM 2024.10.01）=====
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // ===== Room =====
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ===== DataStore / 安全存储 =====
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ===== 网络 =====
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ===== CameraX =====
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ===== 图片加载 =====
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ===== 后台提醒 =====
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ===== 测试 =====
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

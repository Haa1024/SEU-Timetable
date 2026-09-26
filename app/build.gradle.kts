import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起，Compose 编译器是独立插件，不再用 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 更新清单地址，按发行形态分开维护（见下方 productFlavors 的说明）。
val updateBase = "https://raw.githubusercontent.com/Haa1024/SEU-Timetable/main"
// 原版：沿用根目录的 update.json。
// 已发布的 v1.2.0 及更早版本把这两个地址**硬编码**在代码里（当时 UpdateSources 是常量），
// 动它等于让老用户永远收不到更新。所以必须保持原样，让新旧原版共用同一份清单——
// 顺带的好处是发版时原版只需要维护一个文件。
val updatePlain = "$updateBase/update.json,https://cdn.jsdelivr.net/gh/Haa1024/SEU-Timetable@main/update.json"
// AI 版：独立清单，与上面并列放在仓库根，内容各自维护、互不影响。
val updateAi = "$updateBase/update-ai.json,https://cdn.jsdelivr.net/gh/Haa1024/SEU-Timetable@main/update-ai.json"

/**
 * 开发期的界面选型：`-PaiUi=compose` 走 Compose 原生浮窗，默认走 WebView 浮窗。
 *
 * 刻意做成构建参数而非常量：两套界面共用一个 AiViewModel 与同一套课表编解码，
 * 只有渲染层不同，因此适合在开发机上快速对照，而不该让用户去选。
 * 它与「AI 是否启用」是两回事——后者由用户在设置里决定。
 */
val aiUi = providers.gradleProperty("aiUi").getOrElse("webview")

tasks.withType<Test>().configureEach {
    // Opt-in API checks must execute, rather than reuse a previous skipped/cached result.
    if (!System.getenv("SEU_AI_TEST_KEY_FILE").isNullOrBlank()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

// 签名口令放 local.properties（已在 .gitignore 里），不硬编码进构建脚本
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.seu.timetable"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                keystoreProps.getProperty("RELEASE_STORE_FILE", "keystore/seu-timetable.jks")
            )
            storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS", "seu")
            keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.seu.timetable"
        minSdk = 26          // API 26 起自带 java.time，免去 desugaring
        targetSdk = 35
        versionCode = 3         // 3：新手实操引导 + 切页滑动 + 周次自动校正
        versionName = "1.2.0"
    }

    /**
     * 「是否接入 AI」是一个 flavor 维度，不是 buildType。
     *
     * buildType 的语义是「同一产品的不同构建模式」（debug / release）。
     * 把「要不要某个功能」挂上去，结果是**所有**构建模式都带上它——
     * 这正是此前 AI 会默认出现在 release 包里的原因。
     *
     * 换成 flavor 之后，AI 代码物理上属于 ai 变体（见 app/src/ai）：
     * plain 变体在编译期就不含它——没有字节码、没有 assets、没有相关依赖，
     * 因此不存在「运行时忘了关」这种可能。
     */
    flavorDimensions += "ai"

    productFlavors {
        create("plain") {
            dimension = "ai"
            // 与原版共用同一发行身份：applicationId / versionName / app_name 一律不加后缀，
            // 并且复用同一把签名密钥（同一张证书 SHA-256 指纹）。这是老用户能无感覆盖升级的前提。
            // 注意 APK 本身**不是**与 v1.2.0 逐字节相同：main 源码集含本次的 AiFeature 间接层
            // 与数据层加固，实测 classes.dex 比 v1.2.0 大 2 516 字节，其余 116 个条目逐一相同。
            buildConfigField("String", "UPDATE_MANIFEST", "\"$updatePlain\"")
        }
        create("ai") {
            dimension = "ai"
            // 与 plain 同机共存的前提是包名不同。代价是两者的数据沙盒互相独立——
            // 从原版换过来要重新登录、重新导入课表。这条取舍无法两全。
            applicationIdSuffix = ".ai"
            versionNameSuffix = "-ai"
            resValue("string", "app_name", "SEU课表 AI")
            // 更新清单必须按 flavor 分开，否则两版会互相「更新到对方」。
            buildConfigField("String", "AI_UI", "\"$aiUi\"")
            buildConfigField("String", "UPDATE_MANIFEST", "\"$updateAi\"")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 让 App 能读到自己的 versionCode / versionName。
        // 更新功能必须知道本机版本才能比对，而这里（build.gradle.kts）是版本的唯一定义处；
        // 若在代码里另写一份常量，改版本号时就会漏改，表现为「检查更新永远说已是最新」。
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // 显式声明：本地已从它取 LocalLifecycleOwner（做「回前台」生命周期感知）。
    // 它本来是 activity-compose 的传递依赖，运行时早已在包里，此举不增体积；
    // 写出来只为防上游哪天不再传递，导致 import 无声失效。
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 刻意不引 material-icons-extended：它带上千个图标类，debug 包体积直接翻倍。
    // 本项目只需要两个箭头，KeyboardArrowLeft/Right 在 material3 自带的
    // material-icons-core 里就有。

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // AI 专属依赖跟随 flavor：plain 变体不打包，既省体积也少一份供应链面。
    // okhttp 留在上面不动——抓课表本来就要用它。
    "aiImplementation"("io.noties.markwon:core:4.6.2")
    "aiImplementation"("io.noties.markwon:ext-tables:4.6.2")
    "aiImplementation"("androidx.exifinterface:exifinterface:1.3.7")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 解析逻辑用真实 HAR 数据做单元测试（夹具在 src/test/resources）
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起，Compose 编译器是独立插件，不再用 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
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
        // ⚠️ 测试用版本号，测完恢复为 versionCode = 1 / versionName = "1.0.0"
        //    抬高 versionCode 才能让「检查更新」认为有新版可装；
        //    versionName 带 -test 后缀，便于在 App 内确认装上的确实是测试包。
        versionCode = 2
        versionName = "1.0.1-test"
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

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 解析逻辑用真实 HAR 数据做单元测试（夹具在 src/test/resources）
    testImplementation("junit:junit:4.13.2")
}

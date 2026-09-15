import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Release 签名凭据从仓库根目录的 keystore.properties 读取，该文件不入库（见 .gitignore）。
//
// 文件缺失时不报错，release 构建照常进行、只是产出未签名 APK：贡献者与 CI 不需要持有
// 签名密钥就能编译。只有维护者本机有该文件，因此只有维护者能产出可安装的 release 包。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.zoot.englishreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.zoot.englishreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1-beta"
        testInstrumentationRunner = "io.github.zoot.englishreader.HiltTestRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 资源压缩依赖代码压缩（isMinifyEnabled 必须为 true），它会移除未被引用的资源。
            // 注意：通过 getIdentifier()/反射在运行时才解析的资源名不在「被引用」之列，会被删掉。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium 3.x AAR metadata declares coreLibraryDesugaringEnabled=true, so every consumer
        // must enable it. SPIKE-ONLY: this is an app-wide (all variants) build change, not a
        // debug-only one. Remove together with the Readium dependencies if the spike is rejected.
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            // 让 android.util.Log 等 Android stub 返回默认值而非抛异常
            isReturnDefaultValues = true
            // Robolectric 需要真实的 Android 资源（stringResource / 主题）才能组合 Compose UI
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // Room MigrationTestHelper 只从 instrumentation assets 读导出的 schema JSON，不会读
        // room.schemaLocation。少了这一行，EnglishReaderDatabaseMigrationAndroidTest 会以
        // FileNotFoundException("Missing file: .../3.json") 失败，而不是真的迁移失败。
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

// ImportBudgetInvariantTest.chapterCeiling_isDeclaredAsItsOwnLiteral reads ImportBudget.kt
// as a file, because `= 40_000` and `= MAX_IMPORT_CHARS` compile to identical bytecode while
// the two values agree -- the decoupling is invisible to the compiler, which is exactly why
// the guard has to read source.
//
// The consequence is that Gradle sees no input change and marks the test task up-to-date, so
// reverting to the alias reported failures=0 on a normal run and only went red under
// --rerun-tasks. Registering the file as an explicit input closes that: editing it now
// invalidates the task. Previously this was "mitigated" by telling people to pass
// --rerun-tasks in CI, and there is no CI workflow in this repo.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/java/io/github/zoot/englishreader/data/importer/ImportBudget.kt")
        .withPropertyName("importBudgetSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Readium 出版物/容器层。Phase 0 兼容性 Spike 已锁定 3.0.3：3.1.x 携带 Kotlin 2.1
    // metadata（本项目编译器 1.9.22 读不了），3.2+ 要求 compileSdk 36（本项目 34）。
    // 升级 Readium 前必须先抬 Kotlin 与 compileSdk，不是单独换版本号能做的事。
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)

    // Readium 3.x 要求 core library desugaring。这是 app 级（全 variant）构建变更，
    // release 字节码同样经 D8 改写。
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kxml2)
    // Robolectric 为 Settings 与 AI profile 的 Compose 交互测试提供 JVM 运行时（见 libs.versions.toml 注释）
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Readium 已是 implementation 依赖，testDebug 无需再声明；spike 测试仍在 testDebug 中，
    // 通过 main 的 implementation 拿到 Readium。

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.room.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}

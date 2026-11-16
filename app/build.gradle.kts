import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun readGitOutput(vararg args: String): String? = try {
    val process = ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    if (exit == 0 && output.isNotEmpty()) output else null
} catch (_: Throwable) {
    null
}

val gitCommitCount = readGitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
val gitDescribe = readGitOutput("describe", "--tags", "--match", "v*", "--always")?.removePrefix("v") ?: "1.0.0"
val gitShortHash = readGitOutput("rev-parse", "--short", "HEAD") ?: "local"
val buildDateStr = LocalDate.now().toString()

android {
    namespace = "com.pocketeverything.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yyzm.pinpoint"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = gitDescribe

        buildConfigField("String", "GIT_HASH", "\"$gitShortHash\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDateStr\"")
        buildConfigField("String", "GITHUB_REPO", "\"yyzmia1/Pinpoint\"")
        buildConfigField("String", "GITHUB_URL", "\"https://github.com/yyzmia1/Pinpoint\"")
        buildConfigField("String", "DEVELOPER_NAME", "\"yyzmiao\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystore = file("keystore/pocket-release.jks")
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("POCKET_KEYSTORE_PASS") ?: "pocket123456"
                keyAlias = System.getenv("POCKET_KEY_ALIAS") ?: "pocket"
                keyPassword = System.getenv("POCKET_KEY_PASS") ?: "pocket123456"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }

    val unitTestSqliteDir = layout.buildDirectory.dir("generated/unitTestSqlite")
    val unitTestPdfboxDir = layout.buildDirectory.dir("generated/unitTestPdfboxAssets")

    sourceSets {
        getByName("test").resources.srcDir(unitTestPdfboxDir)
    }

    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
    val (sqliteNativeEntryDir, sqliteNativeFileName) = when {
        osName.contains("win") -> "natives/windows_x64" to "sqliteJni.dll"
        osName.contains("mac") || osName.contains("darwin") ->
            (if (isArm64) "natives/osx_arm64" else "natives/osx_x64") to "libsqliteJni.dylib"
        else -> (if (isArm64) "natives/linux_arm64" else "natives/linux_x64") to "libsqliteJni.so"
    }

    val unitTestNativeSqliteCfg by configurations.creating { isTransitive = false }
    val unitTestPdfboxAssetsCfg by configurations.creating { isTransitive = false }

    val extractUnitTestSqliteNative = tasks.register<Sync>("extractUnitTestSqliteNative") {
        from(zipTree(provider { unitTestNativeSqliteCfg.singleFile })) {
            include("$sqliteNativeEntryDir/$sqliteNativeFileName")
            eachFile { path = name }
            includeEmptyDirs = false
        }
        into(unitTestSqliteDir)
    }

    val extractUnitTestPdfboxAssets = tasks.register<Sync>("extractUnitTestPdfboxAssets") {
        from(zipTree(provider { unitTestPdfboxAssetsCfg.singleFile })) {
            include("assets/com/tom_roush/**")
            eachFile { path = path.removePrefix("assets/") }
            includeEmptyDirs = false
        }
        into(unitTestPdfboxDir)
    }

    tasks.matching { it.name.contains("UnitTestJavaRes") }.configureEach {
        dependsOn(extractUnitTestPdfboxAssets)
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.dependsOn(extractUnitTestSqliteNative, extractUnitTestPdfboxAssets)
                val sqliteDirPath = unitTestSqliteDir.get().asFile.absolutePath
                it.jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-Dandroidx.sqlite.driver.bundled.path=$sqliteDirPath",
                            "-Dandroidx.sqlite.driver.bundled.name=$sqliteNativeFileName"
                        )
                    }
                )
                it.maxHeapSize = "2048m"
                it.testLogging {
                    showStandardStreams = true
                    events("passed", "skipped", "failed")
                }
            }
        }
    }
}

dependencies {
    "unitTestNativeSqliteCfg"("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqliteBundled.get()}")
    "unitTestPdfboxAssetsCfg"(libs.pdfbox.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

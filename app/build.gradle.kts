import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.FileInputStream
import java.util.Properties
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val appVersionName = "0.8.1"
val appVersionCode = 12

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

extensions.configure<ApplicationExtension> {
    namespace = "top.wkbin.taixu"
    resourcePrefix = "taixu_"
    compileSdk = 37
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "top.wkbin.taixu"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: project.file("keystore.properties").takeIf { it.exists() }
    val keystoreProperties = Properties()
    if (keystorePropertiesFile != null) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile != null) {
                val storeFilePath = keystoreProperties.getProperty("storeFile") ?: ""
                val resolvedStoreFile = if (storeFilePath.startsWith("/") || storeFilePath.contains(":\\")) {
                    file(storeFilePath)
                } else {
                    rootProject.file(storeFilePath).takeIf { it.exists() } ?: project.file(storeFilePath)
                }
                storeFile = resolvedStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "太墟 (Debug)"
        }
        release {
            manifestPlaceholders["appLabel"] = "太墟"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // PRoot is launched as an extracted ARM64 executable on Android 10+.
            useLegacyPackaging = true
            // TaiXu only supports arm64-v8a; Android AARs may also publish legacy/x86 ABIs.
            excludes += listOf(
                "**/armeabi-v7a/*.so",
                "**/x86/*.so",
                "**/x86_64/*.so",
            )
            // The PRoot tracee loader is an executable payload, not a JNI library.
            // Preserve the official package bytes instead of running AGP's strip tool.
            keepDebugSymbols += "**/libproot-loader.so"
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/license.txt",
                "META-INF/notice.txt"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Baseline Profile 运行时安装器：首帧前将打包进 APK 的 profile 提交给 ART 预编译。
    implementation(libs.androidx.profileinstaller)
    // 生成者模块：generateBaselineProfile 时由此拉起 macrobenchmark 采集
    baselineProfile(project(":baselineprofile"))
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    implementation(project(":feature:components"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:custom_iteration"))
    implementation(project(":feature:theme"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // Android must use the AAR; the default JVM JAR does not package Android JNI libraries.
    implementation(libs.zstd) {
        artifact { type = "aar" }
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val bundledProot = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot.so",
)
val bundledProotLoader = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libproot-loader.so",
)
val bundledPtyNative = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libpty_native.so",
)

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(rootProject.tasks.named("architectureCheck"))
        doFirst {
            check(bundledProot.asFile.isFile && bundledProot.asFile.length() > 4096L) {
                "Missing ARM64 PRoot tracer. Run tools/prepare-proot-runtime.ps1 before building."
            }
            check(bundledProotLoader.asFile.isFile && bundledProotLoader.asFile.length() > 4096L) {
                "Missing ARM64 PRoot loader. Run tools/prepare-proot-runtime.ps1 before building."
            }
            // libpty_native 必须是 NDK/Bionic 构建：若依赖 glibc 的 libc.so.6，设备上
            // dlopen 必失败并静默回退到 script PTY 路径（PTY 回显问题会随之复发）。
            val ptyNativeBytes = bundledPtyNative.asFile.readBytes()
            val glibcMarker = "libc.so.6".toByteArray()
            check(ptyNativeBytes.size < glibcMarker.size ||
                (0..ptyNativeBytes.size - glibcMarker.size).none { offset ->
                    (0 until glibcMarker.size).all { ptyNativeBytes[offset + it] == glibcMarker[it] }
                }) {
                "libpty_native.so is linked against glibc (libc.so.6). Rebuild it with the NDK " +
                    "aarch64-linux-android clang (see app/src/main/cpp/CMakeLists.txt)."
            }
        }
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val buildAppName = "taixu-v${appVersionName}-${variant.name}.apk"
        variant.outputs.forEach { output ->
            output.outputFileName.set(buildAppName)
        }
    }
}

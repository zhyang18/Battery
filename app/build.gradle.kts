import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.battery.analysis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.battery.analysis"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "1.8.2"
    }
    // 正确位置：splits 必须与 defaultConfig 平级，放在 android 闭包下
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // 动态设置 versionCode 并解决多架构文件名冲突
    val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)

    applicationVariants.all {
        val variant = this
        variant.outputs.forEach { output ->
            val abiOutput = output as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (abiOutput != null) {
                val abiFilter = abiOutput.getFilter(com.android.build.OutputFile.ABI)
                if (abiFilter != null) {
                    val abiCode = abiVersionCodes[abiFilter] ?: 0
                    abiOutput.versionCodeOverride = abiCode * 100000 + variant.versionCode
                }
                val abiName = abiFilter ?: "universal"
                abiOutput.outputFileName =
                    "Battery-v${variant.versionName}-${abiName}-${variant.buildType.name}.apk"
            }
        }
    }
    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.reader(Charsets.UTF_8).use { localProperties.load(it) }
            }

            val keystoreFile = file("app.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                    ?: (findProperty("KEYSTORE_PASSWORD") as? String)
                            ?: System.getenv("KEYSTORE_PASSWORD")
                            ?: ""
                keyAlias = localProperties.getProperty("KEY_ALIAS")
                    ?: (findProperty("KEY_ALIAS") as? String)
                            ?: System.getenv("KEY_ALIAS")
                            ?: ""
                keyPassword = localProperties.getProperty("KEY_PASSWORD")
                    ?: (findProperty("KEY_PASSWORD") as? String)
                            ?: System.getenv("KEY_PASSWORD")
                            ?: ""
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true && !releaseSigning.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = true  // 开启 R8 代码压缩与混淆
            isShrinkResources = true // 开启无用资源裁剪
            // 临时开启调试能力，方便用 Android Studio 抓 Logcat 和附加断点
            isDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true && !releaseSigning.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSigning
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Shizuku
    val shizuku_version = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku_version")
    implementation("dev.rikka.shizuku:provider:$shizuku_version")

    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

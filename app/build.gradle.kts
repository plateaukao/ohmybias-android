import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.github.triplet.play") version "3.12.1"
}

// Play 上架用 upload key（與 einkbro/calliplus 共用同一把）— 秘密不進 repo，
// 讀 ~/.secrets/ohmybias-keystore.properties（keys: storeFile[絕對路徑]/storePassword/
// keyAlias/keyPassword/playCredentials），路徑可用 -Pohmybias.keystoreProperties 覆蓋。
val keystorePropsFile = File(
    project.findProperty("ohmybias.keystoreProperties")?.toString()
        ?: (System.getProperty("user.home") + "/.secrets/ohmybias-keystore.properties")
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}
val hasUploadKeystore = keystoreProps.containsKey("storeFile")

android {
    namespace = "info.plateaukao.ohmybias"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.plateaukao.ohmybias"
        minSdk = 28
        targetSdk = 36
        versionCode = 19
        versionName = "0.6.3"
    }

    // 正式簽章金鑰不進 repo — CI 由 GitHub Secrets 注入環境變數，
    // 本機由 ~/.gradle/gradle.properties 提供；兩者皆無時退回 debug key（可照常開發）
    val ksPath = System.getenv("OHMYBIAS_KEYSTORE") ?: findProperty("OHMYBIAS_KEYSTORE") as String?
    if (ksPath != null && file(ksPath).exists()) {
        signingConfigs.create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("OHMYBIAS_KEYSTORE_PASSWORD") ?: findProperty("OHMYBIAS_KEYSTORE_PASSWORD") as String?
            keyAlias = System.getenv("OHMYBIAS_KEY_ALIAS") ?: findProperty("OHMYBIAS_KEY_ALIAS") as String?
            keyPassword = System.getenv("OHMYBIAS_KEY_PASSWORD") ?: findProperty("OHMYBIAS_KEY_PASSWORD") as String?
        }
    }
    if (hasUploadKeystore) {
        signingConfigs.create("play") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 壓掉未用到的 Kotlin stdlib（dex 佔了 APK 大宗）；
            // 本專案無反射、IME/Activity 等 manifest 進入點由 AGP 自動保留
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Google Play 上架版，裝置上是 info.plateaukao.ohmybias.g —
        // 與 GitHub 版（自家 keystore 簽）可並存，簽章互不衝突（同 einkbro .g 做法）
        create("playRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".g"
            signingConfig = signingConfigs.findByName("play") ?: signingConfigs.getByName("debug")
        }
    }

    // 只有 playRelease 在 Play 有 listing；關掉其餘變體讓 publishBundle 等
    // 聚合任務不會嘗試上傳不存在的 applicationId
    playConfigs {
        register("release") { enabled.set(false) }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // phrases.bin 等以 assets 原樣打包（不壓縮與否皆可 — 啟動時複製到 filesDir 再 mmap）

    packaging {
        resources.excludes += setOf(
            // kotlin-reflect 專用的 builtins 中繼資料（~11 KB 壓縮後）— 本專案無反射
            "kotlin/**",
            // AGP 塞進去的版本資訊檔，執行期無人讀
            "META-INF/*.version",
            "META-INF/version-control-info.textproto",
        )
    }
}

// Gradle Play Publisher（同 einkbro/calliplus）：`./gradlew publishPlayReleaseBundle`
// 上傳 AAB；預設 internal 軌，正式發佈明確帶 --track production。
// 首次上架（app 仍是 console 草稿）需帶 --release-status draft。
play {
    serviceAccountCredentials.set(
        file(
            keystoreProps.getProperty(
                "playCredentials",
                System.getProperty("user.home") + "/.secrets/calliplus-play-publisher.json"
            )
        )
    )
    defaultToAppBundles.set(true)
    track.set("internal")
}

dependencies {
    // 引擎層在 JVM 上測試；org.json 在 Android runtime 內建，JVM 測試需同 API 的 test-only 依賴
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

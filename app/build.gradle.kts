import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.nastavnik"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nastavnik"
        minSdk = 29
        targetSdk = 36
        // Номер сборки GitHub Actions: каждая новая сборка «новее» предыдущей — по нему приложение
        // находит обновление в GitHub Releases и ставит его поверх.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 153
        versionCode = maxOf(build, 10114)
        versionName = "1.1.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("proguard-rules.pro")
    }

    testBuildType = "release"

    // Два приложения из одного кода: полный «Наставник» и лёгкий «Наставник · Студент» (расписание, оценки ЛКС,
    // живое уведомление, «Тихо на парах», темы) — свой идентификатор, ставятся рядом.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "STUDENT", "false")
            buildConfigField("String", "APK_NAME", "\"nastavnik.apk\"")
        }
        create("student") {
            dimension = "edition"
            applicationId = "app.nastavnik.student"
            buildConfigField("boolean", "STUDENT", "true")
            buildConfigField("String", "APK_NAME", "\"student.apk\"")
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(keystorePropsFile.inputStream())
    }

    signingConfigs {
        // Общий debug-ключ лежит в репозитории для локальных сборок и тестов.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        val releaseKeyPath = keystoreProps.getProperty("RELEASE_KEYSTORE_PATH")
            ?: System.getenv("RELEASE_KEYSTORE_PATH")
        val releaseKeyStorePass = keystoreProps.getProperty("RELEASE_KEYSTORE_PASSWORD")
            ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
        val releaseKeyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
            ?: System.getenv("RELEASE_KEY_ALIAS")
        val releaseKeyPass = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
            ?: System.getenv("RELEASE_KEY_PASSWORD")

        val hasReleaseSigning = !releaseKeyPath.isNullOrBlank() &&
            !releaseKeyStorePass.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPass.isNullOrBlank() &&
            rootProject.file(releaseKeyPath).exists()

        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeyPath)
                storePassword = releaseKeyStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Шрифты KaTeX уже сжаты (woff2), а WebView читает assets напрямую.
        noCompress += listOf("woff2")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

import java.util.Properties

// «Наставник · Родитель» — отдельное приложение: расписание студента с посещаемостью и успеваемость.
// Данные — с Яндекс Диска студента (облако «Наставника», только чтение) и с сайта расписания.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.nastavnik.parent"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nastavnik.parent"
        minSdk = 29
        targetSdk = 36
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 153
        versionCode = maxOf(build, 10111)
        versionName = "1.1.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(keystorePropsFile.inputStream())
    }

    signingConfigs {
        // Тот же общий debug-ключ, что у «Наставника».
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
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
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

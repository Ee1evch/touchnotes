// Общий код без Android — расписание (Сириус), облако (Яндекс Диск, формат синхронизации), оценки (ЛКС, Moodle).
// Им пользуются «Наставник» (:app) и приложение для родителя (:parent).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

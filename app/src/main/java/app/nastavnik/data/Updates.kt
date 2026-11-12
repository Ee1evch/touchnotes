package app.nastavnik.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Свежая сборка в GitHub Releases: номер, ссылка на APK и размер. */
data class UpdateInfo(val build: Int, val apkUrl: String, val sizeBytes: Long, val title: String)

/** Разбор ответа GitHub API `releases/latest`. Без Android — проверяется тестами. */
object UpdateCheck {
    const val REPO = "Ee1evch/touchnotes"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    /** Обновление, если релиз новее [currentBuild] и в нём есть APK; иначе null. */
    fun parse(body: String, currentBuild: Int, apkName: String = "nastavnik.apk"): UpdateInfo? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = obj.str("tag_name") ?: return null
        val semver = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").find(tag)
        val build = if (semver != null) {
            val (maj, min, patch) = semver.destructured
            maj.toInt() * 10000 + min.toInt() * 100 + patch.toInt()
        } else {
            Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        }
        if (build <= currentBuild) return null
        // В релизе несколько APK — «Наставник», «Студент» и приложение для родителя; нам — только свой.
        val apks = (obj["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter { it.str("name")?.endsWith(".apk") == true }.orEmpty()
        val asset = apks.firstOrNull { it.str("name") == apkName } ?: return null
        val url = asset.str("browser_download_url") ?: return null
        if (!url.startsWith("https://github.com/$REPO/")) return null
        return UpdateInfo(build, url, asset.str("size")?.toLongOrNull() ?: 0, obj.str("name") ?: tag)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}

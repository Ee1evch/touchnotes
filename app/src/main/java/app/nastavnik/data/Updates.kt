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
    fun parse(body: String, currentBuild: Int): UpdateInfo? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = obj.str("tag_name") ?: return null
        val build = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        if (build <= currentBuild) return null
        val asset = (obj["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return null
        val url = asset.str("browser_download_url") ?: return null
        if (!url.startsWith("https://github.com/$REPO/")) return null
        return UpdateInfo(build, url, asset.str("size")?.toLongOrNull() ?: 0, obj.str("name") ?: tag)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}

package app.nastavnik.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Свежая сборка в GitHub Releases: номер, ссылка на APK, размер и опциональный SHA-256. */
data class UpdateInfo(
    val build: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val title: String,
    val sha256: String? = null
)

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

        val notes = obj.str("body")
        val sha256 = extractSha256(notes, apkName)

        return UpdateInfo(
            build = build,
            apkUrl = url,
            sizeBytes = asset.str("size")?.toLongOrNull() ?: 0,
            title = obj.str("name") ?: tag,
            sha256 = sha256
        )
    }

    fun extractSha256(notes: String?, apkName: String): String? {
        if (notes.isNullOrBlank()) return null
        // Формат sha256sum: "<64-hex> [*]?<filename>"
        val r1 = Regex("""(?im)^([a-f0-9]{64})\s+[*]?""" + Regex.escape(apkName) + """\s*$""").find(notes)
        if (r1 != null) return r1.groupValues[1].lowercase()
        // Формат "filename: <64-hex>" или "filename - <64-hex>"
        val r2 = Regex("""(?im)^""" + Regex.escape(apkName) + """[:\s\-]+([a-f0-9]{64})\s*$""").find(notes)
        if (r2 != null) return r2.groupValues[1].lowercase()
        // Произвольный формат "filename ... <64-hex>" в пределах одной строки
        val r3 = Regex("""(?i)""" + Regex.escape(apkName) + """[^\r\n]*?([a-f0-9]{64})""").find(notes)
        if (r3 != null) return r3.groupValues[1].lowercase()
        return null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}

package app.nastavnik.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Яндекс Диск через REST API (cloud-api.yandex.net) по OAuth-токену. WebDAV Яндекс оставил только
 * для платного Яндекс 360 (бесплатным отвечает 402), а REST API работает и на бесплатном Диске.
 * Токен — с Полигона Яндекса (yandex.ru/dev/disk/poligon) — хранится зашифрованным и в журнал не пишется.
 */
class YandexDiskStore(
    private val token: String,
    private val api: String = API,
    client: OkHttpClient? = null,
) : SyncStore {
    private val http = (client ?: shared).newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val known = HashSet<String>()

    private fun disk(path: String) = "disk:/" + path.trim('/')

    private fun call(method: String, endpoint: String, path: String, extra: Map<String, String> = emptyMap(), body: RequestBody? = null): okhttp3.Response {
        val url = (api.trimEnd('/') + endpoint).toHttpUrl().newBuilder().addQueryParameter("path", disk(path))
        extra.forEach { (k, v) -> url.addQueryParameter(k, v) }
        val req = Request.Builder().url(url.build()).header("Authorization", "OAuth $token").header("Accept", "application/json")
            .method(method, body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null).build()
        return http.newCall(req).execute()
    }

    /** Ссылка для скачивания или загрузки (Диск отдаёт её отдельно, на неё ходим уже без токена). */
    private fun href(endpoint: String, path: String, extra: Map<String, String> = emptyMap()): String? =
        call("GET", endpoint, path, extra).use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw fail(r.code)
            json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["href"]?.jsonPrimitive?.contentOrNull
        }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val link = href("/v1/disk/resources/download", path) ?: return@withContext null
        http.newCall(Request.Builder().url(link).get().build()).execute().use { r ->
            if (r.code == 404) null else if (!r.isSuccessful) throw fail(r.code) else r.body?.bytes()
        }
    }

    override suspend fun download(path: String, to: File): Boolean = withContext(Dispatchers.IO) {
        val link = href("/v1/disk/resources/download", path) ?: return@withContext false
        http.newCall(Request.Builder().url(link).get().build()).execute().use { r ->
            if (r.code == 404) return@withContext false
            if (!r.isSuccessful) throw fail(r.code)
            to.parentFile?.mkdirs()
            val tmp = File(to.path + ".part")
            r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@withContext false
            tmp.renameTo(to) || run { to.delete(); tmp.renameTo(to) }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) { put(path, bytes.toRequestBody(octet)) }

    override suspend fun upload(path: String, file: File) = withContext(Dispatchers.IO) { put(path, file.asRequestBody(octet)) }

    private fun put(path: String, body: RequestBody) {
        val link = href("/v1/disk/resources/upload", path, mapOf("overwrite" to "true")) ?: throw IOException("Диск не дал ссылку для загрузки")
        http.newCall(Request.Builder().url(link).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw fail(r.code)
        }
    }

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        var offset = 0
        while (true) {
            val page = call("GET", "/v1/disk/resources", dir, mapOf("limit" to "1000", "offset" to "$offset", "fields" to "_embedded.items.name,_embedded.items.type,_embedded.total")).use { r ->
                if (r.code == 404) return@withContext emptyList()
                if (!r.isSuccessful) throw fail(r.code)
                parseList(r.body?.string().orEmpty())
            }
            out += page.first
            offset += 1000
            if (offset >= page.second) break
        }
        out
    }

    override suspend fun ensureDir(dir: String) = withContext(Dispatchers.IO) {
        if (dir in known) return@withContext
        call("PUT", "/v1/disk/resources", dir).use { r ->
            // 409 — папка уже есть.
            if (!r.isSuccessful && r.code != 409) throw fail(r.code)
        }
        known += dir
    }

    /** Проверка токена: создаём корневую папку синхронизации. */
    suspend fun check() = ensureDir(SyncEngine.ROOT)

    private fun fail(code: Int) = IOException(
        when (code) {
            401 -> "Яндекс не принял токен — получи новый на Полигоне"
            403 -> "У токена нет доступа к Диску"
            507 -> "На Яндекс Диске закончилось место"
            429 -> "Яндекс просит подождать — слишком много запросов"
            else -> "Яндекс Диск ответил ошибкой $code"
        },
    )

    companion object {
        const val API = "https://cloud-api.yandex.net"
        /** Отметка в настройках вместо адреса WebDAV: синхронизация через REST API Яндекса. */
        const val MARKER = "yandex-disk"
        const val POLIGON = "https://yandex.ru/dev/disk/poligon/"
        private val octet = "application/octet-stream".toMediaType()
        private val shared by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        }

        /** Имена файлов (без папок) и общее число элементов. */
        fun parseList(body: String): Pair<List<String>, Int> {
            val emb = Json.parseToJsonElement(body).jsonObject["_embedded"] as? JsonObject ?: return emptyList<String>() to 0
            val items = emb["items"] as? JsonArray ?: return emptyList<String>() to 0
            val names = items.mapNotNull { e ->
                val o = e.jsonObject
                o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { o["type"]?.jsonPrimitive?.contentOrNull == "file" }
            }
            return names to (emb["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: items.size)
        }
    }
}

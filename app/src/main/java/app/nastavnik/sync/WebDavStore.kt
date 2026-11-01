package app.nastavnik.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Облако по WebDAV: Яндекс Диск (https://webdav.yandex.ru, пароль приложения), Nextcloud, ownCloud и любой другой.
 * Пароль уходит только в заголовке Authorization этого сервера и нигде не пишется в журнал.
 */
class WebDavStore(
    baseUrl: String,
    private val login: String,
    private val password: String,
    client: OkHttpClient? = null,
) : SyncStore {
    private val base = baseUrl.trimEnd('/')
    private val http = (client ?: shared).newBuilder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(login, password, Charsets.UTF_8)).build()) }
        .build()
    private val known = HashSet<String>()

    private fun url(path: String) = base + "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(path)).get().build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw fail(r.code)
                else -> r.body?.bytes()
            }
        }
    }

    override suspend fun download(path: String, to: File): Boolean = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(path)).get().build()).execute().use { r ->
            if (r.code == 404) return@withContext false
            if (!r.isSuccessful) throw fail(r.code)
            to.parentFile?.mkdirs()
            val tmp = File(to.path + ".part")
            r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@withContext false
            tmp.renameTo(to) || run { to.delete(); tmp.renameTo(to) }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        put(path, bytes.toRequestBody(octet))
    }

    override suspend fun upload(path: String, file: File) = withContext(Dispatchers.IO) {
        put(path, file.asRequestBody(octet))
    }

    private fun put(path: String, body: okhttp3.RequestBody) {
        http.newCall(Request.Builder().url(url(path)).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw fail(r.code)
        }
    }

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(dir) + "/")
            .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext emptyList()
            if (!r.isSuccessful) throw fail(r.code)
            parseList(r.body?.string().orEmpty(), dir)
        }
    }

    override suspend fun ensureDir(dir: String) = withContext(Dispatchers.IO) {
        if (dir in known) return@withContext
        http.newCall(Request.Builder().url(url(dir) + "/").method("MKCOL", null).build()).execute().use { r ->
            // 405 — папка уже есть; 201 — создали.
            if (!r.isSuccessful && r.code != 405) throw fail(r.code)
        }
        known += dir
    }

    /** Проверка входа: создаём корневую папку синхронизации. */
    suspend fun check() = ensureDir(SyncEngine.ROOT)

    private fun fail(code: Int) = IOException(
        when (code) {
            401, 403 -> "Облако не пустило: проверь логин и пароль приложения"
            404 -> "Нет такого адреса в облаке"
            507 -> "В облаке закончилось место"
            429 -> "Облако просит подождать — слишком много запросов"
            else -> "Облако ответило ошибкой $code"
        },
    )

    companion object {
        const val YANDEX = "https://webdav.yandex.ru"
        private val octet = "application/octet-stream".toMediaType()
        private val shared by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        }
        private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        private val response = Regex("""<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        private val href = Regex("""<(?:\w+:)?href>(.*?)</(?:\w+:)?href>""", RegexOption.DOT_MATCHES_ALL)
        private val collection = Regex("""<(?:\w+:)?collection\s*/>""")

        /** Имена файлов из ответа PROPFIND (сама папка и подпапки отбрасываются). */
        fun parseList(xml: String, dir: String): List<String> = response.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            if (collection.containsMatchIn(body)) return@mapNotNull null
            val h = href.find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val name = URLDecoder.decode(h.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            name.takeIf { it.isNotEmpty() && it != dir.substringAfterLast('/') }
        }.toList()
    }
}

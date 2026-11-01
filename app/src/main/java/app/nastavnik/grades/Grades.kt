package app.nastavnik.grades

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Оценка за задание, тест или итог курса. */
@Serializable
data class GradeItem(
    val id: Long,
    val name: String,
    /** Как показывает сайт: «5,00», «Зачтено», «-». */
    val grade: String,
    val raw: Double? = null,
    val max: Double? = null,
    val percent: String? = null,
    val feedback: String? = null,
    /** Когда выставлена (мс), 0 — не выставлена. */
    val gradedAt: Long = 0,
    /** course — итог курса, category — раздел, mod — задание. */
    val type: String = "mod",
)

@Serializable
data class CourseGrades(
    val id: Long,
    val name: String,
    /** Итог курса, как на сайте. */
    val total: String? = null,
    val totalRaw: Double? = null,
    val items: List<GradeItem> = emptyList(),
)

@Serializable
data class GradesSnapshot(val student: String, val courses: List<CourseGrades>, val loadedAt: Long)

class GradesException(message: String, val badLogin: Boolean = false) : IOException(message)

/**
 * Успеваемость из учебной платформы на Moodle (как moodle.siriusuniversity.ru) — так же, как её получает
 * официальное приложение Moodle: логин и пароль → токен (login/token.php, сервис moodle_mobile_app),
 * дальше веб-сервисы: курсы, итоги по курсам, оценки за задания.
 */
class MoodleClient(baseUrl: String, client: OkHttpClient? = null) {
    private val base = baseUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
    private val http = client ?: shared
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("username", username.trim())
            .add("password", password)
            .add("service", "moodle_mobile_app")
            .build()
        val o = post("$base/login/token.php", body).jsonObject
        o.str("token") ?: throw GradesException(
            when (o.str("errorcode")) {
                "invalidlogin" -> "Неверный логин или пароль"
                "enablewsdescription", "servicenotavailable", "webservicesnotenabled" -> "Сайт не пускает приложения: вход для мобильного приложения выключен"
                "sitemaintenance" -> "Сайт на обслуживании — попробуй позже"
                else -> o.str("error") ?: "Сайт не выдал доступ"
            },
            badLogin = o.str("errorcode") == "invalidlogin",
        )
    }

    private fun post(url: String, body: FormBody): JsonElement {
        val r = runCatching { http.newCall(Request.Builder().url(url).post(body).build()).execute() }
            .getOrElse { throw GradesException("Не удалось связаться с сайтом. Проверь интернет и адрес.") }
        r.use {
            if (!it.isSuccessful) throw GradesException("Сайт ответил ошибкой ${it.code}")
            val text = it.body?.string().orEmpty()
            return runCatching { json.parseToJsonElement(text) }.getOrElse { throw GradesException("Это не похоже на Moodle: проверь адрес сайта") }
        }
    }

    private suspend fun call(token: String, function: String, params: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val b = FormBody.Builder().add("wstoken", token).add("wsfunction", function).add("moodlewsrestformat", "json")
        params.forEach { (k, v) -> b.add(k, v) }
        val res = post("$base/webservice/rest/server.php", b.build())
        val err = (res as? JsonObject)?.takeIf { it.containsKey("exception") || it.containsKey("errorcode") && !it.containsKey("grades") }
        if (err != null) {
            val code = err.str("errorcode")
            throw GradesException(
                if (code == "invalidtoken" || code == "accessexception") "Вход устарел — войди заново" else err.str("message") ?: "Ошибка сайта: $code",
                badLogin = code == "invalidtoken",
            )
        }
        res
    }

    /** Всё сразу: курсы, итоги, оценки за задания (по курсам, где они есть). */
    suspend fun snapshot(token: String, now: Long = System.currentTimeMillis()): GradesSnapshot {
        val info = call(token, "core_webservice_get_site_info").jsonObject
        val userId = info["userid"]?.jsonPrimitiveOrNull()?.longOrNull ?: throw GradesException("Сайт не сказал, кто ты")
        val name = info.str("fullname").orEmpty()
        val courses = (call(token, "core_enrol_get_users_courses", mapOf("userid" to "$userId")) as? JsonArray).orEmpty()
            .mapNotNull { e -> e.jsonObject.let { o -> o["id"]?.jsonPrimitiveOrNull()?.longOrNull?.let { it to (o.str("fullname") ?: o.str("shortname") ?: "Курс $it") } } }
        val totals = runCatching { call(token, "gradereport_overview_get_course_grades", mapOf("userid" to "$userId")).jsonObject["grades"]?.jsonArray }
            .getOrNull().orEmpty()
            .associate { e -> val o = e.jsonObject; (o["courseid"]?.jsonPrimitiveOrNull()?.longOrNull ?: -1L) to (o.str("grade") to o.str("rawgrade")?.replace(',', '.')?.toDoubleOrNull()) }
        val result = courses.map { (id, title) ->
            val items = runCatching {
                call(token, "gradereport_user_get_grade_items", mapOf("courseid" to "$id", "userid" to "$userId")).jsonObject["usergrades"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("gradeitems")?.jsonArray.orEmpty()
                    .mapNotNull { parseItem(it.jsonObject) }
            }.getOrDefault(emptyList())
            val (total, raw) = totals[id] ?: (items.firstOrNull { it.type == "course" }?.let { it.grade to it.raw } ?: (null to null))
            CourseGrades(id, title, total?.takeIf { it.isNotBlank() && it != "-" }, raw, items.filter { it.type != "course" })
        }
        return GradesSnapshot(name, result, now)
    }

    private fun parseItem(o: JsonObject): GradeItem? {
        val type = o.str("itemtype") ?: "mod"
        val name = o.str("itemname")?.takeIf { it.isNotBlank() } ?: if (type == "course") "Итог курса" else return null
        return GradeItem(
            id = o["id"]?.jsonPrimitiveOrNull()?.longOrNull ?: name.hashCode().toLong(),
            name = name,
            grade = o.str("gradeformatted")?.trim().orEmpty().ifEmpty { "-" },
            raw = o["graderaw"]?.jsonPrimitiveOrNull()?.doubleOrNull,
            max = o["grademax"]?.jsonPrimitiveOrNull()?.doubleOrNull,
            percent = o.str("percentageformatted")?.takeIf { it.isNotBlank() && it != "-" },
            feedback = o.str("feedback")?.let { stripHtml(it) }?.takeIf { it.isNotBlank() },
            gradedAt = (o["gradedategraded"]?.jsonPrimitiveOrNull()?.longOrNull ?: 0L) * 1000,
            type = type,
        )
    }

    companion object {
        const val SIRIUS = "https://moodle.siriusuniversity.ru"
        private val shared by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build() }

        fun stripHtml(s: String) = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim()

        /** Новые и изменившиеся оценки (для уведомления). */
        fun changes(old: GradesSnapshot?, new: GradesSnapshot): List<Pair<CourseGrades, GradeItem>> {
            if (old == null) return emptyList()
            val before = old.courses.flatMap { c -> c.items.map { (c.id to it.id) to it.grade } }.toMap()
            return new.courses.flatMap { c ->
                c.items.filter { it.grade != "-" && it.grade.isNotBlank() && before[c.id to it.id] != it.grade }.map { c to it }
            }
        }

        /** Средний процент по выставленным оценкам заданий (для сводки), или null. */
        fun averagePercent(s: GradesSnapshot): Double? {
            val xs = s.courses.flatMap { it.items }.mapNotNull { i -> val r = i.raw; val m = i.max; if (r != null && m != null && m > 0) r / m * 100 else null }
            return xs.takeIf { it.isNotEmpty() }?.average()
        }
    }
}

private fun JsonElement.jsonPrimitiveOrNull() = this as? JsonPrimitive
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

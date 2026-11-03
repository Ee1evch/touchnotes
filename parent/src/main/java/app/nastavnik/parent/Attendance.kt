package app.nastavnik.parent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Отметка старосты: был, опоздал, сильно опоздал, болезнь, уважительная причина, прогул. */
enum class Mark(val key: String, val label: String, val short: String) {
    PRESENT("present", "Был", "✓"),
    LATE("late", "Опоздание", "О"),
    CRITICAL_LATE("critical_late", "Сильное опоздание", "КО"),
    ILL("ill", "Болезнь", "Б"),
    REASON("reason", "Уважительная причина", "П"),
    ABSENT("absent", "Прогул", "×");

    /** Не был на паре (болезнь, причина, прогул). */
    val missed get() = this == ILL || this == REASON || this == ABSENT

    companion object {
        fun of(key: String?): Mark? = entries.firstOrNull { it.key == key }
    }
}

/** Пара в журнале старосты и отметка студента (null — не отмечено). */
@Serializable
data class AttendanceLesson(
    val id: String,
    val date: String,
    val number: Int = 0,
    val title: String,
    val type: String = "",
    val start: String = "",
    val end: String = "",
    val mark: String? = null,
    /** Причину подтвердили (справка принята). */
    val confirmed: Boolean = false,
) {
    val status: Mark? get() = Mark.of(mark)
}

/** Студент на сайте посещаемости: внутренние номера студента и группы. */
@Serializable
data class AttendanceStudent(val registry: String, val studentId: Int, val groupId: Int, val group: String = "")

/**
 * Посещаемость IT-специалитета Сириуса (itattendance.ru) — то, что отмечает староста. Без входа: страница студента
 * по номеру зачётки даёт его внутренний номер и группу, дальше — `/api/attendance` по неделям. Сайт отдаёт отметки
 * всей группы — оставляем только этого студента.
 */
class AttendanceClient(private val base: String = BASE, client: OkHttpClient? = null) {
    private val http = client ?: shared

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "Nastavnik-Parent/1.0 (Android)").get().build()
        return http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("Нет такого номера зачётки на сайте посещаемости")
            if (!r.isSuccessful) throw IOException("Сайт посещаемости ответил ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /** Найти студента по номеру зачётки. */
    suspend fun student(registry: String): AttendanceStudent = withContext(Dispatchers.IO) {
        val reg = registry.trim()
        if (reg.isEmpty() || !reg.all { it.isDigit() }) throw IOException("Номер зачётки — только цифры")
        AttendanceParser.student(get("${base.trimEnd('/')}/students/$reg"), reg)
            ?: throw IOException("Нет такого номера зачётки на сайте посещаемости")
    }

    /** Пары недели с понедельника [monday] и отметки студента. */
    suspend fun week(s: AttendanceStudent, monday: LocalDate): List<AttendanceLesson> = withContext(Dispatchers.IO) {
        AttendanceParser.week(get("${base.trimEnd('/')}/api/attendance?groupId=${s.groupId}&weekStart=$monday&studentId=${s.studentId}"), s.studentId)
    }

    companion object {
        const val BASE = "https://itattendance.ru"
        private val shared by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    }
}

/** Разбор ответов сайта посещаемости — без Android и сети (тесты `AttendanceTest`). */
object AttendanceParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Со страницы студента: `"studentId":112,"registryNumber":"240084","groupId":5` (внутри данных Next.js кавычки
     * экранированы) и название группы — ссылка `/courses/N?groupId=G`.
     */
    fun student(html: String, registry: String): AttendanceStudent? {
        val q = """\\?""""
        val ids = Regex("""studentId$q:(\d+),${q}registryNumber$q:$q${Regex.escape(registry)}$q,${q}groupId$q:(\d+)""").find(html) ?: return null
        val sid = ids.groupValues[1].toInt()
        val gid = ids.groupValues[2].toInt()
        val group = Regex("""groupId=$gid$q,${q}children$q:$q([^"\\]+)""").find(html)?.groupValues?.get(1).orEmpty()
        return AttendanceStudent(registry, sid, gid, group)
    }

    /** `/api/attendance`: пары недели и отметки — ключи «студент-пара», берём только [studentId]. */
    fun week(body: String, studentId: Int): List<AttendanceLesson> {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val marks = (o["overrides"] as? JsonObject).orEmpty()
        val confirmed = (o["reasonConfirmations"] as? JsonObject).orEmpty()
        val lessons = (o["lessons"] as? JsonArray).orEmpty()
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return lessons.mapNotNull { e ->
            val l = e as? JsonObject ?: return@mapNotNull null
            val id = l.s("id").ifEmpty { return@mapNotNull null }
            val key = "$studentId-$id"
            AttendanceLesson(
                id = id, date = l.s("date"), number = (l["number"] as? JsonPrimitive)?.intOrNull ?: 0,
                title = l.s("title"), type = l.s("type"), start = l.s("startTime"), end = l.s("endTime"),
                mark = (marks[key] as? JsonPrimitive)?.contentOrNull,
                confirmed = (confirmed[key] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.sortedWith(compareBy({ it.date }, { it.start }, { it.number }))
    }
}

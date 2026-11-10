package app.nastavnik.grades

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Оценка в журнале ЛКС за занятие. */
@Serializable
data class LksMark(val value: String, val color: String? = null, val second: String? = null)

/** Запись журнала за дату: оценки, пропуск, комментарий, домашнее задание. */
@Serializable
data class LksEntry(
    val date: String,
    val marks: List<LksMark> = emptyList(),
    /** null — был; true — пропуск по уважительной (НУ), false — неуважительной (НН). */
    val absent: Boolean = false,
    val absentValid: Boolean? = null,
    val comment: String? = null,
    val hometask: String? = null,
)

@Serializable
data class LksLesson(
    val title: String,
    val avgMark: String? = null,
    val rating: String? = null,
    val absences: Int = 0,
    val entries: List<LksEntry> = emptyList(),
)

@Serializable
data class LksJournal(val lessons: List<LksLesson>, val period: String = "", val loadedAt: Long = 0)

/**
 * ЛКС Сириуса (lks.siriusuniversity.ru, платформа «Тандем» на Laravel). Оценки — `POST /journal/get` (JSON,
 * с CSRF-токеном со страницы журнала). Войти запросом нельзя: в форме `/login` стоит Яндекс SmartCaptcha
 * (без `smart-token` сайт возвращает на /login с «Подтвердите, что вы не робот»), поэтому вход — во встроенном
 * окне сайта, а сюда приходят его куки ([SessionCookies.fromWebView]).
 * Сайт пускает только с российских адресов (иначе 418) — запросы идут прямо с телефона.
 */
class LksClient(
    baseUrl: String = BASE,
    private val cookies: SessionCookies = SessionCookies(),
    client: OkHttpClient? = null,
) {
    private val base = baseUrl.trimEnd('/')
    private val http = (client ?: shared).newBuilder()
        .cookieJar(cookies)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Nastavnik")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build())
        }
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    class LoginException(message: String) : GradesException(message, badLogin = true)

    private fun get(path: String): Pair<String, String> {
        val r = runCatching { http.newCall(Request.Builder().url(base + path).get().build()).execute() }
            .getOrElse { throw GradesException("ЛКС не отвечает. Проверь интернет (сайт открывается только из России).") }
        r.use {
            if (it.code == 418 || it.code == 403) throw GradesException(BLOCKED)
            if (!it.isSuccessful) throw GradesException("ЛКС ответил ошибкой ${it.code}")
            return it.request.url.encodedPath to it.body?.string().orEmpty()
        }
    }

    /** Журнал: дисциплины, средний балл, рейтинг, пропуски и оценки по датам за [interval] ("term" — семестр, "year" — год). */
    suspend fun journal(
        interval: String = "year",
        now: Long = System.currentTimeMillis(),
        retryDelay: Long = 3000,
    ): LksJournal = withContext(Dispatchers.IO) {
        val (path, page) = get("/journal")
        if (path.startsWith("/login")) throw LoginException(EXPIRED)
        val csrf = metaRe.find(page)?.groupValues?.get(1) ?: throw GradesException("На странице журнала нет токена")
        // Неделя из x-data страницы; если разметка поменялась — текущая неделя, как её считает сайт.
        val week = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/Moscow")).toLocalDate()
            .with(java.time.DayOfWeek.MONDAY)
        val start = periodRe("startPeriod").find(page)?.groupValues?.get(1) ?: week.toString()
        val end = periodRe("endPeriod").find(page)?.groupValues?.get(1) ?: week.plusDays(6).toString()
        var tries = 0
        var result: LksJournal? = null
        while (result == null) {
            val body = buildJsonObject {
                put("startPeriod", start); put("interval", interval); put("col", 400); put("endPeriod", end); put("numPeriod", 0)
                put("search", ""); put("lectures", false); put("seminars", false); put("practices", false); put("laboratories", false)
            }
            val req = Request.Builder().url("$base/journal/get")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("X-CSRF-TOKEN", csrf).header("X-Requested-With", "XMLHttpRequest").header("Accept", "application/json")
                .header("Referer", "$base/journal").build()
            val text = runCatching { http.newCall(req).execute() }.getOrElse { throw GradesException("ЛКС не отвечает") }.use { r ->
                if (r.code == 401 || r.code == 419 || r.request.url.encodedPath.startsWith("/login")) throw LoginException(EXPIRED)
                if (r.code == 418 || r.code == 403) throw GradesException(BLOCKED)
                if (!r.isSuccessful) throw GradesException("Журнал ЛКС ответил ошибкой ${r.code}")
                r.body?.string().orEmpty()
            }
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw GradesException("ЛКС прислал непонятный ответ") }
            // Сайт ещё собирает журнал — так же, как страница (через 3 с, до 30 раз), ждём и спрашиваем снова.
            // Пустой недособранный журнал не отдаём: он затёр бы сохранённые оценки.
            if ((o["cacheBuilding"] as? JsonPrimitive)?.booleanOrNull == true) {
                if (++tries >= 30) throw GradesException("ЛКС ещё собирает журнал — попробуй через минуту")
                delay(retryDelay); continue
            }
            if ("lessons" !in o) throw GradesException("ЛКС прислал непонятный ответ")
            result = parse(o, now)
        }
        result
    }

    companion object {
        const val BASE = "https://lks.siriusuniversity.ru"
        const val EXPIRED = "Вход в ЛКС истёк — войди заново"
        private const val BLOCKED = "ЛКС не пускает с этого адреса — нужен российский интернет (если включён VPN, выключи его)"
        private val shared by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
        private val metaRe = Regex("""<meta name="csrf-token" content="([^"]+)"""")
        /** `startPeriod: 2026-10-26`, `startPeriod: '2026-10-26'` или `&quot;startPeriod&quot;:&quot;2026-10-26`. */
        private fun periodRe(name: String) = Regex(name + """\W{0,12}(\d{4}-\d{2}-\d{2})""")

        private fun JsonElement?.text(): String? = when (this) {
            null, is JsonNull -> null
            is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "-" }
            else -> null
        }

        /** Ответ `/journal/get` → журнал. Поля — как в шаблоне страницы: lessons[].title/avg_mark/rating/count_absent/items[дата][]. */
        fun parse(o: JsonObject, now: Long = 0): LksJournal {
            val lessonsEl = o["lessons"]
            val list: List<JsonElement> = when (lessonsEl) {
                is JsonArray -> lessonsEl
                is JsonObject -> lessonsEl.values.toList()
                else -> emptyList()
            }
            val lessons = list.mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                val title = (l["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val items = l["items"] as? JsonObject
                val entries = items?.entries.orEmpty().flatMap { (date, v) ->
                    val arr: List<JsonElement> = when (v) { is JsonArray -> v; is JsonObject -> v.values.toList(); else -> emptyList() }
                    arr.mapNotNull { it as? JsonObject }.map { it ->
                        val marksEl = it["marks"]
                        val marks: List<JsonElement> = when (marksEl) { is JsonArray -> marksEl; is JsonObject -> marksEl.values.toList(); else -> emptyList() }
                        LksEntry(
                            date = date,
                            marks = marks.mapNotNull { m ->
                                val mo = m as? JsonObject ?: return@mapNotNull null
                                val value = (mo["mark_value_standard"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                                val second = (mo["second_mark"] as? JsonObject)?.get("mark_value_standard").text()
                                LksMark(value, (mo["color"] as? JsonPrimitive)?.contentOrNull, second)
                            },
                            absent = (it["absent"] as? JsonPrimitive)?.let { a -> a.booleanOrNull ?: (a.intOrNull?.let { n -> n != 0 }) } ?: false,
                            absentValid = (it["absent_valid"] as? JsonPrimitive)?.intOrNull?.let { n -> n == 1 },
                            comment = it["comment"].text()?.let(MoodleClient::stripHtml),
                            hometask = it["hometask"].text()?.let(MoodleClient::stripHtml),
                        )
                    }
                }.sortedBy { it.date }
                LksLesson(
                    title = title,
                    avgMark = l["avg_mark"].text(),
                    rating = l["rating"].text(),
                    absences = (l["count_absent"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0,
                    entries = entries,
                )
            }
            val period = (o["studyPeriod"] as? JsonPrimitive)?.contentOrNull ?: (o["intervalText"] as? JsonPrimitive)?.contentOrNull ?: ""
            return LksJournal(lessons, period, now)
        }

        /** Журнал → общий снимок успеваемости (для карточки, средних и уведомлений о новых оценках). */
        fun toSnapshot(j: LksJournal, student: String = ""): GradesSnapshot = GradesSnapshot(
            student,
            j.lessons.map { l ->
                CourseGrades(
                    id = l.title.hashCode().toLong(),
                    name = l.title,
                    total = l.avgMark,
                    totalRaw = l.avgMark?.replace(',', '.')?.toDoubleOrNull(),
                    items = l.entries.flatMapIndexed { i, e ->
                        e.marks.mapIndexed { k, m ->
                            GradeItem(
                                id = (e.date + "#" + i + "#" + k).hashCode().toLong(),
                                name = e.date,
                                grade = m.value + (m.second?.let { "/$it" } ?: ""),
                                raw = m.value.replace(',', '.').toDoubleOrNull(),
                                max = 5.0,
                                feedback = e.comment,
                                gradedAt = runCatching { java.time.LocalDate.parse(e.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0),
                            )
                        }
                    },
                )
            },
            j.loadedAt,
        )
    }
}

/** Куки сессии ЛКС: в памяти + можно сохранить и восстановить (чтобы не входить при каждом открытии). */
class SessionCookies(initial: List<Cookie> = emptyList()) : CookieJar {
    private val list = initial.toMutableList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(list) {
        cookies.forEach { c -> list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; list += c }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(list) {
        list.removeAll { it.expiresAt < System.currentTimeMillis() }
        list.filter { it.matches(url) }
    }

    fun all(): List<Cookie> = synchronized(list) { list.toList() }
    fun clear() = synchronized(list) { list.clear() }

    /** Строка для хранения: по куке на строку, как в заголовке Set-Cookie. */
    fun serialize(): String = all().joinToString("\n") { it.toString() }

    companion object {
        fun parse(stored: String, url: HttpUrl): SessionCookies =
            SessionCookies(stored.lines().filter { it.isNotBlank() }.mapNotNull { Cookie.parse(url, it) })

        /**
         * Куки из встроенного окна (`CookieManager.getCookie`: «a=1; b=2», без сроков). Срок им даём долгий:
         * сайт сам пришлёт настоящий при первом ответе, а кука «Запомнить меня» живёт годами.
         */
        fun fromWebView(header: String, url: HttpUrl, now: Long = System.currentTimeMillis()): SessionCookies =
            SessionCookies(header.split(';').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                if (name.isEmpty()) return@mapNotNull null
                runCatching {
                    Cookie.Builder().name(name).value(part.substringAfter('=').trim()).hostOnlyDomain(url.host).path("/")
                        .expiresAt(now + 400L * 24 * 3600 * 1000).build()
                }.getOrNull()
            })
    }
}

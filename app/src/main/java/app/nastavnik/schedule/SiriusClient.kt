/*
 * Основано на SiriusScheduleDataSource из Lecturial 2 (https://github.com/neckmnull/Lecturial-2),
 * Copyright 2026 Lecturial 2 contributors, Apache License 2.0.
 * Изменено для «Наставника»: kotlinx.serialization вместо org.json, OkHttp 4, свои модели, только группы.
 * Lecturial 2 и «Наставник» — неофициальные проекты, не связанные с Университетом «Сириус».
 */
package app.nastavnik.schedule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Расписание с сайта schedule.siriusuniversity.ru. Сайт сделан на Livewire: открываем страницу,
 * берём начальное состояние и CSRF-токен, дальше «нажимаем» те же кнопки, что и браузер
 * (выбор группы, следующая неделя), и читаем занятия из ответа.
 */
class SiriusClient(
    private val baseUrl: String = "https://schedule.siriusuniversity.ru/",
    client: OkHttpClient? = null,
) {
    private val cookies = MemoryCookies()
    private val http = (client ?: shared).newBuilder()
        .cookieJar(cookies)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "Nastavnik/1.0 (Android; schedule client)").build()) }
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val mutex = Mutex()

    /** Группы, похожие на запрос (пустой запрос — все группы). */
    suspend fun groups(query: String): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            cookies.clear()
            val html = execute(Request.Builder().url(baseUrl).get().build())
            val q = query.trim()
            if (q.isEmpty()) return@withContext GroupParser.parse(html).sortedWith(String.CASE_INSENSITIVE_ORDER)
            val state = stateFrom(html)
            // «иоп ит» сайт не найдёт — пробуем и «иоп-ит», и «иопит».
            variants(q).flatMap { GroupParser.parse(state.syncInput("search", it)) }.distinct()
        }
    }

    /** [count] недель подряд, начиная с недели [first], — каждая по мере загрузки. */
    suspend fun weeks(group: String, first: LocalDate, count: Int, onWeek: suspend (Week) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = first.with(DayOfWeek.MONDAY)
            cookies.clear()
            val state = stateFrom(execute(Request.Builder().url(baseUrl).get().build()))
            var data = state.call("set", buildJsonArray { add(JsonPrimitive(group)) })
            val baseDate = runCatching { LocalDate.parse(data["date"]?.jsonPrimitive?.content.orEmpty(), siteDate) }
                .getOrElse { throw IOException("Сайт расписания вернул непонятную дату", it) }
            val offset = ChronoUnit.WEEKS.between(baseDate.with(DayOfWeek.MONDAY), target)
            if (offset !in -104L..104L) throw IOException("Расписание доступно в пределах двух лет")
            val method = if (offset >= 0) "addWeek" else "minusWeek"
            repeat(kotlin.math.abs(offset).toInt()) { data = state.call(method, JsonArray(emptyList())) }
            var week = target
            repeat(count.coerceAtLeast(0)) { i ->
                if (i > 0) {
                    data = state.call("addWeek", JsonArray(emptyList()))
                    week = week.plusWeeks(1)
                }
                onWeek(EventParser.parse(data, group, week, System.currentTimeMillis()))
            }
        }
    }

    private fun execute(request: Request): String {
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { throw IOException("Не удалось связаться с сайтом расписания. Проверь интернет.", it) }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("Сайт расписания сейчас недоступен (HTTP ${it.code}). Попробуй позже.")
            return body
        }
    }

    private inner class State(private val fingerprint: JsonObject, var memo: JsonObject, private val token: String) {
        private var n = 0
        private val component = fingerprint["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "main-grid"

        fun call(method: String, params: JsonArray): JsonObject {
            send(buildJsonObject {
                put("type", "callMethod")
                put("payload", buildJsonObject {
                    put("id", "nastavnik-${n++}")
                    put("method", method)
                    put("params", params)
                })
            })
            return memo["data"] as? JsonObject ?: throw IOException("Сайт расписания не вернул данные")
        }

        fun syncInput(name: String, value: String): String {
            val response = send(buildJsonObject {
                put("type", "syncInput")
                put("payload", buildJsonObject {
                    put("id", "nastavnik-${n++}")
                    put("name", name)
                    put("value", value)
                })
            })
            return (response["effects"] as? JsonObject)?.get("html")?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Сайт расписания не вернул результаты поиска")
        }

        private fun send(update: JsonObject): JsonObject {
            val payload = buildJsonObject {
                put("fingerprint", fingerprint)
                put("serverMemo", memo)
                put("updates", buildJsonArray { add(update) })
            }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/livewire/message/" + component)
                .header("X-CSRF-TOKEN", token)
                .header("X-Livewire", "true")
                .header("Accept", "text/html, application/xhtml+xml")
                .header("Referer", baseUrl)
                .post(payload.toString().toRequestBody(jsonType))
                .build()
            val response = runCatching { json.parseToJsonElement(execute(request)).jsonObject }
                .getOrElse { throw IOException("Сайт расписания ответил непонятно", it) }
            val patch = response["serverMemo"] as? JsonObject ?: throw IOException("Сайт расписания ответил без состояния")
            memo = merge(memo, patch)
            return response
        }
    }

    private fun stateFrom(html: String): State {
        val initial = initialData.find(html)?.groupValues?.get(1) ?: throw IOException("На странице расписания нет состояния Livewire")
        val token = tokenRe.find(html)?.groupValues?.get(1) ?: throw IOException("На странице расписания нет токена")
        val decoded = initial.replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val root = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrElse { throw IOException("Непонятное состояние страницы расписания", it) }
        return State(
            root["fingerprint"] as? JsonObject ?: throw IOException("Нет fingerprint"),
            root["serverMemo"] as? JsonObject ?: throw IOException("Нет serverMemo"),
            token,
        )
    }

    private fun merge(current: JsonObject, patch: JsonObject): JsonObject = buildJsonObject {
        current.forEach { (k, v) -> put(k, v) }
        patch.forEach { (k, v) ->
            if (k == "data" && v is JsonObject) {
                val old = current["data"] as? JsonObject ?: JsonObject(emptyMap())
                put(k, buildJsonObject { old.forEach { (dk, dv) -> put(dk, dv) }; v.forEach { (dk, dv) -> put(dk, dv) } })
            } else {
                put(k, v)
            }
        }
    }

    companion object {
        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }
        private val json = Json { ignoreUnknownKeys = true }
        private val initialData = Regex("wire:initial-data=\"([^\"]+)\"")
        private val tokenRe = Regex("window\\.livewire_token\\s*=\\s*'([^']+)'")
        private val siteDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val separators = Regex("""[\s_–—]+""")

        fun variants(query: String): List<String> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return listOf(q, q.replace(separators, "-"), q.replace(separators, "")).distinct()
        }
    }
}

internal object GroupParser {
    private val attr = Regex("""data-group\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<String> = attr.findAll(html)
        .map { decode(it.groupValues[2]).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private fun decode(v: String): String {
        val named = v.replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val dec = Regex("""&#(\d+);""").replace(named) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value }
        return Regex("""&#[xX]([0-9a-fA-F]+);""").replace(dec) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value }
    }
}

internal object EventParser {
    private val date = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun parse(data: JsonObject, group: String, weekStart: LocalDate, loadedAt: Long): Week {
        val lessons = ArrayList<Lesson>()
        val buckets = data["events"] as? JsonObject
        buckets?.forEach { (key, bucket) ->
            val items: List<JsonElement> = when (bucket) {
                is JsonArray -> bucket
                is JsonObject -> bucket.values.toList()
                else -> emptyList()
            }
            items.forEachIndexed { i, e -> (e as? JsonObject)?.let { lesson(it, group, "$key:$i") }?.let(lessons::add) }
        }
        val end = weekStart.plusDays(6)
        val inWeek = lessons.filter { val d = LocalDate.parse(it.date); !d.isBefore(weekStart) && !d.isAfter(end) }
        return Week(group, weekStart.toString(), inWeek.sortedWith(compareBy({ it.date }, { it.start }, { it.pair ?: Int.MAX_VALUE }, { it.discipline })), loadedAt)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun lesson(o: JsonObject, group: String, pos: String): Lesson? {
        val day = runCatching { LocalDate.parse(o.str("date").orEmpty(), date) }.getOrNull() ?: return null
        val discipline = o.str("discipline") ?: return null
        val teachers = (o["teachers"] as? JsonObject)?.values?.mapNotNull { t ->
            val tt = t as? JsonObject ?: return@mapNotNull null
            tt.str("fio") ?: listOfNotNull(tt.str("last_name"), tt.str("first_name_one"), tt.str("middle_name_one")).joinToString(" ").takeIf { it.isNotBlank() }
        }.orEmpty().distinct()
        val classroom = ScheduleText.classroom(o.str("classroom"))
        val start = o.str("startTime").orEmpty()
        val end = o.str("endTime").orEmpty()
        val key = listOf(group, day, start, end, o.str("numberPair"), discipline, o.str("groupType"), classroom, teachers.joinToString(), pos).joinToString("|")
        return Lesson(
            id = sha(key),
            date = day.toString(),
            start = start,
            end = end,
            pair = (o["numberPair"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() },
            discipline = discipline,
            type = ScheduleText.eventType(o.str("groupType").orEmpty()),
            classroom = classroom,
            address = ScheduleText.address(ScheduleText.undouble(o.str("address"))),
            teachers = teachers,
            comment = ScheduleText.address(o.str("comment")),
            online = o.str("urlOnline"),
        )
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}

private class MemoryCookies : CookieJar {
    private val list = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(list) {
        cookies.forEach { c -> list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; list += c }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(list) {
        list.removeAll { it.expiresAt < System.currentTimeMillis() }
        list.filter { it.matches(url) }
    }

    fun clear() = synchronized(list) { list.clear() }
}

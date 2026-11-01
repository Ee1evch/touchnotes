package app.nastavnik.schedule

import android.content.Context
import app.nastavnik.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Расписание своей группы: сначала из кеша (работает без сети), потом обновляется с сайта.
 * Недели лежат файлами `schedule/<группа>/<понедельник>.json`.
 */
class ScheduleRepo(private val context: Context, private val settings: SettingsRepo, private val client: SiriusClient = SiriusClient()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    /** Растёт при каждом обновлении кеша — экраны перечитывают недели. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    val group: String get() = settings.scheduleGroup()

    private fun dir(group: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(group.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(context.filesDir, "schedule/$h")
    }

    /** Неделя из кеша (с фильтром по преподавателю языка) или null. */
    suspend fun cached(monday: LocalDate, group: String = this.group): Week? = withContext(Dispatchers.IO) {
        if (group.isBlank()) return@withContext null
        val f = File(dir(group), "$monday.json")
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString(Week.serializer(), f.readText()) }.getOrNull()?.let { w ->
            w.copy(lessons = ScheduleText.forLanguageTeacher(w.lessons, settings.languageTeacher()))
        }
    }

    /** Занятия на день из кеша. */
    suspend fun day(date: LocalDate): List<Lesson> = cached(ScheduleText.monday(date))?.on(date).orEmpty()

    /** Загрузить [count] недель с недели [first]. Ошибку сети пробрасывает — кеш при этом не трогаем. */
    suspend fun refresh(first: LocalDate, count: Int = 2, group: String = this.group) {
        if (group.isBlank()) return
        lock.withLock {
            client.weeks(group, ScheduleText.monday(first), count) { w ->
                withContext(Dispatchers.IO) {
                    val d = dir(group).apply { mkdirs() }
                    val tmp = File(d, "${w.start}.json.tmp")
                    tmp.writeText(json.encodeToString(Week.serializer(), w))
                    tmp.renameTo(File(d, "${w.start}.json"))
                }
                _version.value++
            }
        }
    }

    /** Когда неделя загружена (мс), 0 — нет в кеше. */
    suspend fun loadedAt(monday: LocalDate): Long = cached(monday)?.loadedAt ?: 0L

    /** Свежий ли кеш: обновляем не чаще раза в 3 часа. */
    suspend fun stale(monday: LocalDate): Boolean = System.currentTimeMillis() - loadedAt(monday) > 3 * 3600 * 1000L

    suspend fun groups(query: String): List<String> = client.groups(query)

    /** Все преподаватели иностранного языка в кеше — для выбора своего. */
    suspend fun languageTeachers(monday: LocalDate): List<String> = withContext(Dispatchers.IO) {
        val f = File(dir(group), "$monday.json")
        val w = runCatching { json.decodeFromString(Week.serializer(), f.readText()) }.getOrNull() ?: return@withContext emptyList()
        w.lessons.filter { ScheduleText.isForeignLanguage(it.discipline) }.flatMap { it.teachers }.distinct().sorted()
    }

    fun changed() { _version.value++ }
}

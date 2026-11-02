package app.nastavnik.parent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.schedule.SiriusClient
import app.nastavnik.schedule.Week
import app.nastavnik.sync.CloudReader
import app.nastavnik.sync.YandexDiskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Данные родителя: токен Диска студента и номер зачётки — в зашифрованных настройках (токен в журнал не пишется);
 * последний снимок (студент, недели расписания) — в файлах, чтобы без сети показывать его.
 */
class ParentRepo private constructor(private val context: Context) {
    data class State(
        val student: Student? = null,
        /** Понедельник недели → неделя. */
        val weeks: Map<String, Week> = emptyMap(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir get() = File(context.filesDir, "parent").apply { mkdirs() }
    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val secure: SharedPreferences by lazy {
        fun create() = EncryptedSharedPreferences.create(
            context, "parent_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        try {
            create()
        } catch (_: Exception) {
            // Повреждённое хранилище (например, после переноса на новый телефон) — пересоздаём.
            context.deleteSharedPreferences("parent_secure")
            create()
        }
    }

    val token: String get() = secure.getString("disk_token", "").orEmpty()
    val recordBook: String get() = secure.getString("record_book", "").orEmpty()
    /** Группа вручную; пусто — как в «Наставнике» студента. */
    val groupOverride: String get() = secure.getString("group", "").orEmpty()
    val configured: Boolean get() = token.isNotBlank()

    fun save(token: String, recordBook: String, group: String) {
        secure.edit().putString("disk_token", token.trim()).putString("record_book", recordBook.trim()).putString("group", group.trim()).apply()
    }

    fun disconnect() {
        secure.edit().clear().apply()
        dir.deleteRecursively()
        _state.value = State()
    }

    /** Группа для расписания: вручную или из настроек студента. */
    fun group(student: Student? = _state.value.student): String = groupOverride.ifBlank { student?.group.orEmpty() }

    /** Пары дня — с учётом преподавателя языка студента (его подгруппа). */
    fun day(date: LocalDate, s: State = _state.value): List<Lesson> {
        val week = s.weeks[ScheduleText.monday(date).toString()] ?: return emptyList()
        return ScheduleText.forLanguageTeacher(week.on(date), s.student?.languageTeacher)
    }

    /** Снимок с устройства — сразу при запуске. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (_state.value.student != null) return@withContext
        val student = runCatching { json.decodeFromString(Student.serializer(), File(dir, "student.json").readText()) }.getOrNull()
        val weeks = File(dir, "weeks").listFiles().orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString(Week.serializer(), f.readText()) }.getOrNull()
        }.associateBy { it.start }
        _state.update { it.copy(student = student, weeks = weeks) }
    }

    /** Обновить: Диск студента (оценки, группа), потом расписание — прошлая, эта и следующая недели. */
    suspend fun refresh() = lock.withLock {
        if (!configured) return@withLock
        _state.update { it.copy(loading = true, error = null) }
        try {
            val student = withContext(Dispatchers.IO) {
                val records = CloudReader(YandexDiskStore(token)).latest(ParentData.KEYS)
                ParentData.student(records, System.currentTimeMillis()).also { write("student.json", json.encodeToString(Student.serializer(), it)) }
            }
            _state.update { it.copy(student = student) }
            val group = group(student)
            if (group.isNotBlank()) {
                val weeks = HashMap(_state.value.weeks.filterValues { it.group == group })
                SiriusClient().weeks(group, ScheduleText.monday(LocalDate.now()).minusWeeks(1), 3) { w ->
                    weeks[w.start] = w
                    withContext(Dispatchers.IO) { write("weeks/${w.start}.json", json.encodeToString(Week.serializer(), w)) }
                    _state.update { it.copy(weeks = HashMap(weeks)) }
                }
            }
            _state.update { it.copy(loading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Не удалось обновить") }
        }
    }

    private fun write(name: String, text: String) {
        val f = File(dir, name).apply { parentFile?.mkdirs() }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        @Volatile private var instance: ParentRepo? = null
        fun get(context: Context): ParentRepo = instance ?: synchronized(this) {
            instance ?: ParentRepo(context.applicationContext).also { instance = it }
        }
    }
}

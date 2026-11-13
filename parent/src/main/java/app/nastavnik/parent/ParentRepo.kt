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
import kotlinx.serialization.builtins.ListSerializer
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
        /** Посещаемость (староста): понедельник недели → пары с отметками. */
        val attendance: Map<String, List<AttendanceLesson>> = emptyMap(),
        /** Студент на сайте посещаемости (по номеру зачётки). */
        val attendee: AttendanceStudent? = null,
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

    val vault: KeyStoreVault by lazy { KeyStoreVault(context) }

    val token: String get() = (vault.getString("disk_token") ?: secure.getString("disk_token", "")).orEmpty()
    val passphrase: String get() = (vault.getString("passphrase") ?: secure.getString("passphrase", "")).orEmpty()
    val recordBook: String get() = secure.getString("record_book", "").orEmpty()
    /** Группа вручную; пусто — как в «Наставнике» студента. */
    val groupOverride: String get() = secure.getString("group", "").orEmpty()
    /** Подключено: токен Диска (оценки, группа) и/или номер зачётки (посещаемость, группа). */
    val configured: Boolean get() = token.isNotBlank() || recordBook.isNotBlank()

    fun save(token: String, recordBook: String, group: String, passphrase: String = this.passphrase) {
        // Другая зачётка — отметки прежнего студента больше не нужны.
        if (recordBook.trim() != this.recordBook) {
            File(dir, "attendee.json").delete()
            File(dir, "attendance").deleteRecursively()
            _state.update { it.copy(attendee = null, attendance = emptyMap()) }
        }
        val cleanToken = token.trim()
        val cleanPass = passphrase.trim()
        vault.putString("disk_token", cleanToken)
        vault.putString("passphrase", cleanPass)
        secure.edit()
            .putString("disk_token", cleanToken)
            .putString("passphrase", cleanPass)
            .putString("record_book", recordBook.trim())
            .putString("group", group.trim())
            .apply()
    }

    fun disconnect() {
        vault.clear()
        secure.edit().clear().apply()
        dir.deleteRecursively()
        _state.value = State()
    }

    /** Группа для расписания: вручную, из настроек студента или с сайта посещаемости. */
    fun group(student: Student? = _state.value.student, attendee: AttendanceStudent? = _state.value.attendee): String =
        groupOverride.ifBlank { student?.group?.ifBlank { null } ?: attendee?.group.orEmpty() }

    /** Отметка старосты за пару: та же дата и время начала, иначе тот же предмет в этот день. */
    fun markFor(l: Lesson, s: State = _state.value): AttendanceLesson? {
        val week = s.attendance[ScheduleText.monday(l.day).toString()] ?: return null
        val sameDay = week.filter { it.date == l.date }
        return sameDay.firstOrNull { it.start == l.start } ?: sameDay.firstOrNull { ParentData.sameSubject(it.title, l.discipline) }
    }

    /** Пары дня по журналу старосты — если расписания нет (группа неизвестна). */
    fun attendanceDay(date: LocalDate, s: State = _state.value): List<AttendanceLesson> =
        s.attendance[ScheduleText.monday(date).toString()].orEmpty().filter { it.date == date.toString() }

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
        val attendee = runCatching { json.decodeFromString(AttendanceStudent.serializer(), File(dir, "attendee.json").readText()) }.getOrNull()
            ?.takeIf { it.registry == recordBook }
        val attendance = File(dir, "attendance").listFiles().orEmpty().mapNotNull { f ->
            runCatching { f.name.removeSuffix(".json") to json.decodeFromString(ListSerializer(AttendanceLesson.serializer()), f.readText()) }.getOrNull()
        }.toMap()
        _state.update { it.copy(student = student, weeks = weeks, attendee = attendee, attendance = if (attendee != null) attendance else emptyMap()) }
    }

    /**
     * Обновить: Диск студента (оценки, группа) — если есть токен; посещаемость старосты — если есть номер зачётки;
     * потом расписание — прошлая, эта и следующая недели. Ошибка одного не мешает остальному.
     */
    suspend fun refresh() = lock.withLock {
        if (!configured) return@withLock
        _state.update { it.copy(loading = true, error = null) }
        val errors = mutableListOf<String>()
        val monday = ScheduleText.monday(LocalDate.now())
        var student = _state.value.student
        if (token.isNotBlank()) try {
            student = withContext(Dispatchers.IO) {
                val records = CloudReader(YandexDiskStore(token), passphrase = { passphrase }).latest(ParentData.KEYS)
                ParentData.student(records, System.currentTimeMillis()).also { write("student.json", json.encodeToString(Student.serializer(), it)) }
            }
            _state.update { it.copy(student = student) }
        } catch (e: Exception) {
            errors += "Оценки: ${e.message ?: "не удалось обновить"}"
        }
        val book = recordBook
        if (book.isNotBlank()) try {
            val client = AttendanceClient()
            val attendee = _state.value.attendee?.takeIf { it.registry == book } ?: client.student(book).also {
                withContext(Dispatchers.IO) { write("attendee.json", json.encodeToString(AttendanceStudent.serializer(), it)) }
            }
            val weeks = HashMap(_state.value.attendance)
            for (m in listOf(monday.minusWeeks(1), monday, monday.plusWeeks(1))) {
                val lessons = client.week(attendee, m)
                weeks[m.toString()] = lessons
                withContext(Dispatchers.IO) { write("attendance/$m.json", json.encodeToString(ListSerializer(AttendanceLesson.serializer()), lessons)) }
            }
            _state.update { it.copy(attendee = attendee, attendance = HashMap(weeks)) }
        } catch (e: Exception) {
            errors += "Посещаемость: ${e.message ?: "не удалось обновить"}"
        }
        val group = group(student)
        if (group.isNotBlank()) try {
            val weeks = HashMap(_state.value.weeks.filterValues { it.group == group })
            SiriusClient().weeks(group, monday.minusWeeks(1), 3) { w ->
                weeks[w.start] = w
                withContext(Dispatchers.IO) { write("weeks/${w.start}.json", json.encodeToString(Week.serializer(), w)) }
                _state.update { it.copy(weeks = HashMap(weeks)) }
            }
        } catch (e: Exception) {
            errors += "Расписание: ${e.message ?: "не удалось обновить"}"
        }
        // Обновлено — помечаем время, даже если что-то одно не вышло.
        if (student == null) _state.update { it.copy(student = Student(loadedAt = System.currentTimeMillis())) }
        else if (token.isBlank()) _state.update { it.copy(student = it.student?.copy(loadedAt = System.currentTimeMillis())) }
        _state.update { it.copy(loading = false, error = errors.joinToString("\n").ifBlank { null }) }
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

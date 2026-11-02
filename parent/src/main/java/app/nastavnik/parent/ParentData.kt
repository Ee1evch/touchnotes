package app.nastavnik.parent

import app.nastavnik.grades.LksEntry
import app.nastavnik.grades.LksJournal
import app.nastavnik.schedule.Lesson
import app.nastavnik.sync.SyncRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Что родитель видит о студенте: группа, преподаватель языка (из настроек «Наставника») и журнал ЛКС. */
@Serializable
data class Student(
    val group: String = "",
    val languageTeacher: String = "",
    val journal: LksJournal? = null,
    /** Когда прочитано с Диска (мс). */
    val loadedAt: Long = 0,
)

/** Логика без Android (тесты `ParentDataTest`). */
object ParentData {
    /** Записи облака, которые читает родитель. */
    val KEYS = setOf("grades/lks", "prefs/study")

    private val json = Json { ignoreUnknownKeys = true }

    /** Студент из записей облака; чего нет — пусто. */
    fun student(records: Map<String, SyncRecord>, now: Long): Student {
        val prefs = records["prefs/study"]?.data as? JsonObject
        fun s(k: String) = (prefs?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val journal = records["grades/lks"]?.data?.let { runCatching { json.decodeFromJsonElement(LksJournal.serializer(), it) }.getOrNull() }
        return Student(group = s("group"), languageTeacher = s("lang"), journal = journal, loadedAt = now)
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9]+"), " ").trim()

    /** Один и тот же предмет в расписании и в журнале ЛКС (названия бывают чуть разные). */
    fun sameSubject(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    /** Запись журнала за эту пару: тот же предмет, тот же день. */
    fun entryFor(lesson: Lesson, journal: LksJournal?): LksEntry? {
        val subject = journal?.lessons?.firstOrNull { sameSubject(it.title, lesson.discipline) } ?: return null
        return subject.entries.firstOrNull { it.date.take(10) == lesson.date }
    }

    /** Пропуски по журналу: всего, из них по уважительной. */
    fun absences(journal: LksJournal?): Pair<Int, Int> {
        val all = journal?.lessons.orEmpty().flatMap { it.entries }.filter { it.absent }
        return all.size to all.count { it.absentValid == true }
    }
}

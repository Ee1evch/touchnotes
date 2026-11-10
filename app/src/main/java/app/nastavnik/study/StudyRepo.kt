package app.nastavnik.study

import androidx.room.withTransaction
import app.nastavnik.NastavnikApp
import app.nastavnik.data.ActivityEntity
import app.nastavnik.data.CardEntity
import app.nastavnik.notes.CleanNote
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteStorage
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Карточки повторения и учебные события поверх Room. */
class StudyRepo(private val app: NastavnikApp) {
    private val cards get() = app.db.cards()

    /** Карточки из сохранённого чистовика — сразу доступны для повторения. */
    suspend fun addCards(noteId: Long?, note: CleanNote, topicOverride: String? = null) {
        if (note.cards.isEmpty()) return
        val now = System.currentTimeMillis()
        val topic = topicOverride ?: NoteStorage.plainTitle(note.title).ifBlank { "Конспект" }
        cards.insertAll(
            note.cards.map { c ->
                CardEntity(noteId = noteId, subject = note.subject, topic = topic, front = c.q, back = c.a, dueAt = now, createdAt = now)
            },
        )
    }

    /** Встроенный пример: можно попробовать повторение, ещё не сделав ни одного чистовика. */
    suspend fun addSample() {
        val raw = app.assets.open("sample_note.json").bufferedReader().use { it.readText() }
        val note = CleanNoteParser.parse(raw)
        val topic = SAMPLE_PREFIX + NoteStorage.plainTitle(note.title)
        cards.deleteLoose(topic)
        addCards(null, note, topic)
    }

    /** Карточка после ответа: новая коробка и срок. Считается сразу, без базы — экран не ждёт. */
    fun answered(card: CardEntity, remembered: Boolean, now: Long = System.currentTimeMillis()): CardEntity {
        val next = Leitner.review(card.box, remembered, now)
        // Перед экзаменом карточка предмета не уйдёт дальше утра накануне.
        val exam = ExamPlan.examFor(exams(), card.subject.ifBlank { card.topic }, now)
        return card.copy(
            box = next.box,
            dueAt = ExamPlan.capDue(next.dueAt, now, exam?.at),
            reviews = card.reviews + 1,
            lapses = card.lapses + if (remembered) 0 else 1,
        )
    }

    /** Сохранить ответ и записать его в «Прогресс». */
    suspend fun save(updated: CardEntity, remembered: Boolean) {
        cards.update(updated)
        record(Progress.KIND_CARD, updated.topic, if (remembered) 0 else 1)
        StudyWidget.refresh(app)
        // Повторил на телефоне — на планшете карточки тоже сдвинутся.
        app.sync.soon(8000)
    }

    /** Все учебные события — для калибровки, срезов и плана на день. */
    suspend fun events(): List<Event> = app.db.activity().all()
        .filterNot { it.kind == MISTAKE_DETAIL || it.kind == MISTAKE_REVIEWED }
        .map { Event(it.kind, it.topic, it.errors, it.at) }

    data class PracticeMistake(val id: String, val mistake: Mistake, val reviewed: Boolean)

    /** Учебные события синхронизируются существующим протоколом, включая старые устройства. */
    suspend fun saveMistakes(mistakes: List<Mistake>) {
        val dao = app.db.activity()
        val saved = app.db.withTransaction {
            var added = false
            for (raw in mistakes) {
                val m = raw.copy(text = raw.text.trim().take(1000), topic = raw.topic.trim().take(120))
                if (m.text.isBlank()) continue
                val day = java.time.Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).toLocalDate()
                val key = listOf(day.toString(), m.topic, m.text).joinToString("\u0000").lowercase()
                val uid = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
                if (dao.countUid(uid) != 0) continue
                dao.insert(ActivityEntity(kind = MISTAKE_DETAIL, topic = Json.encodeToString(m), errors = 0, at = m.at, uid = uid))
                added = true
            }
            added
        }
        if (saved) app.sync.soon()
    }

    fun observeMistakes(date: LocalDate) = app.db.activity().observeMistakes(date.startMillis())
        .map { rows -> practiceMistakes(date, rows) }

    private fun practiceMistakes(date: LocalDate, rows: List<ActivityEntity>): List<PracticeMistake> {
        val reviewed = rows.filter { it.kind == MISTAKE_REVIEWED }.mapTo(HashSet()) { it.topic }
        val end = date.plusDays(1).startMillis()
        return rows.filter { it.kind == MISTAKE_DETAIL && it.at < end }.mapNotNull { row ->
            runCatching { Json.decodeFromString<Mistake>(row.topic) }.getOrNull()
                ?.let { PracticeMistake(row.uid, it, row.uid in reviewed) }
        }
    }

    suspend fun reviewMistake(item: PracticeMistake, retry: PracticeTask? = null) {
        val uid = "review-${item.id}"
        val dao = app.db.activity()
        app.db.withTransaction {
            if (dao.countUid(uid) != 0) return@withTransaction
            if (retry != null) app.trainer.problemCard(retry, item.mistake.topic)
            dao.insert(ActivityEntity(kind = MISTAKE_REVIEWED, topic = item.id, errors = 0, at = System.currentTimeMillis(), uid = uid))
        }
        app.sync.soon()
    }

    private fun LocalDate.startMillis() = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun record(kind: String, topic: String, errors: Int = 0) {
        runCatching { app.db.activity().insert(ActivityEntity(kind = kind, topic = topic, errors = errors, at = System.currentTimeMillis())) }
    }

    fun exams(): List<Exam> = ExamPlan.decode(app.settings.examsJson()).sortedBy { it.at }

    fun saveExams(list: List<Exam>) {
        app.settings.setExamsJson(ExamPlan.encode(list.sortedBy { it.at }))
        app.settings.touchStudy()
        StudyWidget.refresh(app)
        app.sync.soon()
    }

    /** Ближайший экзамен, который ещё не прошёл (в течение дня экзамена — тоже он). */
    fun nextExam(now: Long = System.currentTimeMillis()): Exam? = exams().firstOrNull { it.at + Streaks.DAY_MS > now }

    companion object {
        private const val MISTAKE_DETAIL = "mistake_detail"
        private const val MISTAKE_REVIEWED = "mistake_reviewed"
        const val SAMPLE_PREFIX = "Пример: "
    }
}

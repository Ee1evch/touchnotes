package app.nastavnik.study

import app.nastavnik.NastavnikApp
import app.nastavnik.data.ActivityEntity
import app.nastavnik.data.CardEntity
import app.nastavnik.notes.CleanNote
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteStorage

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
        return card.copy(
            box = next.box,
            dueAt = next.dueAt,
            reviews = card.reviews + 1,
            lapses = card.lapses + if (remembered) 0 else 1,
        )
    }

    /** Сохранить ответ и записать его в «Прогресс». */
    suspend fun save(updated: CardEntity, remembered: Boolean) {
        cards.update(updated)
        record(Progress.KIND_CARD, updated.topic, if (remembered) 0 else 1)
    }

    suspend fun record(kind: String, topic: String, errors: Int = 0) {
        runCatching { app.db.activity().insert(ActivityEntity(kind = kind, topic = topic, errors = errors, at = System.currentTimeMillis())) }
    }

    companion object {
        const val SAMPLE_PREFIX = "Пример: "
    }
}

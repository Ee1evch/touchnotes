package app.nastavnik.study

import app.nastavnik.NastavnikApp
import app.nastavnik.ai.AiException
import app.nastavnik.ai.ChatRequest
import app.nastavnik.ai.ChatTurn
import app.nastavnik.ai.ImagePart
import app.nastavnik.ai.Role
import app.nastavnik.data.CardEntity
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Результат запроса к нейросети для тренировки: готово или понятная причина, почему нет. */
sealed interface TrainResult<out T> {
    data class Ok<T>(val value: T) : TrainResult<T>
    data class Failed(val message: String, val needsSettings: Boolean = false) : TrainResult<Nothing>
}

/** Материал для варианта или теста: тема, текст конспекта и (необязательно) страницы тетради. */
data class Material(val topic: String, val text: String? = null, val images: List<ByteArray> = emptyList(), val subject: String = "")

/**
 * Тренировка: вариант контрольной, мини-тест и карточки из подсказок. Все запросы — обычные, без стрима,
 * с ответом в JSON; ключ берётся из защищённого хранилища и никуда не пишется.
 */
class Trainer(private val app: NastavnikApp) {

    suspend fun variant(m: Material, count: Int = 5): TrainResult<List<PracticeTask>> =
        call(PracticePrompts.variantSystem(count), m, maxTokens = 4000) { StudyJson.tasks(it).ifEmpty { null } }

    suspend fun quiz(m: Material, count: Int = 8): TrainResult<List<QuizQuestion>> =
        call(PracticePrompts.quizSystem(count), m, maxTokens = 5000) { StudyJson.quiz(it).ifEmpty { null } }

    /** Карточка из подсказки наставника — сразу в «Повторение». */
    suspend fun cardFromHint(hint: String, topic: String, subject: String = ""): TrainResult<CardEntity> {
        val r = call(PracticePrompts.CARD_SYSTEM, Material(topic, hint), maxTokens = 800) { StudyJson.card(it) }
        if (r !is TrainResult.Ok) return r as TrainResult.Failed
        val now = System.currentTimeMillis()
        val card = CardEntity(
            noteId = null,
            subject = subject,
            topic = topic.ifBlank { "Из подсказок" },
            front = r.value.first,
            back = r.value.second,
            dueAt = now,
            createdAt = now,
        )
        app.db.cards().insertAll(listOf(card))
        return TrainResult.Ok(card)
    }

    /** Карточки из вопросов теста, на которые ответил неверно. */
    suspend fun cardsFromMistakes(wrong: List<QuizQuestion>, topic: String, subject: String = "") {
        if (wrong.isEmpty()) return
        val now = System.currentTimeMillis()
        app.db.cards().insertAll(
            wrong.map { q ->
                val answer = q.options.getOrNull(q.correct).orEmpty()
                CardEntity(
                    noteId = null,
                    subject = subject,
                    topic = topic.ifBlank { "Мини-тест" },
                    front = q.q,
                    back = if (q.why.isBlank()) answer else "$answer\n\n${q.why}",
                    dueAt = now,
                    createdAt = now,
                )
            },
        )
    }

    /** Текст сохранённого чистовика — материал для варианта и теста. */
    suspend fun noteMaterial(noteId: Long): Material? = withContext(Dispatchers.IO) {
        val e = app.db.cleanNotes().get(noteId) ?: return@withContext null
        val note = runCatching { CleanNoteParser.fromStoredJson(e.json) }.getOrNull() ?: return@withContext null
        Material(NoteStorage.plainTitle(note.title).ifBlank { e.title }, NoteText.plain(note), subject = e.subject)
    }

    private suspend fun <T> call(system: String, m: Material, maxTokens: Int, parse: (String) -> T?): TrainResult<T> {
        val settings = app.settings.load()
        app.settings.notReadyReason(settings)?.let { return TrainResult.Failed(it, needsSettings = true) }
        val key = app.settings.apiKey(settings.provider)
        val backend = app.ai(settings)
        val text = PracticePrompts.material(m.topic, m.text).ifBlank { "Тема на твой выбор по высшей математике 1 курса." }
        for (attempt in 0..1) {
            val request = ChatRequest(
                modelId = settings.model,
                system = system,
                turns = listOf(
                    ChatTurn(
                        Role.USER,
                        if (attempt == 0) text else "$text\n\nПрошлый ответ не разобрался. Верни только валидный JSON.",
                        hadImage = m.images.isNotEmpty(),
                    ),
                ),
                images = m.images.map { ImagePart(it) },
                maxTokens = maxTokens,
                jsonOutput = true,
            )
            try {
                val raw = backend.complete(key, request).text
                parse(raw)?.let { return TrainResult.Ok(it) }
            } catch (e: AiException) {
                return TrainResult.Failed(e.userMessage, e.needsSettings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return TrainResult.Failed("Что-то пошло не так (${e.message ?: e.javaClass.simpleName}). Попробуй ещё раз.")
            }
        }
        return TrainResult.Failed("Нейросеть ответила не по формату. Попробуй ещё раз или выбери другую модель.")
    }
}

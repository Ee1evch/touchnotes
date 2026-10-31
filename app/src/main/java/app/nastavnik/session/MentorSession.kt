package app.nastavnik.session

import app.nastavnik.NastavnikApp
import app.nastavnik.ai.AiErrors
import app.nastavnik.ai.AiException
import app.nastavnik.ai.ChatRequest
import app.nastavnik.ai.ChatTurn
import app.nastavnik.ai.ImagePart
import app.nastavnik.ai.Mark
import app.nastavnik.ai.MarkKind
import app.nastavnik.ai.NBox
import app.nastavnik.ai.Prompts
import app.nastavnik.ai.Role
import app.nastavnik.ai.ScreenAnswer
import app.nastavnik.ai.ScreenAnswerParser
import app.nastavnik.ai.StreamUpdate
import app.nastavnik.data.Conversation
import app.nastavnik.data.Message
import app.nastavnik.geom.IRect
import app.nastavnik.geom.LineMap
import app.nastavnik.geom.PageImage
import app.nastavnik.geom.PlaceRef
import app.nastavnik.geom.Thumb
import app.nastavnik.study.Mistake
import app.nastavnik.study.MistakeMemory
import app.nastavnik.study.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Какую часть экрана показывает снимок и в каком размере он ушёл в нейросеть.
 * [imageW]×[imageH] — сама запись; слева к снимку добавлено поле шириной [gutter] с номерами строк [lines].
 */
data class ShotGeometry(
    val screen: IRect,
    val imageW: Int,
    val imageH: Int,
    val gutter: Int = 0,
    val lines: LineMap? = null,
    /** Яркость пикселей записи (без поля) — по ней отмеченная строка находится после увеличения страницы. */
    val luma: IntArray? = null,
) {
    /** Снимок записи для поиска строк, если он сохранён. */
    fun page(): PageImage? {
        val l = luma ?: return null
        val map = lines ?: return null
        return if (l.size == imageW * imageH) PageImage(l, imageW, imageH, map) else null
    }

    companion object {
        /** Последний снимок для наставника — по нему тесты узнают номера строк. */
        @Volatile
        var last: ShotGeometry? = null
    }

    /** Ширина всего снимка, который видит модель (с полем). */
    val sentW: Int get() = imageW + gutter

    /**
     * Место из ответа модели → точная рамка в долях записи: по номеру строки — ровно по её чернилам,
     * рамку «на глаз» притягиваем к строкам, которые она задевает.
     */
    fun place(m: Mark): Mark {
        val map = lines ?: LineMap(emptyList(), imageW, imageH)
        val px = m.box?.let {
            IRect(
                (it.left * sentW).toInt() - gutter, (it.top * imageH).toInt(),
                (it.right * sentW).toInt() - gutter, (it.bottom * imageH).toInt(),
            )
        }
        val r = map.resolve(PlaceRef(m.lines, m.after, px), hintLike = m.kind == MarkKind.HINT)
            ?: return m.copy(box = null, lines = null, after = null)
        val w = imageW.toFloat()
        val h = imageH.toFloat()
        val box = NBox(
            (r.left / w).coerceIn(0f, 1f), (r.top / h).coerceIn(0f, 1f),
            (r.right / w).coerceIn(0f, 1f), (r.bottom / h).coerceIn(0f, 1f),
        )
        return m.copy(box = box.takeIf { it.width > 0f && it.height > 0f }, lines = null, after = null)
    }

    fun toScreen(b: NBox): IRect = IRect(
        screen.left + (b.left * screen.width).toInt(),
        screen.top + (b.top * screen.height).toInt(),
        screen.left + (b.right * screen.width).toInt(),
        screen.top + (b.bottom * screen.height).toInt(),
    )
}

sealed interface ShotResult {
    /** [thumb] — уменьшенный кадр всего экрана в момент снимка (для слежения за прокруткой). */
    class Ok(
        val jpeg: ByteArray,
        val geometry: ShotGeometry,
        val thumb: Thumb? = null,
        /** Другие страницы конспекта по порядку — чтобы наставник видел условие, определения и прошлые шаги. */
        val context: List<ByteArray> = emptyList(),
    ) : ShotResult
    data object Cancelled : ShotResult
    data class Failed(val message: String) : ShotResult
}

enum class AskKind {
    /** Вопрос своими словами. */
    USER,

    /** Быстрое действие: подсказка, ошибка, теория. */
    QUICK,

    /** Тихая проверка в фоне: показываем, только если нашлась ошибка. */
    WATCH,
}

/** Ответ наставника, который сейчас нарисован (или рисуется) на экране. */
data class AnswerUi(
    val seq: Long,
    val kind: AskKind,
    val question: String,
    val marks: List<Mark> = emptyList(),
    val streaming: Boolean = true,
    /** Что происходит, пока заметок ещё нет: «Смотрю на экран…», «Думаю…». */
    val status: String? = null,
    /** Решение верное и законченное — предлагаем чистовик. */
    val done: Boolean = false,
    /** Тихая проверка ничего не нашла. */
    val allGood: Boolean = false,
    val errorText: String? = null,
    val error: AiException? = null,
    val geometry: ShotGeometry? = null,
    val thumb: Thumb? = null,
)

data class MentorUi(
    val conversationId: Long = 0,
    val answer: AnswerUi? = null,
    /** Когда тихая проверка в последний раз сказала «всё верно» — кнопка коротко мигает зелёным. */
    val silentOkAt: Long = 0,
    /** Ошибка тихой проверки (например, нет ключа или денег) — проверки ставятся на паузу. */
    val silentError: AiException? = null,
) {
    val busy: Boolean get() = answer?.streaming == true
}

/**
 * Разговор по одной задаче. Хранится в Room; новая задача начинается сама, если
 * наставника долго не спрашивали, или из меню кнопки. Картинка уходит только с последним вопросом.
 */
class MentorSession(
    private val app: NastavnikApp,
    private val scope: CoroutineScope,
    /**
     * Разговор кнопки поверх других приложений — «текущая задача» из настроек; у тетради свой разговор
     * ([bindToSettings] = false, [onConversation] сохраняет его в тетради), и он не прерывается по времени.
     */
    private val bindToSettings: Boolean = true,
    private val onConversation: ((Long) -> Unit)? = null,
) {
    private val db = app.db
    private val _state = MutableStateFlow(MentorUi())
    val state: StateFlow<MentorUi> = _state.asStateFlow()

    private var job: Job? = null
    private var jobKind: AskKind? = null
    private var seq = 0L

    /** [resume] — студент сам выбрал задачу в истории: продолжаем её, даже если она давняя. */
    suspend fun load(resume: Boolean = false) {
        val id = app.settings.load().activeConversationId
        val ok = id != 0L && db.conversations().get(id) != null
        if (ok && resume) db.conversations().touch(id, System.currentTimeMillis())
        _state.value = MentorUi(conversationId = if (ok) id else 0L)
    }

    fun newTask() {
        cancel()
        if (bindToSettings) app.settings.setActiveConversation(0L)
        _state.value = MentorUi()
        onConversation?.invoke(0L)
    }

    /** Продолжить разговор тетради (0 — новый). */
    suspend fun useConversation(id: Long) {
        val ok = id != 0L && db.conversations().get(id) != null
        _state.value = MentorUi(conversationId = if (ok) id else 0L)
    }

    fun cancel() {
        job?.cancel()
        job = null
        jobKind = null
    }

    val isBusy: Boolean get() = job?.isActive == true

    /** Убрать заметки с экрана (и остановить ответ, если он ещё печатается). */
    fun dismiss() {
        cancel()
        _state.update { it.copy(answer = null) }
    }

    fun ask(question: String, kind: AskKind, shot: suspend () -> ShotResult) {
        if (isBusy) {
            // Вопрос студента важнее тихой проверки: она уступает ему место.
            if (kind == AskKind.WATCH || jobKind != AskKind.WATCH) return
            cancel()
        }
        val id = ++seq
        jobKind = kind
        job = scope.launch {
            fun set(a: AnswerUi?) = _state.update { it.copy(answer = a) }
            // Тихая проверка не трогает то, что уже на экране, пока не найдёт ошибку.
            if (kind != AskKind.WATCH) set(AnswerUi(id, kind, question, status = "Смотрю на экран…"))
            val r = try {
                shot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShotResult.Failed("Не удалось сделать снимок экрана. Попробуй ещё раз.")
            }
            val ok = when (r) {
                is ShotResult.Ok -> r
                is ShotResult.Cancelled -> {
                    if (kind != AskKind.WATCH) set(null)
                    return@launch
                }
                is ShotResult.Failed -> {
                    if (kind != AskKind.WATCH) set(AnswerUi(id, kind, question, streaming = false, errorText = r.message))
                    return@launch
                }
            }
            val base = AnswerUi(id, kind, question, status = "Думаю…", geometry = ok.geometry, thumb = ok.thumb)
            if (kind != AskKind.WATCH) set(base)
            run(base, ok)
        }
    }

    private suspend fun run(base: AnswerUi, shot: ShotResult.Ok) {
        val settings = app.settings.load()
        val silent = base.kind == AskKind.WATCH
        val now = System.currentTimeMillis()
        // Тихая проверка не заводит новых разговоров в истории — только читает текущий.
        val convId = if (silent) currentConversation(now) else ensureConversation(base.question, now)
        val history = (if (convId != 0L) db.messages().forConversation(convId) else emptyList())
            .filter { !it.isError && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY)
            .map { ChatTurn(if (it.role == "user") Role.USER else Role.ASSISTANT, it.text, it.hadImage) }
        if (!silent) {
            db.messages().insert(Message(conversationId = convId, role = "user", text = base.question, hadImage = true, createdAt = now))
            db.conversations().touch(convId, now)
        }
        val g = shot.geometry
        val mode = settings.provider.coordMode(settings.model)
        val turns = history + ChatTurn(Role.USER, Prompts.withShotSize(base.question, g.sentW, g.imageH, shot.context.size), hadImage = true)

        val raw = StringBuilder()
        var lastEmit = 0L
        fun parse(streaming: Boolean): ScreenAnswer =
            ScreenAnswerParser.parse(raw.toString(), mode, g.sentW, g.imageH, streaming).let { a -> a.copy(marks = a.marks.map(g::place)) }
        fun emit(status: String? = null) {
            if (silent) return
            val a = parse(true)
            updateOwn(base.seq) { it.copy(marks = a.marks, status = status ?: if (a.marks.isEmpty()) "Думаю…" else null) }
        }
        try {
            val request = ChatRequest(
                modelId = settings.model,
                system = Prompts.mentorSystem(settings.strictness, mode, MistakeMemory.forPrompt(MistakeMemory.decode(app.settings.mistakesJson()))),
                turns = turns,
                // Сначала страницы всего конспекта, последним — текущий экран с номерами строк.
                images = shot.context.map { ImagePart(it) } + ImagePart(shot.jpeg),
                maxTokens = MAX_TOKENS,
            )
            val stop = app.ai(settings).stream(app.settings.apiKey(settings.provider), request) { update ->
                when (update) {
                    is StreamUpdate.Text -> {
                        raw.append(update.delta)
                        val t = System.currentTimeMillis()
                        if (t - lastEmit > 80) {
                            lastEmit = t
                            emit()
                        }
                    }
                    is StreamUpdate.Restart -> {
                        raw.setLength(0)
                        emit("Думаю…")
                    }
                    else -> Unit
                }
            }
            var final = parse(false)
            if (final.marks.isEmpty() && !final.allGood) throw AiErrors.badResponse("пустой ответ")
            // На вопрос студента модель ответила одним «всё верно» — показываем это заметкой, а не пустотой.
            if (!silent && final.marks.isEmpty()) final = final.copy(marks = listOf(Mark(MarkKind.OK, null, "Ошибок не вижу — всё сходится.")))
            if (stop == "max_tokens" && final.marks.isNotEmpty()) {
                val last = final.marks.last()
                final = final.copy(marks = final.marks.dropLast(1) + last.copy(text = last.text + "\n\n_(ответ обрезан)_"))
            }
            if (silent) {
                // Ошибок нет — экран не трогаем; есть — показываем как обычный ответ.
                if (final.allGood || final.marks.none { it.kind == MarkKind.ERROR }) {
                    // Если на экране висела найденная раньше ошибка — её исправили, убираем.
                    _state.update {
                        it.copy(
                            silentOkAt = System.currentTimeMillis(),
                            silentError = null,
                            answer = if (it.answer?.kind == AskKind.WATCH && it.answer?.streaming == false) null else it.answer,
                        )
                    }
                    return
                }
            } else {
                db.messages().insert(
                    Message(conversationId = convId, role = "assistant", text = ScreenAnswerParser.toPlain(final), createdAt = System.currentTimeMillis()),
                )
                db.conversations().touch(convId, System.currentTimeMillis())
                final.topic?.let { nameConversation(convId, base.question, it) }
            }
            // Для «Прогресса»: тема задачи и сколько ошибок нашёл наставник.
            val topic = (if (convId != 0L) db.conversations().get(convId)?.title else null) ?: final.topic.orEmpty()
            // Память о типичных ошибках: короткие подсказки к найденным ошибкам.
            val found = final.marks.filter { it.kind == MarkKind.ERROR && it.text.isNotBlank() }
            if (found.isNotEmpty()) {
                val at = System.currentTimeMillis()
                val list = MistakeMemory.add(MistakeMemory.decode(app.settings.mistakesJson()), found.map { Mistake(it.text, topic, at) })
                app.settings.setMistakesJson(MistakeMemory.encode(list))
            }
            app.study.record(
                if (silent) Progress.KIND_WATCH else Progress.KIND_ASK,
                topic,
                final.marks.count { it.kind == MarkKind.ERROR },
            )
            _state.update {
                it.copy(answer = base.copy(marks = final.marks, streaming = false, status = null, done = final.done, allGood = final.allGood))
            }
            // Читать подсказки вслух, если студент так попросил в настройках (короткие части по порядку).
            if (app.settings.speakHints() && final.marks.isNotEmpty()) {
                app.speaker.speak(final.marks.joinToString(". ") { it.text })
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (!silent && raw.isNotBlank()) {
                    val partial = ScreenAnswerParser.toPlain(parse(false))
                    if (partial.isNotBlank()) {
                        db.messages().insert(Message(conversationId = convId, role = "assistant", text = "$partial …", createdAt = System.currentTimeMillis()))
                    }
                }
            }
            updateOwn(base.seq) { it.copy(streaming = false, status = null) }
            throw e
        } catch (e: AiException) {
            if (!silent) _state.update { it.copy(answer = base.copy(streaming = false, status = null, errorText = e.userMessage, error = e)) }
            else _state.update { it.copy(silentError = e) }
        } catch (e: Exception) {
            val err = AiErrors.badResponse(e.message)
            if (!silent) _state.update { it.copy(answer = base.copy(streaming = false, status = null, errorText = err.userMessage, error = err)) }
            else _state.update { it.copy(silentError = err) }
        }
    }

    /** Меняет ответ на экране, только если это всё ещё ответ [seq]: остановленный запрос не трогает новый. */
    private fun updateOwn(seq: Long, change: (AnswerUi) -> AnswerUi) = _state.update { s ->
        val a = s.answer
        if (a != null && a.seq == seq) s.copy(answer = change(a)) else s
    }

    /** Задача названа по первому вопросу или дате — даём ей имя по теме, которую узнала модель. */
    private suspend fun nameConversation(id: Long, question: String, topic: String) {
        val conv = db.conversations().get(id) ?: return
        if (conv.title.startsWith(GENERIC_TITLE) || conv.title == question.take(60)) db.conversations().rename(id, topic)
    }

    private suspend fun currentConversation(now: Long): Long {
        val current = _state.value.conversationId
        val conv = if (current != 0L) db.conversations().get(current) else null
        return if (conv != null && (!bindToSettings || now - conv.updatedAt < NEW_TASK_AFTER_MS)) current else 0L
    }

    private suspend fun ensureConversation(firstQuestion: String, now: Long): Long {
        // Долго не спрашивали — скорее всего, это уже другая задача.
        currentConversation(now).let { if (it != 0L) return it }
        val stamp = SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(now))
        val title = if (Prompts.isQuickPrompt(firstQuestion)) "$GENERIC_TITLE$stamp" else firstQuestion.take(60)
        val id = db.conversations().insert(Conversation(title = title, subject = "", createdAt = now, updatedAt = now))
        if (bindToSettings) app.settings.setActiveConversation(id)
        onConversation?.invoke(id)
        _state.update { it.copy(conversationId = id) }
        return id
    }

    companion object {
        /** С запасом: adaptive thinking тоже тратит max_tokens, а сам ответ короткий. */
        const val MAX_TOKENS = 16000
        private const val MAX_HISTORY = 12
        private const val NEW_TASK_AFTER_MS = 40 * 60 * 1000L
        private const val GENERIC_TITLE = "Задача · "
    }
}

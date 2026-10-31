package app.nastavnik.session

import android.view.ViewGroup
import app.nastavnik.NastavnikApp
import app.nastavnik.ai.ChatTurn
import app.nastavnik.ai.AiException
import app.nastavnik.ai.ImagePart
import app.nastavnik.ai.ChatRequest
import app.nastavnik.ai.Prompts
import app.nastavnik.ai.Role
import app.nastavnik.data.CleanNoteEntity
import app.nastavnik.notes.CleanNote
import app.nastavnik.notes.CleanNoteParseException
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteStorage
import app.nastavnik.notes.PdfExporter
import app.nastavnik.notes.NoteApps
import app.nastavnik.study.Progress
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface CleanState {
    val shots: List<ByteArray>

    data object Idle : CleanState {
        override val shots: List<ByteArray> = emptyList()
    }

    /** Собираем снимки (до [MAX_SHOTS]). */
    data class Collecting(override val shots: List<ByteArray>, val capturing: Boolean = false, val message: String? = null) : CleanState

    data class Generating(override val shots: List<ByteArray>, val retry: Boolean) : CleanState

    data class Preview(override val shots: List<ByteArray>, val note: CleanNote, val json: String) : CleanState

    data class Saving(override val shots: List<ByteArray>, val note: CleanNote, val json: String) : CleanState

    data class Saved(
        override val shots: List<ByteArray>,
        val note: CleanNote,
        val json: String,
        val pdf: File,
        val pngs: List<File>,
        val publicPath: String?,
        val sendMessage: String,
    ) : CleanState

    data class Failed(
        override val shots: List<ByteArray>,
        val message: String,
        val note: CleanNote? = null,
        val json: String? = null,
        val needsSettings: Boolean = false,
    ) : CleanState

    companion object {
        const val MAX_SHOTS = 8
    }
}

/** Чистовик: снимки → JSON от нейросети → предпросмотр → PDF/PNG → приложение для записей. */
class CleanSession(private val app: NastavnikApp, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CleanState>(CleanState.Idle)
    val state: StateFlow<CleanState> = _state.asStateFlow()
    private var job: Job? = null
    private val storage = NoteStorage(app)

    val isActive: Boolean get() = _state.value !is CleanState.Idle

    fun start(capture: suspend () -> ShotResult) {
        cancel()
        _state.value = CleanState.Collecting(emptyList(), capturing = true)
        addShot(capture)
    }

    fun addShot(capture: suspend () -> ShotResult) {
        val current = _state.value
        val shots = current.shots
        if (shots.size >= CleanState.MAX_SHOTS) return
        _state.value = CleanState.Collecting(shots, capturing = true)
        job = scope.launch {
            val r = try {
                capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShotResult.Failed("Не удалось сделать снимок экрана. Попробуй ещё раз.")
            }
            _state.value = when (r) {
                is ShotResult.Ok -> CleanState.Collecting(shots + r.jpeg)
                is ShotResult.Cancelled -> if (shots.isEmpty()) CleanState.Idle else CleanState.Collecting(shots)
                is ShotResult.Failed -> if (shots.isEmpty()) CleanState.Failed(emptyList(), r.message) else CleanState.Collecting(shots, message = r.message)
            }
        }
    }

    /** Добавить готовые снимки — например, все запомненные страницы конспекта. */
    fun addImages(images: List<ByteArray>) {
        val shots = _state.value.shots
        val room = CleanState.MAX_SHOTS - shots.size
        if (room <= 0 || images.isEmpty()) return
        _state.value = CleanState.Collecting(shots + images.take(room))
    }


    fun build() {
        val shots = _state.value.shots
        if (shots.isEmpty()) return
        job?.cancel()
        job = scope.launch { generate(shots) }
    }

    private suspend fun generate(shots: List<ByteArray>) {
        val settings = app.settings.load()
        val key = app.settings.apiKey(settings.provider)
        val backend = app.ai(settings)
        for (attempt in 0..1) {
            val retry = attempt == 1
            _state.value = CleanState.Generating(shots, retry)
            val request = ChatRequest(
                modelId = settings.cleanModel,
                system = Prompts.cleanSystem(),
                turns = listOf(ChatTurn(Role.USER, Prompts.cleanUserText(shots.size, retry), hadImage = true)),
                images = shots.map { ImagePart(it) },
                maxTokens = MAX_TOKENS,
                jsonOutput = true,
            )
            try {
                val completion = backend.complete(key, request)
                val parsed = withContext(Dispatchers.Default) { CleanNoteParser.parse(completion.text) }
                val note = parsed.copy(
                    subject = parsed.subject.trim(),
                    date = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date()),
                )
                _state.value = CleanState.Preview(shots, note, CleanNoteParser.toJson(note))
                return
            } catch (e: CleanNoteParseException) {
                if (retry) {
                    _state.value = CleanState.Failed(
                        shots,
                        "Не получилось разобрать ответ модели (${e.message}). Нажми «Пересобрать».",
                    )
                }
            } catch (e: AiException) {
                _state.value = CleanState.Failed(shots, e.userMessage, needsSettings = e.needsSettings)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Неожиданный сбой не должен ронять приложение: снимки остаются, можно пересобрать.
                _state.value = CleanState.Failed(shots, "Что-то пошло не так (${e.message ?: e.javaClass.simpleName}). Нажми «Пересобрать».")
                return
            }
        }
    }

    /** Сохранить PDF+PNG и отправить в приложение для записей. [host] — окно, куда временно встраивается WebView для экспорта. */
    fun save(host: ViewGroup, conversationId: Long?) {
        val s = _state.value as? CleanState.Preview ?: return
        job = scope.launch {
            _state.value = CleanState.Saving(s.shots, s.note, s.json)
            try {
                val settings = app.settings.load()
                val dir = storage.newWorkDir()
                val result = PdfExporter(app).export(host, s.json, dir, NoteStorage.fileBase(s.note.title))
                val subject = s.note.subject.ifBlank { "Конспекты" }
                val published = withContext(Dispatchers.IO) { storage.publish(result.pdf, result.pngs, subject, s.note.title) }
                val noteId = withContext(Dispatchers.IO) {
                    app.db.cleanNotes().insert(
                        CleanNoteEntity(
                            conversationId = conversationId?.takeIf { it != 0L },
                            subject = subject,
                            title = NoteStorage.plainTitle(s.note.title).ifBlank { "Конспект" },
                            json = s.json,
                            pdfPath = result.pdf.absolutePath,
                            pngPaths = result.pngs.joinToString("\n") { it.absolutePath },
                            publicPath = published.publicPath,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                // Карточки из конспекта сразу попадают в «Повторение».
                runCatching { app.study.addCards(noteId, s.note) }
                app.study.record(Progress.KIND_CLEAN, NoteStorage.plainTitle(s.note.title))
                val send = NoteApps.sendPdf(app, result.pdf, NoteStorage.plainTitle(s.note.title), settings.sendTarget)
                val sendMessage = when (send) {
                    is NoteApps.Outcome.Direct -> "Открываю «${send.label}» — подтверди импорт PDF."
                    NoteApps.Outcome.Chooser -> "Выбери, куда отправить конспект (приложение для записей не выбрано)."
                    is NoteApps.Outcome.Failed -> send.message
                }
                val where = published.publicPath?.let { "Сохранено: $it" } ?: (published.error ?: "")
                _state.value = CleanState.Saved(
                    s.shots, s.note, s.json, result.pdf, result.pngs, published.publicPath,
                    listOf(where, cardsMessage(s.note.cards.size), sendMessage).filter { it.isNotBlank() }.joinToString("\n"),
                )
            } catch (e: TimeoutCancellationException) {
                // Ловим раньше CancellationException: это наш таймаут вёрстки, а не отмена.
                _state.value = CleanState.Failed(s.shots, "Чистовик слишком долго рисуется. Попробуй ещё раз.", s.note, s.json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CleanState.Failed(s.shots, "Не удалось сохранить PDF: ${e.message ?: e.javaClass.simpleName}", s.note, s.json)
            }
        }
    }

    private fun cardsMessage(n: Int): String =
        if (n == 0) "" else "В «Повторение» добавлено $n ${ruPlural(n, "карточка", "карточки", "карточек")}."

    fun resend() {
        val s = _state.value as? CleanState.Saved ?: return
        NoteApps.sendPdf(app, s.pdf, NoteStorage.plainTitle(s.note.title), app.settings.load().sendTarget)
    }

    fun sendPngs() {
        val s = _state.value as? CleanState.Saved ?: return
        NoteApps.sendImages(app, s.pngs, NoteStorage.plainTitle(s.note.title))
    }

    /** Вернуться к предпросмотру после ошибки сохранения. */
    fun backToPreview() {
        val s = _state.value as? CleanState.Failed ?: return
        if (s.note != null && s.json != null) _state.value = CleanState.Preview(s.shots, s.note, s.json)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = CleanState.Idle
    }

    companion object {
        const val MAX_TOKENS = 16000
    }
}

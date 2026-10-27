package app.nastavnik.session

import app.nastavnik.NastavnikApp
import app.nastavnik.ai.ChatTurn
import app.nastavnik.ai.AiErrors
import app.nastavnik.ai.AiException
import app.nastavnik.ai.DoneMarker
import app.nastavnik.ai.ImagePart
import app.nastavnik.ai.ChatRequest
import app.nastavnik.ai.Prompts
import app.nastavnik.ai.Role
import app.nastavnik.ai.StreamUpdate
import app.nastavnik.data.Conversation
import app.nastavnik.data.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val hadImage: Boolean = false,
    val isError: Boolean = false,
    val streaming: Boolean = false,
    /** Подпись, пока текста ещё нет: «Делаю снимок…», «Думаю…». */
    val status: String? = null,
)

data class ChatUiState(
    val conversationId: Long = 0,
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    /** Наставник сказал, что решение верное: показываем плашку «Сделать чистовик?». */
    val solved: Boolean = false,
    /** Последняя ошибка — для кнопки «Открыть настройки» и т.п. */
    val lastError: AiException? = null,
)

/**
 * Переписка по одной задаче. Хранится в Room, живёт до «Новая задача».
 * Картинка уходит только с последним вопросом; в истории — только текст.
 */
class MentorSession(private val app: NastavnikApp, private val scope: CoroutineScope) {
    private val db = app.db
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null

    suspend fun load() {
        val id = app.settings.load().activeConversationId
        if (id == 0L || db.conversations().get(id) == null) {
            _state.value = ChatUiState()
            return
        }
        val msgs = db.messages().forConversation(id)
            .filter { it.text.isNotBlank() }
            .map { it.toUi() }
        _state.value = ChatUiState(conversationId = id, messages = msgs)
    }

    fun newTask() {
        cancel()
        app.settings.setActiveConversation(0L)
        _state.value = ChatUiState()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Показывает вопрос сразу, а картинку получает через [shot] (снимок может потребовать
     * разрешения или выбора области). Если [shot] вернул null — вопрос отменяется.
     */
    fun ask(question: String, withShot: Boolean, shot: suspend () -> ShotResult) {
        if (isBusy) return
        job = scope.launch {
            val tempUserId = -System.nanoTime()
            val tempBotId = tempUserId - 1
            _state.update {
                it.copy(
                    busy = true,
                    solved = false,
                    lastError = null,
                    messages = it.messages +
                        ChatMessage(tempUserId, "user", question, hadImage = withShot) +
                        ChatMessage(tempBotId, "assistant", "", streaming = true, status = if (withShot) "Делаю снимок экрана…" else "Наставник думает…"),
                )
            }
            var image: ByteArray? = null
            if (withShot) {
                when (val r = shot()) {
                    is ShotResult.Ok -> image = r.jpeg
                    is ShotResult.Cancelled -> {
                        _state.update { s -> s.copy(busy = false, messages = s.messages.filter { it.id != tempUserId && it.id != tempBotId }) }
                        return@launch
                    }
                    is ShotResult.Failed -> {
                        _state.update { s ->
                            s.copy(
                                busy = false,
                                messages = s.messages.filter { it.id != tempUserId && it.id != tempBotId } +
                                    ChatMessage(tempBotId, "assistant", r.message, isError = true),
                            )
                        }
                        return@launch
                    }
                }
            }
            runQuestion(question, image, tempUserId, tempBotId)
        }
    }

    private suspend fun runQuestion(question: String, image: ByteArray?, tempUserId: Long, tempBotId: Long) {
        val settings = app.settings.load()
        val now = System.currentTimeMillis()
        val convId = ensureConversation(settings.subject, question, now)
        val userId = db.messages().insert(
            Message(conversationId = convId, role = "user", text = question, hadImage = image != null, createdAt = now),
        )
        val botId = db.messages().insert(Message(conversationId = convId, role = "assistant", text = "", createdAt = now + 1))
        db.conversations().touch(convId, now)
        _state.update { s ->
            s.copy(
                conversationId = convId,
                messages = s.messages.map {
                    when (it.id) {
                        tempUserId -> it.copy(id = userId)
                        tempBotId -> it.copy(id = botId, status = "Наставник думает…")
                        else -> it
                    }
                },
            )
        }

        val history = db.messages().forConversation(convId)
            .filter { !it.isError && it.id != botId && it.text.isNotBlank() }
            .map { ChatTurn(if (it.role == "user") Role.USER else Role.ASSISTANT, it.text, it.hadImage) }

        val raw = StringBuilder()
        var lastEmit = 0L
        fun emit(streaming: Boolean, status: String? = null) {
            val processed = DoneMarker.process(raw.toString(), streaming)
            _state.update { s ->
                s.copy(messages = s.messages.map {
                    if (it.id == botId) it.copy(text = processed.text, streaming = streaming, status = status ?: it.status) else it
                })
            }
        }
        try {
            val request = ChatRequest(
                modelId = settings.model,
                system = Prompts.mentorSystem(settings.subject, settings.strictness),
                turns = history,
                images = listOfNotNull(image?.let { ImagePart(it) }),
                maxTokens = MAX_TOKENS,
            )
            val stop = app.ai(settings).stream(app.settings.apiKey(settings.provider), request) { update ->
                when (update) {
                    is StreamUpdate.Text -> {
                        raw.append(update.delta)
                        val t = System.currentTimeMillis()
                        if (t - lastEmit > 60) {
                            lastEmit = t
                            emit(true)
                        }
                    }
                    is StreamUpdate.Thinking -> if (raw.isEmpty()) emit(true, "Наставник думает…")
                    is StreamUpdate.Restart -> {
                        raw.setLength(0)
                        emit(true, "Наставник думает…")
                    }
                    else -> Unit
                }
            }
            var final = DoneMarker.process(raw.toString())
            if (final.text.isBlank()) throw AiErrors.badResponse("пустой ответ")
            if (stop == "max_tokens") final = final.copy(text = final.text + "\n\n_(ответ обрезан)_")
            db.messages().update(botId, final.text, false)
            db.conversations().touch(convId, System.currentTimeMillis())
            _state.update { s ->
                s.copy(
                    busy = false,
                    solved = final.done,
                    messages = s.messages.map { if (it.id == botId) it.copy(text = final.text, streaming = false, status = null) else it },
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val partial = DoneMarker.process(raw.toString()).text
                if (partial.isBlank()) db.messages().delete(botId) else db.messages().update(botId, "$partial …", false)
            }
            _state.update { s ->
                s.copy(busy = false, messages = s.messages.mapNotNull {
                    if (it.id != botId) it else if (raw.isBlank()) null else it.copy(streaming = false, status = null)
                })
            }
            throw e
        } catch (e: AiException) {
            fail(botId, e)
        } catch (e: Exception) {
            fail(botId, AiErrors.badResponse(e.message))
        }
    }

    private suspend fun fail(botId: Long, e: AiException) {
        db.messages().update(botId, e.userMessage, true)
        _state.update { s ->
            s.copy(
                busy = false,
                lastError = e,
                messages = s.messages.map {
                    if (it.id == botId) it.copy(text = e.userMessage, isError = true, streaming = false, status = null) else it
                },
            )
        }
    }

    private suspend fun ensureConversation(subject: String, firstQuestion: String, now: Long): Long {
        val current = _state.value.conversationId
        if (current != 0L && db.conversations().get(current) != null) return current
        val stamp = SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(now))
        val title = if (Prompts.isQuickPrompt(firstQuestion)) "Задача · $stamp" else firstQuestion.take(60)
        val id = db.conversations().insert(Conversation(title = title, subject = subject, createdAt = now, updatedAt = now))
        app.settings.setActiveConversation(id)
        return id
    }

    fun dismissSolved() = _state.update { it.copy(solved = false) }

    private fun Message.toUi() = ChatMessage(id, role, text, hadImage, isError)

    companion object {
        /** С запасом: adaptive thinking тоже тратит max_tokens, а сам ответ короткий. */
        const val MAX_TOKENS = 16000
    }
}

sealed interface ShotResult {
    class Ok(val jpeg: ByteArray) : ShotResult
    data object Cancelled : ShotResult
    data class Failed(val message: String) : ShotResult
}

package app.nastavnik.ai

/** Поддерживаемые провайдеры нейросетей. */
enum class AiProvider(
    val title: String,
    val shortTitle: String,
    /** Где взять ключ — показывается в настройках и в сообщениях об ошибках. */
    val keySite: String,
    val keyUrl: String,
    val keyPlaceholder: String,
) {
    ANTHROPIC(
        "Anthropic Claude",
        "Claude",
        "console.anthropic.com",
        "https://console.anthropic.com/settings/keys",
        "sk-ant-…",
    ),
    GEMINI(
        "Google Gemini",
        "Gemini",
        "aistudio.google.com",
        "https://aistudio.google.com/apikey",
        "AIza…",
    ),
    OPENAI_COMPAT(
        "OpenAI-совместимый (OpenAI, OpenRouter, свой сервер)",
        "OpenAI-совм.",
        "сайте вашего сервиса",
        "https://platform.openai.com/api-keys",
        "sk-…",
    ),
    ;

    companion object {
        fun byName(name: String?): AiProvider = entries.firstOrNull { it.name == name } ?: ANTHROPIC
    }
}

/** Готовая модель для быстрого выбора в настройках. */
data class ModelPreset(val id: String, val label: String, val hint: String)

object ModelPresets {
    fun mentor(provider: AiProvider): List<ModelPreset> = when (provider) {
        AiProvider.ANTHROPIC -> ClaudeModel.entries.map { ModelPreset(it.id, it.label, it.hint) }
        AiProvider.GEMINI -> listOf(
            ModelPreset("gemini-flash-latest", "Gemini Flash (последняя)", "быстро и недорого, есть бесплатный лимит"),
            ModelPreset("gemini-pro-latest", "Gemini Pro (последняя)", "для сложных задач"),
            ModelPreset("gemini-flash-lite-latest", "Gemini Flash-Lite (последняя)", "самая дешёвая"),
        )
        AiProvider.OPENAI_COMPAT -> listOf(
            ModelPreset("gpt-5-mini", "GPT-5 mini", "OpenAI, быстро и недорого"),
            ModelPreset("gpt-5", "GPT-5", "OpenAI, для сложных задач"),
        )
    }

    fun defaultMentor(provider: AiProvider): String = mentor(provider).first().id

    fun defaultClean(provider: AiProvider): String = defaultMentor(provider)
}

/** Готовые адреса OpenAI-совместимых сервисов. */
object OpenAiCompatPresets {
    const val OPENAI = "https://api.openai.com/v1"
    const val OPENROUTER = "https://openrouter.ai/api/v1"
    val all = listOf("OpenAI" to OPENAI, "OpenRouter" to OPENROUTER)
}

/** Запрос, не зависящий от провайдера. */
data class ChatRequest(
    val modelId: String,
    val system: String,
    /** Вся переписка, последней идёт реплика пользователя. */
    val turns: List<ChatTurn>,
    /** Картинки к последней реплике. */
    val images: List<ImagePart> = emptyList(),
    val maxTokens: Int,
    /** Просим ответ строго в JSON (если провайдер это умеет). */
    val jsonOutput: Boolean = false,
)

/** Общий интерфейс клиентов: Anthropic, Gemini, OpenAI-совместимые. Все методы бросают [AiException]. */
interface AiBackend {
    val provider: AiProvider

    /** Потоковый ответ; возвращает причину остановки ("end_turn", "max_tokens", …). */
    suspend fun stream(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String?

    /** Обычный запрос без стрима. */
    suspend fun complete(apiKey: String, request: ChatRequest): Completion

    /** Бесплатная проверка ключа и доступности модели. */
    suspend fun checkKey(apiKey: String, modelId: String): KeyCheck

    /** Список моделей, доступных по ключу. */
    suspend fun listModels(apiKey: String): List<String>
}

/** Итог обычного (не потокового) запроса. */
data class Completion(val text: String, val stopReason: String?)

/** Результат проверки ключа в настройках. */
sealed interface KeyCheck {
    data object Ok : KeyCheck
    data class ModelMissing(val modelId: String) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/** Унифицированная причина остановки. */
object StopReasons {
    const val END = "end_turn"
    const val MAX_TOKENS = "max_tokens"
    const val REFUSAL = "refusal"
}

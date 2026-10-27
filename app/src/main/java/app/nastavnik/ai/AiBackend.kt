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
        "OpenAI-совместимый (OpenAI, OpenRouter, прокси вроде Claudex/CLIProxyAPI)",
        "OpenAI-совм.",
        "сайте вашего сервиса или в настройках прокси",
        "https://platform.openai.com/api-keys",
        "sk-… или ключ прокси",
    ),
    ANTHROPIC_COMPAT(
        "Claude-совместимый прокси (Claudex, CLIProxyAPI, свой сервер)",
        "Прокси Claude",
        "настройках вашего прокси",
        "https://github.com/router-for-me/CLIProxyAPI",
        "ключ/токен прокси (может быть любым)",
    ),
    ;

    /** У прокси и своих серверов ключ может быть не нужен — главное адрес. */
    val keyOptional: Boolean get() = this == OPENAI_COMPAT || this == ANTHROPIC_COMPAT

    /** Провайдеру нужен адрес сервера из настроек. */
    val needsBaseUrl: Boolean get() = this == OPENAI_COMPAT || this == ANTHROPIC_COMPAT

    /**
     * В какой системе модель пишет координаты заметок на снимке. Claude — в пикселях снимка
     * (снимок не больше 1,15 Мп, поэтому API его не уменьшает); Gemini — box_2d на 1000;
     * остальные — доли от 1000: сервис может сам уменьшить картинку, и пиксели «уедут».
     */
    fun coordMode(model: String): CoordMode = when (this) {
        ANTHROPIC -> CoordMode.PIXELS_XY
        GEMINI -> CoordMode.NORM_YX
        ANTHROPIC_COMPAT -> if (model.startsWith("claude")) CoordMode.PIXELS_XY else CoordMode.NORM_XY
        OPENAI_COMPAT -> if (model.startsWith("gemini")) CoordMode.NORM_YX else CoordMode.NORM_XY
    }

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
        // Прокси сам решает, куда отправить модель; эти имена понимают Claudex и CLIProxyAPI.
        AiProvider.ANTHROPIC_COMPAT -> listOf(
            ModelPreset("claude-sonnet-4-5", "claude-sonnet-4-5", "частое имя модели в прокси"),
            ModelPreset("claude-opus-4-1", "claude-opus-4-1", "для сложных задач"),
            ModelPreset("gpt-5-codex", "gpt-5-codex", "если прокси работает через подписку Codex"),
        )
    }

    fun defaultMentor(provider: AiProvider): String = mentor(provider).first().id

    fun defaultClean(provider: AiProvider): String = defaultMentor(provider)
}

/** Как передавать ключ Claude-совместимому прокси. */
enum class AuthMode(val label: String) {
    BOTH("Оба заголовка (подходит почти всем)"),
    BEARER("Authorization: Bearer (как ANTHROPIC_AUTH_TOKEN)"),
    X_API_KEY("x-api-key (как ANTHROPIC_API_KEY)"),
    ;

    companion object {
        fun byName(name: String?): AuthMode = entries.firstOrNull { it.name == name } ?: BOTH
    }
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

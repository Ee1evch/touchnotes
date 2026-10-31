package app.nastavnik.ai

/** Учёт расходов: перед запросом — проверка лимита, после — запись токенов. */
interface UsageMeter {
    /** Бросает [AiException] с видом BUDGET, если дневной лимит уже исчерпан. */
    fun check()

    fun record(model: String, usage: Usage)
}

/**
 * Обёртка над любым клиентом: считает токены каждого запроса. Если сервис прислал точные числа
 * (Claude присылает) — берём их, иначе оцениваем по длине текста и размеру снимков.
 */
class MeteredBackend(private val inner: AiBackend, private val meter: UsageMeter) : AiBackend {
    override val provider: AiProvider get() = inner.provider

    override suspend fun stream(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String? {
        meter.check()
        // Сервис не понял параметры быстрого ответа (старая модель, прокси) — повторяем обычным запросом.
        if (request.fast) {
            try {
                return streamMetered(apiKey, request, onUpdate)
            } catch (e: AiException) {
                if (e.kind != AiException.Kind.BAD_REQUEST) throw e
            }
            return streamMetered(apiKey, request.copy(fast = false), onUpdate)
        }
        return streamMetered(apiKey, request, onUpdate)
    }

    private suspend fun streamMetered(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String? {
        var input: Int? = null
        var output: Int? = null
        var chars = 0
        try {
            return inner.stream(apiKey, request) { u ->
                when (u) {
                    is StreamUpdate.Text -> chars += u.delta.length
                    is StreamUpdate.Tokens -> {
                        u.input?.let { input = it }
                        u.output?.let { output = it }
                    }
                    is StreamUpdate.Stop -> u.outputTokens?.let { output = it }
                    else -> Unit
                }
                onUpdate(u)
            }
        } finally {
            // Сервер ответил хоть что-то — запрос оплачен (даже если его прервали на середине).
            if (chars > 0 || output != null) {
                meter.record(request.modelId, Usage(input ?: TokenEstimate.request(request), output ?: TokenEstimate.chars(chars)))
            }
        }
    }

    override suspend fun complete(apiKey: String, request: ChatRequest): Completion {
        meter.check()
        val c = if (request.fast) {
            try {
                inner.complete(apiKey, request)
            } catch (e: AiException) {
                if (e.kind != AiException.Kind.BAD_REQUEST) throw e
                inner.complete(apiKey, request.copy(fast = false))
            }
        } else {
            inner.complete(apiKey, request)
        }
        meter.record(request.modelId, c.usage ?: Usage(TokenEstimate.request(request), TokenEstimate.text(c.text)))
        return c
    }

    override suspend fun checkKey(apiKey: String, modelId: String): KeyCheck = inner.checkKey(apiKey, modelId)

    override suspend fun listModels(apiKey: String): List<String> = inner.listModels(apiKey)
}

package app.nastavnik.ai

/** Настройки, найденные во вставленном тексте. */
data class ProxyConfig(
    val provider: AiProvider,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val authMode: AuthMode? = null,
)

/**
 * Разбирает то, что обычно копируют из Claudex, CLIProxyAPI и похожих прокси:
 * строки `ANTHROPIC_BASE_URL=…`, `export OPENAI_API_KEY="…"`, JSON `"ANTHROPIC_AUTH_TOKEN": "…"`,
 * а также просто адрес и ключ на отдельных строках.
 */
object ProxyConfigParser {
    private val PAIR = Regex("""^\s*(?:export\s+|set\s+|\${'$'}env:)?["']?([A-Za-z_][A-Za-z0-9_]*)["']?\s*[:=]\s*["']?([^"'\s,]+)["']?\s*,?\s*$""")
    private val URL = Regex("""https?://[^\s"',]+""")

    fun parse(text: String): ProxyConfig? {
        val vars = LinkedHashMap<String, String>()
        val loose = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim().trimEnd(';')
            if (line.isEmpty() || line.startsWith("#") || line == "{" || line == "}") continue
            // «http://…» тоже похоже на «имя:значение» — адрес в начале строки не считаем парой.
            val m = if (URL.find(line)?.range?.first == 0) null else PAIR.matchEntire(line)
            if (m != null) {
                vars[m.groupValues[1].uppercase()] = m.groupValues[2].trim()
            } else {
                loose += line
            }
        }
        fun v(vararg names: String) = names.firstNotNullOfOrNull { vars[it]?.takeIf(String::isNotBlank) }

        val anthropicBase = v("ANTHROPIC_BASE_URL")
        val anthropicToken = v("ANTHROPIC_AUTH_TOKEN")
        val anthropicKey = v("ANTHROPIC_API_KEY")
        val anthropicModel = v("ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL")
        val openaiBase = v("OPENAI_BASE_URL", "OPENAI_API_BASE", "OPENAI_API_BASE_URL", "BASE_URL")
        val openaiKey = v("OPENAI_API_KEY", "API_KEY")
        val openaiModel = v("OPENAI_MODEL", "MODEL")
        val geminiKey = v("GEMINI_API_KEY", "GOOGLE_API_KEY")

        when {
            anthropicBase != null || anthropicToken != null -> return ProxyConfig(
                provider = if (anthropicBase == null || "api.anthropic.com" in anthropicBase) AiProvider.ANTHROPIC else AiProvider.ANTHROPIC_COMPAT,
                baseUrl = anthropicBase,
                apiKey = anthropicToken ?: anthropicKey,
                model = anthropicModel,
                authMode = if (anthropicToken != null) AuthMode.BEARER else if (anthropicKey != null) AuthMode.X_API_KEY else null,
            )
            openaiBase != null || openaiKey != null -> return ProxyConfig(
                provider = AiProvider.OPENAI_COMPAT,
                baseUrl = openaiBase,
                apiKey = openaiKey,
                model = openaiModel,
            )
            anthropicKey != null -> return ProxyConfig(
                provider = AiProvider.ANTHROPIC,
                apiKey = anthropicKey,
                model = anthropicModel,
            )
            geminiKey != null -> return ProxyConfig(provider = AiProvider.GEMINI, apiKey = geminiKey)
        }

        // Без имён переменных: ищем адрес и ключ «на глаз».
        val url = loose.firstNotNullOfOrNull { URL.find(it)?.value }
        val key = loose.firstOrNull { URL.find(it) == null && !it.contains(' ') && it.length >= 8 }
        if (url == null && key == null) return null
        val provider = when {
            url == null && key!!.startsWith("AIza") -> AiProvider.GEMINI
            url == null && key!!.startsWith("sk-ant-") -> AiProvider.ANTHROPIC
            url != null && ("/proxy/" in url || "anthropic" in url || url.trimEnd('/').endsWith("/messages")) -> AiProvider.ANTHROPIC_COMPAT
            else -> AiProvider.OPENAI_COMPAT
        }
        return ProxyConfig(provider = provider, baseUrl = url, apiKey = key)
    }
}

package app.nastavnik.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Сетевая политика приложения:
 * 1. Официальные сервисы (Anthropic, Google, OpenAI, GitHub, Яндекс Диск, ЛКС) — строго по HTTPS.
 * 2. Любые внешние хосты по HTTP — строго запрещены.
 * 3. Локальные HTTP-прокси (RFC1918 или loopback) — разрешены ТОЛЬКО при явном opt-in пользователя
 *    и совпадении с настроенным хостом прокси.
 */
class NetworkPolicy(
    private val allowInsecureLocal: () -> Boolean = { false },
    private val trustedProxyHosts: () -> Set<String> = { emptySet() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val scheme = url.scheme.lowercase()
        val host = url.host.lowercase()

        // 1. Официальные сервисы — строго HTTPS
        if (isOfficialService(host) && scheme != "https") {
            throw IOException("Официальный сервис $host требует обязательного HTTPS-соединения")
        }

        // 2. HTTP проверки
        if (scheme == "http") {
            val isLocal = isPrivateOrLoopback(host)
            if (!isLocal) {
                throw IOException("Незащищённое HTTP-соединение запрещено для внешних хостов: $host")
            }
            val allowed = allowInsecureLocal()
            val trusted = trustedProxyHosts()
            val hostAllowed = trusted.isEmpty() || host in trusted
            if (!allowed || !hostAllowed) {
                throw IOException("Подключение к локальному прокси по HTTP ($host) запрещено политикой безопасности. Включите разрешение в настройках.")
            }
        }

        return chain.proceed(request)
    }

    companion object {
        val OFFICIAL_DOMAINS = setOf(
            "api.anthropic.com",
            "generativelanguage.googleapis.com",
            "api.openai.com",
            "openrouter.ai",
            "api.github.com",
            "cloud-api.yandex.net",
            "lks.siriusuniversity.ru",
        )

        fun isOfficialService(host: String): Boolean {
            val h = host.lowercase().trim().removePrefix("[").removeSuffix("]")
            return OFFICIAL_DOMAINS.any { h == it || h.endsWith(".$it") }
        }

        fun isPrivateOrLoopback(host: String): Boolean {
            val h = host.lowercase().trim().removePrefix("[").removeSuffix("]")
            if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
            if (h.endsWith(".local") || h.endsWith(".internal")) return true
            if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true

            // IPv4: 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            val parts = h.split('.')
            if (parts.size == 4) {
                val bytes = parts.mapNotNull { it.toIntOrNull() }
                if (bytes.size == 4 && bytes.all { it in 0..255 }) {
                    val b0 = bytes[0]
                    val b1 = bytes[1]
                    if (b0 == 127) return true
                    if (b0 == 10) return true
                    if (b0 == 172 && b1 in 16..31) return true
                    if (b0 == 192 && b1 == 168) return true
                }
            }
            return false
        }
    }
}

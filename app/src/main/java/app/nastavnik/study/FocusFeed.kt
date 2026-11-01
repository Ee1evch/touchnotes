package app.nastavnik.study

import java.net.URI
import java.util.TimeZone

/**
 * «Лента»: настоящий TikTok во встроенном окне, но по правилам учёбы.
 * - «Сообщения» открыты всегда — чтобы огоньки с друзьями не сгорели;
 * - «Учёба» — только поиск по темам, где ты ошибаешься, и видео из этого поиска;
 * - «Награда» — обычная лента, и только когда сегодня всё получается, на ограниченное время.
 * Главная лента, «Интересное», эфиры и подписки закрыты, пока награда не открыта.
 */
object FocusFeed {
    enum class Mode { STUDY, MESSAGES, REWARD }

    /** Сколько минут обычной ленты в день, когда всё сделано. */
    const val REWARD_MINUTES = 15

    data class Gate(
        val unlocked: Boolean,
        /** Что ещё сделать, чтобы открыть награду (пусто — открыта). */
        val todo: List<String>,
        val minutesLeft: Int,
    )

    /**
     * Награда открыта, если сегодня: ты занимался, карточек на повторение не осталось и карточки
     * сегодня шли хорошо (вспомнил не меньше 80 %, когда повторил хотя бы пять).
     */
    fun gate(events: List<Event>, dueCards: Int, now: Long, usedTodayMs: Long, zone: TimeZone = TimeZone.getDefault()): Gate {
        val today = Streaks.day(now, zone)
        val todays = events.filter { Streaks.day(it.at, zone) == today }
        val cards = todays.filter { it.kind == Progress.KIND_CARD }
        val accuracy = if (cards.size >= 5) cards.count { it.errors == 0 }.toDouble() / cards.size else 1.0
        val todo = buildList {
            if (todays.isEmpty()) add("позанимайся сегодня: повтори карточки или реши задачу")
            if (dueCards > 0) add("повтори карточки: осталось $dueCards")
            if (accuracy < 0.8) add("сегодня вспомнил ${(accuracy * 100).toInt()} % карточек — нужно 80 %")
        }
        val left = (REWARD_MINUTES - (usedTodayMs / 60_000L).toInt()).coerceAtLeast(0)
        return Gate(todo.isEmpty() && left > 0, todo, left)
    }

    /** Поисковые запросы для «Учёбы»: сначала темы с ошибками, потом предметы из расписания и карточек. */
    fun queries(events: List<Event>, subjects: List<String>, now: Long, limit: Int = 8): List<String> {
        val since = now - 30 * Streaks.DAY_MS
        val weak = events.asSequence()
            .filter { it.at >= since && it.errors > 0 && it.topic.isNotBlank() }
            .groupBy { it.topic.trim() }
            .mapValues { (_, l) -> l.sumOf { it.errors } }
            .entries.sortedByDescending { it.value }
            .map { it.key }
        return (weak + subjects.map { it.trim() })
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(limit)
            .map { topic -> if (topic.split(' ').size <= 4) "$topic объяснение" else topic }
    }

    fun searchUrl(query: String): String = "https://www.tiktok.com/search/video?q=" + java.net.URLEncoder.encode(query, "UTF-8")

    const val MESSAGES_URL = "https://www.tiktok.com/messages"
    const val FEED_URL = "https://www.tiktok.com/foryou"

    /**
     * Можно ли открыть адрес в этом режиме. Вход и сообщения — всегда; в «Учёбе» — поиск, видео и профили
     * (на них попадаешь из поиска); в «Награде» с оставшимся временем — всё на tiktok.com.
     */
    fun allowed(url: String, mode: Mode, rewardActive: Boolean): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (u.scheme == "about" || url.startsWith("about:")) return true
        val host = u.host?.lowercase() ?: return false
        if (!(host == "tiktok.com" || host.endsWith(".tiktok.com"))) return false
        val path = (u.path ?: "/").lowercase().trimEnd('/')
        if (isAccount(path)) return true
        if (path.startsWith("/messages")) return true
        if (mode == Mode.REWARD && rewardActive) return true
        if (mode == Mode.MESSAGES) return false
        return path.startsWith("/search") || Regex("""^/@[^/]+(/video/\d+|/photo/\d+)?$""").matches(path)
    }

    private fun isAccount(path: String) = listOf("/login", "/signup", "/passport", "/auth", "/logout", "/setting").any { path.startsWith(it) }
}

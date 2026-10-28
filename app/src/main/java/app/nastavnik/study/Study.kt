package app.nastavnik.study

import java.util.Calendar
import java.util.TimeZone

/**
 * Интервальное повторение по Лейтнеру: вспомнил — карточка переходит в следующую коробку
 * и вернётся позже, забыл — снова в первую и покажется ещё раз через несколько минут.
 */
object Leitner {
    /** Через сколько дней карточка из коробки вернётся на повторение. Коробка 0 — новая или забытая. */
    val INTERVAL_DAYS = intArrayOf(0, 1, 3, 7, 16, 35)
    const val MAX_BOX = 5

    /** С этой коробки карточка считается выученной. */
    const val LEARNED_BOX = 3

    /** Забытая карточка вернётся в ту же тренировку. */
    const val RELEARN_MS = 10 * 60 * 1000L

    /** Повторение «на завтра» открывается с утра, а не ровно через 24 часа. */
    private const val MORNING_HOUR = 4

    data class Next(val box: Int, val dueAt: Long)

    fun review(box: Int, remembered: Boolean, now: Long, zone: TimeZone = TimeZone.getDefault()): Next {
        if (!remembered) return Next(0, now + RELEARN_MS)
        val next = (box + 1).coerceIn(1, MAX_BOX)
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, INTERVAL_DAYS[next])
        }
        return Next(next, cal.timeInMillis)
    }
}

/** Дни, когда студент занимался: серия подряд, лучшая серия, активность по дням. */
object Streaks {
    /** Номер дня в часовом поясе студента (дни подряд считаются по его календарю). */
    fun day(time: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val offset = zone.getOffset(time)
        return Math.floorDiv(time + offset, DAY_MS)
    }

    /** Сколько дней подряд до сегодня. Сегодня ещё не занимался — серия не сгорает, считаем до вчера. */
    fun current(days: Set<Long>, today: Long): Int {
        var d = if (today in days) today else today - 1
        var n = 0
        while (d in days) {
            n++
            d--
        }
        return n
    }

    fun best(days: Set<Long>): Int {
        var best = 0
        for (d in days) {
            if (d - 1 in days) continue
            var n = 0
            while (d + n in days) n++
            best = maxOf(best, n)
        }
        return best
    }

    /** Сколько было занятий в каждый из последних [n] дней, от старого к сегодняшнему. */
    fun lastDays(counts: Map<Long, Int>, today: Long, n: Int = 14): List<Int> =
        (n - 1 downTo 0).map { counts[today - it] ?: 0 }

    const val DAY_MS = 24 * 60 * 60 * 1000L
}

/** Одно учебное событие: ответ наставника, тихая проверка, чистовик или повторённая карточка. */
data class Event(val kind: String, val topic: String, val errors: Int, val at: Long)

/** Сводка для экрана «Прогресс». */
data class ProgressSummary(
    val streak: Int,
    val bestStreak: Int,
    val days: List<Int>,
    val questions: Int,
    val errorsFound: Int,
    val cleanNotes: Int,
    val cardsReviewed: Int,
    /** Темы, где чаще всего находились ошибки (и забывались карточки) за последние 30 дней. */
    val weakTopics: List<Pair<String, Int>>,
)

object Progress {
    const val KIND_ASK = "ask"
    const val KIND_WATCH = "watch"
    const val KIND_CLEAN = "clean"
    const val KIND_CARD = "card"

    fun summarize(events: List<Event>, now: Long, zone: TimeZone = TimeZone.getDefault()): ProgressSummary {
        val today = Streaks.day(now, zone)
        val perDay = events.groupingBy { Streaks.day(it.at, zone) }.eachCount()
        val since = now - 30 * Streaks.DAY_MS
        val weak = events.asSequence()
            .filter { it.at >= since && it.errors > 0 && it.topic.isNotBlank() }
            .groupBy { it.topic }
            .mapValues { (_, list) -> list.sumOf { it.errors } }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3)
            .map { it.key to it.value }
        return ProgressSummary(
            streak = Streaks.current(perDay.keys, today),
            bestStreak = Streaks.best(perDay.keys),
            days = Streaks.lastDays(perDay, today),
            questions = events.count { it.kind == KIND_ASK },
            errorsFound = events.filter { it.kind == KIND_ASK || it.kind == KIND_WATCH }.sumOf { it.errors },
            cleanNotes = events.count { it.kind == KIND_CLEAN },
            cardsReviewed = events.count { it.kind == KIND_CARD },
            weakTopics = weak,
        )
    }
}

/** «1 день», «3 дня», «5 дней». */
fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}

/** Когда напомнить о повторении: сегодня в [hour]:00, а если это время уже прошло — завтра. */
fun nextReminderAt(now: Long, hour: Int, zone: TimeZone = TimeZone.getDefault()): Long {
    val cal = Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}

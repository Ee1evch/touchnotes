package app.nastavnik.study

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Когда напомнить в следующий раз для повторяющегося напоминания. Чистая функция — проверяется тестами. */
object Repeats {
    const val NONE = "none"
    const val DAILY = "daily"
    const val WEEKDAYS = "weekdays"
    const val WEEKLY = "weekly"

    val labels = linkedMapOf(NONE to "Один раз", DAILY to "Каждый день", WEEKDAYS to "По будням", WEEKLY to "Раз в неделю")

    /** Ближайший раз строго после [after] (или null для однократного, уже прошедшего). */
    fun next(at: Long, repeat: String, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (at > after) return at
        var t = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
        val limit = LocalDateTime.ofInstant(Instant.ofEpochMilli(after), zone)
        fun step() {
            t = when (repeat) {
                DAILY -> t.plusDays(1)
                WEEKLY -> t.plusWeeks(1)
                WEEKDAYS -> {
                    var d = t.plusDays(1)
                    while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                    d
                }
                else -> return
            }
        }
        if (repeat == NONE) return null
        // Прыжок сразу к нужному дню, чтобы не крутить цикл годами.
        val days = java.time.Duration.between(t, limit).toDays()
        if (days > 7) t = when (repeat) {
            WEEKLY -> t.plusWeeks(days / 7)
            else -> t.plusDays(days - 1)
        }
        while (!t.isAfter(limit)) step()
        if (repeat == WEEKDAYS) while (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) t = t.plusDays(1)
        return t.atZone(zone).toInstant().toEpochMilli()
    }
}


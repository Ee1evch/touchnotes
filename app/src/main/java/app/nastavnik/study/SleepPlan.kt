package app.nastavnik.study

import java.time.Duration
import java.time.LocalDateTime

/**
 * Расчёт будильника к первой паре и циклов сна.
 *
 * Сон идёт циклами примерно по 90 минут; просыпаться легче всего в конце цикла, а не посреди глубокого сна.
 * Засыпание занимает ~15 минут. Поэтому:
 *  - крайний подъём = начало первой пары − сборы − дорога (позже вставать нельзя);
 *  - время «лечь спать» = подъём − N циклов − засыпание (рекомендуем 5 циклов = 7,5 ч, минимум 4);
 *  - если человек отметил «Ложусь», будильник сдвигается на конец ближайшего цикла — но только раньше
 *    крайнего подъёма и не больше чем на окно (по умолчанию 30 минут).
 */
object SleepPlan {
    const val CYCLE_MIN = 90L
    const val FALL_ASLEEP_MIN = 15L
    const val WINDOW_MIN = 30L

    /** Крайнее время подъёма: к началу пары нужно успеть собраться и доехать. */
    fun latestWake(firstLesson: LocalDateTime, readyMin: Int, travelMin: Int): LocalDateTime =
        firstLesson.minusMinutes((readyMin + travelMin).toLong())

    /** Когда лечь, чтобы проснуться в конце цикла: для 6, 5 и 4 циклов. */
    fun bedtimes(wake: LocalDateTime): List<Pair<Int, LocalDateTime>> =
        listOf(6, 5, 4).map { n -> n to wake.minusMinutes(n * CYCLE_MIN + FALL_ASLEEP_MIN) }

    /** Рекомендуемое время лечь: 5 циклов (7,5 ч сна). */
    fun bedtime(wake: LocalDateTime): LocalDateTime = wake.minusMinutes(5 * CYCLE_MIN + FALL_ASLEEP_MIN)

    /**
     * Подъём в конце цикла, если известно, когда человек лёг. Ищем последний конец цикла не позже
     * крайнего подъёма; если он в пределах окна — будим тогда, иначе — в крайнее время.
     */
    fun smartWake(latest: LocalDateTime, wentToBed: LocalDateTime?, windowMin: Long = WINDOW_MIN): LocalDateTime {
        if (wentToBed == null) return latest
        val asleep = wentToBed.plusMinutes(FALL_ASLEEP_MIN)
        if (!asleep.isBefore(latest)) return latest
        val slept = Duration.between(asleep, latest).toMinutes()
        val cycles = slept / CYCLE_MIN
        if (cycles < 1) return latest
        val end = asleep.plusMinutes(cycles * CYCLE_MIN)
        return if (Duration.between(end, latest).toMinutes() <= windowMin) end else latest
    }

    /** Сколько полных циклов получится. */
    fun cycles(wentToBed: LocalDateTime, wake: LocalDateTime): Int =
        (Duration.between(wentToBed.plusMinutes(FALL_ASLEEP_MIN), wake).toMinutes() / CYCLE_MIN).toInt().coerceAtLeast(0)

    /** «7 ч 30 мин». */
    fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    /**
     * Пример для выключения: две операции, ответ двузначный-трёхзначный. Проснувшийся решит за 10 секунд,
     * спящий — нет. [seed] — чтобы пример не менялся при повороте экрана.
     */
    fun task(seed: Long): Pair<String, Int> {
        val r = java.util.Random(seed)
        val a = 12 + r.nextInt(78)
        val b = 11 + r.nextInt(78)
        val c = 2 + r.nextInt(8)
        return when (r.nextInt(3)) {
            0 -> "$a + $b" to a + b
            1 -> "$a + $b − $c" to a + b - c
            else -> "$a × $c" to a * c
        }
    }
}

package app.nastavnik.study

import java.time.LocalDate

/** Логика домашки без Android (тесты `HomeworkRulesTest`). */
object HomeworkRules {
    /** Состояние задачи ДЗ. Засчитаны — [DONE] и [DISPUTED] (спорная: ИИ дважды не принял, ты уверен). */
    enum class Status { TODO, WRONG, INCOMPLETE, DONE, DISPUTED }

    /** Сколько «спорных» задач можно засчитать в одном ДЗ — чтобы не обходить проверку. */
    const val MAX_DISPUTED = 1

    /** После скольких непринятых проверок можно отметить задачу спорной. */
    const val DISPUTE_AFTER = 2

    private val range = Regex("""^(\d+)\s*[-–—]\s*(\d+)$""")

    /**
     * «245, 247–251, 260а; 1.12» → «245», «247», «248», «249», «250», «251», «260а», «1.12». Диапазон — только из
     * простых чисел и не длиннее 60 задач; повторы убираются, порядок сохраняется.
     */
    fun parseNumbers(input: String): List<String> {
        val out = LinkedHashSet<String>()
        input.replace("№", " ").split(',', ';', '\n', ' ').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.forEach { part ->
            val m = range.find(part)
            if (m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                if (b >= a && b - a < 60) (a..b).forEach { out += it.toString() } else out += part
            } else {
                out += part.lowercase()
            }
        }
        // «247 – 251» с пробелами разбилось на «247», «–», «251» — склеиваем такие тройки.
        return mergeSpacedRanges(out.toList())
    }

    private fun mergeSpacedRanges(items: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < items.size) {
            val a = items[i].toIntOrNull()
            val dash = items.getOrNull(i + 1)
            val b = items.getOrNull(i + 2)?.toIntOrNull()
            if (a != null && dash != null && dash.all { it in "-–—" } && b != null && b >= a && b - a < 60) {
                (a..b).forEach { out += it.toString() }
                i += 3
            } else {
                if (items[i].any { it.isLetterOrDigit() }) out += items[i]
                i++
            }
        }
        return out.toList()
    }

    /** Порядок номеров в задачнике: «1.12» < «1.13» < «2.1»; «260» < «260а» < «260б» < «261». */
    fun compare(a: String, b: String): Int {
        val ka = key(a); val kb = key(b)
        for (i in 0 until maxOf(ka.first.size, kb.first.size)) {
            val x = ka.first.getOrElse(i) { -1 }; val y = kb.first.getOrElse(i) { -1 }
            if (x != y) return x.compareTo(y)
        }
        return ka.second.compareTo(kb.second)
    }

    private fun key(n: String): Pair<List<Int>, String> {
        val t = n.trim().lowercase()
        val digits = t.takeWhile { it.isDigit() || it == '.' }
        return digits.split('.').mapNotNull { it.toIntOrNull() } to t.drop(digits.length)
    }

    /** Номера задач на странице учебника: первый и последний (по порядку задачника); null — на странице задач нет. */
    data class PageRange(val first: String, val last: String)

    /**
     * Какую страницу учебника посмотреть следующей, чтобы найти задачу [target]. Номера в задачнике идут по порядку,
     * поэтому — поиск делением пополам по уже просмотренным страницам ([known]: страница → номера на ней).
     * Возвращает страницу с задачей, если она уже известна, иначе следующую непросмотренную; null — задачи нет.
     */
    fun nextProbe(known: Map<Int, PageRange?>, target: String, pageCount: Int): Probe {
        known.entries.firstOrNull { (_, r) -> r != null && compare(r.first, target) <= 0 && compare(target, r.last) <= 0 }
            ?.let { return Probe.Found(it.key) }
        // Задача между последним номером одной страницы и первым следующей — на странице, где она продолжается.
        var lo = 0
        var hi = pageCount - 1
        for ((p, r) in known) {
            if (r == null) continue
            if (compare(r.last, target) < 0) lo = maxOf(lo, p)
            if (compare(target, r.first) < 0) hi = minOf(hi, p)
        }
        // Границы — уже просмотренные страницы: ищем строго между ними (сама граница уже не подходит).
        val from = if (known[lo] != null && compare(known[lo]!!.last, target) < 0) lo + 1 else lo
        val to = if (known[hi] != null && compare(target, known[hi]!!.first) < 0) hi - 1 else hi
        if (from > to) {
            // Ни на одной странице номера нет: условие могло начаться внизу страницы [lo] под другим номером — не нашли.
            return Probe.NotFound
        }
        val mid = (from + to) / 2
        // Страницы без задач (теория) уже просмотрены — берём ближайшую непросмотренную к середине.
        val candidates = (from..to).filter { it !in known }
        val page = candidates.minByOrNull { kotlin.math.abs(it - mid) } ?: return Probe.NotFound
        return Probe.Look(page)
    }

    sealed interface Probe {
        data class Found(val page: Int) : Probe
        data class Look(val page: Int) : Probe
        data object NotFound : Probe
    }

    /** ДЗ выполнено на 100%: все задачи засчитаны и спорных не больше [MAX_DISPUTED]. */
    fun complete(statuses: List<Status>): Boolean =
        statuses.isNotEmpty() && statuses.all { it == Status.DONE || it == Status.DISPUTED } && statuses.count { it == Status.DISPUTED } <= MAX_DISPUTED

    /** Можно ли отметить задачу спорной: проверка не приняла её [DISPUTE_AFTER] раза, и спорных в ДЗ ещё нет. */
    fun canDispute(status: Status, failedChecks: Int, statuses: List<Status>): Boolean =
        (status == Status.WRONG || status == Status.INCOMPLETE) && failedChecks >= DISPUTE_AFTER &&
            statuses.count { it == Status.DISPUTED } < MAX_DISPUTED

    /** Сколько задач засчитано. */
    fun done(statuses: List<Status>): Int = statuses.count { it == Status.DONE || it == Status.DISPUTED }

    /**
     * Срок по умолчанию — ближайшая пара этого предмета после сегодняшнего дня ([lessons]: дата и предмет);
     * нет такой — через неделю.
     */
    fun defaultDue(subject: String, today: LocalDate, lessons: List<Pair<LocalDate, String>>, same: (String, String) -> Boolean): LocalDate =
        lessons.filter { (d, s) -> d.isAfter(today) && same(s, subject) }.minByOrNull { it.first }?.first ?: today.plusWeeks(1)

    /** ДЗ, которые надо закрыть в вечер [session] (сдать на следующий день или раньше и ещё не на 100%). */
    fun <T> openFor(session: LocalDate, items: List<T>, due: (T) -> LocalDate, statuses: (T) -> List<Status>): List<T> =
        items.filter { !due(it).isAfter(session.plusDays(1)) && !complete(statuses(it)) }
}

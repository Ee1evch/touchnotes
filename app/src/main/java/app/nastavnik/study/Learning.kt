package app.nastavnik.study

/**
 * Приёмы, которые по исследованиям памяти дают больше всего к результату, — без Android, чтобы проверять тестами.
 *
 *  - Калибровка: перед ответом отмечаешь уверенность. Темы, где был уверен, но ошибся («иллюзия знания»), — опасные,
 *    их карточки идут первыми.
 *  - Перемешивание: карточки разных тем чередуются, а не идут блоком — так тренируется выбор метода.
 *  - Задачи возвращаются: нерешённая задача варианта становится карточкой-задачей и приходит через 1, 3, 7 дней.
 *  - Карточки усложняются: выученная карточка порождает вопрос на применение, тот — вопрос «объясни почему».
 *  - Контрольный срез раз в две недели: одинаковый по размеру тест по всем темам, график результата.
 */
object Learning {
    const val KIND_CALIB = "calib"
    const val KIND_TASK = "task"
    const val KIND_CHECKPOINT = "checkpoint"

    const val PROBLEM_PREFIX = "Задача. "
    const val APPLY_PREFIX = "Примени: "
    const val EXPLAIN_PREFIX = "Объясни: "

    const val CHECKPOINT_EVERY_DAYS = 14
    const val CHECKPOINT_SIZE = 10
    private const val DAY_MS = 24 * 3_600_000L

    enum class Confidence { SURE, UNSURE, NO }

    /** Ошибка «был уверен, но не вспомнил» — для записи калибровки (errors = 1). */
    fun overconfident(c: Confidence, remembered: Boolean): Boolean = c == Confidence.SURE && !remembered

    /** Опасные темы за 30 дней: где был уверен, но ошибся. Чаще — выше. */
    fun dangerous(events: List<Event>, now: Long, limit: Int = 5): List<Pair<String, Int>> =
        events.asSequence()
            .filter { it.kind == KIND_CALIB && it.errors > 0 && it.at >= now - 30 * DAY_MS && it.topic.isNotBlank() }
            .groupingBy { it.topic }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key to it.value }

    /**
     * Порядок повторения: сначала карточки опасных тем, остальные — вперемешку по темам
     * (по одной из каждой темы по кругу), внутри темы порядок сохраняется.
     */
    fun <T> order(items: List<T>, topic: (T) -> String, danger: Set<String>): List<T> {
        val (hot, rest) = items.partition { topic(it) in danger }
        return interleave(hot, topic) + interleave(rest, topic)
    }

    fun <T> interleave(items: List<T>, topic: (T) -> String): List<T> {
        val groups = items.groupBy(topic).values.map { it.toMutableList() }
        val out = ArrayList<T>(items.size)
        while (out.size < items.size) {
            for (g in groups) if (g.isNotEmpty()) out += g.removeAt(0)
        }
        return out
    }

    fun isProblem(front: String) = front.startsWith(PROBLEM_PREFIX)

    /** Уровень карточки: 1 — вспомнить, 2 — применить, 3 — объяснить. Задачи не усложняем. */
    fun level(front: String): Int = when {
        front.startsWith(EXPLAIN_PREFIX) -> 3
        front.startsWith(APPLY_PREFIX) -> 2
        else -> 1
    }

    /** Карточка только что стала выученной — пора сделать из неё карточку следующего уровня. */
    fun shouldUpgrade(front: String, oldBox: Int, newBox: Int): Boolean =
        !isProblem(front) && level(front) < 3 && oldBox < Leitner.LEARNED_BOX && newBox >= Leitner.LEARNED_BOX

    /** Пора ли контрольный срез: первый — сразу, дальше раз в 2 недели. */
    fun checkpointDue(events: List<Event>, now: Long): Boolean {
        val last = events.filter { it.kind == KIND_CHECKPOINT }.maxOfOrNull { it.at } ?: return true
        return now - last >= CHECKPOINT_EVERY_DAYS * DAY_MS
    }

    /** Результаты срезов, старые первыми: процент верных. Срез пишется как topic = «верно/всего». */
    fun checkpoints(events: List<Event>): List<Pair<Long, Int>> =
        events.filter { it.kind == KIND_CHECKPOINT }.sortedBy { it.at }.mapNotNull { e ->
            val (ok, all) = e.topic.split('/').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            if (all <= 0) null else e.at to (ok * 100 / all)
        }

    /**
     * Короткая сводка для плана на день — около страницы текста, чтобы запрос стоил копейки.
     * [screen] — минуты в приложениях за сутки (только самые долгие).
     */
    fun summary(
        dangerous: List<String>,
        weak: List<String>,
        due: Int,
        problems: Int,
        noHint: Int?,
        checkpoints: List<Int>,
        checkpointDue: Boolean,
        tomorrow: List<String>,
        screen: List<Pair<String, Int>>,
    ): String = buildString {
        appendLine("Карточек к повторению сейчас: $due.")
        if (dangerous.isNotEmpty()) appendLine("Опасные темы (уверен, но ошибается): ${dangerous.take(5).joinToString("; ")}.")
        if (weak.isNotEmpty()) appendLine("Слабые темы (много ошибок за 30 дней): ${weak.take(5).joinToString("; ")}.")
        if (problems > 0) appendLine("Нерешённых задач в очереди: $problems.")
        noHint?.let { appendLine("Задач без подсказок за 2 недели: $it%.") }
        if (checkpoints.isNotEmpty()) appendLine("Контрольные срезы, % верных (старые → новые): ${checkpoints.takeLast(4).joinToString(" → ")}.")
        if (checkpointDue) appendLine("Пора пройти контрольный срез.")
        if (tomorrow.isNotEmpty()) appendLine("Пары завтра: ${tomorrow.take(6).joinToString("; ")}.")
        if (screen.isNotEmpty()) appendLine("Время в телефоне за сутки: ${screen.take(5).joinToString("; ") { (app, min) -> "$app — $min мин" }}.")
    }.trim()

    /** Доля задач, решённых без подсказок, за 14 дней (null — задач не было). Задача пишется с errors = подсказок. */
    fun noHintShare(events: List<Event>, now: Long): Int? {
        val tasks = events.filter { it.kind == KIND_TASK && it.at >= now - 14 * DAY_MS }
        if (tasks.isEmpty()) return null
        return tasks.count { it.errors == 0 } * 100 / tasks.size
    }
}

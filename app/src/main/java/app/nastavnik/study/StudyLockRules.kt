package app.nastavnik.study

import app.nastavnik.schedule.Lesson
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Правила учебного замка — без Android, чтобы проверять тестами.
 *
 * Каждый день через 40 минут после последней пары (без пар — с выбранного времени) приложения закрыты (кроме выбранных один раз), пока не сделана
 * вечерняя учёба за этот день:
 *  1) карточки на сегодня — до нуля;
 *  2) «что было сегодня» — по каждой паре дня пара предложений своими словами (становится карточкой);
 *  3) «что будет завтра» — по каждой паре завтра: прошлый конспект и карточки предмета.
 * «День» длится до 5 утра: в час ночи это ещё вечер вчерашнего дня. Не сделал за вечер — замок остаётся
 * и утром, пока не доделаешь (но копится не больше одного пропущенного вечера).
 */
object StudyLockRules {
    const val DAY_STARTS_HOUR = 5
    const val SUMMARY_MIN_CHARS = 60
    /** Сколько смотреть подготовку к паре без карточек и конспектов, прежде чем отметить. */
    const val PREP_MIN_SECONDS = 90
    const val EMERGENCY_MINUTES = 15
    const val EMERGENCY_EVERY_DAYS = 7
    /** Через сколько минут после последней пары закрывать. */
    const val AFTER_LAST_LESSON_MIN = 40L

    /** К какому учебному дню относится момент: до 5 утра — ещё вчера. */
    fun sessionDate(now: LocalDateTime): LocalDate =
        if (now.hour < DAY_STARTS_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()

    fun lockStart(date: LocalDate, startMinutes: Int): LocalDateTime = date.atStartOfDay().plusMinutes(startMinutes.toLong())

    /** Начало замка в этот день: через 40 минут после конца последней пары; без пар — в [fallbackMinutes]. */
    fun lockStart(date: LocalDate, lessons: List<Lesson>, fallbackMinutes: Int): LocalDateTime {
        val last = lessons.filter { "внеучеб" !in it.type.lowercase() }
            .mapNotNull { it.endsAt() ?: it.startsAt()?.plusMinutes(90) }
            .maxOrNull()
        return last?.plusMinutes(AFTER_LAST_LESSON_MIN) ?: lockStart(date, fallbackMinutes)
    }

    /**
     * Какие вечера должны быть сделаны прямо сейчас (старший первым). Пусто — замка нет.
     * [startOf] — когда начинается замок в этот день; [since] — день, с которого замок включён; [done] — сделанные дни.
     */
    fun pending(now: LocalDateTime, startOf: (LocalDate) -> LocalDateTime, since: LocalDate, done: Set<String>): List<LocalDate> {
        val today = sessionDate(now)
        val out = mutableListOf<LocalDate>()
        val yesterday = today.minusDays(1)
        if (!yesterday.isBefore(since) && yesterday.toString() !in done) out += yesterday
        if (!today.isBefore(since) && !now.isBefore(startOf(today)) && today.toString() !in done) out += today
        return out
    }

    fun pending(now: LocalDateTime, startMinutes: Int, since: LocalDate, done: Set<String>): List<LocalDate> =
        pending(now, { lockStart(it, startMinutes) }, since, done)

    private val sentenceEnd = Regex("""(?<=[.!?;])\s+|\n+""")
    private val wordRe = Regex("""[A-Za-zА-Яа-яЁё][A-Za-zА-Яа-яЁё\-]{4,}""")
    private val stop = setOf(
        "который", "которая", "которые", "которых", "потому", "поэтому", "также", "можно", "нужно", "когда", "после",
        "перед", "через", "между", "этого", "этому", "этот", "такое", "такой", "очень", "сегодня", "разбирали",
        "рассмотрели", "говорили", "изучали", "смотрели", "решали", "задачи", "задач", "примеры", "пример",
    )

    /**
     * Карточки «заполни пропуск» из конспекта — без нейросети: в каждой фразе прячем самое длинное
     * значимое слово. Длинные фразы делим по запятым. Не больше [max].
     */
    fun cloze(text: String, max: Int = 6): List<Pair<String, String>> {
        val parts = text.split(sentenceEnd).flatMap { s ->
            val t = s.trim()
            if (t.length > 140) t.split(',').map { it.trim() } else listOf(t)
        }.filter { it.length >= 25 }
        val out = mutableListOf<Pair<String, String>>()
        val used = mutableSetOf<String>()
        for (p in parts) {
            val w = wordRe.findAll(p).map { it.value }
                .filter { it.lowercase() !in stop && it.lowercase() !in used }
                .maxByOrNull { it.length } ?: continue
            used += w.lowercase()
            out += p.replaceFirst(w, "_____") to w
            if (out.size >= max) break
        }
        return out
    }

    /** Пара, по которой нужен конспект: не физкультура и не внеучебное. */
    fun needsSummary(l: Lesson): Boolean {
        val d = l.discipline.lowercase()
        val t = l.type.lowercase()
        return d.isNotBlank() && "физическ" !in d && "физкульт" !in d && "внеучеб" !in t && "внеучеб" !in d
    }

    /** Предметы дня по порядку пар, без повторов. */
    fun disciplines(lessons: List<Lesson>): List<String> =
        lessons.sortedBy { it.start }.filter(::needsSummary).map { it.discipline.trim() }.distinct()

    private val noise = setOf("основы", "введение", "теория", "практикум", "курс", "дисциплина")

    /** Ключевые слова предмета: «Математический анализ» → [математ, анализ]. */
    fun keys(subject: String): List<String> =
        subject.lowercase().replace('ё', 'е').split(Regex("[^a-zа-я0-9]+"))
            .filter { it.length >= 4 && it !in noise }
            .map { it.take(7) }

    /** Подходит ли карточка (её предмет или тема) к паре из расписания. */
    fun sameSubject(cardSubject: String, discipline: String): Boolean {
        val a = keys(cardSubject)
        val b = keys(discipline)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.any { it in b }
    }

    /** Разрешено ли приложение во время замка. */
    fun allowed(pkg: String, chosen: Set<String>, always: Set<String>): Boolean = pkg in chosen || pkg in always

    /** Можно ли взять экстренные 15 минут: раз в неделю. */
    fun emergencyAvailable(lastUsedMs: Long, nowMs: Long): Boolean =
        lastUsedMs <= 0 || nowMs - lastUsedMs >= EMERGENCY_EVERY_DAYS * 24 * 3_600_000L
}

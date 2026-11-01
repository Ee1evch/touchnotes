package app.nastavnik.study

import app.nastavnik.schedule.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StudyLockRulesTest {
    private val mon = LocalDate.of(2026, 11, 2)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m)
    private val start20 = 20 * 60

    @Test
    fun dayLastsUntilFiveAm() {
        assertEquals(mon, StudyLockRules.sessionDate(at(mon, 23, 30)))
        assertEquals(mon, StudyLockRules.sessionDate(at(mon.plusDays(1), 1, 0)))
        assertEquals(mon.plusDays(1), StudyLockRules.sessionDate(at(mon.plusDays(1), 5, 0)))
    }

    @Test
    fun locksOnlyAfterStartTime() {
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon, 19, 59), start20, mon, emptySet()))
        assertEquals(listOf(mon), StudyLockRules.pending(at(mon, 20, 0), start20, mon, emptySet()))
        // Ночью — всё ещё вечер понедельника.
        assertEquals(listOf(mon), StudyLockRules.pending(at(mon.plusDays(1), 2, 0), start20, mon, emptySet()))
        // Сделал — свободен.
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon, 21, 0), start20, mon, setOf(mon.toString())))
    }

    @Test
    fun missedEveningKeepsLockNextDay() {
        val tue = mon.plusDays(1)
        // Вторник утром: понедельник не сделан — заблокировано.
        assertEquals(listOf(mon), StudyLockRules.pending(at(tue, 9, 0), start20, mon, emptySet()))
        // Вторник вечером — и понедельник, и вторник.
        assertEquals(listOf(mon, tue), StudyLockRules.pending(at(tue, 20, 30), start20, mon, emptySet()))
        // Копится не больше одного пропущенного вечера.
        assertEquals(listOf(tue), StudyLockRules.pending(at(tue.plusDays(1), 9, 0), start20, mon, emptySet()))
    }

    @Test
    fun notBeforeEnabled() {
        // Включил во вторник — за понедельник не спрашиваем.
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon.plusDays(1), 9, 0), start20, mon.plusDays(1), emptySet()))
    }

    private fun lesson(d: String, type: String = "Лекция", start: String = "09:00") =
        Lesson(id = d + start, date = mon.toString(), start = start, end = "10:30", discipline = d, type = type)

    @Test
    fun summariesSkipSportAndExtracurricular() {
        val day = listOf(
            lesson("Физика", start = "12:00"),
            lesson("Математический анализ", start = "09:00"),
            lesson("Физическая культура и спорт", start = "14:00"),
            lesson("Кураторский час", type = "Внеучебное", start = "16:00"),
            lesson("Математический анализ", type = "Практика", start = "10:40"),
        )
        assertEquals(listOf("Математический анализ", "Физика"), StudyLockRules.disciplines(day))
    }

    @Test
    fun matchesCardsToSubject() {
        assertTrue(StudyLockRules.sameSubject("Матанализ, пределы", "Математический анализ") || StudyLockRules.sameSubject("Математический анализ", "Математический анализ"))
        assertTrue(StudyLockRules.sameSubject("математика: анализ функций", "Математический анализ"))
        assertTrue(StudyLockRules.sameSubject("Физика", "Физика"))
        assertFalse(StudyLockRules.sameSubject("Физика", "Математический анализ"))
        assertFalse(StudyLockRules.sameSubject("", "Физика"))
    }

    @Test
    fun emergencyOncePerWeek() {
        val now = 1_000_000_000_000L
        assertTrue(StudyLockRules.emergencyAvailable(0, now))
        assertFalse(StudyLockRules.emergencyAvailable(now - 3 * 24 * 3_600_000L, now))
        assertTrue(StudyLockRules.emergencyAvailable(now - 7 * 24 * 3_600_000L, now))
    }

    @Test
    fun allowedApps() {
        assertTrue(StudyLockRules.allowed("org.telegram.messenger", setOf("org.telegram.messenger"), setOf("com.android.dialer")))
        assertTrue(StudyLockRules.allowed("com.android.dialer", emptySet(), setOf("com.android.dialer")))
        assertFalse(StudyLockRules.allowed("com.zhiliaoapp.musically", setOf("org.telegram.messenger"), setOf("com.android.dialer")))
    }

    @Test
    fun lockStartsFortyMinutesAfterLastLesson() {
        val day = listOf(
            Lesson("1", mon.toString(), "09:00", "10:30", discipline = "Физика", type = "Лекция"),
            Lesson("2", mon.toString(), "13:00", "14:30", discipline = "Математический анализ", type = "Практика"),
            Lesson("3", mon.toString(), "18:00", "19:30", discipline = "Кураторский час", type = "Внеучебное"),
        )
        assertEquals(at(mon, 15, 10), StudyLockRules.lockStart(mon, day, start20))
        // Без пар — выбранное время.
        assertEquals(at(mon, 20, 0), StudyLockRules.lockStart(mon, emptyList(), start20))
        val start = { d: LocalDate -> if (d == mon) at(mon, 15, 10) else StudyLockRules.lockStart(d, start20) }
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon, 15, 9), start, mon, emptySet()))
        assertEquals(listOf(mon), StudyLockRules.pending(at(mon, 15, 10), start, mon, emptySet()))
    }

    @Test
    fun clozeCardsFromSummary() {
        val text = "Законы Ньютона: второй закон связывает силу и ускорение. Разбирали задачи на наклонную плоскость с трением! " +
            "Сила трения скольжения пропорциональна силе нормальной реакции опоры."
        val cards = StudyLockRules.cloze(text)
        // Три фразы — по две карточки.
        assertEquals(6, cards.size)
        // Даже одна фраза даёт две карточки.
        assertEquals(2, StudyLockRules.cloze("Законы Ньютона: второй закон F = ma, разбирали задачи на наклонную плоскость и силу трения.").size)
        cards.forEach { (front, back) ->
            assertTrue(front, "_____" in front)
            assertFalse(back in front)
            assertTrue(back.length >= 5)
        }
        // Слова не повторяются, мусор («разбирали», «задачи») не прячем.
        assertEquals(cards.map { it.second.lowercase() }.distinct().size, cards.size)
        assertTrue(cards.none { it.second.lowercase() in setOf("разбирали", "задачи") })
        assertTrue(StudyLockRules.cloze("Коротко.").isEmpty())
    }

    @Test
    fun permissionPagesAreSafeButUninstallIsNot() {
        val app = "Наставник"
        val guard = "Наставник: защита и учебный замок"
        // Разрешения, которые «Наставник» просит сам, — открыть можно.
        assertFalse(StudyLockRules.dangerousSettings("Полноэкранные уведомления Наставник Разрешить полноэкранные уведомления", app, guard))
        assertFalse(StudyLockRules.dangerousSettings("Доступ к истории использования Наставник Разрешить доступ", app, guard))
        assertFalse(StudyLockRules.dangerousSettings("Разрешить приложению «Наставник» всегда работать в фоне? Разрешить Запретить", app, guard))
        // Сведения о приложении с «Удалить» и «Остановить», выключатель службы — закрываем.
        assertTrue(StudyLockRules.dangerousSettings("Сведения о приложении Наставник Открыть Удалить Остановить", app, guard))
        assertTrue(StudyLockRules.dangerousSettings("Использовать сервис «Наставник: защита и учебный замок»", app, guard))
        assertTrue(StudyLockRules.dangerousSettings("App info Наставник Uninstall Force stop", app, guard))
        // Чужое приложение с «Удалить» — не наше дело.
        assertFalse(StudyLockRules.dangerousSettings("Сведения о приложении Telegram Удалить Остановить", app, guard))
    }

    @Test
    fun recognizesPowerMenusOfDifferentPhones() {
        assertTrue(StudyLockRules.powerMenu("Выключить Перезапустить Экстренный вызов")) // Pixel
        assertTrue(StudyLockRules.powerMenu("Выключение Перезагрузка Экстренный режим")) // Samsung
        assertTrue(StudyLockRules.powerMenu("Отключить питание Перезагрузить")) // старые Android
        assertTrue(StudyLockRules.powerMenu("Power off Restart Emergency")) // английский
        assertFalse(StudyLockRules.powerMenu("Выключить звук")) // не меню питания
        assertFalse(StudyLockRules.powerMenu("Перезагрузить страницу"))
    }

    @Test
    fun onlyCoreSubjects() {
        listOf(
            "Математический анализ", "Матан", "Линейная алгебра", "Линейная алгебра и аналитическая геометрия",
            "Математическая логика", "Математическая логика и теория алгоритмов", "Физика", "Общая физика",
        ).forEach { assertTrue(it, StudyLockRules.isCore(it)) }
        listOf(
            "Физическая культура и спорт", "Иностранный язык", "История России", "Программирование",
            "Дискретная математика", "Философия", "Кураторский час",
        ).forEach { assertFalse(it, StudyLockRules.isCore(it)) }
        val day = listOf(
            lesson("Иностранный язык", start = "09:00"),
            lesson("Линейная алгебра", start = "10:40"),
            lesson("Программирование", start = "12:40"),
            lesson("Математическая логика", start = "14:20"),
        )
        assertEquals(listOf("Линейная алгебра", "Математическая логика"), StudyLockRules.disciplines(day))
    }
}

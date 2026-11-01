package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FocusFeedTest {
    private val zone = TimeZone.getTimeZone("Europe/Moscow")
    private val now = 1_800_000_000_000L
    private fun ev(kind: String, topic: String, errors: Int, ago: Long = 0) = Event(kind, topic, errors, now - ago)

    @Test fun rewardOnlyWhenEverythingIsDone() {
        val none = FocusFeed.gate(emptyList(), dueCards = 0, now = now, usedTodayMs = 0, zone = zone)
        assertFalse(none.unlocked)
        val due = FocusFeed.gate(listOf(ev(Progress.KIND_CARD, "Пределы", 0)), dueCards = 3, now = now, usedTodayMs = 0, zone = zone)
        assertFalse(due.unlocked)
        assertTrue(due.todo.single().contains("осталось 3"))
        val bad = List(5) { ev(Progress.KIND_CARD, "Пределы", if (it < 2) 1 else 0) }
        assertFalse(FocusFeed.gate(bad, 0, now, 0, zone).unlocked)
        val good = List(5) { ev(Progress.KIND_CARD, "Пределы", if (it == 0) 1 else 0) }
        val g = FocusFeed.gate(good, 0, now, 0, zone)
        assertTrue(g.unlocked)
        assertEquals(FocusFeed.REWARD_MINUTES, g.minutesLeft)
        // Время награды кончилось — закрыто.
        assertFalse(FocusFeed.gate(good, 0, now, FocusFeed.REWARD_MINUTES * 60_000L, zone).unlocked)
    }

    @Test fun queriesStartWithWeakTopics() {
        val q = FocusFeed.queries(
            listOf(ev(Progress.KIND_ASK, "Ряды Тейлора", 3), ev(Progress.KIND_WATCH, "Пределы", 1), ev(Progress.KIND_ASK, "Старое", 5, ago = 40 * Streaks.DAY_MS)),
            subjects = listOf("Физика", "пределы"), now = now,
        )
        assertEquals(listOf("Ряды Тейлора объяснение", "Пределы объяснение", "Физика объяснение"), q)
        assertTrue(FocusFeed.searchUrl("Ряды Тейлора").startsWith("https://www.tiktok.com/search/video?q=%D0%A0"))
    }

    @Test fun onlyStudyPagesWithoutReward() {
        val s = FocusFeed.Mode.STUDY
        assertTrue(FocusFeed.allowed("https://www.tiktok.com/search/video?q=x", s, false))
        assertTrue(FocusFeed.allowed("https://www.tiktok.com/@teacher/video/123", s, false))
        assertTrue(FocusFeed.allowed("https://www.tiktok.com/messages?lang=ru", s, false))
        assertTrue(FocusFeed.allowed("https://www.tiktok.com/login/phone-or-email", s, false))
        // Служебные переходы TikTok (язык, проверка) не ломаем.
        assertTrue(FocusFeed.allowed("https://www.tiktok.com/search?q=x&lang=ru-RU", s, false))
        assertTrue(FocusFeed.allowed("https://m.tiktok.com/v/123.html", s, false))
        assertFalse(FocusFeed.allowed("intent://tiktok.com/#Intent;scheme=snssdk1233;end", s, false))
        assertFalse(FocusFeed.allowed("https://www.tiktok.com/foryou", s, false))
        assertFalse(FocusFeed.allowed("https://www.tiktok.com/", s, false))
        assertFalse(FocusFeed.allowed("https://www.tiktok.com/explore", s, false))
        assertFalse(FocusFeed.allowed("https://www.tiktok.com/@someone/live", s, false))
        assertFalse(FocusFeed.allowed("https://evil.com/tiktok.com", s, false))
        assertFalse(FocusFeed.allowed("https://www.tiktok.com/search?q=x", FocusFeed.Mode.MESSAGES, false))
        assertTrue(FocusFeed.allowed("https://www.tiktok.com/foryou", FocusFeed.Mode.REWARD, true))
        assertFalse(FocusFeed.allowed("https://www.tiktok.com/foryou", FocusFeed.Mode.REWARD, false))
    }
}

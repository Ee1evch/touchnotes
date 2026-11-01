package app.nastavnik.schedule

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Перенесено из тестов Lecturial 2 (Apache License 2.0) и дополнено. */
class SiriusClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun initialHtml(date: String = "31.08.2026"): String {
        val initial = """{"fingerprint":{"id":"component","name":"main-grid"},"serverMemo":{"data":{"date":"$date","events":{}},"checksum":"initial"}}"""
            .replace("&", "&amp;").replace("\"", "&quot;")
        return """<div wire:initial-data="$initial"></div><script>window.livewire_token = 'token';</script>"""
    }

    private fun weekResponse(vararg events: String) =
        """{"serverMemo":{"data":{"group":"ИОП-ИТ-26/2","events":{"0_0":[${events.joinToString(",")}]}},"checksum":"next"},"effects":{}}"""

    private fun event(date: String, start: String, discipline: String, room: String? = null, teachers: String = "{}") =
        """{"date":"$date","startTime":"$start","endTime":"10:05","discipline":"$discipline","groupType":"Лекции (Основной)","numberPair":1,""" +
            """"classroom":${room?.let { "\"$it\"" } ?: "null"},"teachers":$teachers}"""

    @Test fun loadsWeekThroughLivewire() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "session=test; Path=/").setBody(initialHtml()))
        server.enqueue(MockResponse().setBody(weekResponse(
            event("31.08.2026", "08:45", "Физика", "Альфа 5.8Альфа 5.8 (Основной)", """{"a":{"fio":"Иванов Иван Иванович"}}"""),
        )))
        val weeks = mutableListOf<Week>()
        SiriusClient(server.url("/").toString(), OkHttpClient()).weeks("ИОП-ИТ-26/2", LocalDate.of(2026, 9, 2), 1) { weeks += it }
        val w = weeks.single()
        assertEquals("2026-08-31", w.start)
        val l = w.lessons.single()
        assertEquals("Физика", l.discipline)
        assertEquals("Лекция", l.type)
        assertEquals("Альфа 5.8", l.classroom)
        assertEquals("Иванов И. И.", l.teachersShort)
        assertEquals("/", server.takeRequest().path)
        val lw = server.takeRequest()
        assertEquals("/livewire/message/main-grid", lw.path)
        assertEquals("true", lw.getHeader("X-Livewire"))
        assertTrue(lw.body.readUtf8().contains("ИОП-ИТ-26/2"))
        assertTrue(lw.getHeader("Cookie").orEmpty().contains("session=test"))
    }

    @Test fun walksToRequestedWeek() = runBlocking {
        server.enqueue(MockResponse().setBody(initialHtml()))
        server.enqueue(MockResponse().setBody(weekResponse()))
        server.enqueue(MockResponse().setBody(weekResponse(event("07.09.2026", "10:15", "Матанализ"))))
        server.enqueue(MockResponse().setBody(weekResponse(event("14.09.2026", "10:15", "Алгебра"))))
        val weeks = mutableListOf<Week>()
        SiriusClient(server.url("/").toString(), OkHttpClient()).weeks("Г", LocalDate.of(2026, 9, 9), 2) { weeks += it }
        assertEquals(listOf("2026-09-07", "2026-09-14"), weeks.map { it.start })
        assertEquals("Матанализ", weeks[0].lessons.single().discipline)
        assertEquals("Алгебра", weeks[1].lessons.single().discipline)
    }

    @Test fun parsesGroupsAndSearches() = runBlocking {
        val html = """<li data-group="ИОП-ИТ-26/1"></li><li DATA-GROUP='Дизайн &amp; искусство'></li><li data-group="Группа &#1040;&#x2F;1"></li><li data-group=" ИОП-ИТ-26/1 "></li>"""
        assertEquals(listOf("ИОП-ИТ-26/1", "Дизайн & искусство", "Группа А/1"), GroupParser.parse(html))
        server.enqueue(MockResponse().setBody(initialHtml()))
        server.enqueue(MockResponse().setBody("""{"serverMemo":{"data":{},"checksum":"s"},"effects":{"html":"<li data-group=\"Био &amp; науки\"></li>"}}"""))
        assertEquals(listOf("Био & науки"), SiriusClient(server.url("/").toString(), OkHttpClient()).groups("  био  "))
        assertEquals(listOf("иоп ит", "иоп-ит", "иопит"), SiriusClient.variants("иоп ит"))
    }

    @Test fun currentAndNextLesson() {
        val day = "2026-11-02"
        val ls = listOf(
            Lesson("1", day, "08:45", "10:05", 1, "Физика"),
            Lesson("2", day, "10:15", "11:35", 2, "Алгебра"),
        )
        val s = ScheduleNow.at(ls, LocalDateTime.of(2026, 11, 2, 9, 0))
        assertEquals("Физика", s.current!!.discipline)
        assertEquals("Алгебра", s.next!!.discipline)
        assertEquals(65L, s.minutesLeft)
        val after = ScheduleNow.at(ls, LocalDateTime.of(2026, 11, 2, 12, 0))
        assertNull(after.current); assertNull(after.next)
        assertEquals(1, ScheduleText.forLanguageTeacher(listOf(
            Lesson("3", day, "12:00", "13:20", 3, "Иностранный язык", teachers = listOf("Петрова А. А.")),
            Lesson("4", day, "12:00", "13:20", 3, "Иностранный язык", teachers = listOf("Сидорова Б. Б.")),
        ), "Петрова А. А.").size)
    }
}

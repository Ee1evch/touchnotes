package app.nastavnik.grades

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛКС на мок-сервере: форма входа как на настоящем сайте, страница журнала с токеном, /journal/get с JSON. */
class LksClientTest {
    private val journalJson = """
        {"days":[{"full":"2026-10-20","d":"20","m":"окт"}],
         "lessons":[
           {"index":1,"title":"Математический анализ","avg_mark":"4.67","rating":"12","count_absent":1,
            "items":{"2026-10-20":[{"absent":0,"absent_valid":null,"marks":[{"mark_value_standard":"5","color":"#22c55e","second_mark":null}],"comment":"<p>Хорошо</p>","hometask":"№ 12"}],
                     "2026-10-27":[{"absent":1,"absent_valid":1,"marks":[]}],
                     "2026-10-29":[{"absent":0,"marks":[{"mark_value_standard":"4","second_mark":{"mark_value_standard":"5"}}]}]}},
           {"index":2,"title":"Физика","avg_mark":0,"rating":0,"count_absent":0,"items":[]}
         ],
         "interval":"year","intervalText":"2026/2027","studyPeriod":"1 семестр","col":60,"numPeriod":0,"cacheBuilding":false}
    """.trimIndent()

    private fun server(building: Int = 0): Pair<MockWebServer, MutableList<RecordedRequest>> {
        val s = MockWebServer()
        val seen = mutableListOf<RecordedRequest>()
        var left = building
        s.dispatcher = object : Dispatcher() {
            var loggedIn = false
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path!!
                return when {
                    path == "/login" && request.method == "GET" ->
                        MockResponse().setBody("""<meta name="csrf-token" content="t1"><form method="POST"><input type="hidden" name="_token" value="t1"><input name="login"><input type="password" name="password"></form>""")
                    path == "/login" && request.method == "POST" -> {
                        val b = request.body.readUtf8()
                        if (b.contains("_token=t1") && b.contains("password=right")) {
                            loggedIn = true
                            MockResponse().setResponseCode(302).setHeader("Location", "/dashboard").addHeader("Set-Cookie", "lks_session=abc; Path=/")
                        } else MockResponse().setResponseCode(302).setHeader("Location", "/login")
                    }
                    path == "/dashboard" -> MockResponse().setBody("<html>Рабочий стол</html>")
                    path == "/journal" ->
                        if (request.getHeader("Cookie")?.contains("lks_session=abc") == true)
                            MockResponse().setBody("""<meta name="csrf-token" content="c2"> x-data="{ startPeriod: 2026-10-26, endPeriod: 2026-11-01, interval: 'year' }" """)
                        else MockResponse().setResponseCode(302).setHeader("Location", "/login")
                    path == "/journal/get" -> {
                        assertEquals("c2", request.getHeader("X-CSRF-TOKEN"))
                        if (left-- > 0) MockResponse().setBody("""{"lessons":[],"cacheBuilding":true}""") else MockResponse().setBody(journalJson)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        return s to seen
    }

    @Test fun readsJournalWithCookies() = runBlocking {
        val (s, seen) = server()
        val jar = SessionCookies()
        val url = s.url("/")
        jar.saveFromResponse(url, listOf(okhttp3.Cookie.parse(url, "lks_session=abc; Path=/")!!))
        val c = LksClient(url.toString(), cookies = jar, client = OkHttpClient())
        val j = c.journal(now = 7)
        assertEquals(2, j.lessons.size)
        val ma = j.lessons[0]
        assertEquals("Математический анализ", ma.title)
        assertEquals("4.67", ma.avgMark)
        assertEquals("12", ma.rating)
        assertEquals(1, ma.absences)
        assertEquals(listOf("2026-10-20", "2026-10-27", "2026-10-29"), ma.entries.map { it.date })
        assertEquals("5", ma.entries[0].marks.single().value)
        assertEquals("Хорошо", ma.entries[0].comment)
        assertEquals("№ 12", ma.entries[0].hometask)
        assertTrue(ma.entries[1].absent)
        assertEquals(true, ma.entries[1].absentValid)
        assertEquals("5", ma.entries[2].marks.single().second)
        assertEquals(null, j.lessons[1].avgMark)
        assertEquals("1 семестр", j.period)
        val body = seen.first { it.path == "/journal/get" }.body.readUtf8()
        assertTrue(body.contains("\"startPeriod\":\"2026-10-26\""))
        val snap = LksClient.toSnapshot(j)
        assertEquals(listOf("5", "4/5"), snap.courses[0].items.map { it.grade })
        s.shutdown()
    }

    @Test fun waitsWhileJournalIsBuilding() = runBlocking {
        val (s, _) = server(building = 1)
        val jar = SessionCookies()
        val url = s.url("/")
        jar.saveFromResponse(url, listOf(okhttp3.Cookie.parse(url, "lks_session=abc; Path=/")!!))
        val c = LksClient(url.toString(), cookies = jar, client = OkHttpClient())
        assertEquals(2, c.journal().lessons.size)
        s.shutdown()
    }

    @Test fun expiredSessionAsksToLogInAgain() = runBlocking {
        val (s, _) = server()
        val c = LksClient(s.url("/").toString(), client = OkHttpClient())
        val e = runCatching { c.journal() }.exceptionOrNull()
        assertTrue(e is LksClient.LoginException)
        // Куки переживают сохранение.
        val url = s.url("/")
        val jar = SessionCookies()
        jar.saveFromResponse(url, listOf(okhttp3.Cookie.parse(url, "lks_session=abc; Path=/")!!))
        val restored = SessionCookies.parse(jar.serialize(), url)
        assertEquals("abc", restored.loadForRequest(url).single().value)
        assertTrue("https://x".toHttpUrl().host == "x")
        s.shutdown()
    }

    @Test fun emptyJournalReturnsNullAverage() {
        val j = LksJournal(emptyList())
        org.junit.Assert.assertNull(j.averageMark)
    }

    @Test fun subjectWithoutMarksIsIgnoredInFlatAverage() {
        // Предмет только с серверным avgMark без записей не учитывается в общем среднем балле
        val l1 = LksLesson(
            title = "Философия",
            avgMark = "4.0",
            entries = emptyList(),
        )
        val l2 = LksLesson(
            title = "Математика",
            avgMark = "5.0",
            entries = listOf(
                LksEntry(date = "2026-10-01", marks = listOf(LksMark("5"))),
                LksEntry(date = "2026-10-02", marks = listOf(LksMark("5"))),
            ),
        )
        val j = LksJournal(listOf(l1, l2))
        // Плоское среднее берется только из entries: (5 + 5) / 2 = 5.0 (не (4.0 + 5.0)/2 = 4.5)
        assertEquals(5.0, j.averageMark!!, 0.001)
    }

    @Test fun dashesAndDuplicateMarksHandledCorrectly() {
        val l = LksLesson(
            title = "Информатика",
            entries = listOf(
                LksEntry(date = "2026-10-01", marks = listOf(LksMark("5"), LksMark("-"))),
                LksEntry(date = "2026-10-02", marks = listOf(LksMark("5"))), // повторная 5
                LksEntry(date = "2026-10-03", marks = listOf(LksMark("4"), LksMark("зачтено"))),
            ),
        )
        val j = LksJournal(listOf(l))
        // Числовые оценки: 5, 5, 4. Каждая учитывается ровно один раз. Нечисловые «-» и «зачтено» игнорируются.
        assertEquals((5.0 + 5.0 + 4.0) / 3.0, j.averageMark!!, 0.001)
    }

    @Test fun flatAverageDiffersFromAverageOfAverages() {
        // Предмет 1: 10 оценок «5». Средний балл предмета = 5.0
        val entries1 = (1..10).map { day ->
            LksEntry(date = "2026-10-%02d".format(day), marks = listOf(LksMark("5")))
        }
        val l1 = LksLesson(title = "Матанализ", avgMark = "5.0", entries = entries1)

        // Предмет 2: 1 оценка «3». Средний балл предмета = 3.0
        val l2 = LksLesson(
            title = "История",
            avgMark = "3.0",
            entries = listOf(LksEntry(date = "2026-10-15", marks = listOf(LksMark("3")))),
        )

        val j = LksJournal(listOf(l1, l2))
        val flatAverage = (10 * 5.0 + 3.0) / 11.0 // 53 / 11 ≈ 4.818
        val oldAverageOfAverages = (5.0 + 3.0) / 2.0 // 4.0

        assertEquals(flatAverage, j.averageMark!!, 0.0001)
        assertTrue(kotlin.math.abs(j.averageMark!! - oldAverageOfAverages) > 0.8)
    }
}

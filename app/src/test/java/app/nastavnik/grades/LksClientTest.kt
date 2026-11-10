package app.nastavnik.grades

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЛКС на мок-сервере. Войти запросом нельзя (капча на /login), поэтому клиент получает куки из встроенного
 * окна — как `CookieManager.getCookie` их отдаёт: lkssirius_session, XSRF-TOKEN, remember_web_….
 */
class LksClientTest {
    private val journalJson = """
        {"days":[{"full":"2026-10-20","d":"20","m":"окт","current":false}],
         "lessons":[
           {"index":1,"title":"Математический анализ","avg_mark":"4.67","rating":"12","count_absent":1,
            "items":{"2026-10-20":[{"absent":0,"absent_valid":null,"marks":[{"mark_value_standard":"5","color":"#22c55e","second_mark":null}],"comment":"<p>Хорошо</p>","hometask":"№ 12"}],
                     "2026-10-27":[{"absent":1,"absent_valid":1,"marks":[]}],
                     "2026-10-29":[{"absent":0,"marks":[{"mark_value_standard":"4","second_mark":{"mark_value_standard":"5"}}]}]}},
           {"index":2,"title":"Физика","avg_mark":0,"rating":0,"count_absent":0,"items":[]}
         ],
         "interval":"year","intervalText":"2026/2027","studyPeriod":"1 семестр","col":400,"numPeriod":0,"cacheBuilding":false}
    """.trimIndent()

    private val webCookies = "XSRF-TOKEN=eyJpdiI6Ik1%3D; lkssirius_session=abc; remember_web_59ba36addc2b2f9401580f014c7f58ea4e30989d=xyz"

    /** 2026-11-01 15:00 по Москве — воскресенье, неделя 26 окт – 1 ноя. */
    private val sunday = java.time.ZonedDateTime.of(2026, 11, 1, 15, 0, 0, 0, java.time.ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()

    private fun server(
        building: Int = 0,
        journalPage: String = """<meta name="csrf-token" content="c2"><div x-data="{ startPeriod: 2026-10-26, endPeriod: 2026-11-01, interval: 'year' }">""",
        status: Int = 200,
    ): Pair<MockWebServer, MutableList<RecordedRequest>> {
        val s = MockWebServer()
        val seen = mutableListOf<RecordedRequest>()
        var left = building
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (status != 200) return MockResponse().setResponseCode(status).setBody("<html>Доступ ограничен</html>")
                val authed = request.getHeader("Cookie")?.contains("lkssirius_session=abc") == true
                val path = request.path!!
                return when {
                    path == "/login" -> MockResponse().setBody("""<form method="POST"><input type="hidden" name="_token" value="t1"><input name="login"><input type="password" name="password"><div id="captcha-container" data-sitekey="k"></div></form>""")
                    !authed -> MockResponse().setResponseCode(302).setHeader("Location", "/login")
                    path == "/journal" -> MockResponse().setBody(journalPage)
                        .addHeader("Set-Cookie", "lkssirius_session=abc; Max-Age=36000; Path=/; HttpOnly")
                    path == "/journal/get" -> {
                        assertEquals("c2", request.getHeader("X-CSRF-TOKEN"))
                        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
                        when {
                            left-- > 1 -> MockResponse().setBody("""{"cacheBuilding":true}""")
                            left >= 0 -> MockResponse().setBody("""{"lessons":[],"cacheBuilding":true}""")
                            else -> MockResponse().setBody(journalJson)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        return s to seen
    }

    private fun client(s: MockWebServer, cookies: String = webCookies) =
        LksClient(s.url("/").toString(), SessionCookies.fromWebView(cookies, s.url("/")), OkHttpClient())

    @Test fun readsJournalWithCookiesFromWindow() = runBlocking {
        val (s, seen) = server()
        val j = client(s).journal(now = 7)
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
        val get = seen.first { it.path == "/journal/get" }
        val body = get.body.readUtf8()
        assertTrue(body.contains("\"startPeriod\":\"2026-10-26\""))
        assertTrue(body.contains("\"endPeriod\":\"2026-11-01\""))
        assertTrue(body.contains("\"interval\":\"year\""))
        // Все куки окна уходят на сайт, включая «Запомнить меня».
        assertTrue(get.getHeader("Cookie")!!.contains("remember_web_59ba36addc2b2f9401580f014c7f58ea4e30989d=xyz"))
        assertEquals(listOf("5", "4/5"), LksClient.toSnapshot(j).courses[0].items.map { it.grade })
        s.shutdown()
    }

    @Test fun periodFallsBackToCurrentWeek() = runBlocking {
        val (s, seen) = server(journalPage = """<meta name="csrf-token" content="c2"><div x-data="journal()">""")
        client(s).journal(now = sunday)
        val body = seen.first { it.path == "/journal/get" }.body.readUtf8()
        assertTrue(body, body.contains("\"startPeriod\":\"2026-10-26\"") && body.contains("\"endPeriod\":\"2026-11-01\""))
        s.shutdown()
    }

    @Test fun waitsWhileJournalIsBuilding() = runBlocking {
        val (s, seen) = server(building = 2)
        assertEquals(2, client(s).journal(retryDelay = 0).lessons.size)
        assertEquals(3, seen.count { it.path == "/journal/get" })
        s.shutdown()
    }

    @Test fun doesNotReturnHalfBuiltJournal() = runBlocking {
        val (s, _) = server(building = 1000)
        val e = runCatching { client(s).journal(retryDelay = 0) }.exceptionOrNull()
        assertTrue(e is GradesException && e !is LksClient.LoginException)
        assertTrue(e!!.message!!.contains("собирает"))
        s.shutdown()
    }

    @Test fun expiredSessionAsksToLogInAgain() = runBlocking {
        val (s, _) = server()
        val e = runCatching { client(s, "XSRF-TOKEN=x; lkssirius_session=old").journal() }.exceptionOrNull()
        assertTrue(e is LksClient.LoginException)
        assertEquals(LksClient.EXPIRED, e!!.message)
        s.shutdown()
    }

    @Test fun foreignAddressIsExplained() = runBlocking {
        val (s, _) = server(status = 418)
        val e = runCatching { client(s).journal() }.exceptionOrNull()
        assertTrue(e is GradesException && e !is LksClient.LoginException)
        assertTrue(e!!.message!!.contains("VPN"))
        s.shutdown()
    }

    @Test fun webViewCookiesSurviveStorage() {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("lks.siriusuniversity.ru").build()
        val jar = SessionCookies.fromWebView(" $webCookies; ;broken", url)
        assertEquals(listOf("XSRF-TOKEN", "lkssirius_session", "remember_web_59ba36addc2b2f9401580f014c7f58ea4e30989d"), jar.all().map { it.name })
        assertTrue(jar.all().all { it.persistent && it.hostOnly && it.domain == url.host })
        val restored = SessionCookies.parse(jar.serialize(), url)
        assertEquals(jar.all().map { it.name to it.value }, restored.loadForRequest(url).map { it.name to it.value })
        assertFalse(SessionCookies.fromWebView("", url).all().isNotEmpty())
    }
}

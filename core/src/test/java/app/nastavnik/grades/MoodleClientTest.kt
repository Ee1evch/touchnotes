package app.nastavnik.grades

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodleClientTest {
    private fun server(gradeA: String = "5,00"): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                if (request.path!!.startsWith("/login/token.php")) {
                    return if (body.contains("password=right")) MockResponse().setBody("""{"token":"t123","privatetoken":"p"}""")
                    else MockResponse().setBody("""{"error":"Неверный логин","errorcode":"invalidlogin"}""")
                }
                if (!body.contains("wstoken=t123")) return MockResponse().setBody("""{"exception":"moodle_exception","errorcode":"invalidtoken","message":"Invalid token"}""")
                val fn = Regex("wsfunction=([a-z_]+)").find(body)!!.groupValues[1]
                return MockResponse().setBody(
                    when (fn) {
                        "core_webservice_get_site_info" -> """{"userid":42,"fullname":"Иван Петров","sitename":"Сириус"}"""
                        "core_enrol_get_users_courses" -> """[{"id":7,"shortname":"МА","fullname":"Математический анализ"},{"id":8,"shortname":"Физ","fullname":"Физика"}]"""
                        "gradereport_overview_get_course_grades" -> """{"grades":[{"courseid":7,"grade":"4,50","rawgrade":"4.50000"},{"courseid":8,"grade":"-","rawgrade":null}],"warnings":[]}"""
                        "gradereport_user_get_grade_items" -> if (body.contains("courseid=7"))
                            """{"usergrades":[{"courseid":7,"userid":42,"gradeitems":[
                                {"id":1,"itemname":"Контрольная 1","itemtype":"mod","itemmodule":"quiz","gradeformatted":"$gradeA","graderaw":5,"grademax":5,"percentageformatted":"100,00 %","feedback":"<p>Отлично!</p>","gradedategraded":1700000000},
                                {"id":2,"itemname":"ДЗ 2","itemtype":"mod","gradeformatted":"-","graderaw":null,"grademax":10},
                                {"id":3,"itemname":null,"itemtype":"course","gradeformatted":"4,50","graderaw":4.5,"grademax":5}]}],"warnings":[]}"""
                        else """{"usergrades":[{"courseid":8,"gradeitems":[]}]}"""
                        else -> """{"exception":"x","errorcode":"unknown","message":"?"}"""
                    },
                )
            }
        }
        s.start()
        return s
    }

    @Test fun logsInAndLoadsGrades() = runBlocking {
        val s = server()
        val c = MoodleClient(s.url("/").toString(), OkHttpClient())
        val e = runCatching { c.login("ivan", "wrong") }.exceptionOrNull() as GradesException
        assertTrue(e.badLogin)
        assertEquals("Неверный логин или пароль", e.message)
        val token = c.login("ivan", "right")
        assertEquals("t123", token)
        val snap = c.snapshot(token, now = 1)
        assertEquals("Иван Петров", snap.student)
        val ma = snap.courses.first { it.id == 7L }
        assertEquals("4,50", ma.total)
        assertEquals(listOf("Контрольная 1", "ДЗ 2"), ma.items.map { it.name })
        assertEquals("Отлично!", ma.items[0].feedback)
        assertEquals(1_700_000_000_000L, ma.items[0].gradedAt)
        assertEquals(null, snap.courses.first { it.id == 8L }.total)
        assertEquals(100.0, MoodleClient.averagePercent(snap)!!, 0.01)
        val bad = runCatching { c.snapshot("old") }.exceptionOrNull() as GradesException
        assertTrue(bad.badLogin)
        s.shutdown()
    }

    @Test fun detectsNewGrades() = runBlocking {
        val s1 = server("-")
        val before = MoodleClient(s1.url("/").toString(), OkHttpClient()).snapshot("t123")
        s1.shutdown()
        val s2 = server("5,00")
        val after = MoodleClient(s2.url("/").toString(), OkHttpClient()).snapshot("t123")
        s2.shutdown()
        val ch = MoodleClient.changes(before, after)
        assertEquals(1, ch.size)
        assertEquals("Контрольная 1", ch[0].second.name)
        assertTrue(MoodleClient.changes(null, after).isEmpty())
    }
}

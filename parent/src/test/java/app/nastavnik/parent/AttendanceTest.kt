package app.nastavnik.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceTest {
    // Как на странице студента: данные Next.js внутри строки — кавычки экранированы.
    private val page = """<a href="/courses/3?groupId=7">ГР-1</a><script>self.__next_f.push([1,"[\"${'$'}\",\"${'$'}L6\",null,{\"href\":\"/courses/3?groupId=7\",\"children\":\"ГР-1\"}],[\"${'$'}\",\"${'$'}L14\",\"primary\",{\"studentId\":42,\"registryNumber\":\"240001\",\"groupId\":7,\"canEdit\":false}]"])</script>"""

    @Test fun studentFromPage() {
        assertEquals(AttendanceStudent("240001", 42, 7, "ГР-1"), AttendanceParser.student(page, "240001"))
        assertNull(AttendanceParser.student(page, "240002"))
        assertNull(AttendanceParser.student("<html>нет данных</html>", "240001"))
    }

    // Ответ /api/attendance: отметки всей группы «студент-пара»; наш — 42.
    private val week = """{
      "overrides": {"42-11": "present", "42-12": "absent", "43-11": "absent", "42-13": "reason", "42-14": "weird"},
      "lessons": [
        {"id": "12", "date": "2026-10-27", "number": 1, "title": "Линейная алгебра", "type": "Практика", "startTime": "08:45", "endTime": "10:05"},
        {"id": "11", "date": "2026-10-26", "number": 2, "title": "Дифференциальные уравнения", "type": "Лекции", "startTime": "10:20", "endTime": "11:40"},
        {"id": "13", "date": "2026-10-27", "number": 2, "title": "Физика", "type": "Практика", "startTime": "10:20", "endTime": "11:40"},
        {"id": "15", "date": "2026-10-28", "number": 1, "title": "История", "startTime": "08:45", "endTime": "10:05"}
      ],
      "explanations": {}, "referenceOverrides": {},
      "reasonConfirmations": {"42-13": true, "43-11": false}
    }"""

    @Test fun weekKeepsOnlyThisStudent() {
        val ls = AttendanceParser.week(week, 42)
        assertEquals(listOf("11", "12", "13", "15"), ls.map { it.id }) // по дате и времени
        assertEquals(Mark.PRESENT, ls[0].status)
        assertEquals(Mark.ABSENT, ls[1].status)
        assertEquals(Mark.REASON, ls[2].status)
        assertTrue(ls[2].confirmed)
        assertNull("не отмечено", ls[3].status)
        assertEquals("10:20", ls[0].start)
        assertTrue(Mark.ABSENT.missed && Mark.ILL.missed && !Mark.LATE.missed)
        assertFalse(ls[0].confirmed)
        // Неизвестная отметка — как «не отмечено», без падения.
        assertNull(Mark.of("weird"))
        assertEquals(emptyList<AttendanceLesson>(), AttendanceParser.week("не json", 42))
    }
}

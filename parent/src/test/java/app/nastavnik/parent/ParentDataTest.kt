package app.nastavnik.parent

import app.nastavnik.grades.LksEntry
import app.nastavnik.grades.LksJournal
import app.nastavnik.grades.LksLesson
import app.nastavnik.grades.LksMark
import app.nastavnik.schedule.Lesson
import app.nastavnik.sync.SyncRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentDataTest {
    private val journal = LksJournal(
        listOf(
            LksLesson("Математический анализ", avgMark = "4.5", entries = listOf(
                LksEntry("2026-11-02", marks = listOf(LksMark("5"))),
                LksEntry("2026-11-03", absent = true, absentValid = false),
            )),
            LksLesson("Физика", entries = listOf(LksEntry("2026-11-02T00:00:00", absent = true, absentValid = true))),
        ),
        loadedAt = 7,
    )

    @Test fun studentFromCloudRecords() {
        val records = mapOf(
            "prefs/study" to SyncRecord("prefs", "study", 1, data = Json.parseToJsonElement("""{"group":"К0109-24","lang":"Смит","exams":""}""")),
            "grades/lks" to SyncRecord("grades", "lks", 2, data = Json.encodeToJsonElement(LksJournal.serializer(), journal)),
        )
        val s = ParentData.student(records, now = 99)
        assertEquals("К0109-24", s.group)
        assertEquals("Смит", s.languageTeacher)
        assertEquals("Математический анализ", s.journal!!.lessons.first().title)
        assertEquals(99, s.loadedAt)
        // Ничего нет — пустой студент, без падения.
        assertEquals(Student(loadedAt = 1), ParentData.student(emptyMap(), 1))
    }

    @Test fun subjectsMatchLoosely() {
        assertTrue(ParentData.sameSubject("Математический анализ", "математический анализ."))
        assertTrue(ParentData.sameSubject("Физика", "Физика (практика)"))
        assertTrue(ParentData.sameSubject("Алгебра и Ёлки", "алгебра и елки"))
        assertFalse(ParentData.sameSubject("Физика", "Физическая культура"))
        assertFalse(ParentData.sameSubject("", "Физика"))
    }

    @Test fun entryForLessonByDayAndSubject() {
        fun l(d: String, date: String) = Lesson(id = d + date, date = date, start = "09:00", end = "10:30", discipline = d)
        assertEquals("5", ParentData.entryFor(l("Математический анализ", "2026-11-02"), journal)!!.marks.single().value)
        assertTrue(ParentData.entryFor(l("Математический анализ", "2026-11-03"), journal)!!.absent)
        assertTrue(ParentData.entryFor(l("Физика", "2026-11-02"), journal)!!.absent)
        assertNull(ParentData.entryFor(l("Физика", "2026-11-05"), journal))
        assertNull(ParentData.entryFor(l("История", "2026-11-02"), journal))
        assertNull(ParentData.entryFor(l("Физика", "2026-11-02"), null))
    }

    @Test fun absencesCount() {
        assertEquals(2 to 1, ParentData.absences(journal))
        assertEquals(0 to 0, ParentData.absences(null))
    }
}

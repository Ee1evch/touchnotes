package app.nastavnik.parent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nastavnik.grades.LksEntry
import app.nastavnik.grades.LksJournal
import app.nastavnik.grades.LksLesson
import app.nastavnik.grades.LksMark
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.schedule.Week
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Приложение родителя на снимке данных (без сети): расписание с посещаемостью, успеваемость, настройки. */
@RunWith(AndroidJUnit4::class)
class ParentFlowTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    private fun screenshot(name: String) {
        val f = File(ctx.cacheDir, "$name.png")
        if (!device.takeScreenshot(f)) return
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return
        val w = 760
        val small = Bitmap.createScaledBitmap(bmp, w, (bmp.height * w.toFloat() / bmp.width).toInt(), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        println("IMG-BEGIN $name")
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(3000).forEach { println("IMG-DATA $it") }
        println("IMG-END $name")
    }

    private fun seed() {
        val today = LocalDate.now()
        val monday = ScheduleText.monday(today)
        // Пары сегодня: прошедшая (пропуск), идущая сейчас (оценка 5), вечерняя.
        val now = LocalTime.now().withSecond(0).withNano(0)
        fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
        val lessons = listOf(
            Lesson(id = "1", date = today.toString(), start = hm(now.minusMinutes(200)), end = hm(now.minusMinutes(110)), discipline = "Математический анализ", type = "Лекция", classroom = "Бета 3.3", teachers = listOf("Иванов Пётр Сергеевич")),
            Lesson(id = "2", date = today.toString(), start = hm(now.minusMinutes(30)), end = hm(now.plusMinutes(60)), discipline = "Физика", type = "Практика", classroom = "Альфа 5.8", teachers = listOf("Ваганов Михаил Георгиевич")),
            Lesson(id = "3", date = today.toString(), start = hm(now.plusMinutes(70)), end = hm(now.plusMinutes(160)), discipline = "Линейная алгебра", type = "Семинар", classroom = "Гамма 2.1", teachers = listOf("Петрова Анна Игоревна")),
        )
        val journal = LksJournal(
            listOf(
                LksLesson("Математический анализ", avgMark = "4.33", rating = "12", entries = listOf(
                    LksEntry(today.minusDays(7).toString(), marks = listOf(LksMark("4"))),
                    LksEntry(today.minusDays(5).toString(), marks = listOf(LksMark("5"))),
                    LksEntry(today.toString(), absent = true, absentValid = false),
                )),
                LksLesson("Физика", avgMark = "5", entries = listOf(LksEntry(today.toString(), marks = listOf(LksMark("5"))))),
                LksLesson("Линейная алгебра", avgMark = "3.5", entries = listOf(
                    LksEntry(today.minusDays(3).toString(), marks = listOf(LksMark("3"), LksMark("4"))),
                    LksEntry(today.minusDays(1).toString(), absent = true, absentValid = true, comment = "Справка"),
                )),
            ),
            period = "Осенний семестр",
            loadedAt = System.currentTimeMillis(),
        )
        val json = Json { encodeDefaults = true }
        val dir = File(ctx.filesDir, "parent").apply { deleteRecursively(); mkdirs() }
        File(dir, "student.json").writeText(json.encodeToString(Student.serializer(), Student("К0109-24", "", journal, System.currentTimeMillis())))
        File(dir, "weeks").mkdirs()
        File(dir, "weeks/$monday.json").writeText(json.encodeToString(Week.serializer(), Week("К0109-24", monday.toString(), lessons, System.currentTimeMillis())))
        ParentRepo.get(ctx).save("test-token", "2024-0001", "")
    }

    @Test
    fun scheduleAttendanceAndGrades() {
        seed()
        ctx.startActivity(Intent(ctx, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull("нет пары в расписании", device.wait(Until.findObject(By.text("Физика")), 15000))
        assertNotNull("нет пропуска", device.wait(Until.findObject(By.text("Пропуск")), 5000))
        assertNotNull("нет идущей пары", device.findObject(By.textStartsWith("Идёт сейчас")))
        SystemClock.sleep(800)
        screenshot("parent_schedule")

        device.findObject(By.text("Успеваемость")).click()
        assertNotNull("нет успеваемости", device.wait(Until.findObject(By.text("средний балл")), 8000))
        SystemClock.sleep(800)
        screenshot("parent_grades")
        device.findObject(By.text("Линейная алгебра")).click()
        assertNotNull("не раскрылись оценки", device.wait(Until.findObject(By.text("Справка")), 5000))
        SystemClock.sleep(600)
        screenshot("parent_grades_open")

        device.findObject(By.desc("Настройки")).click()
        assertNotNull("нет настроек", device.wait(Until.findObject(By.text("Номер зачётной книжки")), 5000))
        SystemClock.sleep(600)
        screenshot("parent_settings")
        device.pressBack()
    }
}

package app.nastavnik

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.schedule.Week
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** «Наставник · Студент»: только расписание, оценки и настройки; служб наставника нет. */
@RunWith(AndroidJUnit4::class)
class StudentFlowTest {
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

    @Test
    fun scheduleGradesAndSettingsOnly() {
        assertTrue(BuildConfig.STUDENT)
        assertEquals("app.nastavnik.student", ctx.packageName)
        // Служб полной версии в лёгкой нет.
        for (cls in listOf("app.nastavnik.overlay.BubbleService", "app.nastavnik.overlay.MentorTileService")) {
            val missing = runCatching { ctx.packageManager.getServiceInfo(ComponentName(ctx, cls), 0) }.exceptionOrNull() is PackageManager.NameNotFoundException
            assertTrue("$cls есть в лёгкой версии", missing)
        }
        val today = LocalDate.now()
        val now = LocalTime.now().withSecond(0).withNano(0)
        fun t(x: LocalTime) = "%02d:%02d".format(x.hour, x.minute)
        val start = if (now.isBefore(LocalTime.of(0, 20))) LocalTime.of(0, 0) else now.minusMinutes(15)
        val end = if (now.isAfter(LocalTime.of(22, 30))) LocalTime.of(23, 59) else now.plusMinutes(75)
        ctx.app.settings.setScheduleGroup("ТЕСТ-1")
        runBlocking {
            ctx.app.schedule.store(
                Week(
                    "ТЕСТ-1", ScheduleText.monday(today).toString(),
                    listOf(Lesson("s1", today.toString(), t(start), t(end), 1, "Физика", "Практика", "Альфа 5.8", teachers = listOf("Петрова Анна Сергеевна"))),
                    System.currentTimeMillis(),
                ),
            )
        }
        val sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java))
        try {
            assertNotNull("нет расписания", device.wait(Until.findObject(By.text("Физика")), 10000))
            assertNotNull(device.findObject(By.desc("Оценки")))
            assertNotNull(device.findObject(By.desc("Настройки")))
            assertNull("в лёгкой версии нет «Учёбы»", device.findObject(By.desc("Учёба")))
            SystemClock.sleep(800)
            screenshot("student_schedule")
            device.findObject(By.desc("Оценки")).click()
            assertNotNull("нет оценок", device.wait(Until.findObject(By.text("Успеваемость")), 8000))
            SystemClock.sleep(600)
            screenshot("student_grades")
            device.findObject(By.desc("Настройки")).click()
            assertNotNull("нет оформления", device.wait(Until.findObject(By.text("Открыть оформление")), 8000))
            SystemClock.sleep(600)
            screenshot("student_settings")
            // «Тихо на парах» — ниже, листаем.
            var quiet = device.findObject(By.text("Тихо на парах"))
            repeat(6) {
                if (quiet == null) {
                    device.swipe(device.displayWidth / 2, (device.displayHeight * 0.75).toInt(), device.displayWidth / 2, (device.displayHeight * 0.3).toInt(), 40)
                    quiet = device.wait(Until.findObject(By.text("Тихо на парах")), 1500)
                }
            }
            assertNotNull("нет «Тихо на парах»", quiet)
            assertNull("в лёгкой версии нет выбора «Телефон / Планшет»", device.findObject(By.text("Планшет")))
            SystemClock.sleep(600)
            screenshot("student_settings_more")
        } finally {
            sc.close()
        }
    }
}

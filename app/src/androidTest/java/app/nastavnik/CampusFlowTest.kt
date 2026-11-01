package app.nastavnik

import android.content.Intent
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
import app.nastavnik.data.CardEntity
import app.nastavnik.data.ReminderEntity
import app.nastavnik.ink.Paper
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.schedule.Week
import app.nastavnik.sync.AppSyncSource
import app.nastavnik.sync.LocalItem
import app.nastavnik.sync.SyncRecord
import app.nastavnik.sync.SyncEngine
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Суперприложение на эмуляторе: телефонный режим (сегодня, расписание, карта с маршрутом, напоминания),
 * карта на планшете и перенос данных синхронизацией (выгрузка → удаление → приём обратно).
 */
@RunWith(AndroidJUnit4::class)
class CampusFlowTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    @Before
    fun setUp() {
        device.executeShellCommand("settings put global hide_error_dialogs 1")
        // Иначе поверх экрана выскочит системный вопрос про уведомления.
        device.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION")
        device.executeShellCommand("pm grant ${ctx.packageName} android.permission.ACTIVITY_RECOGNITION")
        ctx.app.settings.setOnboardingDone(true)
    }

    @After
    fun tearDown() {
        ctx.app.settings.setDeviceRole("auto")
        ctx.app.settings.setScheduleGroup("")
    }

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

    /** Неделя с тремя парами сегодня: прошедшая в «Бета 3.3», идущая сейчас в «Альфа 5.8», вечерняя. */
    private fun seedSchedule() = runBlocking {
        val today = LocalDate.now()
        val now = LocalTime.now()
        fun t(x: LocalTime) = "%02d:%02d".format(x.hour, x.minute)
        // Если тест запущен сразу после полуночи — сдвигаем, чтобы прошедшая пара всё равно была в прошлом.
        val base = if (now.isBefore(LocalTime.of(0, 30))) LocalTime.of(0, 30) else now
        val lessons = listOf(
            Lesson("a", today.toString(), "00:00", t(base.minusMinutes(20)), 1, "Математический анализ", "Лекция", "Бета 3.3", teachers = listOf("Иванов Иван Иванович")),
            Lesson("b", today.toString(), t(base.minusMinutes(10)), t(base.plusMinutes(70).coerceAtMost23()), 2, "Физика", "Практика", "Альфа 5.8", teachers = listOf("Петрова Анна Сергеевна")),
            Lesson("c", today.plusDays(1).toString(), "10:15", "11:35", 1, "Иностранный язык", "Практика", "К_У. К4 (комп)"),
        )
        ctx.app.settings.setScheduleGroup("ТЕСТ-1")
        ctx.app.schedule.store(Week("ТЕСТ-1", ScheduleText.monday(today).toString(), lessons.filter { ScheduleText.monday(LocalDate.parse(it.date)) == ScheduleText.monday(today) }, System.currentTimeMillis()))
        val nextMonday = ScheduleText.monday(today.plusDays(1))
        if (nextMonday != ScheduleText.monday(today)) ctx.app.schedule.store(Week("ТЕСТ-1", nextMonday.toString(), lessons.filter { it.id == "c" }, System.currentTimeMillis()))
    }

    private fun LocalTime.coerceAtMost23(): LocalTime = if (isBefore(LocalTime.of(1, 0)) && this != LocalTime.MIDNIGHT) LocalTime.of(23, 59) else this

    @Test
    fun phoneTodayScheduleMapAndReminders() {
        ctx.app.settings.setDeviceRole("phone")
        seedSchedule()
        runBlocking { ctx.app.db.reminders().all().forEach { ctx.app.db.reminders().delete(it.uid) } }
        val sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOME))
        try {
            assertNotNull("нет вкладок телефона", device.wait(Until.findObject(By.desc("Расписание")), 10000))
            assertNotNull("нет текущей пары на «Сегодня»", device.wait(Until.findObject(By.text("Физика")), 8000))
            SystemClock.sleep(900)
            screenshot("phone_today")

            device.findObject(By.desc("Расписание")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Математический анализ")), 8000))
            SystemClock.sleep(900)
            screenshot("phone_schedule")

            // Аудитория пары → карта с выбранной аудиторией → маршрут от предыдущей пары.
            device.findObject(By.text("Альфа 5.8")).click()
            assertNotNull("карта не открылась", device.wait(Until.findObject(By.desc("Карта этажа")), 10000))
            assertNotNull("аудитория не выбрана", device.wait(Until.findObject(By.text("Как дойти")), 10000))
            SystemClock.sleep(1500)
            screenshot("phone_map_room")
            device.findObject(By.text("Как дойти")).click()
            assertNotNull("маршрут не построен", device.wait(Until.findObject(By.textContains(" мин")), 20000))
            SystemClock.sleep(1500)
            screenshot("phone_route")

            // По шагам, как в картах: плашка с манёвром сверху, «Завершить» снизу.
            device.findObject(By.text("Поехали")).click()
            assertNotNull("нет навигации по шагам", device.wait(Until.findObject(By.text("Завершить")), 8000))
            assertNotNull(device.wait(Until.findObject(By.textContains("через")), 5000))
            SystemClock.sleep(1200)
            screenshot("phone_navigation")
            device.findObject(By.desc("Следующий шаг"))?.click()
            SystemClock.sleep(900)
            screenshot("phone_navigation_next")
            device.findObject(By.text("Завершить")).click()
            SystemClock.sleep(600)

            // Поиск на карте.
            val search = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000)
            search.click()
            search.text = "буран"
            assertNotNull("поиск не нашёл музей", device.wait(Until.findObject(By.textContains("Буран")), 8000))
            SystemClock.sleep(600)
            screenshot("phone_map_search")
            device.pressBack()

            // Напоминание через «Учёба» → «Напоминания».
            device.findObject(By.desc("Учёба")).click()
            device.wait(Until.findObject(By.text("Напоминания")), 8000)!!.click()
            device.wait(Until.findObject(By.text("Новое")), 8000)!!.click()
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000)
            field.text = "Сдать лабу по физике"
            device.findObject(By.text("Завтра в 9:00"))?.click()
            device.findObject(By.text("Сохранить")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Сдать лабу по физике")), 8000))
            SystemClock.sleep(800)
            screenshot("phone_reminders")
            // «Лента»: TikTok по правилам учёбы — обычная лента закрыта, пока не всё сделано.
            device.findObject(By.desc("Лента")).click()
            assertNotNull("нет переключателя ленты", device.wait(Until.findObject(By.text("Сообщения")), 8000))
            SystemClock.sleep(2500)
            screenshot("phone_feed")
            device.findObject(By.textContains("Награда"))?.click()
            assertNotNull("награда не закрыта", device.wait(Until.findObject(By.textContains("Обычная лента закрыта")), 5000))
            screenshot("phone_feed_locked")
            val saved = runBlocking { ctx.app.db.reminders().all() }
            assertEquals("Сдать лабу по физике", saved.single().title)
            assertTrue(saved.single().at > System.currentTimeMillis())
        } finally {
            sc.close()
        }
    }

    @Test
    fun tabletMapFindsRoomAndRoutesAcrossFloors() {
        ctx.app.settings.setDeviceRole("tablet")
        val sc = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_MAP).putExtra(MainActivity.EXTRA_ROOM, "Бета 3.3"),
        )
        try {
            assertNotNull(device.wait(Until.findObject(By.text("Как дойти")), 15000))
            SystemClock.sleep(1500)
            screenshot("tablet_map")
            // Откуда — с третьего этажа: маршрут с лестницей.
            device.findObject(By.text("Откуда")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Откуда идёшь?")), 5000))
            device.findObject(By.desc("3 этаж"))?.click()
            SystemClock.sleep(1500)
            screenshot("tablet_map_floor3")
        } finally {
            sc.close()
        }
    }

    @Test
    fun syncSourceRoundTrip() = runBlocking {
        val app = ctx.app
        val src = AppSyncSource(app)
        val card = CardEntity(noteId = null, subject = "Матан", topic = "Пределы", front = "lim sin x / x", back = "1", dueAt = 5, createdAt = 1)
        app.db.cards().insertAll(listOf(card))
        app.db.reminders().put(ReminderEntity(uid = "rem-sync-test", title = "Зачётка", at = 42))
        val (nb, page) = app.notebooks.store.create("Синхронная", Paper.GRID)
        app.notebooks.store.savePage(nb.id, page)

        val items = src.export()
        val c = items.single { it.kind == "card" && it.id == card.uid }
        val r = items.single { it.kind == "rem" && it.id == "rem-sync-test" }
        val b = items.single { it.kind == "book" && it.id == nb.id.toString() }
        assertTrue("файл страницы не попал в синхронизацию", b.files.keys.any { it.endsWith(".ink") })

        // Удаляем и принимаем обратно, как будто пришло с другого устройства.
        val copies = b.files.mapValues { (name, f) -> File(ctx.cacheDir, "sync-$name").also { f.copyTo(it, overwrite = true) } }
        src.delete("card", card.uid); src.delete("rem", "rem-sync-test"); src.delete("book", nb.id.toString())
        assertNull(app.db.cards().byUid(card.uid))
        assertNull(app.notebooks.store.meta(nb.id))
        fun rec(i: LocalItem) = SyncRecord(i.kind, i.id, 1, data = i.data, hash = SyncEngine.recordHash(i.data, emptyMap()))
        src.apply(rec(c), emptyMap())
        src.apply(rec(r), emptyMap())
        src.apply(rec(b), copies)
        assertEquals("lim sin x / x", app.db.cards().byUid(card.uid)!!.front)
        assertEquals("Зачётка", app.db.reminders().get("rem-sync-test")!!.title)
        val back = app.notebooks.store.meta(nb.id)!!
        assertEquals("Синхронная", back.title)
        assertEquals(1, app.notebooks.store.loadPages(back).size)
        // Вторая выгрузка — то же самое содержимое.
        assertEquals(c.data, src.export().single { it.kind == "card" && it.id == card.uid }.data)
        app.db.cards().deleteUid(card.uid); app.db.reminders().delete("rem-sync-test"); app.notebooks.store.delete(nb.id)
    }
}

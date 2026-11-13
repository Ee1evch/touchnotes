package app.nastavnik

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.UiAutomation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nastavnik.data.CardEntity
import app.nastavnik.data.ReminderEntity
import app.nastavnik.ink.Paper
import app.nastavnik.schedule.DayTimeline
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.schedule.Week
import app.nastavnik.sync.AppSyncSource
import app.nastavnik.sync.LocalItem
import app.nastavnik.sync.SyncRecord
import app.nastavnik.sync.SyncEngine
import app.nastavnik.study.Learning
import app.nastavnik.study.HomeworkRules
import app.nastavnik.study.Homeworks
import app.nastavnik.study.LiveNotifications
import app.nastavnik.study.PageProblem
import app.nastavnik.study.Textbook
import app.nastavnik.ui.Brand
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Суперприложение на эмуляторе: телефонный режим (сегодня, расписание, успеваемость, живое уведомление,
 * напоминания) и перенос данных синхронизацией (выгрузка → удаление → приём обратно).
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
    fun phoneTodayScheduleAndGrades() {
        ctx.app.settings.setDeviceRole("phone")
        seedSchedule()
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

            // Вместо карты — успеваемость.
            device.findObject(By.desc("Оценки")).click()
            assertNotNull("нет вкладки успеваемости", device.wait(Until.findObject(By.text("Успеваемость")), 8000))
            SystemClock.sleep(900)
            screenshot("phone_grades")

            // Живое уведомление на весь день: идёт физика (пара 2 из 2), полоса — весь день.
            runBlocking { LiveNotifications.updateLesson(ctx) }
            val live = ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 7702 }
            assertNotNull("нет живого уведомления", live)
            assertEquals("Физика", live!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            val liveSub = live.notification.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            assertTrue("подпись: $liveSub", liveSub.endsWith("пара 2 из 2"))
            device.openNotification()
            SystemClock.sleep(1500)
            screenshot("live_notification")
            // Закрыть шторку наверняка: «Назад» и жест на планшете её не всегда закрывают.
            device.executeShellCommand("cmd statusbar collapse")
            assertNotNull("шторка не закрылась", device.wait(Until.findObject(By.desc("Учёба")), 8000))
            SystemClock.sleep(500)

        } finally {
            // Открытая шторка не должна ломать следующие тесты.
            device.executeShellCommand("cmd statusbar collapse")
            sc.close()
        }
    }

    /**
     * Живое уведомление: на паре — предмет, тип, аудитория, преподаватель, отсчёт до конца; на перемене — крупно
     * «ПЕРЕМЕНА» и что дальше. Системный шаблон с просьбой о «живом» (без своих макетов) — снимок каждого.
     */
    @Test
    fun liveNotificationLessonAndBreak() {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        fun live() = nm.activeNotifications.firstOrNull { it.id == LiveNotifications.LESSON_ID }?.notification
        fun shot(name: String, title: String) {
            // Шторка после обновления уведомления иногда закрывается сама — открываем, пока заголовок не виден.
            var seen = false
            repeat(3) {
                if (!seen) {
                    device.openNotification()
                    SystemClock.sleep(1000)
                    seen = device.wait(Until.findObject(By.text(title)), 4000) != null
                }
            }
            assertTrue("нет «$title» в шторке", seen)
            // Развернуть уведомление, чтобы на снимке были все строки.
            device.findObject(By.text(title))?.parent?.parent?.findObject(By.res("android:id/expand_button"))?.click()
            SystemClock.sleep(800)
            screenshot(name)
            device.executeShellCommand("cmd statusbar collapse")
            SystemClock.sleep(600)
        }
        try {
            nm.cancel(LiveNotifications.LESSON_ID)
            runBlocking { LiveNotifications.preview(ctx) }
            SystemClock.sleep(600)
            val n = live()
            assertNotNull("нет живого уведомления", n)
            val x = n!!.extras
            assertEquals("Физика", x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            assertEquals("Практика · Альфа 5.8 · Ваганов М. Г.", x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
            assertTrue("нет просьбы о «живом»", x.getBoolean(LiveNotifications.EXTRA_PROMOTE))
            @Suppress("DEPRECATION")
            assertTrue("свой макет — система не сделает «живым»", n.contentView == null && n.bigContentView == null)
            assertTrue("нет отсчёта", x.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) && x.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            shot("live_lesson", "Физика")

            // Перемена: физика кончилась 5 минут назад, линал через 10.
            val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
            fun hm(t: java.time.LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)
            val today = now.toLocalDate().toString()
            val day = listOf(
                Lesson(id = "b1", date = today, start = hm(now.minusMinutes(95)), end = hm(now.minusMinutes(5)), discipline = "Физика", type = "Практика", classroom = "Альфа 5.8"),
                Lesson(id = "b2", date = today, start = hm(now.plusMinutes(10)), end = hm(now.plusMinutes(100)), discipline = "Линейная алгебра", type = "Семинар", classroom = "Гамма 2.1"),
            )
            val st = DayTimeline.at(day, now)!!
            nm.notify(LiveNotifications.LESSON_ID, LiveNotifications.build(ctx, st, now))
            SystemClock.sleep(600)
            val br = live()!!.extras
            assertEquals("ПЕРЕМЕНА", br.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            assertEquals("Дальше в ${hm(now.plusMinutes(10))} — Линейная алгебра · Гамма 2.1", br.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
            shot("live_break", "ПЕРЕМЕНА")
        } finally {
            nm.cancel(LiveNotifications.LESSON_ID)
            device.executeShellCommand("cmd statusbar collapse")
        }
    }

    /**
     * Домашка без нейросети: учебник с уже просмотренной страницей (задачи 245 и 246), ДЗ по номерам — условия
     * находятся, экран «Домашка», «Решать» — страница в тетради с условием сверху и плашкой проверки;
     * незасчитанное ДЗ на завтра держит вечернюю учёбу, засчитанное на 100% (одна спорная можно) — отпускает.
     */
    @Test
    fun homeworkFromTextbookToNotebook() {
        val pdf = File(ctx.cacheDir, "zadachnik.pdf")
        android.graphics.pdf.PdfDocument().apply {
            val page = startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val p = android.graphics.Paint().apply { textSize = 16f; color = android.graphics.Color.BLACK }
            page.canvas.drawText("245. Найти предел lim (sin 3x)/x при x -> 0.", 50f, 120f, p)
            page.canvas.drawText("246. Доказать, что последовательность 1/n сходится.", 50f, 300f, p)
            finishPage(page)
            pdf.outputStream().use { writeTo(it) }
            close()
        }
        val book = runBlocking { Homeworks.importBook(ctx, android.net.Uri.fromFile(pdf), "Математический анализ") }.getOrThrow()
        // Страница уже «просмотрена» — нейросеть не нужна.
        File(ctx.filesDir, "textbooks/${book.id}.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                Textbook.serializer(),
                book.copy(index = mapOf(0 to listOf(PageProblem("245", 0.1f, 0.3f), PageProblem("246", 0.32f, 0.5f)))),
            ),
        )
        val tomorrow = LocalDate.now().plusDays(1)
        val hw = Homeworks.fromBook(ctx, Homeworks.book(ctx, book.id)!!, HomeworkRules.parseNumbers("245–246"), tomorrow)
        assertTrue(runBlocking { Homeworks.locate(ctx, hw.id) }.isSuccess)
        val located = Homeworks.get(ctx, hw.id)!!
        assertTrue("условия не нашлись", located.tasks.all { it.parts.isNotEmpty() })
        assertEquals(0.32f, located.tasks.first().parts.first().bottom, 0.001f)

        ctx.app.settings.setEditorTipsShown()
        val sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOMEWORK))
        try {
            assertNotNull("нет экрана «Домашка»", device.wait(Until.findObject(By.text("Домашка")), 10000))
            assertNotNull("нет задачи", device.wait(Until.findObject(By.text("№ 245")), 8000))
            SystemClock.sleep(800)
            screenshot("homework_list")
            device.findObject(By.text("Решать")).click()
            val bar = device.wait(Until.findObject(By.textStartsWith("ДЗ · Математический анализ · № 245")), 15000)
            if (bar == null) {
                val t = Homeworks.get(ctx, hw.id)!!.tasks.first()
                println("HWDIAG task nb=${t.notebookId} page=${t.pageId} pages=${ctx.app.notebooks.store.meta(t.notebookId)?.pages}")
                screenshot("homework_page_missing")
            }
            assertNotNull("нет плашки ДЗ в тетради", bar)
            assertNotNull("нет кнопки проверки", device.findObject(By.text("Готово — проверь")))
            SystemClock.sleep(1200)
            screenshot("homework_page")
            val task = Homeworks.get(ctx, hw.id)!!.tasks.first()
            assertTrue("страница не создана", task.pageId != 0L && ctx.app.notebooks.store.meta(task.notebookId)?.pages?.contains(task.pageId) == true)
        } finally {
            sc.close()
        }
        // Засчитано: одна задача верно, вторая — спорная (после двух непринятых проверок) → 100%.
        val cur = Homeworks.get(ctx, hw.id)!!
        Homeworks.save(ctx, cur.copy(tasks = listOf(cur.tasks[0].copy(status = HomeworkRules.Status.DONE), cur.tasks[1].copy(status = HomeworkRules.Status.WRONG, failed = 2))))
        assertTrue("спорную не дали", Homeworks.dispute(ctx, hw.id, cur.tasks[1].id))
        assertTrue(Homeworks.get(ctx, hw.id)!!.complete)
        // Убрать за собой всё, включая тетрадь ДЗ, — следующие тесты берут «первую тетрадь».
        val nbId = Homeworks.get(ctx, hw.id)!!.tasks.first().notebookId
        Homeworks.delete(ctx, Homeworks.get(ctx, hw.id)!!)
        Homeworks.deleteBook(ctx, Homeworks.book(ctx, book.id)!!)
        if (nbId != 0L) ctx.app.notebooks.store.delete(nbId)
        File(ctx.filesDir, "homework/notebooks.json").delete()
    }

    /** Конструктор главной: правка, убрать блок, вернуть, сменить размер — раскладка сохраняется. */
    @Test
    fun homeBuilderEditsLayout() {
        ctx.app.settings.setDeviceRole("phone")
        ctx.app.settings.setHomeLayout(false, "")
        seedSchedule()
        val sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOME))
        try {
            device.wait(Until.findObject(By.desc("Изменить главную")), 10000)!!.click()
            assertNotNull("нет режима правки", device.wait(Until.findObject(By.text("Добавить блок")), 8000))
            SystemClock.sleep(800)
            screenshot("home_edit")
            // Убрать первый блок — он появляется в «Добавить».
            device.findObject(By.desc("Убрать")).click()
            val addBtn = device.wait(Until.findObject(By.textStartsWith("+ ")), 5000)
            assertNotNull("нет кнопки добавления блока", addBtn)
            addBtn.click()
            SystemClock.sleep(500)
            // Размер первого блока: L → (M или S, по кругу) — сохраняется.
            val before = ctx.app.settings.homeLayout(false)
            device.findObject(By.descStartsWith("Размер")).click()
            SystemClock.sleep(500)
            assertTrue("размер не поменялся", ctx.app.settings.homeLayout(false) != before)
            device.findObject(By.text("Готово")).click()
            assertTrue(device.wait(Until.gone(By.text("Добавить блок")), 5000))
            SystemClock.sleep(800)
            screenshot("home_after_edit")
        } finally {
            ctx.app.settings.setHomeLayout(false, "")
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

        // Оценки: здесь входа в ЛКС нет — журнал с телефона принимается и показывается, обратно не выгружается.
        assertTrue("без входа журнал не выгружается", src.export().none { it.kind == "grades" })
        val journal = """{"lessons":[{"title":"Физика","avgMark":"4.5","entries":[{"date":"2026-11-02","marks":[{"value":"5"}]}]}],"loadedAt":7}"""
        src.apply(SyncRecord("grades", "lks", 1, data = kotlinx.serialization.json.Json.parseToJsonElement(journal)), emptyMap())
        assertTrue("журнал с телефона не принят", app.grades.synced && app.grades.configured && app.grades.viaLks)
        assertEquals("Физика", app.grades.journal.value!!.lessons.single().title)
        assertTrue("копию не выгружаем обратно", src.export().none { it.kind == "grades" })
        src.delete("grades", "lks")
        assertTrue("копия не удалилась", !app.grades.synced && app.grades.journal.value == null)
    }
}

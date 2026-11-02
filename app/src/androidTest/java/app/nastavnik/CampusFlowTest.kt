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
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.schedule.Week
import app.nastavnik.sync.AppSyncSource
import app.nastavnik.sync.LocalItem
import app.nastavnik.sync.SyncRecord
import app.nastavnik.sync.SyncEngine
import app.nastavnik.study.AlarmShield
import app.nastavnik.study.Learning
import app.nastavnik.study.LiveNotifications
import app.nastavnik.study.LockPrefs
import app.nastavnik.study.Siren
import app.nastavnik.study.SirenPrefs
import app.nastavnik.study.SirenService
import app.nastavnik.study.SleepPlan
import app.nastavnik.study.StudyLock
import app.nastavnik.study.StudyLockRules
import app.nastavnik.study.WakeGuard
import app.nastavnik.study.WakeAlarm
import app.nastavnik.study.WakePrefs
import app.nastavnik.study.WakeService
import app.nastavnik.ui.Brand
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.SirenActivity
import app.nastavnik.ui.WakeActivity
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
    fun phoneTodayScheduleGradesAndReminders() {
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

            // Вместо карты — успеваемость.
            device.findObject(By.desc("Оценки")).click()
            assertNotNull("нет вкладки успеваемости", device.wait(Until.findObject(By.text("Успеваемость")), 8000))
            SystemClock.sleep(900)
            screenshot("phone_grades")

            // Живое уведомление на весь день: идёт физика (пара 2 из 2), полоса — весь день.
            runBlocking { LiveNotifications.updateLesson(ctx) }
            val live = ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 7702 }
            assertNotNull("нет живого уведомления", live)
            assertEquals("Физика · Альфа 5.8", live!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            assertEquals("Пара 2 из 2", live.notification.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString())
            device.openNotification()
            SystemClock.sleep(1500)
            screenshot("live_notification")
            device.pressBack()
            SystemClock.sleep(500)

            // Напоминание через «Учёба» → «Напоминания».
            device.findObject(By.desc("Учёба")).click()
            // «Напоминания» есть и на «Сегодня» — жмём строку вкладки «Учёба» по её подписи.
            device.wait(Until.findObject(By.text("Дедлайны и дела на всех устройствах")), 8000)!!.click()
            device.wait(Until.findObject(By.text("Новое")), 8000)!!.click()
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000)
            field.text = "Сдать лабу по физике"
            device.findObject(By.text("Завтра в 9:00"))?.click()
            device.findObject(By.text("Сохранить")).click()
            assertNotNull(device.wait(Until.findObject(By.text("Сдать лабу по физике")), 8000))
            SystemClock.sleep(800)
            screenshot("phone_reminders")
            val saved = runBlocking { ctx.app.db.reminders().all() }
            assertEquals("Сдать лабу по физике", saved.single().title)
            assertTrue(saved.single().at > System.currentTimeMillis())
        } finally {
            sc.close()
        }
    }

    /** Будильник: время от первой пары завтра (10:15 − 45 сборы − 15 дорога), «Ложусь» — к концу цикла, экран звонка. */
    @Test
    fun wakeAlarmPlansAndRings() {
        seedSchedule()
        val st = WakePrefs.of(ctx)
        val tomorrow = LocalDate.now().plusDays(1)
        try {
            st.on = true
            st.bedAt = 0
            val plan = runBlocking { WakeAlarm.plan(ctx) }
            assertNotNull("нет будильника", plan)
            assertEquals(tomorrow.atTime(9, 15), plan!!.wake)
            assertEquals(tomorrow.atTime(10, 15), plan.lessonAt)
            // 9:15 − 5 циклов по 90 мин − 15 мин на засыпание = 1:30.
            assertEquals(tomorrow.atTime(1, 30), plan.bedtime)
            // Лёг в 23:30 → уснул 23:45 → конец 6-го цикла 8:45 — в пределах 30 минут до 9:15.
            st.bedAt = WakeAlarm.ms(tomorrow.minusDays(1).atTime(23, 30))
            assertEquals(tomorrow.atTime(8, 45), runBlocking { WakeAlarm.plan(ctx) }!!.wake)
            st.bedAt = 0
            // Ставим будильник — экран звонка берёт пару из запомненного.
            runBlocking { WakeAlarm.reschedule(ctx) }
            assertEquals(WakeAlarm.ms(tomorrow.atTime(10, 15)), st.lessonAt)

            // Тёмная тема: будильник не должен перекрашивать приложение в светлое (был такой баг).
            ctx.app.settings.setThemeMode("DARK")
            val sc = ActivityScenario.launch<WakeActivity>(Intent(ctx, WakeActivity::class.java).putExtra(WakeAlarm.EXTRA_TEST, true))
            try {
                assertNotNull(device.wait(Until.findObject(By.text("Проверка будильника")), 10000))
                assertNotNull(device.wait(Until.findObject(By.textContains("Первая пара в 10:15")), 5000))
                assertNotNull(device.findObject(By.text("Реши, чтобы выключить")))
                SystemClock.sleep(800)
                screenshot("wake_ring")
                assertTrue("будильник перекрасил тему в светлую", Brand.dark)
                // Клавиатура не закрывает поле ответа — поле поднимается над ней.
                device.findObject(By.clazz("android.widget.EditText")).click()
                SystemClock.sleep(1500)
                val ua = instr.uiAutomation
                ua.serviceInfo = ua.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
                val ime = ua.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                screenshot("wake_keyboard")
                if (ime != null) {
                    val r = android.graphics.Rect().also { ime.getBoundsInScreen(it) }
                    val f = device.findObject(By.clazz("android.widget.EditText")).visibleBounds
                    assertTrue("клавиатура перекрывает поле ответа: поле $f, клавиатура $r", f.bottom <= r.top)
                }
                device.pressBack()
                device.findObject(By.text("Выключить проверку")).click()
                SystemClock.sleep(800)
            } finally {
                sc.close()
            }
        } finally {
            ctx.app.settings.setThemeMode("LIGHT")
            st.on = false
            st.bedAt = 0
            runBlocking { WakeAlarm.reschedule(ctx) }
        }
    }

    /** Вечерняя учёба: конспекты сегодняшних пар становятся карточками, подготовка к завтрашней паре. */
    @Test
    fun eveningSessionSummariesAndPrep() {
        seedSchedule()
        val today = LocalDate.now()
        val sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_EVENING))
        try {
            assertNotNull(device.wait(Until.findObject(By.text("Карточки на сегодня")), 15000))
            assertNotNull(device.findObject(By.text("Физика")))
            SystemClock.sleep(800)
            screenshot("evening_start")
            // До 5 утра «день» ещё вчерашний — тогда проверять нечего.
            if (runBlocking { StudyLock.status(ctx).date } != today) return
            runBlocking {
                StudyLock.saveSummary(ctx, today, "Математический анализ", "Пределы: замечательный предел sin x / x стремится к 1, разбирали эквивалентности и примеры.")
                StudyLock.saveSummary(ctx, today, "Физика", "Законы Ньютона: второй закон F = ma, разбирали задачи на наклонную плоскость и силу трения.")
                val st = StudyLock.status(ctx)
                assertTrue("конспекты не засчитаны: ${st.summaries}", st.summariesDone)
                // Английский — не основной предмет: ни подготовки, ни карточек.
                assertTrue("английский в подготовке", st.prep.none { it.first == "Иностранный язык" })
                assertTrue(StudyLock.status(ctx).prepDone)
                val physics = ctx.app.db.cards().all().filter { it.subject == "Физика" }
                assertTrue(physics.any { it.front.contains("Вспомни главное") })
                // Из конспекта — ещё и карточки «заполни пропуск».
                assertTrue("мало карточек из конспекта: ${physics.size}", physics.count { it.front.startsWith("Заполни пропуск") } >= 2)
            }
        } finally {
            sc.close()
        }
    }

    /**
     * Учебный замок по-настоящему: включаем службу защиты, замок закрывает системные настройки и открывает
     * вечернюю учёбу; «Наставник» не мигает (6 секунд остаётся на экране); рабочий стол открыт.
     */
    @Test
    fun studyLockClosesAppsWithoutFlicker() {
        val lp = LockPrefs.of(ctx)
        // Без пар замок начинается в выбранное время — 00:00, то есть уже идёт. Одна карточка ждёт повторения.
        ctx.app.settings.setScheduleGroup("")
        runBlocking { ctx.app.db.cards().insertAll(listOf(CardEntity(noteId = null, subject = "Замок", topic = "Замок", front = "2 + 2", back = "4", dueAt = 0, createdAt = 0))) }
        lp.configureOnce(0, emptySet(), LocalDate.now())
        // UiAutomator сам — служба спец. возможностей и по умолчанию глушит остальные, в том числе нашу защиту.
        // На телефоне его нет; в тесте просим не глушить.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val guard = "${ctx.packageName}/${WakeGuard::class.java.name}"
        device.executeShellCommand("settings put secure enabled_accessibility_services $guard")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        try {
            assertTrue("замок не закрыт", runBlocking { StudyLock.status(ctx).locked })
            SystemClock.sleep(3000)
            // Рабочий стол — настоящий лаунчер, а не запасной «рабочий стол» системных настроек.
            assertTrue("настройки считаются рабочим столом", StudyLock.homePackages(ctx).none { "settings" in it })
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val blocked = device.wait(Until.findObject(By.text("Карточки на сегодня")), 15000)
            if (blocked == null) {
                println("LOCKDIAG pkg=${device.currentPackageName} guard=${WakeGuard.enabled(ctx)} locked=${StudyLock.locked}")
                screenshot("lock_not_blocked")
            }
            assertNotNull("настройки не закрылись", blocked)
            screenshot("lock_blocked")
            // Без мигания: «Наставник» остаётся на экране.
            repeat(6) {
                SystemClock.sleep(1000)
                assertEquals("мигает: на экране ${device.currentPackageName}", ctx.packageName, device.currentPackageName)
            }
            // Рабочий стол открыт: после «Домой» на нём и остаёмся.
            device.pressHome()
            SystemClock.sleep(4000)
            val home = device.currentPackageName
            if (home == ctx.packageName || home == "com.android.settings") screenshot("lock_home_fail")
            assertTrue("рабочий стол закрыт: $home", home != ctx.packageName && home != "com.android.settings")
            screenshot("lock_home")

            // Разрешение «будильник на заблокированном экране», открытое из «Наставника», — не закрывается даже в замок.
            val pkgUri = android.net.Uri.parse("package:${ctx.packageName}")
            StudyLock.openSettings(ctx, Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", pkgUri))
            SystemClock.sleep(5000)
            screenshot("lock_permission_page")
            assertEquals("страницу разрешения закрыло", "com.android.settings", device.currentPackageName)
            // А «Сведения о приложении» с «Удалить» и «Остановить» — закрываются.
            StudyLock.openSettings(ctx, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            SystemClock.sleep(5000)
            screenshot("lock_app_info")
            assertTrue("сведения о приложении не закрылись", device.currentPackageName != "com.android.settings")
        } finally {
            device.executeShellCommand("settings delete secure enabled_accessibility_services")
            Configurator.getInstance().uiAutomationFlags = 0
            lp.markDone(StudyLockRules.sessionDate(java.time.LocalDateTime.now()))
            runBlocking {
                ctx.app.db.cards().all().filter { it.subject == "Замок" }.forEach { ctx.app.db.cards().update(it.copy(dueAt = Long.MAX_VALUE / 2)) }
                StudyLock.status(ctx)
            }
        }
    }

    /** Пока звонит будильник, меню выключения закрывается само — настоящая служба защиты, как на телефоне. */
    @Test
    fun alarmClosesPowerMenu() {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val ua = instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device.executeShellCommand("settings put secure enabled_accessibility_services ${ctx.packageName}/${WakeGuard::class.java.name}")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        try {
            SystemClock.sleep(3000)
            WakeAlarm.startRinging(ctx, test = true)
            SystemClock.sleep(2500)
            assertTrue("защита будильника не включилась", WakePrefs.of(ctx).guarding)
            // Щит поверх всего экрана: только пример и своя клавиатура.
            assertNotNull("нет щита будильника", device.wait(Until.findObject(By.text("Проверка будильника")), 8000))
            assertNotNull(AlarmShield.answer)
            screenshot("alarm_shield")
            // Как долгое нажатие кнопки питания: системное меню «Выключить / Перезапустить» — закрывается, а щит на месте.
            ua.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            SystemClock.sleep(3000)
            screenshot("alarm_power_menu")
            assertNull("меню выключения не закрылось", device.findObject(By.text("Power off")) ?: device.findObject(By.text("Выключить")))
            assertNotNull("щит пропал", device.findObject(By.text("Проверка будильника")))
            // Кнопки громкости не работают, пока звонит.
            val audio = ctx.getSystemService(android.media.AudioManager::class.java)
            val vol = audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            repeat(3) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN); SystemClock.sleep(200) }
            assertTrue("громкость убавилась кнопкой", audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM) >= vol)
            // Выключить можно только решив пример на щите.
            AlarmShield.answer!!.toString().forEach { ch -> device.findObject(By.text(ch.toString())).click(); SystemClock.sleep(150) }
            SystemClock.sleep(1500)
            assertTrue("будильник не выключился решением примера", !WakeService.ringing)
        } finally {
            runBlocking { WakeAlarm.dismiss(ctx, true) }
            device.findObject(By.text("Выключить проверку"))?.click()
            device.executeShellCommand("settings delete secure enabled_accessibility_services")
            Configurator.getInstance().uiAutomationFlags = 0
        }
    }

    /**
     * Сирена: звучит, громкость не убавить (возвращается сама), мини-задача снимает звук на 5 минут,
     * основная задача — ответ вводится и проверяется — даёт тишину до завтра.
     */
    @Test
    fun sirenMiniTaskThenMainTask() {
        val s = SirenPrefs.of(ctx)
        val audio = ctx.getSystemService(android.media.AudioManager::class.java)
        val alarm = android.media.AudioManager.STREAM_ALARM
        runBlocking {
            ctx.app.db.cards().insertAll(
                listOf(CardEntity(noteId = null, subject = "Физика", topic = "Сирена", front = Learning.PROBLEM_PREFIX + "Сколько будет 7 + 5?", back = "\$12\$", dueAt = Long.MAX_VALUE / 2, createdAt = 0)),
            )
        }
        var sc: ActivityScenario<SirenActivity>? = null
        try {
            Siren.forced = true
            Siren.start(ctx)
            repeat(20) { if (!SirenService.ringing) SystemClock.sleep(250) }
            assertTrue("сирена не зазвучала", SirenService.ringing)
            // Громкость не убавить: ставим минимум — через пару секунд снова не ниже 70 %.
            audio.setStreamVolume(alarm, 1, 0)
            SystemClock.sleep(2500)
            val max = audio.getStreamMaxVolume(alarm)
            assertTrue("громкость убавилась: ${audio.getStreamVolume(alarm)} из $max", audio.getStreamVolume(alarm) >= (max * 0.7f).toInt())

            sc = ActivityScenario.launch(Intent(ctx, SirenActivity::class.java))
            assertNotNull(device.wait(Until.findObject(By.text("Мини-задача — 5 минут тишины на основную")), 10000))
            SystemClock.sleep(600)
            screenshot("siren_mini")
            device.findObject(By.clazz("android.widget.EditText")).text = SleepPlan.task(s.miniSeed).second.toString()
            assertNotNull("мини-задача не сняла звук", device.wait(Until.findObject(By.text("Основная задача")), 8000))
            assertTrue(!SirenService.ringing)
            assertNotNull(device.wait(Until.findObject(By.textContains("7 + 5")), 10000))
            SystemClock.sleep(600)
            screenshot("siren_main")
            device.findObject(By.clazz("android.widget.EditText")).text = "12"
            device.findObject(By.text("Проверить")).click()
            assertNotNull("основная задача не засчитана", device.wait(Until.findObject(By.text("Решено. Свободен до завтра")), 8000))
            screenshot("siren_solved")
            assertTrue(s.solved(StudyLockRules.sessionDate(java.time.LocalDateTime.now())) >= 1)
        } finally {
            Siren.forced = false
            ctx.stopService(Intent(ctx, SirenService::class.java))
            sc?.close()
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

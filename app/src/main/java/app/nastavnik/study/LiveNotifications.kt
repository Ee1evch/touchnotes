package app.nastavnik.study

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.DayTimeline
import app.nastavnik.schedule.Lesson
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Живое уведомление учебного дня — системный шаблон, чтобы Android 16 показывал его «живым»: в строке состояния —
 * отсчёт до конца текущего (пары или перемены). Свои макеты система «живыми» не делает.
 *  - на паре: предмет; тип, аудитория, преподаватель; «13:30–14:50 · пара 2 из 4»; полоса пары цветом типа;
 *  - на перемене: крупно «ПЕРЕМЕНА», что дальше и где; полоса перемены;
 *  - до пар: первая пара, во сколько и где.
 * Обновление: «экономно» — только на границах пар; «обычно» — раз в 3 минуты; «точно» — сервис, раз в минуту.
 */
object LiveNotifications {
    const val ACTION_LIVE = "app.nastavnik.LESSON_LIVE"
    const val LESSON_ID = 7702
    private const val LIVE_REQUEST = 7710
    private const val BREAK_COLOR = 0xFF8A93A3.toInt()
    const val EXTRA_PROMOTE = "android.requestPromotedOngoing"

    enum class Mode(val id: String, val label: String, val hint: String) {
        BOUNDARY("boundary", "Экономно", "Только в начале и конце пар — отсчёт идёт сам, полоса двигается редко"),
        INTERVAL("interval", "Обычно", "Раз в 3 минуты"),
        SERVICE("service", "Точно", "Каждую минуту, пока идут пары (работает сервис)"),
    }

    fun mode(context: Context) = Mode.entries.firstOrNull { it.id == context.app.settings.liveMode() } ?: Mode.INTERVAL

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Цвет пары по типу — как в расписании. */
    private fun color(type: String): Int = when (type) {
        "Лекция" -> 0xFF4C7DF0.toInt()
        "Практика" -> 0xFF1FA463.toInt()
        "Семинар" -> 0xFFE08A1E.toInt()
        "Лабораторная" -> 0xFF9B5DE5.toInt()
        else -> 0xFF7C8596.toInt()
    }

    /** Состояние дня, если уведомление сейчас должно быть видно: с «за N минут до первой пары» до конца последней. */
    private fun visible(day: List<Lesson>, alert: Int, now: LocalDateTime): DayTimeline.State? {
        val s = DayTimeline.at(day, now) ?: return null
        if (s.phase == DayTimeline.Phase.AFTER || s.focus == null) return null
        val first = s.parts.firstOrNull { it.lesson != null }?.lesson?.startsAt() ?: return null
        return if (now.isBefore(first.minusMinutes(alert.toLong()))) null else s
    }

    /** Обновить по текущему моменту и поставить следующее обновление. */
    suspend fun updateLesson(context: Context) {
        val app = context.app
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alert = app.settings.lessonAlertMin()
        val now = LocalDateTime.now()
        val day = app.schedule.day(LocalDate.now())
        if (alert <= 0 || !Permissions.notifications(context)) { stop(context, nm); return }
        val s = visible(day, alert, now) ?: run { stop(context, nm); return }
        runCatching { nm.notify(LESSON_ID, build(context, s, now)) }
        val focus = s.focus!!
        val boundary = ms(if (s.phase == DayTimeline.Phase.LESSON) focus.endsAt() ?: focus.startsAt()!!.plusMinutes(90) else focus.startsAt()!!) + 1000
        when (mode(context)) {
            Mode.BOUNDARY -> schedule(context, boundary)
            Mode.INTERVAL -> schedule(context, minOf(ms(now.plusMinutes(3)), boundary))
            Mode.SERVICE -> { schedule(context, boundary); LiveService.start(context) }
        }
    }

    private fun stop(context: Context, nm: NotificationManager) {
        nm.cancel(LESSON_ID)
        LiveService.stop(context)
    }

    private fun open(context: Context) = PendingIntent.getActivity(
        context, 7701,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SCHEDULE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Уведомление по состоянию дня: идущая пара, перемена (крупно) или первая пара. */
    fun build(context: Context, s: DayTimeline.State, now: LocalDateTime): Notification {
        val focus = s.focus!!
        val start = focus.startsAt()!!
        val end = focus.endsAt() ?: start.plusMinutes(90)
        val phase = s.phase
        val target = if (phase == DayTimeline.Phase.LESSON) end else start
        val details = listOfNotNull(focus.type.takeIf { it.isNotBlank() }, focus.classroom?.takeIf { it.isNotBlank() }, focus.teachersShort).joinToString(" · ")
        val times = "${focus.start}–${focus.end.ifBlank { "%02d:%02d".format(end.hour, end.minute) }}"
        val title: String
        val text: String
        val sub: String
        // Полоса: на паре — сколько прошло от пары, на перемене — от перемены, до пар — пустая полоса первой пары.
        val total: Int
        val passed: Int
        val tint: Int
        when (phase) {
            DayTimeline.Phase.LESSON -> {
                title = focus.discipline
                text = details.ifBlank { s.after?.let { "Дальше в ${it.start} — ${it.discipline}" } ?: "Последняя пара" }
                sub = "$times · пара ${s.number} из ${s.count}"
                total = Duration.between(start, end).toMinutes().toInt().coerceAtLeast(1)
                passed = Duration.between(start, now).toMinutes().toInt().coerceIn(0, total)
                tint = color(focus.type)
            }
            DayTimeline.Phase.BREAK -> {
                title = "ПЕРЕМЕНА"
                text = "Дальше в ${focus.start} — ${focus.discipline}" + (focus.classroom?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                sub = "до ${focus.start} · пара ${s.number} из ${s.count}"
                // Перемена — кусок дня прямо перед следующей парой.
                val i = s.parts.indexOfFirst { it.lesson == focus }
                total = s.parts.getOrNull(i - 1)?.takeIf { it.lesson == null }?.minutes?.coerceAtLeast(1) ?: 1
                passed = (total - Duration.between(now, start).toMinutes().toInt()).coerceIn(0, total)
                tint = BREAK_COLOR
            }
            else -> {
                title = focus.discipline
                text = "Начало в ${focus.start}" + (details.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                sub = "Сегодня ${s.count} ${ruPlural(s.count, "пара", "пары", "пар")}"
                total = Duration.between(start, end).toMinutes().toInt().coerceAtLeast(1)
                passed = 0
                tint = color(focus.type)
            }
        }
        val b = Notification.Builder(context, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(tint)
            .setContentIntent(open(context))
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Отсчёт до конца пары или перемены — он же в строке состояния у «живого» уведомления.
            .setWhen(ms(target))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(total).setColor(tint)))
                    .setProgress(passed)
                    .setStyledByProgress(true),
            )
        } else {
            b.setProgress(total, passed, false)
        }
        // Просьба показать «живым» (Android 16+): системный шаблон, без своих макетов.
        b.extras.putBoolean(EXTRA_PROMOTE, true)
        return b.build()
    }

    /** «Показать сейчас»: пример (сейчас идёт физика, началась 25 минут назад) на 2 минуты. */
    suspend fun preview(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        fun lesson(id: String, from: Long, to: Long, d: String, type: String, room: String, teacher: String) = Lesson(
            id = id, date = now.toLocalDate().toString(),
            start = "%02d:%02d".format(now.plusMinutes(from).hour, now.plusMinutes(from).minute),
            end = "%02d:%02d".format(now.plusMinutes(to).hour, now.plusMinutes(to).minute),
            discipline = d, type = type, classroom = room, teachers = listOf(teacher),
        )
        val day = listOf(
            lesson("p0", -125, -35, "Математический анализ", "Лекция", "Бета 3.3", "Иванов Пётр Сергеевич"),
            lesson("p1", -25, 65, "Физика", "Практика", "Альфа 5.8", "Ваганов Михаил Георгиевич"),
            lesson("p2", 75, 165, "Линейная алгебра", "Семинар", "Гамма 2.1", "Петрова Анна Игоревна"),
        )
        val s = DayTimeline.at(day, now) ?: return
        runCatching { nm.notify(LESSON_ID, Notification.Builder.recoverBuilder(context, build(context, s, now)).setTimeoutAfter(120_000L).build()) }
    }

    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, LIVE_REQUEST, Intent(context, AlarmReceiver::class.java).setAction(ACTION_LIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Точное срабатывание (для запуска сервиса режима «точно»); на старых версиях — окно.
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000L, pi)
        }
    }
}

/**
 * Режим «точно»: пока идут пары, сервис раз в минуту обновляет живое уведомление (оно же — уведомление сервиса).
 * После последней пары останавливается сам.
 */
class LiveService : Service() {
    companion object {
        @Volatile var running = false
            private set

        fun start(context: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveService::class.java)) }
        }

        fun stop(context: Context) {
            if (running) runCatching { context.stopService(Intent(context, LiveService::class.java)) }
        }
    }

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val placeholder = Notification.Builder(this, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_calendar).setContentTitle("Учебный день").setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(LiveNotifications.LESSON_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(LiveNotifications.LESSON_ID, placeholder)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            job = CoroutineScope(Dispatchers.IO).launch {
                while (isActive && running) {
                    runCatching { LiveNotifications.updateLesson(this@LiveService) }
                    delay(60_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        super.onDestroy()
    }
}

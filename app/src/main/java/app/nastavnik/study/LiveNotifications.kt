package app.nastavnik.study

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
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
 * Живое уведомление учебного дня — карточка пары (свой макет `live_lesson.xml`, свёрнутое — `live_lesson_small.xml`):
 * слева начало и конец, цветная полоса по типу пары, справа предмет, тип, аудитория в «таблетке», преподаватель;
 * внизу — полоса пары (сколько прошло; до пары и на перемене — пустая). В шапке — «Пара 2 из 4» и отсчёт до конца
 * пары (до пары и на перемене — до начала следующей).
 * Обновление: «экономно» — только на границах пар; «обычно» — раз в 3 минуты; «точно» — сервис, раз в минуту.
 */
object LiveNotifications {
    const val ACTION_LIVE = "app.nastavnik.LESSON_LIVE"
    const val LESSON_ID = 7702
    private const val LIVE_REQUEST = 7710
    private const val ACCENT = 0xFF2563EB.toInt()

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

    private fun where(l: Lesson) = listOfNotNull(l.classroom, l.type.takeIf { it.isNotBlank() }).joinToString(" · ")

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

    /** Уведомление по состоянию дня: карточка идущей пары, а до пар и на перемене — следующей. */
    fun build(context: Context, s: DayTimeline.State, now: LocalDateTime): Notification {
        val focus = s.focus!!
        val start = focus.startsAt()!!
        val end = focus.endsAt() ?: start.plusMinutes(90)
        val lesson = s.phase == DayTimeline.Phase.LESSON
        val target = if (lesson) end else start
        val title = when (s.phase) {
            DayTimeline.Phase.BEFORE -> "${focus.discipline} в ${focus.start}"
            DayTimeline.Phase.LESSON -> listOfNotNull(focus.discipline, focus.classroom).joinToString(" · ")
            else -> "Перемена · дальше ${focus.discipline}"
        }
        val sub = when (s.phase) {
            DayTimeline.Phase.BEFORE -> "Сегодня ${s.count} ${ruPlural(s.count, "пара", "пары", "пар")}"
            DayTimeline.Phase.LESSON -> "Пара ${s.number} из ${s.count}"
            else -> "Перемена · пара ${s.number} из ${s.count}"
        }
        val text = when (s.phase) {
            DayTimeline.Phase.LESSON -> s.after?.let { "Дальше в ${it.start} — ${it.discipline}" } ?: "Последняя пара"
            else -> where(focus).ifBlank { "в ${focus.start}" }
        }
        val total = Duration.between(start, end).toMinutes().toInt().coerceAtLeast(1)
        val passed = if (lesson) Duration.between(start, now).toMinutes().toInt().coerceIn(0, total) else 0
        val tint = color(focus.type)
        val place = focus.classroom?.takeIf { it.isNotBlank() } ?: if (!focus.online.isNullOrBlank()) "Онлайн" else null
        fun card(layout: Int, withTeacher: Boolean) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.live_start, focus.start)
            setTextViewText(R.id.live_end, focus.end.ifBlank { "%02d:%02d".format(end.hour, end.minute) })
            setInt(R.id.live_stripe, "setColorFilter", tint)
            setTextViewText(R.id.live_title, focus.discipline)
            if (focus.type.isBlank()) setViewVisibility(R.id.live_type, View.GONE)
            else { setTextViewText(R.id.live_type, focus.type); setTextColor(R.id.live_type, tint) }
            if (place == null) setViewVisibility(R.id.live_place, View.GONE) else setTextViewText(R.id.live_room, place)
            if (withTeacher) {
                val teacher = focus.teachersShort
                if (teacher == null) setViewVisibility(R.id.live_teacher, View.GONE) else setTextViewText(R.id.live_teacher, teacher)
            }
            setProgressBar(R.id.live_progress, total, passed, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setColorStateList(R.id.live_progress, "setProgressTintList", ColorStateList.valueOf(tint))
        }
        return Notification.Builder(context, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(tint)
            .setContentIntent(open(context))
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(ms(target))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            // Текст — для экрана блокировки, часов и озвучки; в шторке — карточка.
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(card(R.layout.live_lesson_small, withTeacher = false))
            .setCustomBigContentView(card(R.layout.live_lesson, withTeacher = true))
            .build()
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
        // Точно (будильник может запустить сервис режима «точно»); на старых — окно.
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

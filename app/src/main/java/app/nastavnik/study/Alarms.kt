package app.nastavnik.study

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.ReminderEntity
import app.nastavnik.schedule.Lesson
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Будильники: свои напоминания и «через 10 минут пара». Ставятся заново при запуске, после
 * синхронизации, обновления расписания и перезагрузки — так напоминание, созданное на планшете, сработает и на телефоне.
 */
object Alarms {
    const val ACTION_PERSONAL = "app.nastavnik.PERSONAL"
    const val ACTION_LESSON = "app.nastavnik.LESSON"
    const val ACTION_DONE = "app.nastavnik.REMINDER_DONE"
    const val EXTRA_UID = "uid"
    private const val LESSON_REQUEST = 7700

    fun rescheduleAll(context: Context) {
        val app = context.app
        app.appScope.launch(Dispatchers.IO) {
            runCatching { schedulePersonal(context) }
            runCatching { scheduleNextLesson(context) }
        }
    }

    private fun am(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun set(context: Context, at: Long, pi: PendingIntent) {
        val am = am(context) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
    }

    private fun personalIntent(context: Context, uid: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, uid.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_PERSONAL).putExtra(EXTRA_UID, uid),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun schedulePersonal(context: Context) {
        val now = System.currentTimeMillis()
        for (r in context.app.db.reminders().all()) {
            val pi = personalIntent(context, r.uid) ?: continue
            am(context)?.cancel(pi)
            if (r.done) continue
            val at = if (r.at > now) r.at else Repeats.next(r.at, r.repeat, now)
            if (at != null && at > now) set(context, at, pi)
        }
    }

    suspend fun scheduleNextLesson(context: Context) {
        val app = context.app
        val pi = PendingIntent.getBroadcast(
            context, LESSON_REQUEST, Intent(context, AlarmReceiver::class.java).setAction(ACTION_LESSON),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am(context)?.cancel(pi)
        val before = app.settings.lessonAlertMin()
        if (before <= 0 || app.settings.scheduleGroup().isBlank()) return
        val now = LocalDateTime.now()
        var next: Pair<Lesson, LocalDateTime>? = null
        for (d in 0L..6L) {
            for (l in app.schedule.day(LocalDate.now().plusDays(d))) {
                val s = l.startsAt() ?: continue
                if (s.minusMinutes(before.toLong()).isAfter(now) && (next == null || s.isBefore(next.second))) next = l to s
            }
            if (next != null) break
        }
        if (next == null) return
        val at = next.second.minusMinutes(before.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        set(context, at, pi)
    }

    suspend fun onPersonal(context: Context, uid: String) {
        val app = context.app
        val r = app.db.reminders().get(uid) ?: return
        if (r.done) return
        notifyPersonal(context, r)
        // Повторяющееся — переносим на следующий раз.
        val next = Repeats.next(r.at, r.repeat, System.currentTimeMillis())
        if (r.repeat != Repeats.NONE && next != null) app.db.reminders().put(r.copy(at = next))
        schedulePersonal(context)
    }

    suspend fun markDone(context: Context, uid: String) {
        val app = context.app
        val r = app.db.reminders().get(uid) ?: return
        if (r.repeat == Repeats.NONE) app.db.reminders().put(r.copy(done = true))
        context.getSystemService(NotificationManager::class.java)?.cancel(uid.hashCode())
        schedulePersonal(context)
        app.sync.soon()
    }

    private fun notifyPersonal(context: Context, r: ReminderEntity) {
        if (!Permissions.notifications(context)) return
        val open = PendingIntent.getActivity(
            context, r.uid.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_REMINDERS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            context, r.uid.hashCode() + 1,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_UID, r.uid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_PERSONAL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(r.title)
            .setContentText(r.note.ifBlank { Repeats.labels[r.repeat].takeIf { r.repeat != Repeats.NONE } ?: "Напоминание" })
            .setContentIntent(open)
            .addAction(0, if (r.repeat == Repeats.NONE) "Готово" else "Понятно", done)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(r.uid.hashCode(), n) }
    }

    suspend fun onLesson(context: Context) {
        val app = context.app
        val before = app.settings.lessonAlertMin()
        val now = LocalDateTime.now()
        val lesson: Lesson? = app.schedule.day(LocalDate.now())
            .filter { it.startsAt()?.let { s -> !s.isBefore(now) && !s.isAfter(now.plusMinutes(before + 5L)) } == true }
            .minByOrNull { it.start }
        if (lesson != null && Permissions.notifications(context)) {
            val open = PendingIntent.getActivity(
                context, 7701,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, if (lesson.classroom != null) MainActivity.SCREEN_MAP else MainActivity.SCREEN_SCHEDULE)
                    .putExtra(MainActivity.EXTRA_ROOM, lesson.classroom)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val where = listOfNotNull(lesson.classroom, lesson.type.takeIf { it.isNotBlank() }).joinToString(" · ")
            val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_LESSONS)
                .setSmallIcon(R.drawable.ic_calendar)
                .setContentTitle("${lesson.start} — ${lesson.discipline}")
                .setContentText(if (lesson.classroom != null) "$where · нажми, чтобы проложить путь" else where.ifBlank { "Скоро пара" })
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setTimeoutAfter(before * 60_000L + 15 * 60_000L)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(7702, n) }
        }
        scheduleNextLesson(context)
    }
}

/** Будильники напоминаний и пар, кнопка «Готово» в уведомлении. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uid = intent.getStringExtra(Alarms.EXTRA_UID)
                when (intent.action) {
                    Alarms.ACTION_PERSONAL -> if (uid != null) runCatching { Alarms.onPersonal(context, uid) }
                    Alarms.ACTION_DONE -> if (uid != null) runCatching { Alarms.markDone(context, uid) }
                    Alarms.ACTION_LESSON -> runCatching { Alarms.onLesson(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

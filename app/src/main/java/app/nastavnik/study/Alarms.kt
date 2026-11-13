package app.nastavnik.study

import app.nastavnik.BuildConfig
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Планирование живых уведомлений о парах при запуске, синхронизации и перезагрузке.
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
            runCatching { scheduleNextLesson(context) }
            runCatching { LiveNotifications.updateLesson(context) }
            // Лёгкая версия («Наставник · Студент»): только пары, живое уведомление и «Тихо на парах».
        }
    }

    private fun am(context: Context) = context.getSystemService(AlarmManager::class.java)

    /** Отменяем напоминания, созданные предыдущей версией, не удаляя сохранённые данные. */
    fun cancelRemovedReminders(context: Context) {
        HomeworkReminder.cancel(context)
        val manager = am(context) ?: return
        val daily = PendingIntent.getBroadcast(context, 0,
            Intent(context, ReminderReceiver::class.java).setAction("app.nastavnik.REMIND"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(daily)
        context.getSystemService(NotificationManager::class.java)?.cancel(77)
        context.app.appScope.launch(Dispatchers.IO) {
            for (r in context.app.db.reminders().all()) {
                personalIntent(context, r.uid)?.let { manager.cancel(it) }
                context.getSystemService(NotificationManager::class.java)?.cancel(r.uid.hashCode())
            }
        }
    }

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

    /** За N минут до пары: «живое» уведомление с отсчётом (оно само обновится к началу и во время пары). */
    suspend fun onLesson(context: Context) {
        LiveNotifications.updateLesson(context)
        scheduleNextLesson(context)
    }
}

/** Обновляет живое уведомление о паре. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Alarms.ACTION_LESSON -> runCatching { Alarms.onLesson(context) }
                    LiveNotifications.ACTION_LIVE -> runCatching { LiveNotifications.updateLesson(context) }
                    LessonQuiet.ACTION -> runCatching { LessonQuiet.update(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

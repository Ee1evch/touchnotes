package app.nastavnik.study

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Вечернее напоминание о карточках. Приходит, только если есть что повторять и сегодня ещё не занимался —
 * не чаще раза в день. Будильник неточный: Android сам выбирает удобный момент рядом с нужным часом.
 */
object Reminders {
    /** Поставить (или снять) будильник на следующее напоминание по настройкам. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        val hour = context.app.settings.load().reminderHour
        if (hour < 0) return
        val at = nextReminderAt(System.currentTimeMillis(), hour)
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Проверить, нужно ли напомнить, и показать уведомление. */
    suspend fun remindIfNeeded(context: Context) {
        val app = context.app
        val now = System.currentTimeMillis()
        val due = app.db.cards().observeDueCount(now).first()
        if (due == 0 || !Permissions.notifications(context)) return
        val events = app.db.activity().observeAll().first()
        val days = events.map { Streaks.day(it.at) }.toSet()
        val today = Streaks.day(now)
        if (today in days) return
        val streak = Streaks.current(days, today)
        val open = PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "$due ${ruPlural(due, "карточка ждёт", "карточки ждут", "карточек ждут")} повторения"
        val text = if (streak > 0) {
            "Пара минут — и серия $streak ${ruPlural(streak, "день", "дня", "дней")} не прервётся."
        } else {
            "Пара минут сегодня — и материал останется в голове к экзамену."
        }
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    const val ACTION_REMIND = "app.nastavnik.REMIND"
    private const val NOTIFICATION_ID = 77
    private const val WINDOW_MS = 30 * 60 * 1000L
}

/** Будильник напоминания и перезагрузка планшета (будильники после неё нужно ставить заново). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == Reminders.ACTION_REMIND) runCatching { Reminders.remindIfNeeded(context) }
                Reminders.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}

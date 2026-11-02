package app.nastavnik.study

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.study.HomeworkRules.Status
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Напоминания о домашке: в 17:00 — план на сегодня от ИИ (что решить сегодня, что завтра); в 21:00 — настойчиво,
 * если сдавать завтра, а ДЗ не на 100%. Без несделанного ДЗ — тишина.
 */
object HomeworkReminder {
    const val ACTION = "app.nastavnik.HOMEWORK"
    private const val REQUEST = 7760
    private const val NOTIFY = 7761
    private const val PLAN_HOUR = 17
    private const val URGENT_HOUR = 21

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Следующее напоминание: 17:00 или 21:00 — что раньше после [now]. */
    fun next(now: LocalDateTime): LocalDateTime {
        val d = now.toLocalDate()
        return listOf(d.atTime(PLAN_HOUR, 0), d.atTime(URGENT_HOUR, 0), d.plusDays(1).atTime(PLAN_HOUR, 0)).first { it.isAfter(now) }
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context))
        if (Homeworks.list(context).none { !it.complete && !it.dueDate.isBefore(LocalDate.now()) }) return
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, ms(next(LocalDateTime.now())), 10 * 60_000L, pending(context)) }
    }

    suspend fun onFire(context: Context) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val open = Homeworks.list(context).filter { !it.complete && !it.dueDate.isBefore(today) }
        if (open.isNotEmpty() && Permissions.notifications(context)) {
            if (now.hour >= URGENT_HOUR - 1) {
                val tomorrow = open.filter { it.dueDate == today.plusDays(1) }
                if (tomorrow.isNotEmpty()) notify(
                    context,
                    "Завтра сдавать ДЗ — ещё не на 100%",
                    tomorrow.joinToString("\n") { "${it.subject}: решено ${HomeworkRules.done(it.statuses)} из ${it.tasks.size}" },
                )
            } else {
                val left = open.sumOf { hw -> hw.tasks.count { it.status != Status.DONE && it.status != Status.DISPUTED } }
                notify(context, "ДЗ: осталось $left ${ruPlural(left, "задача", "задачи", "задач")}", Homeworks.plan(context, open))
            }
        }
        schedule(context)
    }

    private fun notify(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, REQUEST + 1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOMEWORK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_pen)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIFY, n) }
    }
}

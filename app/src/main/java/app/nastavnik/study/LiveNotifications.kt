package app.nastavnik.study

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * «Живые» уведомления (Live Updates Android 16): закреплены вверху шторки и на экране блокировки,
 * с обратным отсчётом и полосой прогресса. На старых версиях — обычное закреплённое уведомление с таймером.
 */
object LiveNotifications {
    const val ACTION_LIVE = "app.nastavnik.LESSON_LIVE"
    private const val LESSON_ID = 7702
    private const val NAV_ID = 7720
    private const val LIVE_REQUEST = 7710

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun builder(context: Context, channel: String): Notification.Builder =
        Notification.Builder(context, channel).apply {
            setSmallIcon(R.drawable.ic_calendar)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(true)
            // Просьба показать как «живое» уведомление (Android 16+).
            extras.putBoolean("android.requestPromotedOngoing", true)
        }

    /** Пара: перед началом — отсчёт до неё, во время — сколько осталось и полоса. Ставит следующее обновление. */
    suspend fun updateLesson(context: Context) {
        val app = context.app
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val before = app.settings.lessonAlertMin()
        val now = LocalDateTime.now()
        val today = app.schedule.day(LocalDate.now())
        val current = today.firstOrNull { l -> val s = l.startsAt(); val e = l.endsAt(); s != null && e != null && !now.isBefore(s) && now.isBefore(e) }
        val soon = today.filter { it.startsAt()?.let { s -> s.isAfter(now) && !s.isAfter(now.plusMinutes(before.toLong())) } == true }.minByOrNull { it.start }
        val lesson = current ?: soon
        if (lesson == null || before <= 0 || !Permissions.notifications(context)) {
            nm.cancel(LESSON_ID)
            return
        }
        val start = lesson.startsAt()!!
        val end = lesson.endsAt() ?: start.plusMinutes(80)
        val open = PendingIntent.getActivity(
            context, 7701,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, if (lesson.classroom != null) MainActivity.SCREEN_MAP else MainActivity.SCREEN_SCHEDULE)
                .putExtra(MainActivity.EXTRA_ROOM, lesson.classroom)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val where = listOfNotNull(lesson.classroom, lesson.type.takeIf { it.isNotBlank() }).joinToString(" · ")
        val b = builder(context, NastavnikApp.CHANNEL_LESSONS).setContentIntent(open)
        val next: Long
        if (current == null) {
            // До начала: «Физика через 9:41» — таймер считает сам, обновлять не нужно.
            b.setContentTitle(lesson.discipline)
                .setContentText(listOfNotNull("Начало в ${lesson.start}", where.ifBlank { null }).joinToString(" · "))
                .setWhen(ms(start)).setUsesChronometer(true).setChronometerCountDown(true)
                .setTimeoutAfter(Duration.between(now, end).toMillis())
            if (lesson.classroom != null) b.addAction(Notification.Action.Builder(null, "Маршрут", open).build())
            next = ms(start)
        } else {
            val total = Duration.between(start, end).toMinutes().coerceAtLeast(1)
            val passed = Duration.between(start, now).toMinutes().coerceIn(0, total)
            val following = today.filter { it.startsAt()?.isAfter(now) == true }.minByOrNull { it.start }
            b.setContentTitle("${lesson.discipline} · идёт")
                .setContentText(listOfNotNull(where.ifBlank { null }, following?.let { "дальше ${it.start} ${it.discipline}" }).joinToString(" · "))
                .setWhen(ms(end)).setUsesChronometer(true).setChronometerCountDown(true)
                .setTimeoutAfter(Duration.between(now, end).toMillis())
            if (Build.VERSION.SDK_INT >= 36) {
                b.setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(total.toInt()).setColor(Color.parseColor("#4C7DF0"))))
                        .setProgress(passed.toInt()),
                )
            } else {
                b.setProgress(total.toInt(), passed.toInt(), false)
            }
            // Полоса — раз в 5 минут; в конце пары уведомление уйдёт само.
            next = minOf(ms(now.plusMinutes(5)), ms(end) + 1000)
        }
        runCatching { nm.notify(LESSON_ID, b.build()) }
        schedule(context, next)
    }

    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, LIVE_REQUEST, Intent(context, AlarmReceiver::class.java).setAction(ACTION_LIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000L, pi) }
    }

    /** Навигация: текущий шаг в шторке, пока идёшь по маршруту. */
    fun showNavigation(context: Context, title: String, text: String, step: Int, steps: Int) {
        if (!Permissions.notifications(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, 7721,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_MAP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = builder(context, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_route)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(steps.coerceAtLeast(1)).setColor(Color.parseColor("#1FA463"))))
                    .setProgress(step.coerceAtMost(steps)),
            )
        } else {
            b.setProgress(steps.coerceAtLeast(1), step.coerceAtMost(steps), false)
        }
        runCatching { nm.notify(NAV_ID, b.build()) }
    }

    fun hideNavigation(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NAV_ID)
    }
}

package app.nastavnik.study

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.DayTimeline
import app.nastavnik.schedule.Lesson
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * «Живое» уведомление на весь учебный день (Live Updates Android 16): закреплено вверху шторки и на экране
 * блокировки, в строке состояния — отсчёт. Полоса — весь день: каждая пара своим цветом по типу, перемены серые,
 * бегунок — где ты сейчас. До пар — «Физика через 9:41», на паре — сколько осталось и что дальше, на перемене —
 * до какой пары и где. После последней пары исчезает. На старых Android — то же с обычной полосой.
 */
object LiveNotifications {
    const val ACTION_LIVE = "app.nastavnik.LESSON_LIVE"
    private const val LESSON_ID = 7702
    private const val LIVE_REQUEST = 7710
    private const val ACCENT = 0xFF2563EB.toInt()
    private const val BREAK_COLOR = 0xFFB8BEC9.toInt()

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

    /** Обновить уведомление по текущему моменту и поставить следующее обновление. */
    suspend fun updateLesson(context: Context) {
        val app = context.app
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val before = app.settings.lessonAlertMin()
        val now = LocalDateTime.now()
        val s = DayTimeline.at(app.schedule.day(LocalDate.now()), now)
        val first = s?.parts?.firstOrNull { it.lesson != null }?.lesson?.startsAt()
        // Показываем с «за N минут до первой пары» и до конца последней.
        if (s == null || s.phase == DayTimeline.Phase.AFTER || before <= 0 || !Permissions.notifications(context) ||
            (first != null && now.isBefore(first.minusMinutes(before.toLong())))
        ) {
            nm.cancel(LESSON_ID)
            return
        }
        val focus = s.focus ?: run { nm.cancel(LESSON_ID); return }
        val start = focus.startsAt()!!
        val end = focus.endsAt() ?: start.plusMinutes(90)
        val open = PendingIntent.getActivity(
            context, 7701,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SCHEDULE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(context, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(ACCENT)
            .setContentIntent(open)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // Просьба показать как «живое» уведомление (Android 16+).
        b.extras.putBoolean("android.requestPromotedOngoing", true)
        val next = s.after?.let { "Дальше в ${it.start} — ${it.discipline}" } ?: "Последняя пара"
        when (s.phase) {
            DayTimeline.Phase.BEFORE -> b
                .setContentTitle("${focus.discipline} в ${focus.start}")
                .setContentText(where(focus).ifBlank { "Первая пара" })
                .setSubText("Сегодня ${s.count} ${ruPlural(s.count, "пара", "пары", "пар")}")
                .setWhen(ms(start))
            DayTimeline.Phase.LESSON -> b
                .setContentTitle(listOfNotNull(focus.discipline, focus.classroom).joinToString(" · "))
                .setContentText(next)
                .setSubText("Пара ${s.number} из ${s.count}")
                .setWhen(ms(end))
            else -> b
                .setContentTitle("Перемена · дальше ${focus.discipline}")
                .setContentText(listOfNotNull(focus.classroom, "в ${focus.start}").joinToString(" · "))
                .setSubText("Пара ${s.number} из ${s.count}")
                .setWhen(ms(start))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // Весь день полосой: пары цветом по типу, перемены серые, точки — начала пар.
            var at = 0
            val points = mutableListOf<Notification.ProgressStyle.Point>()
            s.parts.forEach { p ->
                if (p.lesson != null && at > 0) points += Notification.ProgressStyle.Point(at).setColor(color(p.lesson.type))
                at += p.minutes
            }
            b.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(s.parts.map { p -> Notification.ProgressStyle.Segment(p.minutes).setColor(p.lesson?.let { color(it.type) } ?: BREAK_COLOR) })
                    .setProgressPoints(points)
                    .setProgress(s.progress)
                    .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_flame).setTint(ACCENT))
                    .setStyledByProgress(true),
            )
        } else {
            b.setProgress(s.total, s.progress, false)
        }
        b.addAction(Notification.Action.Builder(null, "Расписание", open).build())
        runCatching { nm.notify(LESSON_ID, b.build()) }
        // Отсчёт идёт сам; полосу и этап (пара → перемена) обновляем раз в 3 минуты и точно на границах.
        val boundary = if (s.phase == DayTimeline.Phase.LESSON) ms(end) else ms(start)
        schedule(context, minOf(ms(now.plusMinutes(3)), boundary + 1000))
    }

    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, LIVE_REQUEST, Intent(context, AlarmReceiver::class.java).setAction(ACTION_LIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000L, pi) }
    }
}

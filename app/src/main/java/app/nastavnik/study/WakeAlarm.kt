package app.nastavnik.study

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.WakeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Будильник к первой паре.
 *
 * Надёжность важнее всего, поэтому:
 *  - ставится через `setAlarmClock` — это «настоящий» будильник: срабатывает в режиме сна телефона, виден в
 *    строке состояния, система не откладывает его ради батареи;
 *  - звонит сервис на потоке будильника (проходит «Не беспокоить»), громкость сама поднимается до 80 %,
 *    звук нарастает, есть вибрация; экран будильника открывается поверх блокировки;
 *  - выключить можно только решив пример — спящий не решит; отложить — максимум дважды по 5 минут и только
 *    если останется время собраться;
 *  - если не выключил за 10 минут — звонок замолкает и через 3 минуты начинается снова (до начала пары);
 *  - через 3 минуты после «Встаю» — проверка «Ты встал?»; не ответил за 2 минуты — снова звонок;
 *  - после перезагрузки, синхронизации и обновления расписания будильник ставится заново.
 *
 * Сон — циклами (см. [SleepPlan]): вечером напоминание лечь так, чтобы проснуться в конце цикла; кнопка
 * «Ложусь» сдвигает подъём на конец ближайшего цикла (только раньше, не позже крайнего времени).
 */
object WakeAlarm {
    const val ACTION_RING = "app.nastavnik.WAKE_RING"
    const val ACTION_SNOOZE = "app.nastavnik.WAKE_SNOOZE"
    const val ACTION_DISMISS = "app.nastavnik.WAKE_DISMISS"
    const val ACTION_CHECK = "app.nastavnik.WAKE_CHECK"
    const val ACTION_AWAKE = "app.nastavnik.WAKE_AWAKE"
    const val ACTION_BEDTIME = "app.nastavnik.WAKE_BEDTIME"
    const val ACTION_BED = "app.nastavnik.WAKE_BED"
    const val ACTION_SKIP = "app.nastavnik.WAKE_SKIP"
    const val EXTRA_TEST = "test"

    const val NOTIFY_RING = 7720
    const val NOTIFY_CHECK = 7721
    const val NOTIFY_BEDTIME = 7722

    private const val REQ_RING = 7710
    private const val REQ_BEDTIME = 7711
    private const val REQ_CHECK = 7712
    private const val REQ_BACKUP = 7713

    const val SNOOZE_MIN = 5L
    const val MAX_SNOOZES = 2
    const val CHECK_AFTER_MIN = 3L
    const val CHECK_GRACE_MIN = 2L
    /** Сколько звонить без ответа, прежде чем замолчать и начать снова. */
    const val RING_LIMIT_MS = 10 * 60_000L
    const val RING_AGAIN_MIN = 3L

    private val hmFormat = DateTimeFormatter.ofPattern("H:mm")
    fun hm(t: LocalDateTime): String = t.format(hmFormat)

    data class Plan(
        val date: LocalDate,
        /** Когда зазвонит (с учётом циклов и «отложить»). */
        val wake: LocalDateTime,
        /** Позже — уже не успеть. */
        val latest: LocalDateTime,
        val lessonAt: LocalDateTime,
        val lesson: Lesson,
        val bedtime: LocalDateTime,
        val snoozed: Boolean,
    ) {
        val label: String get() = listOfNotNull(lesson.discipline.takeIf { it.isNotBlank() }, lesson.classroom).joinToString(" · ")
    }

    fun ms(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun time(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    /** Ближайший будильник по расписанию (null — выключен или пар на неделе нет). */
    suspend fun plan(context: Context, now: LocalDateTime = LocalDateTime.now()): Plan? {
        val app = context.app
        val st = app.settings
        if (!st.wakeOn() || st.scheduleGroup().isBlank()) return null
        val snoozeUntil = st.wakeSnoozeUntil().takeIf { it > 0 }?.let(::time)
        for (d in 0L..7L) {
            val date = now.toLocalDate().plusDays(d)
            if (date.toString() == st.wakeSkipDay() || date.toString() == st.wakeDoneDay()) continue
            val lessons = app.schedule.day(date).filter { it.startsAt() != null }
            val first = lessons.minByOrNull { it.startsAt()!! } ?: continue
            val start = first.startsAt()!!
            val travel = if (!first.online.isNullOrBlank()) 0 else st.wakeTravelMin()
            val latest = SleepPlan.latestWake(start, st.wakeReadyMin(), travel)
            if (!start.isAfter(now)) continue
            val bedAt = st.wakeBedAt().takeIf { it > 0 }?.let(::time)
                ?.takeIf { it.isBefore(latest) && it.isAfter(latest.minusHours(14)) }
            val smart = SleepPlan.smartWake(latest, bedAt)
            val wake = when {
                snoozeUntil != null && snoozeUntil.toLocalDate() == date && snoozeUntil.isAfter(now) -> snoozeUntil
                smart.isAfter(now) -> smart
                latest.isAfter(now) -> latest
                // Будильник на этот день уже стоял и должен был прозвенеть, но его не выключали
                // (телефон был выключен?) — звоним сразу. Иначе (включили будильник поздно) — не будим.
                st.wakeArmed() in 1..ms(now) && time(st.wakeArmed()).toLocalDate() == date -> now.plusSeconds(30)
                else -> continue
            }
            return Plan(date, wake, latest, start, first, SleepPlan.bedtime(latest), snoozed = wake == snoozeUntil)
        }
        return null
    }

    private fun am(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun broadcast(context: Context, action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        context, req, Intent(context, WakeReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQ_RING,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** «Настоящий» будильник: срабатывает точно, даже в глубоком сне телефона. */
    private fun setClock(context: Context, at: Long, pi: PendingIntent) {
        val am = am(context) ?: return
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(context)), pi) }
            .onFailure { runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
    }

    private fun setExact(context: Context, at: Long, pi: PendingIntent) {
        val am = am(context) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
    }

    /** Поставить заново: при запуске, после смены расписания, настроек, «Ложусь», перезагрузки. */
    suspend fun reschedule(context: Context) {
        val am = am(context) ?: return
        val ring = broadcast(context, ACTION_RING, REQ_RING)
        val bed = broadcast(context, ACTION_BEDTIME, REQ_BEDTIME)
        am.cancel(ring)
        am.cancel(bed)
        val st = context.app.settings
        // Вчерашние отметки больше не нужны.
        val today = LocalDate.now().toString()
        if (st.wakeDoneDay().isNotEmpty() && st.wakeDoneDay() < today) st.setWakeDoneDay("")
        if (st.wakeSkipDay().isNotEmpty() && st.wakeSkipDay() < today) st.setWakeSkipDay("")
        val p = plan(context) ?: run { st.setWakeArmed(0); return }
        setClock(context, ms(p.wake), ring)
        st.setWakeArmed(ms(p.wake))
        // Вечером — «пора спать» за 30 минут до времени на 5 циклов, если ещё не нажал «Ложусь».
        val remind = p.bedtime.minusMinutes(30)
        val now = LocalDateTime.now()
        val bedMarked = st.wakeBedAt() > 0 && time(st.wakeBedAt()).isAfter(p.latest.minusHours(14))
        if (!bedMarked && !p.snoozed && remind.isAfter(now) && remind.isAfter(p.latest.minusHours(20))) {
            setExact(context, ms(remind), bed)
        }
    }

    fun startRinging(context: Context, test: Boolean = false) {
        val i = Intent(context, WakeService::class.java).setAction(ACTION_RING).putExtra(EXTRA_TEST, test)
        runCatching { ContextCompat.startForegroundService(context, i) }
    }

    /** Можно ли ещё отложить: не больше двух раз и только если останется хотя бы 15 минут собраться. */
    suspend fun canSnooze(context: Context): Boolean {
        val st = context.app.settings
        if (st.wakeSnoozes() >= MAX_SNOOZES) return false
        val p = plan(context) ?: return false
        val after = LocalDateTime.now().plusMinutes(SNOOZE_MIN)
        val travel = if (!p.lesson.online.isNullOrBlank()) 0 else st.wakeTravelMin()
        return !after.isAfter(p.lessonAt.minusMinutes(travel + 15L))
    }

    suspend fun snooze(context: Context) {
        val st = context.app.settings
        st.setWakeSnoozes(st.wakeSnoozes() + 1)
        st.setWakeSnoozeUntil(System.currentTimeMillis() + SNOOZE_MIN * 60_000L)
        stopRinging(context)
        reschedule(context)
    }

    /** Не выключил за 10 минут — пауза и снова звонок, пока не начнётся пара. */
    suspend fun ringAgainLater(context: Context) {
        val st = context.app.settings
        val p = plan(context)
        if (p != null && LocalDateTime.now().plusMinutes(RING_AGAIN_MIN).isBefore(p.lessonAt)) {
            st.setWakeSnoozeUntil(System.currentTimeMillis() + RING_AGAIN_MIN * 60_000L)
        } else {
            st.setWakeSnoozeUntil(0)
            st.setWakeSnoozes(0)
            p?.let { st.setWakeDoneDay(it.date.toString()) }
        }
        reschedule(context)
    }

    /** «Встаю» (пример решён). */
    suspend fun dismiss(context: Context, test: Boolean) {
        stopRinging(context)
        if (test) return
        val st = context.app.settings
        plan(context)?.let { st.setWakeDoneDay(it.date.toString()) }
        st.setWakeSnoozes(0)
        st.setWakeSnoozeUntil(0)
        st.setWakeBedAt(0)
        if (st.wakeCheck()) setExact(context, System.currentTimeMillis() + CHECK_AFTER_MIN * 60_000L, broadcast(context, ACTION_CHECK, REQ_CHECK))
        reschedule(context)
    }

    private fun stopRinging(context: Context) {
        runCatching { context.stopService(Intent(context, WakeService::class.java)) }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_RING)
    }

    /** Через 3 минуты после «Встаю»: «Ты встал?»; молчание 2 минуты — снова звонок. */
    fun onCheck(context: Context) {
        setClock(context, System.currentTimeMillis() + CHECK_GRACE_MIN * 60_000L, broadcast(context, ACTION_RING, REQ_BACKUP))
        val full = PendingIntent.getActivity(
            context, NOTIFY_CHECK,
            Intent(context, WakeActivity::class.java).putExtra(WakeActivity.EXTRA_CHECK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Ты встал?")
            .setContentText("Нажми «Да» — иначе через 2 минуты будильник зазвонит снова")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "Да, встал", broadcast(context, ACTION_AWAKE, REQ_CHECK + 100))
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setOngoing(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY_CHECK, n) }
    }

    fun onAwake(context: Context) {
        am(context)?.cancel(broadcast(context, ACTION_RING, REQ_BACKUP))
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_CHECK)
    }

    /** Вечером: когда лечь, чтобы проснуться в конце цикла. */
    suspend fun onBedtime(context: Context) {
        val p = plan(context) ?: return
        val options = SleepPlan.bedtimes(p.latest).joinToString(" · ") { (n, t) -> "${hm(t)} — $n цикл${if (n == 4) "а" else "ов"}" }
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_SLEEP)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Ложись до ${hm(p.bedtime)} — подъём в ${hm(p.latest)}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Первая пара в ${hm(p.lessonAt)}${p.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}.\n" +
                        "Проснёшься в конце цикла, если лечь: $options.\n" +
                        "Нажми «Ложусь», когда ляжешь, — подгоню будильник под твой цикл.",
                ),
            )
            .setContentText("Нажми «Ложусь», когда ляжешь, — подгоню будильник под цикл сна")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .addAction(0, "Ложусь", broadcast(context, ACTION_BED, REQ_BEDTIME + 100))
            .addAction(0, "Завтра не будить", broadcast(context, ACTION_SKIP, REQ_BEDTIME + 101))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY_BEDTIME, n) }
    }

    /** «Ложусь»: подъём — в конце ближайшего цикла. */
    suspend fun onBed(context: Context) {
        val st = context.app.settings
        st.setWakeBedAt(System.currentTimeMillis())
        reschedule(context)
        val p = plan(context) ?: return
        val now = LocalDateTime.now()
        val cycles = SleepPlan.cycles(now, p.wake)
        val sleep = java.time.Duration.between(now.plusMinutes(SleepPlan.FALL_ASLEEP_MIN), p.wake).toMinutes().coerceAtLeast(0)
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_SLEEP)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Спокойной ночи. Будильник — ${hm(p.wake)}")
            .setContentText(
                if (p.wake != p.latest) "Конец $cycles-го цикла, сон ${SleepPlan.duration(sleep)}. Позже ${hm(p.latest)} будить нельзя — пара."
                else "Сон ${SleepPlan.duration(sleep)}. Позже будить нельзя — пара в ${hm(p.lessonAt)}.",
            )
            .setSilent(true)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY_BEDTIME, n) }
    }

    suspend fun onSkip(context: Context) {
        val p = plan(context) ?: return
        context.app.settings.setWakeSkipDay(p.date.toString())
        reschedule(context)
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_SLEEP)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Не разбужу ${if (p.date == LocalDate.now().plusDays(1)) "завтра" else p.date.format(DateTimeFormatter.ofPattern("d.MM"))}")
            .setContentText("Пара в ${hm(p.lessonAt)} — сам. Вернуть можно в настройках.")
            .setSilent(true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY_BEDTIME, n) }
    }
}

/** События будильника: звонок, «отложить», проверка подъёма, вечернее «пора спать». */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Звонок — сразу, без корутин: сервис должен стартовать, пока действует разрешение от будильника.
        if (intent.action == WakeAlarm.ACTION_RING) {
            WakeAlarm.startRinging(context)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    WakeAlarm.ACTION_SNOOZE -> runCatching { if (WakeAlarm.canSnooze(context)) WakeAlarm.snooze(context) }
                    WakeAlarm.ACTION_CHECK -> runCatching { WakeAlarm.onCheck(context) }
                    WakeAlarm.ACTION_AWAKE -> runCatching { WakeAlarm.onAwake(context) }
                    WakeAlarm.ACTION_BEDTIME -> runCatching { WakeAlarm.onBedtime(context) }
                    WakeAlarm.ACTION_BED -> runCatching { WakeAlarm.onBed(context) }
                    WakeAlarm.ACTION_SKIP -> runCatching { WakeAlarm.onSkip(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

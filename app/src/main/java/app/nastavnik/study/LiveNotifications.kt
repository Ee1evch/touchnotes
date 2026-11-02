package app.nastavnik.study

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CardEntity
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
 * Живое уведомление учебного дня — несколько видов и способов обновления на выбор (Настройки → Живое уведомление):
 *  - «Минимум»: одна строка и отсчёт;
 *  - «Большие цифры»: только время крупно, полоса одной пары и аудитория (свой макет — на Android 16 не «живое»);
 *  - «Полоса дня»: весь день полосой (пары цветом по типу, перемены серые), что дальше, «пара N из M»;
 *  - «Своя карточка»: свой макет — полоса цвета пары, крупный отсчёт, аудитория в «таблетке» (на Android 16 не
 *    «живое»: свои макеты система не продвигает);
 *  - «Только моменты»: без постоянного уведомления — коротко «скоро пара» и «перемена»;
 *  - «Компаньон»: полоса дня, а на перемене — карточки по следующей паре прямо в уведомлении.
 * Обновление: «экономно» — только на границах пар; «обычно» — раз в 3 минуты; «точно» — сервис, раз в минуту.
 */
object LiveNotifications {
    const val ACTION_LIVE = "app.nastavnik.LESSON_LIVE"
    const val ACTION_CARD_SHOW = "app.nastavnik.LIVE_CARD_SHOW"
    const val ACTION_CARD_YES = "app.nastavnik.LIVE_CARD_YES"
    const val ACTION_CARD_NO = "app.nastavnik.LIVE_CARD_NO"
    const val LESSON_ID = 7702
    private const val LIVE_REQUEST = 7710
    private const val ACCENT = 0xFF2563EB.toInt()
    private const val BREAK_COLOR = 0xFFB8BEC9.toInt()
    /** Сколько карточек за перемену в «Компаньоне». */
    private const val CARDS_PER_BREAK = 3

    enum class Design(val id: String, val label: String, val hint: String) {
        MINIMAL("minimal", "Минимум", "Одна строка: пара, аудитория и отсчёт"),
        BIG("big", "Большие цифры", "Только время крупно, полоса пары и аудитория — на Android 16 не «живое»"),
        TIMELINE("timeline", "Полоса дня", "Весь день полосой, что дальше, «пара 2 из 4»"),
        CARD("card", "Своя карточка", "Цвет пары, крупный отсчёт, аудитория — красивее, но на Android 16 не «живое»"),
        MOMENTS("moments", "Только моменты", "Без постоянного уведомления: «скоро пара» и «перемена»"),
        COMPANION("companion", "Компаньон", "Полоса дня, а на перемене — карточки к следующей паре прямо в уведомлении"),
    }

    enum class Mode(val id: String, val label: String, val hint: String) {
        BOUNDARY("boundary", "Экономно", "Только в начале и конце пар — отсчёт идёт сам"),
        INTERVAL("interval", "Обычно", "Раз в 3 минуты"),
        SERVICE("service", "Точно", "Каждую минуту, пока идут пары (работает сервис)"),
    }

    fun design(context: Context) = Design.entries.firstOrNull { it.id == context.app.settings.liveDesign() } ?: Design.TIMELINE
    fun mode(context: Context) = Mode.entries.firstOrNull { it.id == context.app.settings.liveMode() } ?: Mode.INTERVAL

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun prefs(context: Context) = context.getSharedPreferences("live", Context.MODE_PRIVATE)

    /** Цвет пары по типу — как в расписании. */
    private fun color(type: String): Int = when (type) {
        "Лекция" -> 0xFF4C7DF0.toInt()
        "Практика" -> 0xFF1FA463.toInt()
        "Семинар" -> 0xFFE08A1E.toInt()
        "Лабораторная" -> 0xFF9B5DE5.toInt()
        else -> 0xFF7C8596.toInt()
    }

    private fun where(l: Lesson) = listOfNotNull(l.classroom, l.type.takeIf { it.isNotBlank() }).joinToString(" · ")
    private fun plain(s: String) = s.replace("$", "").replace("\\", "").trim()

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
        val design = design(context)
        if (alert <= 0 || !Permissions.notifications(context)) { stop(context, nm); return }
        if (design == Design.MOMENTS) { LiveService.stop(context); moments(context, nm, day, alert, now); return }
        val s = visible(day, alert, now) ?: run { stop(context, nm); return }
        val companion = if (design == Design.COMPANION && s.phase == DayTimeline.Phase.BREAK) companionCard(context, s.focus!!) else null
        runCatching { nm.notify(LESSON_ID, build(context, design, s, companion)) }
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

    private fun action(context: Context, action: String, req: Int) = PendingIntent.getBroadcast(
        context, req, Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Карточка «Компаньона» на перемене: id, вопрос, ответ, открыт ли ответ, сколько уже повторено. */
    data class Companion(val card: CardEntity?, val revealed: Boolean, val done: Int)

    /** Уведомление выбранного вида по состоянию дня. */
    fun build(context: Context, design: Design, s: DayTimeline.State, companion: Companion?): Notification {
        val focus = s.focus!!
        val start = focus.startsAt()!!
        val end = focus.endsAt() ?: start.plusMinutes(90)
        val target = if (s.phase == DayTimeline.Phase.LESSON) end else start
        val b = Notification.Builder(context, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(ACCENT)
            .setContentIntent(open(context))
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(ms(target))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
        val next = s.after?.let { "Дальше в ${it.start} — ${it.discipline}" } ?: "Последняя пара"
        val title = when (s.phase) {
            DayTimeline.Phase.BEFORE -> "${focus.discipline} в ${focus.start}"
            DayTimeline.Phase.LESSON -> listOfNotNull(focus.discipline, focus.classroom).joinToString(" · ")
            else -> "Перемена · дальше ${focus.discipline}"
        }
        val sub = if (s.phase == DayTimeline.Phase.BEFORE) "Сегодня ${s.count} ${ruPlural(s.count, "пара", "пары", "пар")}" else "Пара ${s.number} из ${s.count}"
        val text = when (s.phase) {
            DayTimeline.Phase.BEFORE -> where(focus).ifBlank { "Первая пара" }
            DayTimeline.Phase.LESSON -> next
            else -> listOfNotNull(focus.classroom, "в ${focus.start}").joinToString(" · ")
        }
        when (design) {
            Design.MINIMAL -> b.setContentTitle(title)
            Design.BIG -> {
                // Свёрнутое — цифры поменьше (по высоте свёрнутого уведомления), развёрнутое — крупно.
                fun big(sp: Float) = RemoteViews(context.packageName, R.layout.live_big).apply {
                    setChronometer(R.id.live_big_clock, SystemClock.elapsedRealtime() + (ms(target) - System.currentTimeMillis()), null, true)
                    setChronometerCountDown(R.id.live_big_clock, true)
                    setTextViewTextSize(R.id.live_big_clock, android.util.TypedValue.COMPLEX_UNIT_SP, sp)
                    if (focus.classroom.isNullOrBlank()) setViewVisibility(R.id.live_big_room, View.GONE) else setTextViewText(R.id.live_big_room, focus.classroom)
                    // Полоса одной пары: сколько прошло; до пары и на перемене — пустая.
                    val total = Duration.between(start, end).toMinutes().toInt().coerceAtLeast(1)
                    val passed = if (s.phase == DayTimeline.Phase.LESSON) Duration.between(start, LocalDateTime.now()).toMinutes().toInt().coerceIn(0, total) else 0
                    setProgressBar(R.id.live_big_progress, total, passed, false)
                }
                b.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(big(30f)).setCustomBigContentView(big(52f))
            }
            Design.CARD -> {
                val rv = RemoteViews(context.packageName, R.layout.live_card)
                rv.setInt(R.id.live_stripe, "setColorFilter", color(focus.type))
                rv.setTextViewText(R.id.live_kicker, when (s.phase) {
                    DayTimeline.Phase.BEFORE -> "Скоро · $sub"
                    DayTimeline.Phase.LESSON -> "Идёт · $sub"
                    else -> "Перемена · $sub"
                })
                rv.setTextViewText(R.id.live_title, focus.discipline)
                rv.setChronometer(R.id.live_clock, SystemClock.elapsedRealtime() + (ms(target) - System.currentTimeMillis()), null, true)
                rv.setChronometerCountDown(R.id.live_clock, true)
                if (focus.classroom.isNullOrBlank()) rv.setViewVisibility(R.id.live_room, View.GONE) else rv.setTextViewText(R.id.live_room, focus.classroom)
                rv.setTextViewText(R.id.live_next, if (s.phase == DayTimeline.Phase.LESSON) next else "в ${focus.start}${s.after?.let { " · потом ${it.discipline}" } ?: ""}")
                rv.setProgressBar(R.id.live_progress, s.total, s.progress, false)
                b.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(rv).setCustomBigContentView(rv)
            }
            else -> {
                b.setContentTitle(title).setContentText(text).setSubText(sub)
                progress(context, b, s)
                b.addAction(Notification.Action.Builder(null, "Расписание", open(context)).build())
            }
        }
        // Просьба показать как «живое» (Android 16+) — кроме своих макетов.
        if (design != Design.CARD && design != Design.BIG) b.extras.putBoolean("android.requestPromotedOngoing", true)
        // «Компаньон» на перемене: карточка по следующей паре — ответ и «Помню / Не помню» прямо здесь.
        if (design == Design.COMPANION && companion != null) {
            val c = companion.card
            if (c == null) {
                b.setContentText(if (companion.done > 0) "Повторено ${companion.done} — хватит на эту перемену ✓" else text)
            } else {
                b.setContentText(plain(c.front))
                b.setStyle(Notification.BigTextStyle().bigText(if (companion.revealed) "${plain(c.front)}\n\nОтвет: ${plain(c.back)}" else plain(c.front)))
                b.setActions()
                if (!companion.revealed) {
                    b.addAction(Notification.Action.Builder(null, "Показать ответ", action(context, ACTION_CARD_SHOW, 7751)).build())
                } else {
                    b.addAction(Notification.Action.Builder(null, "Помню", action(context, ACTION_CARD_YES, 7752)).build())
                    b.addAction(Notification.Action.Builder(null, "Не помню", action(context, ACTION_CARD_NO, 7753)).build())
                }
            }
        }
        return b.build()
    }

    /** Полоса: на Android 16 — весь день сегментами по типам пар, иначе обычная. */
    private fun progress(context: Context, b: Notification.Builder, s: DayTimeline.State) {
        if (Build.VERSION.SDK_INT >= 36) {
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
    }

    /** «Только моменты»: коротко в нужный момент — «скоро пара», «перемена»; постоянного уведомления нет. */
    private fun moments(context: Context, nm: NotificationManager, day: List<Lesson>, alert: Int, now: LocalDateTime) {
        val all = DayTimeline.moments(day, alert)
        val cur = all.lastOrNull { !it.at.isAfter(now) && now.isBefore(it.at.plusMinutes(3)) }
        val p = prefs(context)
        if (cur != null && p.getLong("moment_at", 0L) != ms(cur.at)) {
            p.edit().putLong("moment_at", ms(cur.at)).apply()
            runCatching { nm.notify(LESSON_ID, moment(context, cur)) }
        }
        all.firstOrNull { it.at.isAfter(now) }?.let { schedule(context, ms(it.at)) }
    }

    private fun moment(context: Context, m: DayTimeline.Moment): Notification {
        val l = m.lesson
        val start = l.startsAt()!!
        return Notification.Builder(context, NastavnikApp.CHANNEL_LESSONS)
            .setSmallIcon(R.drawable.ic_calendar)
            .setColor(ACCENT)
            .setContentIntent(open(context))
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentTitle(if (m.soon) "${l.discipline} через ${Duration.between(m.at, start).toMinutes()} мин" else "Перемена ${m.breakMinutes} мин → ${l.discipline}")
            .setContentText(listOfNotNull(l.classroom, l.type.takeIf { it.isNotBlank() }, "в ${l.start}").joinToString(" · "))
            .setWhen(ms(start)).setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter(Duration.between(LocalDateTime.now(), start).toMillis().coerceAtLeast(60_000L))
            .build()
    }

    /** Карточка для перемены: по предмету следующей пары, иначе любая к повторению. Не больше 3 за перемену. */
    private suspend fun companionCard(context: Context, next: Lesson): Companion {
        val p = prefs(context)
        val key = "${next.date}/${next.id}"
        if (p.getString("comp_key", "") != key) p.edit().putString("comp_key", key).putLong("comp_card", 0).putBoolean("comp_shown", false).putInt("comp_done", 0).apply()
        val done = p.getInt("comp_done", 0)
        if (done >= CARDS_PER_BREAK) return Companion(null, false, done)
        val cards = context.app.db.cards().due(System.currentTimeMillis(), 50)
        val id = p.getLong("comp_card", 0)
        val card = cards.firstOrNull { it.id == id }
            ?: cards.firstOrNull { StudyLockRules.sameSubject(it.subject.ifBlank { it.topic }, next.discipline) }
            ?: cards.firstOrNull()
        p.edit().putLong("comp_card", card?.id ?: 0).apply()
        return Companion(card, p.getBoolean("comp_shown", false), done)
    }

    /** Кнопки «Компаньона»: показать ответ, «Помню», «Не помню». */
    suspend fun onCardAction(context: Context, action: String) {
        val p = prefs(context)
        val id = p.getLong("comp_card", 0)
        val card = context.app.db.cards().all().firstOrNull { it.id == id }
        when (action) {
            ACTION_CARD_SHOW -> p.edit().putBoolean("comp_shown", true).apply()
            ACTION_CARD_YES, ACTION_CARD_NO -> if (card != null) {
                val ok = action == ACTION_CARD_YES
                val study = context.app.study
                study.save(study.answered(card, ok), ok)
                p.edit().putLong("comp_card", 0).putBoolean("comp_shown", false).putInt("comp_done", p.getInt("comp_done", 0) + 1).apply()
            }
        }
        updateLesson(context)
    }

    /** «Показать сейчас»: выбранный вид на примере дня (сейчас идёт физика), на 2 минуты. */
    suspend fun preview(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val design = design(context)
        fun lesson(id: String, from: Long, to: Long, d: String, type: String, room: String) = Lesson(
            id = id, date = now.toLocalDate().toString(),
            start = "%02d:%02d".format(now.plusMinutes(from).hour, now.plusMinutes(from).minute),
            end = "%02d:%02d".format(now.plusMinutes(to).hour, now.plusMinutes(to).minute),
            discipline = d, type = type, classroom = room,
        )
        // Для «Компаньона» и «Моментов» — перемена, для остальных — идёт пара.
        val inBreak = design == Design.COMPANION || design == Design.MOMENTS
        val day = if (inBreak) listOf(
            lesson("p1", -95, -5, "Физика", "Практика", "Альфа 5.8"),
            lesson("p2", 5, 95, "Математический анализ", "Лекция", "Бета 3.3"),
        ) else listOf(
            lesson("p0", -125, -35, "Математический анализ", "Лекция", "Бета 3.3"),
            lesson("p1", -25, 65, "Физика", "Практика", "Альфа 5.8"),
            lesson("p2", 75, 165, "Линейная алгебра", "Семинар", "Гамма 2.1"),
        )
        val n = if (design == Design.MOMENTS) {
            moment(context, DayTimeline.moments(day, 10).first { !it.soon })
        } else {
            val s = DayTimeline.at(day, now) ?: return
            val companion = if (design == Design.COMPANION) {
                val c = context.app.db.cards().due(System.currentTimeMillis(), 1).firstOrNull()
                    ?: CardEntity(noteId = null, subject = "Математический анализ", topic = "Пределы", front = "Чему равен предел sin x / x при x → 0?", back = "1", dueAt = 0, createdAt = 0)
                Companion(c, false, 0)
            } else null
            build(context, design, s, companion)
        }
        runCatching { nm.notify(LESSON_ID, Notification.Builder.recoverBuilder(context, n).setTimeoutAfter(120_000L).build()) }
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

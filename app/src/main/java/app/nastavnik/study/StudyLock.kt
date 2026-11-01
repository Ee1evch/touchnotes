package app.nastavnik.study

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CardEntity
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Настройки замка — в хранилище, доступном и до разблокировки (служба защиты работает и там). */
class LockPrefs private constructor(private val p: SharedPreferences) {
    companion object {
        @Volatile private var instance: LockPrefs? = null
        fun of(context: Context): LockPrefs = instance ?: synchronized(this) {
            instance ?: LockPrefs(
                context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("study_lock", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }

    /** Замок включён (выключить из приложения нельзя). */
    val configured: Boolean get() = p.getBoolean("configured", false)
    /** С какого времени вечером закрыто (минуты от полуночи). */
    val startMinutes: Int get() = p.getInt("start", 20 * 60)
    val since: LocalDate get() = runCatching { LocalDate.parse(p.getString("since", "")) }.getOrDefault(LocalDate.MAX)
    /** Разрешённые приложения — выбраны один раз. */
    val allowed: Set<String> get() = p.getStringSet("allowed", emptySet()).orEmpty()

    /** Один раз и навсегда: время и разрешённые приложения. Повторно не перезаписывается. */
    fun configureOnce(startMinutes: Int, allowed: Set<String>, since: LocalDate): Boolean {
        if (configured) return false
        p.edit(commit = true) {
            putInt("start", startMinutes)
            putStringSet("allowed", allowed)
            putString("since", since.toString())
            putBoolean("configured", true)
        }
        return true
    }

    val done: Set<String> get() = p.getStringSet("done", emptySet()).orEmpty()
    fun markDone(date: LocalDate) {
        // Храним последние 30 дней.
        val keep = done.filter { runCatching { LocalDate.parse(it) }.getOrNull()?.isAfter(date.minusDays(30)) == true }.toSet()
        p.edit { putStringSet("done", keep + date.toString()) }
    }

    var emergencyAt: Long
        get() = p.getLong("emergency_at", 0L)
        set(v) = p.edit { putLong("emergency_at", v) }
    val emergencyActive: Boolean get() = System.currentTimeMillis() - emergencyAt < StudyLockRules.EMERGENCY_MINUTES * 60_000L
}

/** Что сделано за вечер: конспекты пар и подготовка к завтрашним. Лежит в files/evening/<дата>.json. */
@Serializable
data class Evening(
    val summaries: Map<String, String> = emptyMap(),
    val prepared: List<String> = emptyList(),
)

/**
 * Учебный замок: каждый вечер с выбранного времени закрыты все приложения, кроме выбранных один раз,
 * пока не сделана вечерняя учёба. Закрывает служба защиты ([WakeGuard]).
 */
object StudyLock {
    const val ACTION_WARN = "app.nastavnik.LOCK_WARN"
    const val ACTION_START = "app.nastavnik.LOCK_START"
    private const val NOTIFY = 7730
    private const val REQ_WARN = 7731
    private const val REQ_START = 7732
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ru = Locale.forLanguageTag("ru")

    /** Заблокировано прямо сейчас (обновляется при каждом [status]). */
    @Volatile var locked: Boolean = false
        private set

    data class Status(
        val configured: Boolean,
        /** За какой день вечерняя учёба (старший несделанный, иначе сегодня). */
        val date: LocalDate,
        val locked: Boolean,
        val due: Int,
        /** Предметы дня и есть ли по ним конспект. */
        val summaries: List<Pair<String, Boolean>>,
        /** Предметы следующего дня и готов ли к ним. */
        val prep: List<Pair<String, Boolean>>,
        val nextDate: LocalDate,
        /** Когда в этот день закрывается: через 40 минут после последней пары. */
        val startAt: LocalDateTime,
        val emergency: Boolean,
    ) {
        val cardsDone get() = due == 0
        val summariesDone get() = summaries.all { it.second }
        val prepDone get() = prep.all { it.second }
        val allDone get() = cardsDone && summariesDone && prepDone
        val steps get() = listOf(cardsDone, summariesDone, prepDone).count { it }
    }

    private fun dir(context: Context) = File(context.filesDir, "evening").apply { mkdirs() }
    private fun file(context: Context, date: LocalDate) = File(dir(context), "$date.json")

    fun evening(context: Context, date: LocalDate): Evening =
        runCatching { json.decodeFromString(Evening.serializer(), file(context, date).readText()) }.getOrDefault(Evening())

    private fun save(context: Context, date: LocalDate, e: Evening) {
        file(context, date).writeText(json.encodeToString(Evening.serializer(), e))
    }

    /** Следующий учебный день после [date] (с парами), не дальше недели. */
    private suspend fun nextSchoolDay(context: Context, date: LocalDate): Pair<LocalDate, List<String>> {
        for (d in 1L..7L) {
            val day = date.plusDays(d)
            val subjects = StudyLockRules.disciplines(context.app.schedule.day(day))
            if (subjects.isNotEmpty()) return day to subjects
        }
        return date.plusDays(1) to emptyList()
    }

    /** Состояние вечерней учёбы; заодно отмечает день сделанным и обновляет замок. */
    suspend fun status(context: Context, now: LocalDateTime = LocalDateTime.now()): Status = withContext(Dispatchers.IO) {
        val app = context.app
        val lp = LockPrefs.of(context)
        val today0 = StudyLockRules.sessionDate(now)
        val starts = mapOf(
            today0 to startOf(context, today0, lp.startMinutes),
            today0.minusDays(1) to startOf(context, today0.minusDays(1), lp.startMinutes),
        )
        val startFn = { d: LocalDate -> starts[d] ?: StudyLockRules.lockStart(d, lp.startMinutes) }
        val pending = if (lp.configured) StudyLockRules.pending(now, startFn, lp.since, lp.done) else emptyList()
        val date = pending.firstOrNull() ?: StudyLockRules.sessionDate(now)
        val e = evening(context, date)
        val today = StudyLockRules.disciplines(app.schedule.day(date))
        val (next, tomorrow) = nextSchoolDay(context, date)
        // Подготовка — только к ближайшему дню (завтра или после выходных), если до него не больше 3 дней.
        val prepSubjects = if (next.isAfter(date.plusDays(3))) emptyList() else tomorrow
        val due = runCatching { app.db.cards().dueCount(System.currentTimeMillis()) }.getOrDefault(0)
        var s = Status(
            configured = lp.configured,
            date = date,
            locked = false,
            due = due,
            summaries = today.map { it to ((e.summaries[it]?.trim()?.length ?: 0) >= StudyLockRules.SUMMARY_MIN_CHARS) },
            prep = prepSubjects.map { it to (it in e.prepared) },
            nextDate = next,
            startAt = startFn(date),
            emergency = lp.emergencyActive,
        )
        // Заранее можно — но не раньше полудня: конспекты пар пишут после пар.
        if (lp.configured && s.allDone && date.toString() !in lp.done && (date in pending || now.isAfter(date.atTime(12, 0)))) {
            // Сделал — день закрыт (если сделал заранее, днём, — вечером замка не будет).
            lp.markDone(date)
        }
        val stillPending = if (lp.configured) StudyLockRules.pending(now, startFn, lp.since, lp.done) else emptyList()
        s = s.copy(locked = stillPending.isNotEmpty() && !lp.emergencyActive)
        locked = s.locked
        if (!s.locked) context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY)
        s
    }

    /** Начало замка в день [date]: через 40 минут после последней пары, без пар — в выбранное время. */
    suspend fun startOf(context: Context, date: LocalDate, fallbackMinutes: Int): LocalDateTime =
        StudyLockRules.lockStart(date, runCatching { context.app.schedule.day(date) }.getOrDefault(emptyList()), fallbackMinutes)

    /**
     * «Что было сегодня»: сохранить конспект пары и сделать из него карточки на завтра —
     * «вспомни главное», «заполни пропуск» по каждой фразе и (если настроена нейросеть) вопросы по конспекту.
     */
    suspend fun saveSummary(context: Context, date: LocalDate, subject: String, text: String) = withContext(Dispatchers.IO) {
        val e = evening(context, date)
        val first = e.summaries[subject].isNullOrBlank()
        save(context, date, e.copy(summaries = e.summaries + (subject to text.trim())))
        if (first) {
            val app = context.app
            val now = System.currentTimeMillis()
            val due = now + 20 * 3_600_000L
            val day = date.format(DateTimeFormatter.ofPattern("d MMMM", ru))
            val topic = "Пара $day"
            val cards = mutableListOf(
                CardEntity(
                    noteId = null, subject = subject, topic = topic,
                    front = "Что разбирали на паре «$subject» $day? Вспомни главное своими словами.",
                    back = text.trim(), dueAt = due, createdAt = now,
                ),
            )
            StudyLockRules.cloze(text).forEach { (front, back) ->
                cards += CardEntity(noteId = null, subject = subject, topic = topic, front = "Заполни пропуск ($subject):\n$front", back = back, dueAt = due, createdAt = now)
            }
            app.db.cards().insertAll(cards)
            app.study.record(Progress.KIND_CARD, subject)
            // Вопросы по конспекту от нейросети — в фоне, если она настроена (не настроена — просто пропускаем).
            app.appScope.launch(Dispatchers.IO) {
                val r = runCatching { app.trainer.quiz(Material(subject, text.trim(), subject = subject), count = 6) }.getOrNull()
                if (r is TrainResult.Ok) {
                    val t = System.currentTimeMillis()
                    app.db.cards().insertAll(
                        r.value.map { q ->
                            val answer = q.options.getOrNull(q.correct).orEmpty()
                            CardEntity(
                                noteId = null, subject = subject, topic = topic, front = q.q,
                                back = if (q.why.isBlank()) answer else "$answer\n\n${q.why}", dueAt = due, createdAt = t,
                            )
                        },
                    )
                }
                app.sync.soon(8000)
            }
        }
    }

    suspend fun markPrepared(context: Context, date: LocalDate, subject: String) = withContext(Dispatchers.IO) {
        val e = evening(context, date)
        if (subject !in e.prepared) save(context, date, e.copy(prepared = e.prepared + subject))
    }

    /** Материалы к паре: последний конспект по предмету и его карточки. */
    suspend fun materials(context: Context, before: LocalDate, subject: String): Pair<String?, List<CardEntity>> = withContext(Dispatchers.IO) {
        var summary: String? = null
        for (d in 0L..45L) {
            val day = before.minusDays(d)
            val f = file(context, day)
            if (!f.exists()) continue
            val e = evening(context, day)
            val hit = e.summaries.entries.firstOrNull { it.key == subject || StudyLockRules.sameSubject(it.key, subject) }
            if (hit != null) { summary = hit.value; break }
        }
        val cards = context.app.db.cards().all().filter { StudyLockRules.sameSubject(it.subject, subject) || StudyLockRules.sameSubject(it.topic, subject) }
            .sortedBy { it.box }.take(8)
        summary to cards
    }

    fun startEmergency(context: Context): Boolean {
        val lp = LockPrefs.of(context)
        if (!StudyLockRules.emergencyAvailable(lp.emergencyAt, System.currentTimeMillis())) return false
        lp.emergencyAt = System.currentTimeMillis()
        locked = false
        return true
    }

    /**
     * Приложения, которые открыты всегда: телефон, экстренные вызовы, клавиатура, рабочий стол, система —
     * и системные помощники, без которых не работают разрешённые приложения: «Поделиться», выбор файла и фото,
     * вход через Google.
     */
    fun alwaysAllowed(context: Context): Set<String> {
        val out = mutableSetOf(
            context.packageName, "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.incallui", "com.google.android.dialer", "com.android.dialer",
            "com.android.intentresolver", "com.android.documentsui", "com.google.android.documentsui",
            "com.google.android.providers.media.module", "com.android.providers.media.module", "com.google.android.gms",
        )
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(out::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(out::add)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { out += it.packageName }
        }
        out += homePackages(context)
        return out
    }

    /** Рабочие столы (лаунчеры): все установленные и тот, что по умолчанию. */
    fun homePackages(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(home, 0)
            list.forEach { out += it.activityInfo.packageName }
        }
        runCatching {
            val def = if (Build.VERSION.SDK_INT >= 33) pm.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            else @Suppress("DEPRECATION") pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            def?.activityInfo?.packageName?.takeIf { it != "android" }?.let(out::add)
        }
        return out
    }

    private fun broadcast(context: Context, action: String, req: Int) = PendingIntent.getBroadcast(
        context, req, Intent(context, StudyLockReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Будильники: за 30 минут предупредить, в начало — закрыть и показать уведомление. */
    suspend fun schedule(context: Context) {
        val lp = LockPrefs.of(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val warn = broadcast(context, ACTION_WARN, REQ_WARN)
        val start = broadcast(context, ACTION_START, REQ_START)
        am.cancel(warn); am.cancel(start)
        if (!lp.configured) return
        val now = LocalDateTime.now()
        var startAt = startOf(context, now.toLocalDate(), lp.startMinutes)
        if (!startAt.isAfter(now)) startAt = startOf(context, now.toLocalDate().plusDays(1), lp.startMinutes)
        val warnAt = startAt.minusMinutes(30)
        fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        fun set(at: Long, pi: PendingIntent) = runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
        if (warnAt.isAfter(now)) set(ms(warnAt), warn)
        set(ms(startAt), start)
    }

    private fun open(context: Context) = PendingIntent.getActivity(
        context, NOTIFY,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_EVENING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun todo(s: Status): String = listOfNotNull(
        s.due.takeIf { it > 0 }?.let { "$it ${ruPlural(it, "карточка", "карточки", "карточек")}" },
        s.summaries.count { !it.second }.takeIf { it > 0 }?.let { "$it ${ruPlural(it, "конспект", "конспекта", "конспектов")} пар" },
        s.prep.count { !it.second }.takeIf { it > 0 }?.let { "подготовка к $it ${ruPlural(it, "паре", "парам", "парам")}" },
    ).joinToString(", ")

    suspend fun onWarn(context: Context) {
        val s = status(context)
        if (s.allDone) return
        notify(context, "Через 30 минут — вечерняя учёба", "Осталось: ${todo(s)}. Сделай сейчас — и замка не будет.", ongoing = false)
    }

    suspend fun onStart(context: Context) {
        val s = status(context)
        if (s.locked) notify(context, "Приложения закрыты до вечерней учёбы", "Осталось: ${todo(s)}", ongoing = true)
    }

    private fun notify(context: Context, title: String, text: String, ongoing: Boolean) {
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_LOCK)
            .setSmallIcon(R.drawable.ic_cards)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(context))
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Начать", open(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY, n) }
    }
}

class StudyLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    StudyLock.ACTION_WARN -> runCatching { StudyLock.onWarn(context) }
                    StudyLock.ACTION_START -> runCatching { StudyLock.onStart(context) }
                }
                StudyLock.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}

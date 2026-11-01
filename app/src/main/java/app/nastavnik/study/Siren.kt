package app.nastavnik.study

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.nastavnik.app
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Состояние сирены — в хранилище до разблокировки: после перезагрузки она продолжит. */
class SirenPrefs private constructor(private val p: SharedPreferences) {
    companion object {
        @Volatile private var instance: SirenPrefs? = null
        fun of(context: Context): SirenPrefs = instance ?: synchronized(this) {
            instance ?: SirenPrefs(
                context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("siren", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }

    /** Включена — навсегда (выключить из приложения нельзя). */
    var on: Boolean
        get() = p.getBoolean("on", false)
        set(v) = p.edit(commit = true) { putBoolean("on", v) }
    /** Учебный день с парами, на который поставлена сирена. */
    var lessonDay: String
        get() = p.getString("lesson_day", "").orEmpty()
        set(v) = p.edit { putString("lesson_day", v) }
    var solvedDay: String
        get() = p.getString("solved_day", "").orEmpty()
        set(v) = p.edit { putString("solved_day", v) }
    var solvedCount: Int
        get() = p.getInt("solved_count", 0)
        set(v) = p.edit { putInt("solved_count", v) }
    var quietUntil: Long
        get() = p.getLong("quiet_until", 0L)
        set(v) = p.edit { putLong("quiet_until", v) }
    /** Сколько раз звонила за день: первый раз — громко, дальше — на максимуме. */
    var rings: Int
        get() = p.getInt("rings", 0)
        set(v) = p.edit { putInt("rings", v) }
    var ringsDay: String
        get() = p.getString("rings_day", "").orEmpty()
        set(v) = p.edit { putString("rings_day", v) }
    /** Текущая мини-задача (чтобы не менялась при повороте экрана). */
    var miniSeed: Long
        get() = p.getLong("mini_seed", 0L)
        set(v) = p.edit { putLong("mini_seed", v) }

    fun solved(date: LocalDate): Int = if (solvedDay == date.toString()) solvedCount else 0

    /**
     * Сирена «держит» телефон: с 20:00 учебного дня, пока не решена основная задача, — служба защиты закрывает
     * меню выключения, настройки и удаление (в том числе в 5 минут тишины).
     */
    fun guarding(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!on && !Siren.forced) return false
        val date = StudyLockRules.sessionDate(now)
        return (lessonDay == date.toString() || Siren.forced) && solved(date) == 0 && !now.isBefore(SirenRules.start(date))
    }
}

/**
 * Учебная сирена: в день с парами к 20:00 нужно решить основную задачу по слабым темам, иначе с 20:00 звук
 * (сначала «wake up», потом сигнал по кругу, громкость не убавить). Мини-задача — 5 минут тишины на основную.
 */
object Siren {
    const val ACTION_CHECK = "app.nastavnik.SIREN_CHECK"
    private const val REQ = 7740

    /** Запущена вручную (проверка, тест) — без расписания. */
    @Volatile var forced = false

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQ, Intent(context, SirenReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setClock(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context, REQ, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(context)) }
            .onFailure { runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context)) } }
    }

    /** Были ли в день пары (до разблокировки — по запомненному дню). */
    private suspend fun hasLessons(context: Context, date: LocalDate): Boolean {
        if (!WakePrefs.unlocked(context)) return SirenPrefs.of(context).lessonDay == date.toString()
        return StudyLockRules.disciplines(context.app.schedule.day(date)).isNotEmpty()
    }

    /** Пересчитать: звонить сейчас или поставить будильник на 20:00 ближайшего учебного дня / конец тишины. */
    suspend fun check(context: Context) {
        val s = SirenPrefs.of(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am?.cancel(pending(context))
        if (!s.on) return
        val now = LocalDateTime.now()
        val date = StudyLockRules.sessionDate(now)
        val today = hasLessons(context, date)
        if (today) s.lessonDay = date.toString()
        val quiet = s.quietUntil.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
        if (SirenRules.shouldRing(now, today, s.solved(date), quiet)) {
            start(context)
            return
        }
        // Тишина после мини-задачи — проверим, как закончится.
        if (today && s.solved(date) == 0 && quiet != null && quiet.isAfter(now)) {
            setClock(context, ms(quiet))
            return
        }
        // Ближайший учебный день: сегодня (если ещё нет 20:00 и не решено) или следующие.
        for (d in 0L..7L) {
            val day = date.plusDays(d)
            if (d == 0L && (!today || s.solved(day) > 0 || !now.isBefore(SirenRules.start(day)))) continue
            if (d > 0L && !hasLessons(context, day)) continue
            if (d > 0L) s.lessonDay = day.toString()
            setClock(context, ms(SirenRules.start(day)))
            return
        }
    }

    fun start(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, SirenService::class.java)) }
    }

    /** Мини-задача решена: 5 минут тишины на основную. */
    fun miniSolved(context: Context) {
        val s = SirenPrefs.of(context)
        val until = System.currentTimeMillis() + SirenRules.QUIET_MINUTES * 60_000L
        s.quietUntil = until
        s.miniSeed = 0
        context.stopService(Intent(context, SirenService::class.java))
        setClock(context, until)
    }

    /** Основная задача решена: тишина до завтра. */
    fun mainSolved(context: Context) {
        val s = SirenPrefs.of(context)
        val date = StudyLockRules.sessionDate(LocalDateTime.now())
        s.solvedCount = s.solved(date) + 1
        s.solvedDay = date.toString()
        s.quietUntil = 0
        forced = false
        context.stopService(Intent(context, SirenService::class.java))
        CoroutineScope(Dispatchers.IO).launch { runCatching { check(context) } }
    }
}

/** Будильник сирены: 20:00 и конец 5 минут тишины. Работает и до разблокировки. */
class SirenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { Siren.check(context) } } finally { pending.finish() }
        }
    }
}

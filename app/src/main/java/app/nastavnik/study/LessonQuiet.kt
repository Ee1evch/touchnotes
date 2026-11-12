package app.nastavnik.study

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import app.nastavnik.app
import app.nastavnik.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * «Тихо на парах»: на время пары телефон сам уходит в «Не беспокоить» и сам выходит после неё.
 *
 * Сделано системным режимом (AutomaticZenRule), а не прямым переключением «Не беспокоить»: система сама
 * совмещает его с режимами человека — если он уже включил «Не беспокоить» вручную, конец пары его не выключит.
 * В Android 15+ режим виден в настройках как «Пара» (тип «по расписанию»), его можно донастроить там.
 * Повторные звонки проходят — чтобы не пропустить важное.
 */
object LessonQuiet {
    const val ACTION = "app.nastavnik.QUIET"
    private const val REQUEST = 7701
    private val CONDITION: Uri = Uri.parse("condition://app.nastavnik/lesson")

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** Есть ли у приложения доступ к «Не беспокоить» (выдаётся человеком в настройках системы). */
    fun hasAccess(context: Context): Boolean = runCatching { nm(context)!!.isNotificationPolicyAccessGranted }.getOrDefault(false)

    fun accessIntent(): Intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ruleId(context: Context): String? {
        val nm = nm(context) ?: return null
        val saved = context.app.settings.quietRuleId()
        if (saved.isNotEmpty() && runCatching { nm.getAutomaticZenRule(saved) }.getOrNull() != null) return saved
        val policy = ZenPolicy.Builder()
            .disallowAllSounds()
            .allowAlarms(true)
            .allowRepeatCallers(true)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED)
            .build()
        val activity = ComponentName(context, MainActivity::class.java)
        val rule = if (Build.VERSION.SDK_INT >= 35) {
            AutomaticZenRule.Builder("Пара", CONDITION)
                .setType(AutomaticZenRule.TYPE_SCHEDULE_TIME)
                .setConfigurationActivity(activity)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(policy)
                .setTriggerDescription("Во время пар по расписанию")
                .setManualInvocationAllowed(false)
                .build()
        } else {
            AutomaticZenRule("Пара", null, activity, CONDITION, policy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        }
        val id = nm.addAutomaticZenRule(rule) ?: return null
        context.app.settings.setQuietRuleId(id)
        return id
    }

    /** Включить или выключить тишину по текущему времени и запланировать следующую смену. */
    suspend fun update(context: Context) {
        val app = context.app
        val nm = nm(context) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        am?.cancel(pi)
        if (!app.settings.quietLessons() || !hasAccess(context)) {
            disable(context)
            return
        }
        val id = runCatching { ruleId(context) }.getOrNull() ?: return
        val now = LocalDateTime.now()
        // Сейчас идёт пара — тихо до её конца; иначе громко до начала следующей.
        var current: LocalDateTime? = null
        var next: LocalDateTime? = null
        for (d in 0L..7L) {
            for (l in app.schedule.day(LocalDate.now().plusDays(d))) {
                val s = l.startsAt() ?: continue
                val e = l.endsAt() ?: s.plusMinutes(90)
                if (!now.isBefore(s) && now.isBefore(e)) { if (current == null || e.isAfter(current)) current = e }
                else if (s.isAfter(now) && (next == null || s.isBefore(next))) next = s
            }
            if (next != null) break
        }
        val quiet = current != null
        runCatching {
            nm.setAutomaticZenRuleState(
                id,
                Condition(CONDITION, if (quiet) "Идёт пара" else "Перемена", if (quiet) Condition.STATE_TRUE else Condition.STATE_FALSE),
            )
        }
        val at = (current ?: next) ?: return
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am!!.canScheduleExactAlarms()
            if (exact) am!!.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am!!.setWindow(AlarmManager.RTC_WAKEUP, ms, 60_000L, pi)
        }
    }

    /** Убрать режим совсем — «Не беспокоить» от пары сразу выключится. */
    fun disable(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        val id = context.app.settings.quietRuleId()
        if (id.isEmpty()) return
        runCatching { nm(context)?.removeAutomaticZenRule(id) }
        context.app.settings.setQuietRuleId("")
    }
}

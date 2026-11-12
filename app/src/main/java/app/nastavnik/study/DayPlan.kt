package app.nastavnik.study

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import app.nastavnik.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * План на день от наставника. Не читаем экран — это было бы дорого (сотни тысяч слов в день) и небезопасно
 * (переписки, банк). Вместо этого — короткая сводка: что знаешь плохо, где переоцениваешь себя, нерешённые задачи,
 * срезы, завтрашние пары и сколько минут провёл в каких приложениях (по разрешению «Доступ к истории
 * использования»). Один запрос в день, сводка ~1 страница — стоит копейки. Нет нейросети — плана нет.
 */
object DayPlan {
    private fun prefs(context: Context) = context.getSharedPreferences("learning", Context.MODE_PRIVATE)
    private val list = ListSerializer(String.serializer())

    /** Есть ли разрешение видеть время в приложениях. */
    fun usageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Рабочие столы: время в них — не «время в приложениях». */
    private fun homePackages(context: Context): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Минуты в приложениях за последние сутки, самые долгие первыми (без «Наставника» и рабочего стола). */
    fun screenTime(context: Context): List<Pair<String, Int>> {
        if (!usageAccess(context)) return emptyList()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val pm = context.packageManager
        val skip = homePackages(context) + context.packageName + "com.android.systemui"
        return runCatching {
            usm.queryAndAggregateUsageStats(now - 24 * 3_600_000L, now).values
                .filter { it.packageName !in skip && it.totalTimeInForeground >= 5 * 60_000L }
                .sortedByDescending { it.totalTimeInForeground }
                .take(5)
                .map { s ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
                    label to (s.totalTimeInForeground / 60_000L).toInt()
                }
        }.getOrDefault(emptyList())
    }

    /** План на сегодня: из кэша, а если его ещё нет — один запрос к нейросети. */
    suspend fun today(context: Context, refresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val day = LocalDate.now().toString()
        if (!refresh && p.getString("plan_day", "") == day) {
            return@withContext runCatching { Json.decodeFromString(list, p.getString("plan", "[]")!!) }.getOrDefault(emptyList())
        }
        val app = context.app
        if (app.settings.notReadyReason() != null) return@withContext emptyList()
        val events = app.study.events()
        val now = System.currentTimeMillis()
        val cards = app.db.cards().all()
        val summary = Learning.summary(
            dangerous = Learning.dangerous(events, now).map { it.first },
            weak = Progress.summarize(events, now).weakTopics.map { it.first },
            due = app.db.cards().dueCount(now),
            problems = cards.count { Learning.isProblem(it.front) && it.box < Leitner.LEARNED_BOX },
            noHint = Learning.noHintShare(events, now),
            checkpoints = Learning.checkpoints(events).map { it.second },
            checkpointDue = Learning.checkpointDue(events, now),
            tomorrow = app.schedule.day(LocalDate.now().plusDays(1)).map { it.discipline },
            screen = screenTime(context),
        )
        val r = app.trainer.dayPlan(summary)
        val plan = (r as? TrainResult.Ok)?.value.orEmpty()
        if (plan.isNotEmpty()) p.edit().putString("plan_day", day).putString("plan", Json.encodeToString(list, plan)).apply()
        plan
    }
}

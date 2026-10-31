package app.nastavnik.study

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Виджет на главном экране: сколько карточек ждёт, серия дней и ближайший экзамен;
 * кнопки «Повторить», «Тетрадь» и «Наставник». Обновляется раз в час и после каждого повторения.
 */
class StudyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Перерисовать все виджеты (после повторения, нового экзамена, запуска приложения). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, StudyWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(context.applicationContext, manager, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.app
            val now = System.currentTimeMillis()
            val due = app.db.cards().observeDueCount(now).first()
            val days = app.db.activity().observeAll().first().map { Streaks.day(it.at) }.toSet()
            val streak = Streaks.current(days, Streaks.day(now))
            val exam = app.study.nextExam(now)

            val views = RemoteViews(context.packageName, R.layout.widget_study)
            views.setTextViewText(
                R.id.widget_due,
                if (due > 0) "$due ${ruPlural(due, "карточка ждёт", "карточки ждут", "карточек ждут")}" else "На сегодня всё повторено",
            )
            views.setTextViewText(R.id.widget_streak, if (streak > 0) "$streak ${ruPlural(streak, "день", "дня", "дней")} подряд" else "")
            views.setTextViewText(
                R.id.widget_exam,
                exam?.let {
                    val left = ExamPlan.daysLeft(it.at, now)
                    if (left == 0) "Сегодня экзамен: ${it.subject}" else "${it.subject}: экзамен через $left ${ruPlural(left, "день", "дня", "дней")}"
                } ?: "Застрял — нажми «Наставник», подскажу прямо на записях.",
            )
            views.setOnClickPendingIntent(R.id.widget_review, open(context, MainActivity.SCREEN_REVIEW, 1))
            views.setOnClickPendingIntent(R.id.widget_notebook, open(context, MainActivity.SCREEN_NOTEBOOKS, 2))
            views.setOnClickPendingIntent(R.id.widget_mentor, open(context, MainActivity.ACTION_START_MENTOR, 3))
            views.setOnClickPendingIntent(R.id.widget_root, open(context, MainActivity.SCREEN_HOME, 4))
            manager.updateAppWidget(ids, views)
        }

        private fun open(context: Context, screen: String, code: Int): PendingIntent = PendingIntent.getActivity(
            context,
            100 + code,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

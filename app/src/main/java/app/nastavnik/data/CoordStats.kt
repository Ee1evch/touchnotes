package app.nastavnik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Запись о промахе координат для анализа качества промта и дообучения («бесплатная разметка»). */
@Serializable
data class CoordMissFeedback(
    val timestamp: Long,
    val model: String,
    val question: String? = null,
    val box: String? = null,
    val line: Int? = null,
    val userReported: Boolean = false,
    val details: String? = null,
)

/** Сводка качества координат: всего проверок, попаданий, промахов и жалоб пользователя. */
data class CoordSummary(
    val total: Int,
    val hits: Int,
    val misses: Int,
    val userMisses: Int,
) {
    val totalMisses: Int get() = misses + userMisses
    val missPercent: Float get() = if (total > 0) (totalMisses.toFloat() / total * 100f).coerceAtMost(100f) else 0f
    val hitPercent: Float get() = if (total > 0) (100f - missPercent).coerceAtLeast(0f) else 100f
}

/** Вспомогательные чистые функции для работы с логом разметки промахов (для тестов без Android). */
object CoordFeedbackLog {
    private val json = Json { ignoreUnknownKeys = true }
    const val MAX_FEEDBACK = 100

    fun add(list: List<CoordMissFeedback>, entry: CoordMissFeedback): List<CoordMissFeedback> =
        (list + entry).takeLast(MAX_FEEDBACK)

    fun encode(list: List<CoordMissFeedback>): String = json.encodeToString(list)

    fun decode(raw: String?): List<CoordMissFeedback> =
        raw?.let { runCatching { json.decodeFromString<List<CoordMissFeedback>>(it) }.getOrNull() }.orEmpty()

    fun calculateSummary(total: Int, hits: Int, misses: Int, userMisses: Int): CoordSummary =
        CoordSummary(total, hits, misses, userMisses)
}

/**
 * Подсчёт доли промахов координат модели и сбор разметки «Не здесь»
 * для оценки влияния изменений в системном промте.
 */
class CoordStats(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun record(hit: Boolean, model: String = "", line: Int? = null, box: String? = null, question: String? = null) {
        val total = prefs.getInt(KEY_TOTAL, 0) + 1
        val hits = prefs.getInt(KEY_HITS, 0) + (if (hit) 1 else 0)
        val misses = prefs.getInt(KEY_MISSES, 0) + (if (hit) 0 else 1)
        prefs.edit {
            putInt(KEY_TOTAL, total)
            putInt(KEY_HITS, hits)
            putInt(KEY_MISSES, misses)
        }
        if (!hit) {
            saveFeedback(
                CoordMissFeedback(
                    timestamp = System.currentTimeMillis(),
                    model = model,
                    question = question,
                    box = box,
                    line = line,
                    userReported = false,
                    details = "Рамка модели не легла на сегменты чернил (авто-верификация)",
                ),
            )
        }
    }

    @Synchronized
    fun recordUserMiss(model: String = "", line: Int? = null, box: String? = null, question: String? = null) {
        val userMisses = prefs.getInt(KEY_USER_MISSES, 0) + 1
        prefs.edit {
            putInt(KEY_USER_MISSES, userMisses)
        }
        saveFeedback(
            CoordMissFeedback(
                timestamp = System.currentTimeMillis(),
                model = model,
                question = question,
                box = box,
                line = line,
                userReported = true,
                details = "Студент нажал «Не здесь» (пользовательская разметка)",
            ),
        )
    }

    fun summary(): CoordSummary {
        val total = prefs.getInt(KEY_TOTAL, 0)
        val hits = prefs.getInt(KEY_HITS, 0)
        val misses = prefs.getInt(KEY_MISSES, 0)
        val userMisses = prefs.getInt(KEY_USER_MISSES, 0)
        return CoordFeedbackLog.calculateSummary(total, hits, misses, userMisses)
    }

    fun feedbackList(): List<CoordMissFeedback> {
        val raw = prefs.getString(KEY_FEEDBACK, null)
        return CoordFeedbackLog.decode(raw)
    }

    @Synchronized
    fun reset() {
        prefs.edit {
            putInt(KEY_TOTAL, 0)
            putInt(KEY_HITS, 0)
            putInt(KEY_MISSES, 0)
            putInt(KEY_USER_MISSES, 0)
            remove(KEY_FEEDBACK)
        }
    }

    private fun saveFeedback(entry: CoordMissFeedback) {
        val list = CoordFeedbackLog.add(feedbackList(), entry)
        val raw = CoordFeedbackLog.encode(list)
        prefs.edit { putString(KEY_FEEDBACK, raw) }
    }

    companion object {
        private const val PREFS_NAME = "coord_stats"
        private const val KEY_TOTAL = "total"
        private const val KEY_HITS = "hits"
        private const val KEY_MISSES = "misses"
        private const val KEY_USER_MISSES = "user_misses"
        private const val KEY_FEEDBACK = "feedback_log"
    }
}

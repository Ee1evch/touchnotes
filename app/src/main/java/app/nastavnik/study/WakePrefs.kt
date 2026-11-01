package app.nastavnik.study

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import androidx.core.content.edit

/**
 * Настройки и состояние будильника — в хранилище, доступном до первой разблокировки после перезагрузки
 * (device protected storage). Иначе телефон, перезагрузившийся ночью, не разбудил бы, пока его не разблокируют.
 * Здесь нет ничего секретного: время, флажки, название пары.
 */
class WakePrefs private constructor(private val p: SharedPreferences) {
    companion object {
        @Volatile private var instance: WakePrefs? = null

        fun of(context: Context): WakePrefs = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val de = app.createDeviceProtectedStorageContext()
                val prefs = de.getSharedPreferences("wake", Context.MODE_PRIVATE)
                // Перенос из обычных настроек (будильник появился раньше этого хранилища).
                if (!prefs.getBoolean("migrated", false) && unlocked(app)) {
                    val old = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    prefs.edit {
                        for ((k, v) in old.all) if (k.startsWith("wake_")) when (v) {
                            is Boolean -> putBoolean(k, v)
                            is Int -> putInt(k, v)
                            is Long -> putLong(k, v)
                            is String -> putString(k, v)
                        }
                        putBoolean("migrated", true)
                    }
                }
                WakePrefs(prefs).also { instance = it }
            }
        }

        fun unlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
    }

    var on: Boolean
        get() = p.getBoolean("wake_on", false)
        set(v) = p.edit { putBoolean("wake_on", v) }
    /** Сколько минут собираться. */
    var readyMin: Int
        get() = p.getInt("wake_ready", 45)
        set(v) = p.edit { putInt("wake_ready", v) }
    /** Дорога до кампуса (для онлайн-пар — 0). */
    var travelMin: Int
        get() = p.getInt("wake_travel", 15)
        set(v) = p.edit { putInt("wake_travel", v) }
    var math: Boolean
        get() = p.getBoolean("wake_math", true)
        set(v) = p.edit { putBoolean("wake_math", v) }
    var check: Boolean
        get() = p.getBoolean("wake_check", true)
        set(v) = p.edit { putBoolean("wake_check", v) }
    /** Когда нажал «Ложусь» (мс). */
    var bedAt: Long
        get() = p.getLong("wake_bed_at", 0L)
        set(v) = p.edit { putLong("wake_bed_at", v) }
    var skipDay: String
        get() = p.getString("wake_skip_day", "").orEmpty()
        set(v) = p.edit { putString("wake_skip_day", v) }
    var doneDay: String
        get() = p.getString("wake_done_day", "").orEmpty()
        set(v) = p.edit { putString("wake_done_day", v) }
    var snoozes: Int
        get() = p.getInt("wake_snoozes", 0)
        set(v) = p.edit { putInt("wake_snoozes", v) }
    var snoozeUntil: Long
        get() = p.getLong("wake_snooze_until", 0L)
        set(v) = p.edit { putLong("wake_snooze_until", v) }

    // Последний поставленный будильник — чтобы звонить без расписания (до разблокировки, после перезагрузки).
    var armed: Long
        get() = p.getLong("wake_armed", 0L)
        set(v) = p.edit { putLong("wake_armed", v) }
    var armedDate: String
        get() = p.getString("wake_armed_date", "").orEmpty()
        set(v) = p.edit { putString("wake_armed_date", v) }
    var lessonAt: Long
        get() = p.getLong("wake_lesson_at", 0L)
        set(v) = p.edit { putLong("wake_lesson_at", v) }
    var lessonLabel: String
        get() = p.getString("wake_lesson_label", "").orEmpty()
        set(v) = p.edit { putString("wake_lesson_label", v) }
    var lessonOnline: Boolean
        get() = p.getBoolean("wake_lesson_online", false)
        set(v) = p.edit { putBoolean("wake_lesson_online", v) }

    /** Пока будильник звонит (и до проверки «Ты встал?») — нельзя удалить приложение и выключить телефон. */
    var guardUntil: Long
        get() = p.getLong("wake_guard_until", 0L)
        set(v) = p.edit { putLong("wake_guard_until", v) }
    val guarding: Boolean get() = guardUntil > System.currentTimeMillis()
    /** Защита включена в настройках (нужна служба специальных возможностей). */
    var guardOn: Boolean
        get() = p.getBoolean("wake_guard_on", true)
        set(v) = p.edit { putBoolean("wake_guard_on", v) }
}

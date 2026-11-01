package app.nastavnik.study

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.nastavnik.ui.WakeActivity

/**
 * Защита будильника (служба специальных возможностей). Работает ТОЛЬКО пока будильник звонит и до конца
 * проверки «Ты встал?» ([WakePrefs.guarding]); в остальное время ничего не делает и ничего не читает.
 *
 * Пока звонит, закрывает:
 *  - меню выключения/перезагрузки (кнопка питания, плитка в шторке) — система его рисует, мы жмём «Назад»;
 *  - удаление приложения (окно установщика пакетов, Google Play);
 *  - системные настройки целиком — там «Остановить», «Удалить» и отключение этой самой защиты.
 * После этого возвращает экран будильника.
 *
 * Что нельзя перехватить ни одному приложению: принудительную перезагрузку (кнопка питания ~10 секунд).
 * На этот случай будильник хранит состояние до разблокировки ([WakePrefs]) и после включения звонит снова.
 */
class WakeGuard : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastBlock = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val w = WakePrefs.of(this)
        if (!w.guardOn || !w.guarding) return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg == packageName) return
        val cls = e.className?.toString().orEmpty()
        val text = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).lowercase()
        val block = when {
            isPowerMenu(pkg, cls, text, e.source) -> true
            pkg.contains("packageinstaller") -> true
            pkg == "com.android.vending" -> true
            isSettings(pkg) -> true
            else -> false
        }
        if (!block) return
        // Закрываем окно и возвращаем будильник. Повторяем дважды: некоторые меню открываются с анимацией.
        val now = System.currentTimeMillis()
        if (now - lastBlock < 250) return
        lastBlock = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 180)
        if (isSettings(pkg) || pkg == "com.android.vending" || pkg.contains("packageinstaller")) {
            main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 260)
        }
        main.postDelayed({ bringAlarmBack() }, 420)
    }

    private fun isSettings(pkg: String) =
        pkg == "com.android.settings" || pkg == "com.samsung.android.settings" || pkg.endsWith(".securitycenter") ||
            pkg == "com.miui.securitycenter" || pkg == "com.coloros.safecenter" || pkg == "com.huawei.systemmanager"

    /** Меню питания: по имени окна, а если оно нестандартное — по кнопкам «Выключить»/«Перезапустить». */
    private fun isPowerMenu(pkg: String, cls: String, text: String, root: AccessibilityNodeInfo?): Boolean {
        val c = cls.lowercase()
        if (c.contains("globalactions") || c.contains("shutdown") || c.contains("powermenu") || c.contains("poweroff")) return true
        if (pkg.contains("globalactions")) return true
        if (pkg != "com.android.systemui" && !pkg.contains("systemui")) return false
        if (hasPowerWords(text)) return true
        // Текст кнопок внутри окна.
        val words = StringBuilder()
        runCatching { collect(root, words, 0) }
        return hasPowerWords(words.toString().lowercase())
    }

    private fun hasPowerWords(t: String): Boolean {
        val off = listOf("выключить", "отключить питание", "power off", "shut down").any { it in t }
        val restart = listOf("перезапустить", "перезагрузить", "перезагрузка", "restart", "reboot").any { it in t }
        return off && restart
    }

    private fun collect(n: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (n == null || depth > 6 || out.length > 2000) return
        n.text?.let { out.append(it).append(' ') }
        n.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun bringAlarmBack() {
        // Экран будильника — только если он ещё звонит (а не ждёт «Ты встал?»).
        val ringing = WakeService.ringing
        runCatching {
            startActivity(
                Intent(this, WakeActivity::class.java)
                    .putExtra(WakeActivity.EXTRA_CHECK, !ringing)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun onInterrupt() {}

    companion object {
        /** Включена ли служба в настройках специальных возможностей. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, WakeGuard::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}

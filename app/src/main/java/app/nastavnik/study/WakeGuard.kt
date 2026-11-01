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
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.WakeActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Служба защиты «Наставника» (специальные возможности). Делает три вещи:
 *
 * 1. Будильник: пока звонит и до конца проверки «Ты встал?» — закрывает меню выключения/перезагрузки,
 *    удаление приложения, Google Play и системные настройки, возвращает экран будильника.
 * 2. Учебный замок: вечером, пока не сделана вечерняя учёба, любое приложение, кроме выбранных один раз
 *    (и телефона, экстренных вызовов, клавиатуры, рабочего стола), сразу закрывается — открывается вечерняя
 *    учёба. Настройки, Google Play и установщик в это время тоже закрыты.
 * 3. Если замок включён — всегда: окна настроек и установщика, где видно «Наставник» (сведения о приложении,
 *    «Удалить», спец. возможности), закрываются. Так замок нельзя снять, удалив приложение или выключив службу.
 *
 * Смотрит только на то, какое приложение на экране, а текст окон читает лишь у настроек, установщика и
 * системного интерфейса. Принудительную перезагрузку и безопасный режим ни одно приложение не перехватит.
 */
class WakeGuard : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastBlock = 0L
    private var always: Set<String> = emptySet()
    private var alwaysAt = 0L
    private var refresher: Job? = null
    private val label by lazy { getString(R.string.app_name).lowercase() }
    private val guardLabel by lazy { getString(R.string.wake_guard_label) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Замок пересчитываем каждые 20 секунд (и сразу — его обновляют экраны учёбы).
        refresher = app.appScope.launch {
            while (isActive) {
                if (WakePrefs.unlocked(this@WakeGuard) && LockPrefs.of(this@WakeGuard).configured) {
                    runCatching { StudyLock.status(this@WakeGuard) }
                }
                delay(20_000)
            }
        }
        main.post(tick)
    }

    override fun onDestroy() {
        refresher?.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Раз в 2 секунды — что на экране (приложение могло быть открыто до начала замка). */
    private val tick = object : Runnable {
        override fun run() {
            if (LockPrefs.of(this@WakeGuard).configured || WakePrefs.of(this@WakeGuard).guarding) {
                val root = runCatching { rootInActiveWindow }.getOrNull()
                val pkg = root?.packageName?.toString()
                if (pkg != null) evaluate(pkg, "", "", root)
            }
            // Пока звонит будильник — чаще: меню выключения закрываем, не дав нажать.
            main.postDelayed(this, if (WakePrefs.of(this@WakeGuard).guarding) 500L else 2_000L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val text = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).lowercase()
        evaluate(pkg, e.className?.toString().orEmpty(), text, runCatching { e.source }.getOrNull())
    }

    private fun evaluate(pkg: String, cls: String, text: String, root: AccessibilityNodeInfo?) {
        if (pkg == packageName) return
        val wake = WakePrefs.of(this)
        val lock = LockPrefs.of(this)
        val system = isSettings(pkg) || pkg.contains("packageinstaller") || pkg == "com.android.vending"

        // Рабочий стол открыт всегда: иначе «Домой» → закрыть → «Домой» по кругу.
        if (isLauncher(pkg)) return

        // 1. Будильник: меню питания — «Назад»; настройки и установщик — экран будильника поверх.
        if (wake.guardOn && wake.guarding) {
            if (isPowerMenu(pkg, cls, text, root)) { pressBack { bringAlarmBack() }; return }
            if (system) { cover(home = true) { bringAlarmBack() }; return }
        }
        if (!lock.configured) return

        // 2. Учебный замок: чужое приложение — «Домой» (оно уходит в фон, видео и звук не идут) и вечерняя учёба.
        // Рабочий стол разрешён, поэтому «Домой» не зацикливается. Решаем только по окну, которое реально активно:
        // события приходят и от фоновых окон (виджет поиска на рабочем столе и т. п.) — из-за них «Наставник»
        // открывался поверх рабочего стола снова и снова.
        // Настройки, которые «Наставник» открыл сам ради разрешения, — можно, если окно не опасное.
        val lockedNow = StudyLock.locked && !lock.emergencyActive
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val granted = lock.grantActive && isSettings(pkg)
        if (lockedNow && active == pkg && ((system && !granted) || (!system && !StudyLockRules.allowed(pkg, lock.allowed, alwaysAllowed())))) {
            cover(home = true) { openEvening() }
            return
        }

        // 3. Самозащита: удаление «Наставника» в установщике и опасные окна настроек про него (удалить,
        // остановить, выключить службу). Окна разрешений не трогаем — иначе разрешение не выдать.
        if (system && dangerous(pkg, text, root)) pressBack { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    private fun isLauncher(pkg: String) = pkg.contains("launcher") || pkg in homes()

    private var homeSet: Set<String> = emptySet()
    private fun homes(): Set<String> {
        if (homeSet.isEmpty()) homeSet = StudyLock.homePackages(this)
        return homeSet
    }

    /** Убрать чужое окно «Домой» и открыть наш экран — не чаще раза в 1,5 секунды. */
    private fun cover(home: Boolean, action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastBlock < 1500) return
        lastBlock = now
        if (home) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            main.postDelayed(action, 250)
        } else {
            action()
        }
    }

    /** Закрыть системное окно (меню питания, диалог удаления) кнопкой «Назад», потом [then]. */
    private fun pressBack(then: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastBlock < 700) return
        lastBlock = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 180)
        main.postDelayed(then, 380)
    }

    private fun alwaysAllowed(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - alwaysAt > 60_000L) { always = StudyLock.alwaysAllowed(this); alwaysAt = now }
        return always
    }

    private fun dangerous(pkg: String, text: String, root: AccessibilityNodeInfo?): Boolean {
        val sb = StringBuilder(text).append(' ')
        runCatching { collect(root, sb, 0, 25) }
        val all = sb.toString().lowercase()
        return if (isSettings(pkg)) StudyLockRules.dangerousSettings(all, label, guardLabel) else label in all
    }

    private fun isSettings(pkg: String) =
        pkg == "com.android.settings" || pkg == "com.samsung.android.settings" || pkg.endsWith(".securitycenter") ||
            pkg == "com.miui.securitycenter" || pkg == "com.coloros.safecenter" || pkg == "com.huawei.systemmanager" ||
            pkg == "com.google.android.settings.intelligence"

    /** Меню питания: по имени окна, а если оно нестандартное — по кнопкам «Выключить»/«Перезапустить». */
    private fun isPowerMenu(pkg: String, cls: String, text: String, root: AccessibilityNodeInfo?): Boolean {
        val c = cls.lowercase()
        if (c.contains("globalactions") || c.contains("shutdown") || c.contains("powermenu") || c.contains("poweroff")) return true
        if (pkg.contains("globalactions")) return true
        if (!pkg.contains("systemui")) return false
        if (hasPowerWords(text)) return true
        val words = StringBuilder()
        runCatching { collect(root, words, 0, 8) }
        return hasPowerWords(words.toString().lowercase())
    }

    private fun hasPowerWords(t: String): Boolean = StudyLockRules.powerMenu(t)

    private fun collect(n: AccessibilityNodeInfo?, out: StringBuilder, depth: Int, max: Int) {
        if (n == null || depth > max || out.length > 6000) return
        n.text?.let { out.append(it).append(' ') }
        n.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1, max)
    }

    private fun bringAlarmBack() {
        val ringing = WakeService.ringing
        runCatching {
            startActivity(
                Intent(this, WakeActivity::class.java)
                    .putExtra(WakeActivity.EXTRA_CHECK, !ringing)
                    .putExtra(WakeAlarm.EXTRA_TEST, WakeService.test)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    private fun openEvening() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_EVENING)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

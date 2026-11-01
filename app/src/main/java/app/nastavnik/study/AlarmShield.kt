package app.nastavnik.study

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.nastavnik.app
import app.nastavnik.ui.SirenActivity
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Щит будильника: пока звучит будильник или сирена, служба защиты кладёт поверх ВСЕГО экрана своё окно
 * (TYPE_ACCESSIBILITY_OVERLAY — выше экрана блокировки, шторки, панели навигации и меню выключения).
 * Даже если меню выключения откроется — нажать в нём нельзя: сверху щит. Сделать можно только одно —
 * решить пример на щите (своя цифровая клавиатура: системная оказалась бы под щитом).
 */
class AlarmShield(private val service: AccessibilityService) {
    companion object {
        /** Ответ на текущий пример — для теста на эмуляторе. */
        @Volatile var answer: Int? = null
            private set
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var kind = ""
    private var clock: TextView? = null
    private var typed = ""
    /** Только что решено — звук ещё мгновение «играет», щит снова не показываем. */
    private var quietUntil = 0L

    /** Показать, убрать или обновить щит по тому, что сейчас звучит. */
    fun update() {
        val want = when {
            SirenService.ringing -> "siren"
            WakeService.ringing -> "wake"
            else -> ""
        }
        if (want != kind) {
            hide()
            if (want.isNotEmpty() && System.currentTimeMillis() > quietUntil) runCatching { show(want) }
        }
        clock?.text = WakeAlarm.hm(LocalDateTime.now())
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        clock = null
        kind = ""
        answer = null
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) paint.isFakeBoldText = true
    }

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(service).apply {
        text = label
        textSize = 22f
        isAllCaps = false
        setTextColor(fg)
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
        setOnClickListener { onClick() }
    }

    private fun show(k: String) {
        kind = k
        typed = ""
        val bg = Color.rgb(18, 18, 22)
        val ink = Color.WHITE
        val soft = Color.argb(170, 255, 255, 255)
        val key = Color.argb(40, 255, 255, 255)
        val accent = Color.rgb(37, 99, 235)

        val wake = WakePrefs.of(service)
        val test = WakeService.test
        val needMath = k == "siren" || wake.math
        val (task, ans) = SleepPlan.task(System.nanoTime())
        answer = if (needMath) ans else null

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        root.addView(text(if (k == "siren") "Задача дня не решена" else if (test) "Проверка будильника" else "Доброе утро", 20f, soft))
        clock = text(WakeAlarm.hm(LocalDateTime.now()), 64f, ink).also { root.addView(it) }
        val info = if (k == "siren") "Мини-задача — 5 минут тишины на основную"
        else wake.lessonAt.takeIf { it > 0 }?.let { "Первая пара в ${WakeAlarm.hm(WakeAlarm.time(it))}\n${wake.lessonLabel}" }.orEmpty()
        if (info.isNotBlank()) root.addView(text(info, 16f, soft).apply { setPadding(0, dp(8), 0, dp(16)) })

        if (needMath) {
            root.addView(text("$task = ?", 34f, ink, bold = true))
            val shown = text("…", 30f, accent).apply { setPadding(0, dp(8), 0, dp(12)) }
            root.addView(shown)
            val grid = GridLayout(service).apply { columnCount = 3; rowCount = 4 }
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "−", "0", "⌫")
            for (kk in keys) {
                val b = button(kk, key, ink) {
                    typed = when (kk) {
                        "⌫" -> typed.dropLast(1)
                        "−" -> if (typed.startsWith("-")) typed.drop(1) else "-$typed"
                        else -> (typed + kk).take(6)
                    }
                    shown.text = typed.ifEmpty { "…" }
                    shown.setTextColor(accent)
                    val n = typed.toIntOrNull()
                    if (n == ans) solved(k, test)
                    else if (typed.length >= ans.toString().length) shown.setTextColor(Color.rgb(229, 72, 77))
                }
                grid.addView(b, GridLayout.LayoutParams().apply {
                    width = dp(88); height = dp(64); setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            root.addView(grid)
        } else {
            root.addView(button("Встаю", accent, Color.WHITE) { solved(k, test) }.apply { setPadding(dp(48), dp(16), dp(48), dp(16)) })
        }
        // Отложить (будильник, если можно) и выключить проверку.
        if (k == "wake" && !test && WakeAlarm.canSnooze(service)) {
            root.addView(button("Ещё ${WakeAlarm.SNOOZE_MIN} минут", key, ink) {
                service.app.appScope.launch { WakeAlarm.snooze(service) }
            }.apply { textSize = 16f })
        }
        if (k == "wake" && test) {
            root.addView(button("Выключить проверку", key, ink) { solved(k, true) }.apply { textSize = 16f })
        }

        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, p)
        view = root
    }

    private fun solved(k: String, test: Boolean) {
        hide()
        quietUntil = System.currentTimeMillis() + 3000
        if (k == "siren") {
            Siren.miniSolved(service)
            // Дальше — основная задача: её ответ вводится обычной клавиатурой на экране сирены.
            runCatching { service.startActivity(Intent(service, SirenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            service.app.appScope.launch { WakeAlarm.dismiss(service, test) }
        }
    }
}

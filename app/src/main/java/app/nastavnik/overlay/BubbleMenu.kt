package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.nastavnik.R

/** Меню по долгому нажатию на кнопку: «Спрятать на 10 минут», «Настройки», «Выключить». */
class BubbleMenu(baseContext: Context, private val wm: WindowManager) {
    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private var root: LinearLayout? = null

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, anchorSize: Int, onLeft: Boolean, items: List<Pair<String, () -> Unit>>) {
        dismiss()
        val dp = { v: Float -> OverlayWindows.dp(ctx, v) }
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            background = GradientDrawable().apply {
                setColor(0xFA16161A.toInt())
                cornerRadius = dp(20f).toFloat()
                setStroke(dp(1f), 0x1AFFFFFF)
            }
            elevation = dp(12f).toFloat()
        }
        items.forEach { (label, action) ->
            val tv = TextView(ctx).apply {
                text = label
                textSize = 15.5f
                typeface = Fonts.medium(ctx)
                setTextColor(0xFFF4F4F6.toInt())
                setPadding(dp(14f), dp(12f), dp(28f), dp(12f))
                setBackgroundResource(R.drawable.menu_row_bg)
                setOnClickListener {
                    dismiss()
                    action()
                }
            }
            menu.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // Тап мимо меню закрывает его.
        menu.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
            false
        }
        val params = OverlayWindows.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindows.PASS_THROUGH_FLAGS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            gravity = Gravity.TOP or if (onLeft) Gravity.START else Gravity.END
            val screen = OverlayWindows.screenSize(ctx)
            x = if (onLeft) anchorX + anchorSize + dp(8f) else screen.x - anchorX + dp(8f)
            y = (anchorY - dp(10f)).coerceAtLeast(0)
        }
        try {
            wm.addView(menu, params)
            root = menu
        } catch (_: Exception) {
        }
    }

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}

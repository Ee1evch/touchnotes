package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.nastavnik.R

/** Пункт меню по долгому нажатию: подпись, значок и действие. */
data class MenuEntry(val label: String, val icon: Int, val action: () -> Unit)

/** Меню по долгому нажатию на кнопку: «Следить за решением», «Новая задача», «Настройки», «Выключить»… */
class BubbleMenu(baseContext: Context, private val wm: WindowManager) {
    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private var root: LinearLayout? = null

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, anchorSize: Int, onLeft: Boolean, items: List<MenuEntry>) {
        dismiss()
        val dp = { v: Float -> OverlayWindows.dp(ctx, v) }
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            background = GlassDrawable(ctx, dp(20f).toFloat(), 0.86f)
            elevation = dp(12f).toFloat()
        }
        items.forEach { item ->
            // Строка как в коротком меню у кнопки: графитовый значок в стеклянном квадрате и подпись.
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(6f), dp(24f), dp(6f))
                setBackgroundResource(R.drawable.menu_row_bg)
                contentDescription = item.label
                setOnClickListener {
                    dismiss()
                    item.action()
                }
            }
            val icon = ImageView(ctx).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(GlassInk.TEXT)
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(9f).toFloat()
                    setColor(0x0F16161A)
                    setStroke(dp(0.8f), 0xE6FFFFFF.toInt())
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(12f) })
            row.addView(
                TextView(ctx).apply {
                    text = item.label
                    textSize = 15f
                    typeface = Fonts.medium(ctx)
                    setTextColor(GlassInk.TEXT)
                },
            )
            menu.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)))
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
            // Кнопка внизу экрана — меню поднимаем, чтобы оно не уходило за край.
            menu.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(screen.x, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(screen.y, android.view.View.MeasureSpec.AT_MOST),
            )
            val area = OverlayWindows.contentRect(ctx)
            y = (anchorY - dp(10f)).coerceAtMost(area.bottom - menu.measuredHeight - dp(8f)).coerceAtLeast(area.top)
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

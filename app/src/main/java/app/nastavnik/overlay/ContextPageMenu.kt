package app.nastavnik.overlay

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.nastavnik.R

/** Выбор ранее просмотренных страниц поверх другого приложения. */
class ContextPageMenu(base: Context, private val wm: WindowManager) {
    private val context = ContextThemeWrapper(base, R.style.Theme_Nastavnik_Overlay)
    private var root: LinearLayout? = null
    private fun dp(value: Float) = OverlayWindows.dp(context, value)

    fun show(ids: List<Long>, selected: Set<Long>?, onSave: (Set<Long>?) -> Unit) {
        dismiss()
        var all = selected == null
        val chosen = (selected ?: ids.toSet()).toMutableSet()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GlassDrawable(context, dp(20f).toFloat(), 0.96f)
        }
        container.addView(TextView(context).apply {
            text = "Страницы для наставника"
            textSize = 18f
            setTextColor(GlassInk.TEXT)
        })
        container.addView(TextView(context).apply {
            text = "Текущий экран отправляется всегда. Номера — в порядке конспекта."
            textSize = 13f
            setTextColor(GlassInk.TEXT)
            setPadding(0, dp(6f), 0, dp(6f))
        })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val checks = ids.mapIndexed { i, id ->
            CheckBox(context).apply {
                text = "Страница ${i + 1}"
                setTextColor(GlassInk.TEXT)
                isChecked = id in chosen
                setOnCheckedChangeListener { _, checked ->
                    all = false
                    if (checked) chosen.add(id) else chosen.remove(id)
                }
                list.addView(this)
            }
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(context).apply {
            text = "Все"
            setOnClickListener { checks.forEach { it.isChecked = true }; all = true }
        })
        row.addView(Button(context).apply {
            text = "Снять все"
            setOnClickListener { checks.forEach { it.isChecked = false }; all = false }
        })
        container.addView(row)
        val area = OverlayWindows.contentRect(context)
        container.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(dp(300f), minOf(dp(320f), area.height() / 2)))
        container.addView(Button(context).apply {
            text = "Готово"
            setOnClickListener { onSave(if (all) null else chosen.toSet()); dismiss() }
        })
        val params = OverlayWindows.params(
            minOf(dp(340f), area.width()), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(container, params); root = container }
    }

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}

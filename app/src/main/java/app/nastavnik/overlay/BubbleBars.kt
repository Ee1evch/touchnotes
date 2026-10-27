package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.nastavnik.R
import app.nastavnik.geom.IRect

/** Где кнопка и с какой стороны экрана она прилипла — от этого зависит, куда раскрываются меню. */
data class BubbleAnchor(val rect: IRect, val onLeft: Boolean)

/** Пункт меню у кнопки. */
data class BarItem(
    val label: String,
    val icon: Int? = null,
    val tint: Int? = null,
    val primary: Boolean = false,
    val action: () -> Unit,
)

/** Светлая карточка (строка вопроса). */
private fun card(ctx: Context, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(Color.WHITE)
    cornerRadius = radius
    setStroke(OverlayWindows.dp(ctx, 1f), 0x14000000)
}

/** Тёмная «графитовая» карточка: меню и сообщения у кнопки хорошо видны на белом листе. */
private fun darkCard(ctx: Context, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(0xFA16161A.toInt())
    cornerRadius = radius
    setStroke(OverlayWindows.dp(ctx, 1f), 0x1AFFFFFF)
}

private const val ON_DARK = 0xFFF4F4F6.toInt()
private const val ON_DARK_MUTED = 0xFF8E8D98.toInt()

/**
 * Небольшие всплывающие элементы рядом с кнопкой: список действий («Подсказка», «Где ошибка?»…)
 * или сообщение с кнопками («Нет ключа — Настройки», «Чистовик: снимков 2»).
 * Это не окно приложения: они появляются на секунду и исчезают от касания мимо.
 */
class BubbleBar(baseContext: Context, private val wm: WindowManager) {
    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissOnOutside = false
    private var captureHidden = false
    private val autoHide = Runnable { dismiss() }

    /** Что сейчас показано: список действий или сообщение. */
    var showingActions = false
        private set

    val isShowing: Boolean get() = root != null

    var onDismiss: (() -> Unit)? = null

    private fun dp(v: Float) = OverlayWindows.dp(ctx, v)

    /** Меню у кнопки: одна карточка, в ней строки со значками в цветных кружках. */
    fun showActions(anchor: BubbleAnchor, items: List<BarItem>) {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = darkCard(ctx, dp(22f).toFloat())
            elevation = dp(12f).toFloat()
            setPadding(dp(8f), dp(10f), dp(8f), dp(8f))
        }
        val head = TextView(ctx).apply {
            text = "НАСТАВНИК"
            setTextColor(ON_DARK_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Fonts.semibold(ctx)
            letterSpacing = 0.08f
            setPadding(dp(12f), dp(2f), dp(14f), dp(8f))
        }
        col.addView(head)
        items.forEach { item ->
            val row = chip(item) {
                dismiss()
                item.action()
            }
            col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50f)))
        }
        show(anchor, col, outside = true, centerOnBubble = true)
        showingActions = true
    }

    /**
     * @param busy крутить индикатор (идёт работа)
     * @param autoHideMs спрятать само через столько миллисекунд (0 — не прятать)
     */
    fun showMessage(anchor: BubbleAnchor, text: String, items: List<BarItem> = emptyList(), busy: Boolean = false, autoHideMs: Long = 0, outside: Boolean = false) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = darkCard(ctx, dp(22f).toFloat())
            setPadding(dp(18f), dp(14f), dp(12f), dp(14f))
            elevation = dp(12f).toFloat()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (busy) {
            val pb = ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(0xFFA99BFF.toInt())
            }
            row.addView(pb, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(10f) })
        }
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(ON_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.regular(ctx)
            setLineSpacing(0f, 1.12f)
        }
        // Ширину текста считаем сами: так окно сообщения точно вмещает и текст, и кнопки.
        val natural = text.lines().maxOf { tv.paint.measureText(it) }.toInt() + dp(2f)
        row.addView(tv, LinearLayout.LayoutParams(minOf(natural, dp(360f)), LinearLayout.LayoutParams.WRAP_CONTENT))
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ON_DARK_MUTED)
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
            contentDescription = "Закрыть"
            setOnClickListener { dismiss() }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginStart = dp(6f) })
        // Явно WRAP_CONTENT: строка «по ширине родителя» не участвует в ширине карточки, и текст обрезался бы.
        box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (items.isNotEmpty()) {
            val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { item ->
                val b = TextView(ctx).apply {
                    this.text = item.label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Fonts.semibold(ctx)
                    setTextColor(if (item.primary) 0xFF16161A.toInt() else ON_DARK)
                    setBackgroundResource(if (item.primary) R.drawable.light_btn_bg else R.drawable.ghost_dark_btn_bg)
                    setPadding(dp(16f), 0, dp(16f), 0)
                    setOnClickListener { item.action() }
                }
                buttons.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginEnd = dp(6f) })
            }
            box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        }
        show(anchor, box, outside = outside, centerOnBubble = false)
        showingActions = false
        if (autoHideMs > 0) main.postDelayed(autoHide, autoHideMs)
    }

    private fun chip(item: BarItem, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.menu_row_bg)
            setPadding(dp(8f), 0, dp(24f), 0)
            isClickable = true
            setOnClickListener { onClick() }
            contentDescription = item.label
        }
        item.icon?.let {
            val tint = item.tint ?: ON_DARK
            val iv = ImageView(ctx).apply {
                setImageResource(it)
                imageTintList = ColorStateList.valueOf(tint)
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                // Скруглённый квадрат цвета значка.
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.argb(46, Color.red(tint), Color.green(tint), Color.blue(tint)))
                }
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { marginEnd = dp(12f) })
        }
        val tv = TextView(ctx).apply {
            text = item.label
            setTextColor(ON_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Fonts.medium(ctx)
        }
        row.addView(tv)
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(anchor: BubbleAnchor, content: View, outside: Boolean, centerOnBubble: Boolean) {
        dismiss(notify = false)
        val frame = FrameLayout(ctx)
        val m = dp(16f) // место под тень
        frame.setPadding(m, m, m, m)
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && dismissOnOutside) dismiss()
            false
        }
        val screen = OverlayWindows.screenSize(ctx)
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.AT_MOST),
        )
        val w = frame.measuredWidth
        val h = frame.measuredHeight
        val gap = dp(6f)
        val b = anchor.rect
        val x = if (anchor.onLeft) b.right + gap else b.left - gap - w
        val y0 = if (centerOnBubble) b.centerY - h / 2 else b.top
        val area = OverlayWindows.contentRect(ctx)
        val y = y0.coerceIn(area.top, (area.bottom - h).coerceAtLeast(area.top))
        val p = OverlayWindows.absParams(
            x.coerceIn(0, (screen.x - w).coerceAtLeast(0)), y, w, h,
            OverlayWindows.PASS_THROUGH_FLAGS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply { alpha = if (captureHidden) 0f else 1f }
        dismissOnOutside = outside
        try {
            wm.addView(frame, p)
            root = frame
            params = p
        } catch (_: Exception) {
        }
    }

    fun rect(): IRect? = params?.let { IRect(it.x, it.y, it.x + it.width, it.y + it.height) }

    fun setCaptureHidden(hidden: Boolean) {
        captureHidden = hidden
        val r = root ?: return
        val p = params ?: return
        p.alpha = if (hidden) 0f else 1f
        runCatching { wm.updateViewLayout(r, p) }
    }

    fun dismiss() = dismiss(notify = true)

    private fun dismiss(notify: Boolean) {
        main.removeCallbacks(autoHide)
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null
        params = null
        showingActions = false
        if (notify) onDismiss?.invoke()
    }
}

/**
 * Строка «Спросить своими словами» рядом с кнопкой: поле ввода, микрофон и «Отправить».
 * Пока она открыта, окно получает фокус, чтобы появилась клавиатура.
 */
@SuppressLint("ClickableViewAccessibility")
class AskBar(baseContext: Context, private val wm: WindowManager, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onSend(text: String)
        fun onMic()
    }

    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private val imm = ctx.getSystemService(InputMethodManager::class.java)
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var input: EditText? = null
    private var mic: ImageView? = null
    private var draft = ""

    val isShowing: Boolean get() = root != null

    private fun dp(v: Float) = OverlayWindows.dp(ctx, v)

    fun show(anchor: BubbleAnchor) {
        dismiss()
        val screen = OverlayWindows.screenSize(ctx)
        val area = OverlayWindows.contentRect(ctx)
        val frame = FrameLayout(ctx)
        val m = dp(14f) // место под тень
        frame.setPadding(m, m, m, m)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(ctx, dp(28f).toFloat())
            setPadding(dp(18f), dp(6f), dp(6f), dp(6f))
            elevation = dp(10f).toFloat()
        }
        val et = EditText(ctx).apply {
            hint = "Спроси наставника…"
            setText(draft)
            setSelection(draft.length)
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF16161A.toInt())
            setHintTextColor(0xFF8E8D98.toInt())
            typeface = Fonts.regular(ctx)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    send(); true
                } else {
                    false
                }
            }
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val micView = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(0xFF5E5D66.toInt())
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            contentDescription = "Сказать голосом"
            setOnClickListener { callbacks.onMic() }
        }
        row.addView(micView, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        val sendView = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setBackgroundResource(R.drawable.send_btn_bg)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            contentDescription = "Отправить"
            setOnClickListener { send() }
        }
        row.addView(sendView, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply { marginStart = dp(4f) })
        frame.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
            false
        }
        val w = minOf(dp(560f), (screen.x * 0.6f).toInt()).coerceAtLeast(dp(300f)).coerceAtMost(screen.x)
        val b = anchor.rect
        val x = if (anchor.onLeft) b.right + dp(4f) else b.left - dp(4f) - w
        // Клавиатура закрывает низ экрана — строку держим в верхней части.
        val y = minOf(b.top, (area.top + area.height() * 0.3f).toInt()).coerceAtLeast(area.top)
        val p = OverlayWindows.absParams(
            x.coerceIn(0, (screen.x - w).coerceAtLeast(0)), y, w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        try {
            wm.addView(frame, p)
            root = frame
            params = p
            input = et
            mic = micView
            et.post {
                et.requestFocus()
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (_: Exception) {
        }
    }

    private fun send() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        draft = ""
        input?.setText("")
        dismiss()
        callbacks.onSend(text)
    }

    fun setText(text: String) {
        val et = input ?: run { draft = text; return }
        et.setText(text)
        et.setSelection(text.length)
    }

    fun setListening(listening: Boolean) {
        mic?.imageTintList = ColorStateList.valueOf(if (listening) 0xFFE5484D.toInt() else 0xFF5E5D66.toInt())
    }

    fun rect(): IRect? = params?.let { p -> root?.let { IRect(p.x, p.y, p.x + p.width, p.y + it.height) } }

    fun setCaptureHidden(hidden: Boolean) {
        val r = root ?: return
        val p = params ?: return
        p.alpha = if (hidden) 0f else 1f
        runCatching { wm.updateViewLayout(r, p) }
    }

    fun dismiss() {
        val r = root ?: return
        input?.let {
            draft = it.text?.toString().orEmpty()
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
        runCatching { wm.removeView(r) }
        root = null
        params = null
        input = null
        mic = null
    }
}

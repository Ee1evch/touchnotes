package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

sealed interface RegionChoice {
    data class Region(val rect: Rect) : RegionChoice
    data object Full : RegionChoice
    data object Cancel : RegionChoice
}

/**
 * Затемнение поверх экрана, на котором пальцем обводят прямоугольник с задачей.
 * Под затемнением рисуется уже сделанный снимок — поэтому координаты выделения
 * совпадают с пикселями снимка один в один.
 */
class RegionSelector(private val context: Context, private val wm: WindowManager) {

    suspend fun select(shot: Bitmap): RegionChoice = suspendCancellableCoroutine { cont ->
        val root = FrameLayout(context)
        var finished = false
        fun finish(choice: RegionChoice) {
            if (finished) return
            finished = true
            runCatching { wm.removeView(root) }
            if (cont.isActive) cont.resume(choice)
        }

        val canvasView = SelectionCanvas(context, shot)
        root.addView(canvasView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val dp = { v: Float -> OverlayWindows.dp(context, v) }
        val hint = TextView(context).apply {
            text = "Обведи пальцем задачу, которую нужно отправить"
            setTextColor(GlassInk.TEXT)
            textSize = 17f
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GlassDrawable(context, dp(20f).toFloat(), 0.88f)
            typeface = Fonts.medium(context)
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(48f)
        })

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = GlassDrawable(context, dp(28f).toFloat(), 0.88f)
            elevation = dp(6f).toFloat()
        }
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = Fonts.semibold(context)
            setTextColor(if (primary) Color.WHITE else GlassInk.TEXT)
            background = GradientDrawable().apply {
                setColor(if (primary) GlassInk.TEXT else 0x8CFFFFFF.toInt())
                cornerRadius = dp(22f).toFloat()
                if (!primary) setStroke(dp(1f), 0x2416161A)
            }
            setPadding(dp(18f), 0, dp(18f), 0)
            minHeight = dp(44f)
            minimumHeight = dp(44f)
            setOnClickListener { onClick() }
        }
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44f)).apply { marginStart = dp(4f); marginEnd = dp(4f) } }
        bar.addView(button("Отмена", false) { finish(RegionChoice.Cancel) }, lp())
        bar.addView(button("Весь экран", false) { finish(RegionChoice.Full) }, lp())
        val done = button("Отправить область", true) {
            val r = canvasView.selectionInShot()
            finish(if (r != null) RegionChoice.Region(r) else RegionChoice.Full)
        }
        done.isEnabled = false
        done.alpha = 0.5f
        bar.addView(done, lp())
        canvasView.onSelectionChanged = { has ->
            done.isEnabled = has
            done.alpha = if (has) 1f else 0.5f
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(40f)
        })

        try {
            wm.addView(root, OverlayWindows.fullScreenParams())
        } catch (e: Exception) {
            finish(RegionChoice.Full)
        }
        cont.invokeOnCancellation { root.post { runCatching { wm.removeView(root) } } }
    }

    @SuppressLint("ViewConstructor")
    private class SelectionCanvas(context: Context, private val shot: Bitmap) : View(context) {
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = resources.displayMetrics.density * 2.5f
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var startX = 0f
        private var startY = 0f
        private var sel: RectF? = null
        private val loc = IntArray(2)
        var onSelectionChanged: ((Boolean) -> Unit)? = null

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            // Пиксель снимка (x, y) лежит на экране в (x, y): сдвигаем на положение окна.
            canvas.drawBitmap(shot, -loc[0].toFloat(), -loc[1].toFloat(), null)
            val s = sel
            if (s == null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                return
            }
            canvas.drawRect(0f, 0f, width.toFloat(), s.top, dim)
            canvas.drawRect(0f, s.bottom, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, s.top, s.left, s.bottom, dim)
            canvas.drawRect(s.right, s.top, width.toFloat(), s.bottom, dim)
            canvas.drawRect(s, border)
            val r = resources.displayMetrics.density * 5f
            canvas.drawCircle(s.left, s.top, r, corner)
            canvas.drawCircle(s.right, s.top, r, corner)
            canvas.drawCircle(s.left, s.bottom, r, corner)
            canvas.drawCircle(s.right, s.bottom, r, corner)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    sel = RectF(startX, startY, startX, startY)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    sel = RectF(min(startX, e.x), min(startY, e.y), max(startX, e.x), max(startY, e.y))
                    val s = sel!!
                    val ok = s.width() > MIN_SIDE * resources.displayMetrics.density && s.height() > MIN_SIDE * resources.displayMetrics.density
                    if (e.actionMasked == MotionEvent.ACTION_UP && !ok) sel = null
                    onSelectionChanged?.invoke(sel != null && ok)
                }
            }
            invalidate()
            return true
        }

        fun selectionInShot(): Rect? {
            val s = sel ?: return null
            getLocationOnScreen(loc)
            return Rect(
                (s.left + loc[0]).toInt(),
                (s.top + loc[1]).toInt(),
                (s.right + loc[0]).toInt(),
                (s.bottom + loc[1]).toInt(),
            )
        }

        companion object {
            private const val MIN_SIDE = 40f
        }
    }
}

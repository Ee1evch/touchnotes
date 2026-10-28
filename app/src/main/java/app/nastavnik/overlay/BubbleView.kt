package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import app.nastavnik.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Круглая плавающая кнопка. Реагирует только на палец: касание стилусом или ластиком
 * не нажимает кнопку, а сообщает [Listener.onStylus], чтобы окно временно стало «прозрачным» для касаний.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onDragStart()
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
        fun onStylus()
    }

    private val density = resources.displayMetrics.density
    // Стеклянный круг: светлый, полупрозрачный, с бликом сверху, светлой кромкой и мягкой тенью.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(7 * density, 0f, 2.5f * density, 0x3316161A)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = Color.argb(40, 20, 20, 30)
    }
    // Графитовая дуга бежит по кромке, пока наставник думает.
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF16161A.toInt()
    }
    private val arcRect = android.graphics.RectF()
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_sparkle)!!.mutate().apply { setTint(0xFF16161A.toInt()) }
    private var angle = 0f
    private var spinner: ValueAnimator? = null

    /** Пока нейросеть думает, по кромке бежит дуга. */
    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            spinner?.cancel()
            spinner = if (value) Rainbow.spinner(1400) { angle = it; invalidate() }.also { it.start() } else null
            invalidate()
        }

    /** Включена фоновая проверка — маленькая зелёная точка на кнопке. */
    var watching: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4ADE80.toInt() }
    private val dotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val flashRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private var flashUntil = 0L

    /** Короткая вспышка кольца одним цветом: например, зелёным — «проверил, всё верно». */
    fun flash(color: Int, ms: Long = 1600) {
        flashRing.color = color
        flashUntil = System.currentTimeMillis() + ms
        invalidate()
        postDelayed({ invalidate() }, ms + 20)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var tracking = false
    private val longPressRunnable = Runnable {
        if (tracking && !dragging) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPress()
        }
    }

    init {
        contentDescription = "Наставник"
        isHapticFeedbackEnabled = true
    }

    init {
        // Тень у круга рисуется только программно.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val top = h * 0.1f
        val bottom = h * 0.9f
        fill.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(235, 255, 255, 255), Color.argb(190, 246, 246, 250), Shader.TileMode.CLAMP)
        ring.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(255, 255, 255, 255), Color.argb(70, 255, 255, 255), Shader.TileMode.CLAMP)
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 5 * density
        val r = width.coerceAtMost(height) / 2f - pad
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, hairline)
        if (System.currentTimeMillis() < flashUntil) {
            canvas.drawCircle(cx, cy, r - flashRing.strokeWidth / 2, flashRing)
        } else {
            canvas.drawCircle(cx, cy + 0.6f * density, r - ring.strokeWidth, ring)
            if (busy) {
                val ar = r - arc.strokeWidth / 2
                arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
                canvas.drawArc(arcRect, angle - 90f, 70f, false, arc)
            }
        }
        val s = (r * 0.86f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
        if (watching) {
            val dr = 4.5f * density
            val dx = cx + r * 0.68f
            val dy = cy - r * 0.68f
            canvas.drawCircle(dx, dy, dr + 1.5f * density, dotBorder)
            canvas.drawCircle(dx, dy, dr, dot)
        }
    }

    private fun isPen(event: MotionEvent): Boolean {
        val tool = event.getToolType(event.actionIndex.coerceAtLeast(0))
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Стилус завис над кнопкой — значит, сейчас будут писать: пропускаем касания сквозь окно.
        if (isPen(event) && (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)) {
            listener.onStylus()
            return true
        }
        return super.onHoverEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isPen(event)) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) listener.onStylus()
            cancelTracking()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                longPressed = false
                tracking = true
                postDelayed(longPressRunnable, longPressTimeout)
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && !longPressed && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    listener.onDragStart()
                }
                if (dragging) listener.onDrag(dx.toInt(), dy.toInt())
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                removeCallbacks(longPressRunnable)
                isPressed = false
                tracking = false
                when {
                    dragging -> listener.onDragEnd()
                    !longPressed && abs(event.rawX - downX) <= touchSlop -> {
                        performClick()
                        listener.onTap()
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener.onDragEnd()
                cancelTracking()
            }
            MotionEvent.ACTION_POINTER_DOWN -> Unit
        }
        return true
    }

    private fun cancelTracking() {
        removeCallbacks(longPressRunnable)
        tracking = false
        dragging = false
        isPressed = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

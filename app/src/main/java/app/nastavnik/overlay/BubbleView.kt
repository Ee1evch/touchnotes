package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_bulb)!!.mutate().apply { setTint(Color.WHITE) }

    var busy: Boolean = false
        set(value) {
            field = value
            invalidate()
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

    override fun onDraw(canvas: Canvas) {
        val r = width.coerceAtMost(height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        fill.color = ContextCompat.getColor(context, if (busy) R.color.accent_dark else R.color.accent)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth, fill)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)
        val s = (r * 1.05f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
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

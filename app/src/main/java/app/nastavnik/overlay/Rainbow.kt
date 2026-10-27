package app.nastavnik.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/** Радуга Google: синий → красный → жёлтый → зелёный → снова синий (замкнутый круг для SweepGradient). */
object Rainbow {
    val COLORS = intArrayOf(
        Color.parseColor("#4285F4"),
        Color.parseColor("#9B72CB"),
        Color.parseColor("#EA4335"),
        Color.parseColor("#FBBC04"),
        Color.parseColor("#34A853"),
        Color.parseColor("#4285F4"),
    )

    /** Бесконечное вращение градиента: угол в градусах передаётся в [onAngle]. */
    fun spinner(durationMs: Long, onAngle: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { onAngle(it.animatedValue as Float) }
        }
}

/**
 * Фон плавающей карточки «как у Google»: белая скруглённая карточка с радужной светящейся рамкой.
 * Свечение рисуется в поле [glow] вокруг карточки, поэтому у View нужен такой же внутренний отступ.
 */
class GlowCardDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    val glow = (10 * density).toInt()
    private val radius = 28 * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val strokes = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9 * density; alpha = 28 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * density; alpha = 60 },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; alpha = 255 },
    )
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var angle = 0f
    private var spinner: ValueAnimator? = null

    /** «Думает» — рамка вращается, как подсветка Gemini. */
    var thinking: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                spinner = Rainbow.spinner(2600) { angle = it; invalidateSelf() }.also { it.start() }
            } else {
                spinner?.cancel()
                spinner = null
                invalidateSelf()
            }
        }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        shader = SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(), Rainbow.COLORS, null)
        strokes.forEach { it.shader = shader }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left + glow.toFloat(), b.top + glow.toFloat(), b.right - glow.toFloat(), b.bottom - glow.toFloat())
        matrix.setRotate(angle, b.exactCenterX(), b.exactCenterY())
        shader?.setLocalMatrix(matrix)
        // Мягкое свечение снаружи, затем белая карточка и тонкая радужная рамка поверх.
        strokes[0].let { canvas.drawRoundRect(rect, radius, radius, it) }
        strokes[1].let { canvas.drawRoundRect(rect, radius, radius, it) }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, strokes[2])
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Корневой контейнер окна: сообщает о любом касании внутри (чтобы окно «проснулось»)
 * и о касании мимо него (ACTION_OUTSIDE — человек пишет в Touchnotes).
 */
class TouchAwareFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var onInsideTouch: (() -> Unit)? = null
    var onOutsideTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                onOutsideTouch?.invoke()
                return false
            }
            MotionEvent.ACTION_DOWN -> onInsideTouch?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }
}

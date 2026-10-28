package app.nastavnik.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * «Жидкое стекло» для окон поверх Touchnotes: полупрозрачная светлая пластина, сквозь которую видны записи,
 * блик сверху, светлая кромка и тонкая тёмная линия по краю — чтобы стекло читалось и на белом листе.
 * Настоящего размытия под окном Android поверх чужих приложений не даёт, поэтому стекло — матовое за счёт
 * плотности: текст на нём читается, а записи под ним просвечивают.
 *
 * @param opacity насколько стекло плотное: 0…1 (меню плотнее, заметки прозрачнее)
 * @param radius скругление; для круглой кнопки — половина размера
 */
class GlassDrawable(context: Context, private val radius: Float, private val opacity: Float = 0.78f) : Drawable() {
    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * d
        color = Color.argb(30, 20, 20, 30)
    }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(0.6f * d, 0.6f * d)
        val top = (255 * (opacity + 0.08f).coerceAtMost(0.96f)).toInt()
        val bottom = (255 * (opacity - 0.1f).coerceAtLeast(0.3f)).toInt()
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(Color.argb(top, 255, 255, 255), Color.argb(bottom, 248, 248, 251)),
            null, Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(Color.argb(245, 255, 255, 255), Color.argb(80, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val r = radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, hairline)
        canvas.save()
        canvas.translate(0f, 0.6f * d)
        canvas.drawRoundRect(rect, r, r, rim)
        canvas.restore()
    }

    /** Тень окна по форме стекла и мягкая: под полупрозрачной пластиной она лишь чуть затемняет фон. */
    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(b.left, b.top, b.right, b.bottom, radius.coerceAtMost(minOf(b.width(), b.height()) / 2f))
        outline.alpha = 0.22f
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Цвета текста и значков на стекле. */
object GlassInk {
    const val TEXT = 0xFF16161A.toInt()
    const val MUTED = 0xFF6B6A73.toInt()
}

package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.nastavnik.ai.MarkKind
import app.nastavnik.geom.Side

/** Цвета заметок: ошибка — красная, подсказка — синяя, верно — зелёная, теория — фиолетовая. */
object NoteColors {
    fun of(kind: MarkKind): Int = when (kind) {
        MarkKind.ERROR -> Color.parseColor("#EA4335")
        MarkKind.HINT -> Color.parseColor("#4285F4")
        MarkKind.OK -> Color.parseColor("#34A853")
        MarkKind.THEORY -> Color.parseColor("#9B72CB")
        MarkKind.NOTE -> Color.parseColor("#4285F4")
    }

    /** Значок в кружке у рамки. */
    fun badge(kind: MarkKind): String = when (kind) {
        MarkKind.ERROR -> "!"
        MarkKind.HINT -> "?"
        MarkKind.OK -> "✓"
        MarkKind.THEORY -> "i"
        MarkKind.NOTE -> "•"
    }

    /** Подсказка и заметка — с радужной рамкой, как у Gemini; остальные — своим цветом. */
    fun rainbow(kind: MarkKind) = kind == MarkKind.HINT || kind == MarkKind.NOTE
}

/**
 * Рамка вокруг места на записи. Рисуется в отдельном окне чуть больше самой рамки:
 * окно не принимает касаний, поэтому внутри рамки можно спокойно писать.
 */
@SuppressLint("ViewConstructor")
class MarkView(context: Context, val kind: MarkKind) : View(context) {
    private val d = resources.displayMetrics.density
    val pad = (12 * d).toInt()
    private val color = NoteColors.of(kind)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        this.color = this@MarkView.color
        if (kind == MarkKind.HINT) pathEffect = DashPathEffect(floatArrayOf(9 * d, 6 * d), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@MarkView.color
        alpha = if (kind == MarkKind.ERROR) 30 else 18
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@MarkView.color }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = 12 * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val label = NoteColors.badge(kind)
    private val rect = RectF()

    init {
        contentDescription = "Отметка: ${kind.label}"
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(pad.toFloat(), pad.toFloat(), width - pad.toFloat(), height - pad.toFloat())
        val r = 10 * d
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)
        val br = 9 * d
        val cx = rect.left
        val cy = rect.top
        canvas.drawCircle(cx, cy, br, badgeFill)
        canvas.drawText(label, cx, cy - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
    }
}

/**
 * Фон заметки: белая карточка с цветной (или радужной) рамкой и «хвостиком»,
 * который указывает на отмеченное место.
 */
class NoteBackground(context: Context, kind: MarkKind) : Drawable() {
    private val d = context.resources.displayMetrics.density
    val tail = (10 * d).toInt()
    private val radius = 20 * d
    private val rainbow = NoteColors.rainbow(kind)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = NoteColors.of(kind)
    }
    private val path = Path()
    private val card = RectF()

    var side: Side = Side.NONE
        set(value) { field = value; rebuild(); invalidateSelf() }

    /** Где по краю карточки стоит хвостик (в пикселях от начала края). */
    var tailAt: Float = 0f
        set(value) { field = value; rebuild(); invalidateSelf() }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        if (rainbow) border.shader = SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(), Rainbow.COLORS, null)
        rebuild()
    }

    /** Отступы, которые нужны содержимому из-за хвостика. */
    fun insets(): IntArray = when (side) {
        Side.RIGHT -> intArrayOf(tail, 0, 0, 0) // заметка справа от места — хвостик слева
        Side.LEFT -> intArrayOf(0, 0, tail, 0)
        Side.BELOW -> intArrayOf(0, tail, 0, 0)
        Side.ABOVE -> intArrayOf(0, 0, 0, tail)
        Side.NONE -> intArrayOf(0, 0, 0, 0)
    }

    private fun rebuild() {
        val b = bounds
        val i = insets()
        val half = border.strokeWidth / 2
        card.set(b.left + i[0] + half, b.top + i[1] + half, b.right - i[2] - half, b.bottom - i[3] - half)
        path.reset()
        path.addRoundRect(card, radius, radius, Path.Direction.CW)
        if (side == Side.NONE) return
        val t = tail.toFloat()
        val w = t * 0.9f
        val tri = Path()
        when (side) {
            Side.RIGHT -> {
                val y = tailAt.coerceIn(card.top + radius, card.bottom - radius)
                tri.moveTo(card.left + 1, y - w); tri.lineTo(b.left + half, y); tri.lineTo(card.left + 1, y + w)
            }
            Side.LEFT -> {
                val y = tailAt.coerceIn(card.top + radius, card.bottom - radius)
                tri.moveTo(card.right - 1, y - w); tri.lineTo(b.right - half, y); tri.lineTo(card.right - 1, y + w)
            }
            Side.BELOW -> {
                val x = tailAt.coerceIn(card.left + radius, card.right - radius)
                tri.moveTo(x - w, card.top + 1); tri.lineTo(x, b.top + half); tri.lineTo(x + w, card.top + 1)
            }
            Side.ABOVE -> {
                val x = tailAt.coerceIn(card.left + radius, card.right - radius)
                tri.moveTo(x - w, card.bottom - 1); tri.lineTo(x, b.bottom - half); tri.lineTo(x + w, card.bottom - 1)
            }
            Side.NONE -> Unit
        }
        tri.close()
        path.op(tri, Path.Op.UNION)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fill)
        canvas.drawPath(path, border)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Корень окна заметки. Стилус над заметкой (или касание им) — сигнал «сейчас буду писать здесь»:
 * заметка становится прозрачной и пропускает касания. Палец работает как обычно.
 */
class NoteRoot(context: Context) : FrameLayout(context) {
    var onStylus: (() -> Unit)? = null

    private fun isPen(e: MotionEvent): Boolean {
        val tool = e.getToolType(e.actionIndex.coerceAtLeast(0))
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isPen(ev)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onStylus?.invoke()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (isPen(event) && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT) {
            onStylus?.invoke()
            return true
        }
        return super.dispatchHoverEvent(event)
    }
}

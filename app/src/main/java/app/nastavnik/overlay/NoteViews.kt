package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.nastavnik.ai.MarkKind
import app.nastavnik.geom.Side

/** Цвета заметок: ошибка — коралловая, следующий шаг — графитовая, верно — зелёная, теория — янтарная. */
object NoteColors {
    fun of(kind: MarkKind): Int = when (kind) {
        MarkKind.ERROR -> Color.parseColor("#E5484D")
        MarkKind.HINT -> Color.parseColor("#3A3A42")
        MarkKind.OK -> Color.parseColor("#1F9D60")
        MarkKind.THEORY -> Color.parseColor("#D9822B")
        MarkKind.NOTE -> Color.parseColor("#3A3A42")
    }

    /** Светлый фон метки вида в заголовке заметки. */
    fun soft(kind: MarkKind): Int = when (kind) {
        MarkKind.ERROR -> Color.parseColor("#FDECEC")
        MarkKind.HINT -> Color.parseColor("#ECECEF")
        MarkKind.OK -> Color.parseColor("#E5F6ED")
        MarkKind.THEORY -> Color.parseColor("#FCF0E3")
        MarkKind.NOTE -> Color.parseColor("#ECECEF")
    }

    /** Текст метки вида — темнее основного цвета, чтобы читался на светлом фоне. */
    fun text(kind: MarkKind): Int = when (kind) {
        MarkKind.ERROR -> Color.parseColor("#C8333A")
        MarkKind.HINT -> Color.parseColor("#2A2A31")
        MarkKind.OK -> Color.parseColor("#17804D")
        MarkKind.THEORY -> Color.parseColor("#A85F14")
        MarkKind.NOTE -> Color.parseColor("#2A2A31")
    }

    /** Значок вида. */
    fun glyph(kind: MarkKind): String = when (kind) {
        MarkKind.ERROR -> "!"
        MarkKind.HINT -> "✎"
        MarkKind.OK -> "✓"
        MarkKind.THEORY -> "i"
        MarkKind.NOTE -> "✦"
    }

    /** Подпись метки вида. */
    fun title(kind: MarkKind): String = when (kind) {
        MarkKind.ERROR -> "Ошибка"
        MarkKind.HINT -> "Следующий шаг"
        MarkKind.OK -> "Верно"
        MarkKind.THEORY -> "Теория"
        MarkKind.NOTE -> "Наставник"
    }
}

/**
 * Отметка на записи в стиле маркера: строка подсвечена полупрозрачным цветом, у ошибки — волнистое
 * подчёркивание, у подсказки — пунктирное место «пиши здесь». Окно не принимает касаний,
 * поэтому поверх отметки можно спокойно писать.
 */
@SuppressLint("ViewConstructor")
class MarkView(context: Context, val kind: MarkKind) : View(context) {
    private val d = resources.displayMetrics.density

    /** Поле вокруг самой отметки: под значок и волну. */
    val pad = (12 * d).toInt()
    private val color = NoteColors.of(kind)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@MarkView.color
        alpha = when (kind) {
            MarkKind.ERROR -> 40
            MarkKind.OK -> 38
            MarkKind.THEORY -> 44
            else -> 16
        }
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = this@MarkView.color
    }
    private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f); strokeWidth = 2f * d }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@MarkView.color }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = 12 * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val wave = Path()

    init {
        contentDescription = "Отметка: ${kind.label}"
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(pad.toFloat(), pad.toFloat(), width - pad.toFloat(), height - pad.toFloat())
        val r = 8 * d
        when (kind) {
            MarkKind.ERROR -> {
                canvas.drawRoundRect(rect, r, r, fill)
                // Волнистое подчёркивание, как у проверки орфографии.
                wave.reset()
                val y = rect.bottom + 3 * d
                val step = 5 * d
                var x = rect.left
                wave.moveTo(x, y)
                var up = true
                while (x < rect.right) {
                    val nx = minOf(x + step, rect.right)
                    wave.quadTo((x + nx) / 2, if (up) y - 3 * d else y + 3 * d, nx, y)
                    up = !up
                    x = nx
                }
                canvas.drawPath(wave, line)
                badge(canvas, rect.left, rect.top)
            }
            MarkKind.OK -> {
                canvas.drawRoundRect(rect, r, r, fill)
                badge(canvas, rect.right, rect.centerY())
            }
            MarkKind.THEORY -> {
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.drawLine(rect.left + 1.5f * d, rect.top + 2 * d, rect.left + 1.5f * d, rect.bottom - 2 * d, line.apply { strokeWidth = 4 * d })
                badge(canvas, rect.left, rect.top)
            }
            else -> {
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.drawRoundRect(rect, r, r, dashed)
                badge(canvas, rect.left, rect.centerY())
            }
        }
    }

    private fun badge(canvas: Canvas, cx: Float, cy: Float) {
        val br = 9.5f * d
        canvas.drawCircle(cx, cy, br + 1.5f * d, badgeRing)
        canvas.drawCircle(cx, cy, br, badgeFill)
        canvas.drawText(NoteColors.glyph(kind), cx, cy - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
    }
}

/**
 * Фон заметки — «жидкое стекло»: полупрозрачная светлая пластина, сквозь которую видны записи,
 * блик сверху, светлая кромка и лёгкая тень; «хвостик» указывает на отмеченное место.
 * Тень рисуется в поле [shadow] вокруг пластины — у окна должен быть такой же внутренний отступ.
 */
class NoteBackground(context: Context) : Drawable() {
    private val d = context.resources.displayMetrics.density
    val tail = (9 * d).toInt()
    val shadow = (8 * d).toInt()
    private val radius = 20 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * d
        color = Color.argb(26, 20, 20, 30)
    }
    // Мягкая тень без размытия (оно не работает с аппаратной отрисовкой и WebView): несколько полупрозрачных обводок.
    private val shadows = listOf(8f, 5f, 2.5f).map { w ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * d
            color = Color.argb(7, 22, 22, 26)
        }
    }
    private val path = Path()
    private val card = RectF()

    var side: Side = Side.NONE
        set(value) { field = value; rebuild(); invalidateSelf() }

    /** Где по краю карточки стоит хвостик (в пикселях от начала края). */
    var tailAt: Float = 0f
        set(value) { field = value; rebuild(); invalidateSelf() }

    override fun onBoundsChange(bounds: Rect) = rebuild()

    /** Отступы содержимого: тень и хвостик. */
    fun insets(): IntArray {
        val s = shadow
        return when (side) {
            Side.RIGHT -> intArrayOf(s + tail, s, s, s) // заметка справа от места — хвостик слева
            Side.LEFT -> intArrayOf(s, s, s + tail, s)
            Side.BELOW -> intArrayOf(s, s + tail, s, s)
            Side.ABOVE -> intArrayOf(s, s, s, s + tail)
            Side.NONE -> intArrayOf(s, s, s, s)
        }
    }

    private fun rebuild() {
        val b = bounds
        val i = insets()
        card.set(b.left + i[0].toFloat(), b.top + i[1].toFloat(), b.right - i[2].toFloat(), b.bottom - i[3].toFloat())
        path.reset()
        // Пока у окна нет размера, рисовать нечего.
        if (card.width() <= 2 * radius || card.height() <= 2 * radius) return
        // Стекло: сверху светлее (блик), книзу прозрачнее — записи под заметкой видны.
        fill.shader = LinearGradient(
            0f, card.top, 0f, card.bottom,
            intArrayOf(Color.argb(GLASS_TOP, 255, 255, 255), Color.argb(GLASS_BOTTOM, 250, 250, 252)),
            null, Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, card.top, 0f, card.bottom,
            intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(90, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
        path.addRoundRect(card, radius, radius, Path.Direction.CW)
        if (side == Side.NONE) return
        val t = tail.toFloat()
        val w = t * 0.95f
        val tri = Path()
        when (side) {
            Side.RIGHT -> {
                val y = tailAt.coerceIn(card.top + radius, card.bottom - radius)
                tri.moveTo(card.left + 1, y - w); tri.lineTo(card.left - t, y); tri.lineTo(card.left + 1, y + w)
            }
            Side.LEFT -> {
                val y = tailAt.coerceIn(card.top + radius, card.bottom - radius)
                tri.moveTo(card.right - 1, y - w); tri.lineTo(card.right + t, y); tri.lineTo(card.right - 1, y + w)
            }
            Side.BELOW -> {
                val x = tailAt.coerceIn(card.left + radius, card.right - radius)
                tri.moveTo(x - w, card.top + 1); tri.lineTo(x, card.top - t); tri.lineTo(x + w, card.top + 1)
            }
            Side.ABOVE -> {
                val x = tailAt.coerceIn(card.left + radius, card.right - radius)
                tri.moveTo(x - w, card.bottom - 1); tri.lineTo(x, card.bottom + t); tri.lineTo(x + w, card.bottom - 1)
            }
            Side.NONE -> Unit
        }
        tri.close()
        path.op(tri, Path.Op.UNION)
    }

    override fun draw(canvas: Canvas) {
        if (path.isEmpty) return
        canvas.save()
        canvas.translate(0f, 2 * d)
        shadows.forEach { canvas.drawPath(path, it) }
        canvas.restore()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, hairline)
        canvas.save()
        canvas.translate(0f, 0.6f * d)
        canvas.drawPath(path, rim)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Насколько непрозрачно стекло вверху и внизу (из 255): текст читается, записи под ним видны. */
        const val GLASS_TOP = 214
        const val GLASS_BOTTOM = 178
    }
}

/** «Ручка» в заголовке заметки: шесть точек — за неё заметку можно перетащить. */
class GripView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A3A2AB") }

    init {
        contentDescription = "Перетащить заметку"
    }

    override fun onDraw(canvas: Canvas) {
        val r = 1.7f * d
        val gx = 5f * d
        val gy = 5f * d
        val cx = width / 2f
        val cy = height / 2f
        for (i in -1..1) for (j in 0..1) canvas.drawCircle(cx + (j - 0.5f) * gx, cy + i * gy, r, dot)
    }
}

/**
 * Корень окна заметки. Стилус над текстом заметки — сигнал «сейчас буду писать здесь»:
 * заметка становится прозрачной и пропускает касания. Но в зоне [dragZone] (заголовок с ручкой)
 * стилус, как и палец, перетаскивает заметку.
 */
class NoteRoot(context: Context) : FrameLayout(context) {
    var onStylus: (() -> Unit)? = null
    var dragZone: View? = null
    private var penInDragZone = false
    private val loc = IntArray(2)
    private val zoneLoc = IntArray(2)

    private fun isPen(e: MotionEvent): Boolean {
        val tool = e.getToolType(e.actionIndex.coerceAtLeast(0))
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun inDragZone(e: MotionEvent): Boolean {
        val z = dragZone ?: return false
        getLocationOnScreen(loc)
        z.getLocationOnScreen(zoneLoc)
        val x = e.x + loc[0] - zoneLoc[0]
        val y = e.y + loc[1] - zoneLoc[1]
        return x >= 0 && y >= 0 && x < z.width && y < z.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isPen(ev)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                penInDragZone = inDragZone(ev)
                if (!penInDragZone) onStylus?.invoke()
            }
            if (!penInDragZone) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (isPen(event) && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT && !inDragZone(event)) {
            onStylus?.invoke()
            return true
        }
        return super.dispatchHoverEvent(event)
    }
}

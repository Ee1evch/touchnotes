package app.nastavnik.ink

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.nastavnik.ai.MarkKind
import java.util.WeakHashMap

/** Отметка наставника на странице: номер (как у заметки в панели), вид и место в единицах страницы. */
data class PageMark(val number: Int, val kind: MarkKind, val rect: RectF)

/**
 * Рисует страницу тетради в единицах страницы (ширина [PAGE_W]): бумагу, подложку, штрихи и отметки.
 * Один и тот же код рисует и лист на экране, и снимок для наставника, и превью.
 */
class PageDrawer {
    private val paths = WeakHashMap<Stroke, Path>()

    private val paperPaint = Paint().apply { color = PAPER }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RULE
        strokeWidth = 1.1f
        style = Paint.Style.STROKE
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MARGIN
        strokeWidth = 1.4f
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Маркер как настоящий: умножается на бумагу и не закрывает текст под собой.
        blendMode = BlendMode.MULTIPLY
    }

    /** Контур штриха кэшируется: штрихи не меняются, а рисуются на каждом кадре. */
    fun pathOf(s: Stroke): Path = paths.getOrPut(s) { toPath(StrokeGeometry.outline(s)) }

    fun toPath(o: FloatArray): Path = Path().apply {
        if (o.size < 4) return@apply
        moveTo(o[0], o[1])
        var i = 2
        while (i + 1 < o.size) {
            lineTo(o[i], o[i + 1])
            i += 2
        }
        close()
    }

    /**
     * Вся страница: лист, подложка, разлиновка, сначала маркер, потом ручка.
     * [visible] — видимая часть страницы в её единицах: разлиновку рисуем только там.
     */
    fun drawPage(c: Canvas, page: InkPage, background: Bitmap?, visible: RectF? = null) {
        val h = page.height
        c.drawRect(0f, 0f, PAGE_W, h, paperPaint)
        if (background != null && !background.isRecycled) {
            c.drawBitmap(background, null, RectF(0f, 0f, PAGE_W, h), bitmapPaint)
        }
        drawPaper(c, page.paper, h, visible)
        for (s in page.strokes) if (s.tool == Tool.HIGHLIGHTER) drawStroke(c, s)
        for (s in page.strokes) if (s.tool == Tool.PEN) drawStroke(c, s)
    }

    fun drawStroke(c: Canvas, s: Stroke, path: Path = pathOf(s)) {
        val p = if (s.tool == Tool.HIGHLIGHTER) markerPaint else inkPaint
        p.color = s.color
        if (s.tool == Tool.HIGHLIGHTER) p.alpha = MARKER_ALPHA
        c.drawPath(path, p)
    }

    private fun drawPaper(c: Canvas, paper: Paper, h: Float, visible: RectF?) {
        val top = (visible?.top ?: 0f).coerceAtLeast(0f)
        val bottom = (visible?.bottom ?: h).coerceAtMost(h)
        val left = (visible?.left ?: 0f).coerceAtLeast(0f)
        val right = (visible?.right ?: PAGE_W).coerceAtMost(PAGE_W)
        when (paper) {
            Paper.GRID -> {
                var y = CELL
                while (y < h) {
                    if (y in top..bottom) c.drawLine(left, y, right, y, linePaint)
                    y += CELL
                }
                var x = CELL
                while (x < PAGE_W) {
                    if (x in left..right) c.drawLine(x, top, x, bottom, linePaint)
                    x += CELL
                }
                // Поля справа, как в школьной тетради.
                c.drawLine(PAGE_W - CELL * 2, top, PAGE_W - CELL * 2, bottom, marginPaint)
            }
            Paper.LINED -> {
                var y = LINE_TOP
                while (y < h - CELL) {
                    if (y in top..bottom) c.drawLine(left, y, right, y, linePaint)
                    y += LINE_STEP
                }
                c.drawLine(LINE_MARGIN, top, LINE_MARGIN, bottom, marginPaint)
            }
            Paper.DOTS -> {
                var y = CELL
                while (y < h) {
                    if (y in top..bottom) {
                        var x = CELL
                        while (x < PAGE_W) {
                            if (x in left..right) c.drawCircle(x, y, 1.6f, dotPaint)
                            x += CELL
                        }
                    }
                    y += CELL
                }
            }
            Paper.BLANK -> Unit
        }
    }

    // ---------------- отметки наставника ----------------

    private val markFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pinText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val wave = Path()

    /**
     * Отметки в единицах страницы: подсветка строки цветом вида и кружок с номером заметки.
     * [scale] — сколько пикселей в единице страницы: значок и линии не должны расти при увеличении.
     */
    fun drawMarks(c: Canvas, marks: List<PageMark>, selected: Int?, scale: Float) {
        val px = 1f / scale.coerceAtLeast(0.05f)
        for (m in marks) {
            val color = kindColor(m.kind)
            val r = RectF(m.rect).apply { inset(-4 * px, -3 * px) }
            val strong = selected == m.number
            markFill.color = color
            markFill.alpha = if (strong) 70 else when (m.kind) {
                MarkKind.ERROR -> 42
                MarkKind.OK -> 38
                MarkKind.THEORY -> 44
                else -> 22
            }
            val radius = 7 * px
            c.drawRoundRect(r, radius, radius, markFill)
            markLine.color = color
            markLine.strokeWidth = 2.2f * px
            markLine.pathEffect = null
            when (m.kind) {
                MarkKind.ERROR -> {
                    wave.reset()
                    val y = r.bottom + 3 * px
                    val step = 5 * px
                    var x = r.left
                    wave.moveTo(x, y)
                    var up = true
                    while (x < r.right) {
                        val nx = minOf(x + step, r.right)
                        wave.quadTo((x + nx) / 2, if (up) y - 3 * px else y + 3 * px, nx, y)
                        up = !up
                        x = nx
                    }
                    c.drawPath(wave, markLine)
                }
                MarkKind.THEORY -> c.drawLine(r.left + 1.5f * px, r.top + 2 * px, r.left + 1.5f * px, r.bottom - 2 * px, markLine.apply { strokeWidth = 4 * px })
                MarkKind.OK -> Unit
                else -> {
                    markLine.pathEffect = DashPathEffect(floatArrayOf(8 * px, 6 * px), 0f)
                    c.drawRoundRect(r, radius, radius, markLine)
                }
            }
            // Номер — тот же, что у заметки в панели наставника.
            val pr = (if (strong) 13f else 11f) * px
            val cx = r.left
            val cy = r.top
            pinPaint.color = color
            c.drawCircle(cx, cy, pr + 2 * px, pinRing)
            c.drawCircle(cx, cy, pr, pinPaint)
            pinText.textSize = 13 * px
            c.drawText(m.number.toString(), cx, cy - (pinText.ascent() + pinText.descent()) / 2, pinText)
        }
    }

    companion object {
        const val PAPER = 0xFFFFFEFB.toInt()
        const val RULE = 0xFFD9E2EC.toInt()
        const val MARGIN = 0x66E5484D
        const val DOT = 0xFFC4CBD5.toInt()
        const val MARKER_ALPHA = 110

        /** Клетка ≈ 5 мм на листе A4. */
        const val CELL = 24f
        const val LINE_STEP = 36f
        const val LINE_TOP = 96f
        const val LINE_MARGIN = 84f

        fun kindColor(k: MarkKind): Int = when (k) {
            MarkKind.ERROR -> 0xFFE5484D.toInt()
            MarkKind.OK -> 0xFF1F9D60.toInt()
            MarkKind.THEORY -> 0xFFD9822B.toInt()
            else -> 0xFF3A3A42.toInt()
        }
    }
}

/** Снимок страницы в картинку — для наставника, превью и чистовика. */
object PageRenderer {
    private val drawer = PageDrawer()

    /** Страница шириной [widthPx] пикселей (высота — по пропорциям страницы). */
    @Synchronized
    fun render(page: InkPage, background: Bitmap?, widthPx: Int): Bitmap {
        val k = widthPx / PAGE_W
        val h = (page.height * k).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(k, k)
        drawer.drawPage(c, page, background)
        return bmp
    }

    /** Ширина, при которой страница занимает не больше [maxPixels]. */
    fun widthFor(page: InkPage, maxPixels: Int): Int =
        kotlin.math.sqrt(maxPixels * PAGE_W / page.height).toInt().coerceIn(200, 2000)
}

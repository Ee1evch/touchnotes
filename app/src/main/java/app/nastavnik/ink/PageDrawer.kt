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
import android.text.StaticLayout
import android.text.TextPaint
import app.nastavnik.ai.Drawing
import app.nastavnik.ai.MarkKind
import java.util.WeakHashMap

/** Отметка наставника на странице: номер (как у заметки в панели), вид и место в единицах страницы. */
data class PageMark(val number: Int, val kind: MarkKind, val rect: RectF, val drawing: Drawing? = null)

/**
 * Рисует страницу тетради в единицах страницы (ширина [PAGE_W]): бумагу, подложку, штрихи и отметки.
 * Один и тот же код рисует и лист на экране, и снимок для наставника, и превью.
 */
class PageDrawer {
    private val lines = WeakHashMap<Stroke, StrokeLines>()
    private val markerPaths = WeakHashMap<Stroke, Path>()

    /**
     * Тёмная тема: бумага тёмная, разлиновка приглушённая, тёмные чернила рисуются светлыми.
     * В файле и в снимках для наставника цвета не меняются — это только отображение.
     */
    var dark: Boolean = false
        set(value) {
            field = value
            paperPaint.color = if (value) PAPER_DARK else PAPER
            linePaint.color = if (value) RULE_DARK else RULE
            dotPaint.color = if (value) DOT_DARK else DOT
            markerPaint.blendMode = if (value) BlendMode.SCREEN else BlendMode.MULTIPLY
        }

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
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DOT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private val markRect = RectF()
    private var paperBuf = FloatArray(1024)
    private var cachedDashPx = -1f
    private var cachedDash: DashPathEffect? = null

    private fun ensurePaperBuf(needed: Int): FloatArray {
        if (paperBuf.size < needed) {
            paperBuf = FloatArray(maxOf(needed, paperBuf.size * 2))
        }
        return paperBuf
    }

    /** Ручка: отрезки с круглыми концами — без щелей и «перекрученных» мест на разворотах пера. */
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Маркер — одной обводкой постоянной толщины: перекрытия не темнеют. */
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // Маркер как настоящий: умножается на бумагу и не закрывает текст под собой.
        blendMode = BlendMode.MULTIPLY
    }

    /** Подготовленный к рисованию штрих кэшируется: штрихи не меняются, а рисуются часто. */
    fun linesOf(s: Stroke): StrokeLines = lines.getOrPut(s) { InkCurve.lines(s) }

    private fun markerPathOf(s: Stroke): Path = markerPaths.getOrPut(s) { polyline(s.points) }

    private fun polyline(pts: FloatArray): Path = Path().apply {
        val n = pts.size / 3
        if (n == 0) return@apply
        moveTo(pts[0], pts[1])
        if (n == 1) lineTo(pts[0] + 0.01f, pts[1])
        for (i in 1 until n) lineTo(pts[i * 3], pts[i * 3 + 1])
    }

    /**
     * Вся страница: лист, подложка, разлиновка, сначала маркер, потом ручка.
     * [visible] — видимая часть страницы в её единицах: разлиновку рисуем только там.
     */
    fun drawPage(c: Canvas, page: InkPage, background: Bitmap?, visible: RectF? = null, exclude: Set<Any> = emptySet()) {
        val h = page.height
        c.drawRect(0f, 0f, PAGE_W, h, paperPaint)
        if (background != null && !background.isRecycled) {
            bgRect.set(0f, 0f, PAGE_W, h)
            c.drawBitmap(background, null, bgRect, bitmapPaint)
        }
        drawPaper(c, page.paper, h, visible)
        val hasExclude = exclude.isNotEmpty()
        for (s in page.strokes) if (s.tool == Tool.HIGHLIGHTER && (!hasExclude || s !in exclude) && visibleStroke(s, visible)) drawStroke(c, s)
        for (t in page.texts) if (!hasExclude || t !in exclude) drawText(c, t)
        for (s in page.strokes) if (s.tool != Tool.HIGHLIGHTER && (!hasExclude || s !in exclude) && visibleStroke(s, visible)) drawStroke(c, s)
    }

    // ---------------- текст ----------------

    private val layouts = WeakHashMap<TextBox, StaticLayout>()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun layoutOf(t: TextBox): StaticLayout = layouts.getOrPut(t) {
        val p = TextPaint(textPaint).apply { textSize = t.size; color = t.color }
        StaticLayout.Builder.obtain(t.text, 0, t.text.length, p, t.width.toInt().coerceAtLeast(20))
            .setLineSpacing(0f, 1.15f)
            .build()
    }

    /** Высота текста после вёрстки — для попадания пальцем и рамки выделения. */
    fun textHeight(t: TextBox): Float = layoutOf(t).height.toFloat()

    fun drawText(c: Canvas, t: TextBox) {
        val l = layoutOf(t)
        l.paint.color = displayColor(t.color)
        c.save()
        c.translate(t.x, t.y)
        l.draw(c)
        c.restore()
    }

    /** Подсветка выделенного: мягкий ореол под штрихом. */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun drawGlow(c: Canvas, s: Stroke, color: Int, extra: Float) {
        val g = linesOf(s)
        glowPaint.color = color
        if (g.dot.isNotEmpty()) {
            glowPaint.strokeWidth = g.dot[2] * 2 + extra
            c.drawPoint(g.dot[0], g.dot[1], glowPaint)
            return
        }
        for (k in g.widths.indices) {
            glowPaint.strokeWidth = g.widths[k] + extra
            c.drawLines(g.lines[k], glowPaint)
        }
    }

    private fun visibleStroke(s: Stroke, v: RectF?): Boolean {
        if (v == null) return true
        val b = s.bounds()
        return b[2] >= v.left && b[0] <= v.right && b[3] >= v.top && b[1] <= v.bottom
    }

    /** Один штрих (и уже записанный, и тот, что пишется сейчас). */
    fun drawStroke(c: Canvas, s: Stroke, prepared: StrokeLines? = null) {
        if (s.tool == Tool.HIGHLIGHTER) {
            markerPaint.color = displayColor(s.color)
            markerPaint.alpha = if (dark) MARKER_ALPHA_DARK else MARKER_ALPHA
            markerPaint.strokeWidth = s.width
            c.drawPath(if (prepared == null) markerPathOf(s) else polyline(s.points), markerPaint)
            return
        }
        val g = prepared ?: linesOf(s)
        val color = displayColor(s.color)
        if (g.dot.isNotEmpty()) {
            dotFill.color = color
            c.drawCircle(g.dot[0], g.dot[1], g.dot[2], dotFill)
            return
        }
        inkPaint.color = color
        for (k in g.widths.indices) {
            inkPaint.strokeWidth = g.widths[k]
            c.drawLines(g.lines[k], inkPaint)
        }
    }

    /** Быстрая отрисовка живого маркера напрямую из накапливаемого [Path] без аллокаций на каждый кадр. */
    fun drawLiveHighlighter(c: Canvas, path: Path, color: Int, width: Float) {
        markerPaint.color = displayColor(color)
        markerPaint.alpha = if (dark) MARKER_ALPHA_DARK else MARKER_ALPHA
        markerPaint.strokeWidth = width
        c.drawPath(path, markerPaint)
    }

    /** Быстрая отрисовка живого штриха ручки по накапливаемым корзинам толщин + хвосту. */
    fun drawLivePen(
        c: Canvas,
        color: Int,
        dotX: Float,
        dotY: Float,
        dotR: Float,
        hasDot: Boolean,
        buckets: Array<FloatArray?>,
        bucketSizes: IntArray,
        tailBuckets: Array<FloatArray?>,
        tailSizes: IntArray,
    ) {
        val dispColor = displayColor(color)
        if (hasDot) {
            dotFill.color = dispColor
            c.drawCircle(dotX, dotY, dotR, dotFill)
            return
        }
        inkPaint.color = dispColor
        for (b in buckets.indices) {
            val fSize = bucketSizes[b]
            val tSize = tailSizes[b]
            if (fSize == 0 && tSize == 0) continue
            inkPaint.strokeWidth = b * 0.4f
            if (fSize > 0) {
                val arr = buckets[b]
                if (arr != null) c.drawLines(arr, 0, fSize, inkPaint)
            }
            if (tSize > 0) {
                val arr = tailBuckets[b]
                if (arr != null) c.drawLines(arr, 0, tSize, inkPaint)
            }
        }
    }

    /** Цвет чернил на экране: в тёмной теме тёмные чернила светлеют, цветные — чуть ярче. */
    fun displayColor(c: Int): Int {
        if (!dark) return c
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        val t = if (lum < 0.3) 0.86f else 0.32f
        fun mix(v: Int) = (v + (236 - v) * t).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(c), mix(r), mix(g), mix(b))
    }

    private fun drawPaper(c: Canvas, paper: Paper, h: Float, visible: RectF?) {
        val top = (visible?.top ?: 0f).coerceAtLeast(0f)
        val bottom = (visible?.bottom ?: h).coerceAtMost(h)
        val left = (visible?.left ?: 0f).coerceAtLeast(0f)
        val right = (visible?.right ?: PAGE_W).coerceAtMost(PAGE_W)
        if (top > bottom || left > right) return
        when (paper) {
            Paper.GRID -> {
                val hCount = (h / CELL).toInt() + 2
                val vCount = (PAGE_W / CELL).toInt() + 2
                val buf = ensurePaperBuf((hCount + vCount) * 4)
                var idx = 0
                var y = CELL
                while (y < h) {
                    if (y in top..bottom) {
                        buf[idx++] = left
                        buf[idx++] = y
                        buf[idx++] = right
                        buf[idx++] = y
                    }
                    y += CELL
                }
                var x = CELL
                while (x < PAGE_W) {
                    if (x in left..right) {
                        buf[idx++] = x
                        buf[idx++] = top
                        buf[idx++] = x
                        buf[idx++] = bottom
                    }
                    x += CELL
                }
                if (idx > 0) c.drawLines(buf, 0, idx, linePaint)
                // Поля справа, как в школьной тетради.
                c.drawLine(PAGE_W - CELL * 2, top, PAGE_W - CELL * 2, bottom, marginPaint)
            }
            Paper.LINED -> {
                val hCount = ((h - LINE_TOP) / LINE_STEP).toInt().coerceAtLeast(0) + 2
                val buf = ensurePaperBuf(hCount * 4)
                var idx = 0
                var y = LINE_TOP
                while (y < h - CELL) {
                    if (y in top..bottom) {
                        buf[idx++] = left
                        buf[idx++] = y
                        buf[idx++] = right
                        buf[idx++] = y
                    }
                    y += LINE_STEP
                }
                if (idx > 0) c.drawLines(buf, 0, idx, linePaint)
                c.drawLine(LINE_MARGIN, top, LINE_MARGIN, bottom, marginPaint)
            }
            Paper.DOTS -> {
                val rows = ((bottom - top) / CELL).toInt().coerceAtLeast(0) + 2
                val cols = ((right - left) / CELL).toInt().coerceAtLeast(0) + 2
                val buf = ensurePaperBuf(rows * cols * 2)
                var idx = 0
                val firstY = ((kotlin.math.ceil((top / CELL).toDouble()).toInt()).coerceAtLeast(1)) * CELL
                val firstX = ((kotlin.math.ceil((left / CELL).toDouble()).toInt()).coerceAtLeast(1)) * CELL
                var y = firstY
                while (y < h && y <= bottom) {
                    var x = firstX
                    while (x < PAGE_W && x <= right) {
                        buf[idx++] = x
                        buf[idx++] = y
                        x += CELL
                    }
                    y += CELL
                }
                if (idx > 0) c.drawPoints(buf, 0, idx, dotPaint)
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

    private fun dashFor(px: Float): DashPathEffect {
        if (kotlin.math.abs(cachedDashPx - px) > 1e-4f || cachedDash == null) {
            cachedDashPx = px
            cachedDash = DashPathEffect(floatArrayOf(8 * px, 6 * px), 0f)
        }
        return cachedDash!!
    }

    /**
     * Отметки в единицах страницы: подсветка строки цветом вида и кружок с номером заметки.
     * [scale] — сколько пикселей в единице страницы: значок и линии не должны расти при увеличении.
     */
    fun drawMarks(c: Canvas, marks: List<PageMark>, selected: Int?, scale: Float) {
        val px = 1f / scale.coerceAtLeast(0.05f)
        for (m in marks) {
            val color = kindColor(m.kind)
            markRect.set(m.rect)
            markRect.inset(-4 * px, -3 * px)
            val r = markRect
            val strong = selected == m.number
            if (m.drawing != null) {
                markLine.color = color
                markLine.strokeWidth = 2.5f * px
                markLine.pathEffect = null
                MentorDrawing.draw(c, m.rect, m.drawing, markLine, px)
                continue
            }
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
                    markLine.pathEffect = dashFor(px)
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
        const val PAPER_DARK = 0xFF202025.toInt()
        const val RULE = 0xFFD9E2EC.toInt()
        const val RULE_DARK = 0xFF34353D.toInt()
        const val DOT_DARK = 0xFF4A4B55.toInt()
        const val MARKER_ALPHA_DARK = 90
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

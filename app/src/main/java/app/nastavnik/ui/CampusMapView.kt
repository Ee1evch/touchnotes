package app.nastavnik.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import app.nastavnik.campus.FloorGeo
import app.nastavnik.campus.GeoLayer
import app.nastavnik.campus.NavGrid
import app.nastavnik.campus.Room
import app.nastavnik.campus.Route
import app.nastavnik.campus.Site
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Карта этажа: помещения цветными треугольниками (drawVertices — быстро даже для тысяч комнат),
 * стены, двери, лестницы, подписи, выбранная аудитория, маршрут и «я здесь».
 * Двумя пальцами — масштаб, одним — двигать, двойное касание — приблизить, касание — выбрать помещение.
 */
class CampusMapView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    var onRoomTap: ((Room?) -> Unit)? = null

    private var site: Site? = null
    var floor: Int = 1
        private set
    private var geo: FloorGeo? = null
    private var grid: NavGrid? = null
    private var rooms: List<Room> = emptyList()
    private var chunks: List<Chunk> = emptyList()
    private var lines: Map<Int, FloatArray> = emptyMap()

    private var selected: Room? = null
    private var route: Route? = null
    private var me: Triple<Int, Double, Double>? = null
    private var dark = false

    // Камера: центр в метрах и пикселей на метр.
    private var cx = 0.0
    private var cy = 0.0
    private var scale = 2.0
    private var minScale = 0.5
    private val maxScale get() = 60.0 * density

    // Анимации: появление этажа, прорисовка маршрута, пульс выбранного и «я здесь».
    private var floorAlpha = 1f
    private var routeReveal = 1f
    private var pulse = 0f
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float; if (selected != null || me != null || route != null) invalidate() }
    }
    private var camAnim: ValueAnimator? = null

    private class Chunk(val verts: FloatArray, val colors: IntArray?, val indices: ShortArray, val color: Int)

    // ---------- данные ----------

    fun show(site: Site, floor: Int, geo: FloorGeo, keepCamera: Boolean) {
        val first = this.site == null || this.site?.id != site.id
        this.site = site
        this.floor = floor
        this.geo = geo
        this.grid = null
        rooms = site.rooms.filter { it.floor == floor && !it.isGeneric }
            .sortedByDescending { it.area }
        chunks = buildChunks(geo)
        lines = geo.layers.filter { !it.triangles }.associate { it.kind to it.verts }
        if (first || !keepCamera) fit(animate = !first)
        // Новый этаж проявляется.
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { floorAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    fun setGrid(g: NavGrid?) { grid = g }

    fun setTheme(dark: Boolean) { this.dark = dark; invalidate() }

    fun select(room: Room?, focus: Boolean) {
        selected = room
        if (room != null && focus) animateTo(room.x, room.y, max(scale, 7.0 * density))
        invalidate()
    }

    fun setRoute(r: Route?, focus: Boolean) {
        route = r
        if (r != null) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                interpolator = PathInterpolator(0.3f, 0f, 0.2f, 1f)
                addUpdateListener { routeReveal = it.animatedValue as Float; invalidate() }
                start()
            }
            if (focus) focusRoute()
        }
        invalidate()
    }

    fun setMe(floor: Int, x: Double, y: Double, focus: Boolean) {
        me = Triple(floor, x, y)
        if (focus) animateTo(x, y, max(scale, 6.0 * density))
        invalidate()
    }

    fun clearMe() { me = null; invalidate() }

    fun focusRoute() {
        val legs = route?.legs?.filter { it.floor == floor }.orEmpty()
        val pts = legs.flatMap { it.points }
        if (pts.isEmpty()) return
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val w = width.coerceAtLeast(1) * 0.8; val h = height.coerceAtLeast(1) * 0.62
        val s = min(w / max(maxX - minX, 8.0), h / max(maxY - minY, 8.0)).coerceIn(minScale, maxScale)
        animateTo((minX + maxX) / 2, (minY + maxY) / 2 - (height * 0.1) / s, s)
    }

    private fun fit(animate: Boolean) {
        val b = geo?.bounds ?: return
        if (width == 0 || height == 0) { post { fit(false) }; return }
        val s = min(width * 0.94 / (b[2] - b[0]), height * 0.86 / (b[3] - b[1]))
        minScale = s * 0.6
        if (animate) animateTo((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0, s) else {
            cx = (b[0] + b[2]) / 2.0; cy = (b[1] + b[3]) / 2.0; scale = s
        }
    }

    fun animateTo(x: Double, y: Double, s: Double) {
        camAnim?.cancel()
        val x0 = cx; val y0 = cy; val s0 = scale
        val target = s.coerceIn(minScale, maxScale)
        camAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                // Масштаб — по логарифму, чтобы приближение ощущалось равномерным.
                scale = s0 * Math.pow(target / s0, t.toDouble())
                cx = x0 + (x - x0) * t
                cy = y0 + (y - y0) * t
                invalidate()
            }
            start()
        }
    }

    /** Треугольники слоя — кусками до 30 000 вершин: индексы drawVertices 16-битные. */
    private fun buildChunks(geo: FloorGeo): List<Chunk> {
        val out = ArrayList<Chunk>()
        for (l in listOf(GeoLayer.ROOMS, GeoLayer.WALLS).mapNotNull { geo.layer(it) }) {
            val idx = l.indices ?: continue
            val color = if (l.kind == GeoLayer.WALLS) WALL else 0
            val map = IntArray(l.verts.size / 2) { -1 }
            var verts = FloatArray(0); var cols = IntArray(0); var ind = ShortArray(0)
            var vn = 0; var inN = 0
            val vBuf = FloatArray(60_000 * 2); val cBuf = IntArray(60_000); val iBuf = ShortArray(idx.size.coerceAtMost(200_000))
            val touched = ArrayList<Int>()
            fun flush() {
                if (inN == 0) return
                verts = vBuf.copyOf(vn * 2); cols = cBuf.copyOf(vn * 2); ind = iBuf.copyOf(inN)
                out += Chunk(verts, if (l.colors != null) cols else null, ind, color)
                touched.forEach { map[it] = -1 }; touched.clear()
                vn = 0; inN = 0
            }
            var i = 0
            while (i + 2 < idx.size) {
                if (vn > 30_000 - 3 || inN + 3 > iBuf.size) flush()
                for (k in 0 until 3) {
                    val v = idx[i + k]
                    var local = map[v]
                    if (local < 0) {
                        local = vn++
                        map[v] = local; touched += v
                        vBuf[local * 2] = l.verts[v * 2]; vBuf[local * 2 + 1] = l.verts[v * 2 + 1]
                        if (l.colors != null) cBuf[local] = l.colors[v]
                    }
                    iBuf[inN++] = local.toShort()
                }
                i += 3
            }
            flush()
        }
        return out
    }

    // ---------- рисование ----------

    private val vertPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val haloPaint = Paint(textPaint).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * density; strokeJoin = Paint.Join.ROUND }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePath = Path()
    private val labelBoxes = ArrayList<RectF>()

    var accent: Int = 0xFF2F6BFF.toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (dark) 0xFF1B1D22.toInt() else 0xFFF4F2EE.toInt())
        val g = geo ?: return
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        val k = 0.985f + 0.015f * floorAlpha
        canvas.scale(scale.toFloat() * k, -scale.toFloat() * k)
        canvas.translate(-cx.toFloat(), -cy.toFloat())
        val alpha = (255 * floorAlpha).toInt()
        val px = 1f / scale.toFloat()

        // Территория: ограды и дорожки — тонко и светло.
        lines[GeoLayer.FENCE]?.let { drawLines(canvas, it, 0xFFB8C4B0.toInt(), 1f * px * density, alpha) }
        lines[GeoLayer.PATHS]?.let { drawLines(canvas, it, 0xFFCBD6C4.toInt(), 1.4f * px * density, alpha) }
        lines[GeoLayer.ROOF]?.let { drawLines(canvas, it, 0xFFC9C3B8.toInt(), 1f * px * density, alpha) }

        vertPaint.alpha = alpha
        for (c in chunks) {
            vertPaint.color = if (c.colors == null) (if (dark) 0xFF9AA0AA.toInt() else c.color) else Color.WHITE
            vertPaint.alpha = alpha
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, c.verts.size, c.verts, 0, null, 0, c.colors, 0, c.indices, 0, c.indices.size, vertPaint)
        }
        if (scale > 3 * density) lines[GeoLayer.FURNITURE]?.let { drawLines(canvas, it, 0xFFB9B4AA.toInt(), 0.8f * px * density, alpha) }
        lines[GeoLayer.STAIRS]?.let { drawLines(canvas, it, 0xFF7C8DB5.toInt(), 1f * px * density, alpha) }
        lines[GeoLayer.DOORS]?.let { drawLines(canvas, it, 0xFFD69A5A.toInt(), 1.2f * px * density, alpha) }

        drawSelectedArea(canvas, px)
        drawRoute(canvas, px)
        canvas.restore()

        drawLabels(canvas)
        drawPins(canvas)
    }

    private fun drawLines(canvas: Canvas, v: FloatArray, color: Int, w: Float, alpha: Int) {
        linePaint.color = color
        linePaint.alpha = alpha
        linePaint.strokeWidth = w
        canvas.drawLines(v, linePaint)
    }

    private fun drawSelectedArea(canvas: Canvas, px: Float) {
        val r = selected ?: return
        if (r.floor != floor) return
        val radius = (14f * density + 6f * density * sin(pulse * Math.PI.toFloat() * 2)) * px
        dotPaint.color = accent
        dotPaint.alpha = (60 * (1 - pulse) + 30).toInt()
        canvas.drawCircle(r.x.toFloat(), r.y.toFloat(), radius * (1 + pulse), dotPaint)
    }

    private fun drawRoute(canvas: Canvas, px: Float) {
        val r = route ?: return
        val legs = r.legs.filter { it.floor == floor }
        for (leg in legs) {
            routePath.reset()
            val pts = leg.points
            if (pts.size < 2) continue
            // Прорисовка: маршрут «вытягивается» от начала к концу.
            val total = leg.meters.coerceAtLeast(0.01)
            var left = total * routeReveal
            routePath.moveTo(pts[0].first.toFloat(), pts[0].second.toFloat())
            for (i in 1 until pts.size) {
                val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
                val d = hypot(x1 - x0, y1 - y0)
                if (d <= left) { routePath.lineTo(x1.toFloat(), y1.toFloat()); left -= d } else {
                    val t = (left / d).coerceIn(0.0, 1.0)
                    routePath.lineTo((x0 + (x1 - x0) * t).toFloat(), (y0 + (y1 - y0) * t).toFloat())
                    break
                }
            }
            routePaint.pathEffect = null
            routePaint.color = Color.WHITE
            routePaint.strokeWidth = 9f * density * px
            canvas.drawPath(routePath, routePaint)
            routePaint.color = accent
            routePaint.strokeWidth = 5.5f * density * px
            canvas.drawPath(routePath, routePaint)
            // Бегущие точки по маршруту — куда идти.
            routePaint.color = 0xCCFFFFFF.toInt()
            routePaint.strokeWidth = 2.2f * density * px
            routePaint.pathEffect = DashPathEffect(floatArrayOf(0.1f * px, 14f * density * px), -pulse * 14f * density * px * 2)
            canvas.drawPath(routePath, routePaint)
            routePaint.pathEffect = null
        }
    }

    private fun toScreen(x: Double, y: Double): Pair<Float, Float> =
        ((x - cx) * scale + width / 2).toFloat() to ((cy - y) * scale + height / 2).toFloat()

    private fun drawLabels(canvas: Canvas) {
        if (floorAlpha < 0.5f) return
        labelBoxes.clear()
        val textColor = if (dark) 0xFFE8E8EC.toInt() else 0xFF2B2E36.toInt()
        val halo = if (dark) 0xCC1B1D22.toInt() else 0xE6FFFFFF.toInt()
        textPaint.color = textColor
        haloPaint.color = halo
        val minArea = (44.0 * density / scale).let { it * it * 0.6 }
        var drawn = 0
        for (r in rooms) {
            if (r.area < minArea && r != selected) continue
            val (sx, sy) = toScreen(r.labels.firstOrNull()?.first ?: r.x, r.labels.firstOrNull()?.second ?: r.y)
            if (sx < -60 || sy < -20 || sx > width + 60 || sy > height + 20) continue
            val text = r.title.let { if (it.length > 22) it.take(21) + "…" else it }
            val w = textPaint.measureText(text)
            val box = RectF(sx - w / 2 - 4, sy - textPaint.textSize, sx + w / 2 + 4, sy + 4)
            if (labelBoxes.any { RectF.intersects(it, box) }) continue
            labelBoxes += box
            canvas.drawText(text, sx, sy, haloPaint)
            canvas.drawText(text, sx, sy, textPaint)
            if (++drawn > 160) break
        }
    }

    private fun drawPins(canvas: Canvas) {
        // Конец и начало маршрута на этом этаже, переходы между этажами.
        route?.let { r ->
            r.legs.forEachIndexed { i, leg ->
                if (leg.floor != floor || leg.points.isEmpty()) return@forEachIndexed
                val (sx, sy) = toScreen(leg.points.first().first, leg.points.first().second)
                val (ex, ey) = toScreen(leg.points.last().first, leg.points.last().second)
                dot(canvas, sx, sy, if (i == 0) 0xFF1FA463.toInt() else 0xFF7C8DB5.toInt(), 7f)
                if (i == r.legs.lastIndex) pin(canvas, ex, ey, accent) else dot(canvas, ex, ey, 0xFF7C8DB5.toInt(), 7f)
            }
        }
        selected?.takeIf { it.floor == floor && route == null }?.let { r ->
            val (x, y) = toScreen(r.x, r.y)
            pin(canvas, x, y, accent)
        }
        me?.takeIf { it.first == floor }?.let { (_, x, y) ->
            val (sx, sy) = toScreen(x, y)
            dotPaint.color = 0xFF2F80FF.toInt()
            dotPaint.alpha = (90 * (1 - pulse)).toInt()
            canvas.drawCircle(sx, sy, (10f + 22f * pulse) * density, dotPaint)
            dot(canvas, sx, sy, 0xFF2F80FF.toInt(), 8f)
        }
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, color: Int, r: Float) {
        dotPaint.color = Color.WHITE; dotPaint.alpha = 255
        canvas.drawCircle(x, y, (r + 2.5f) * density, dotPaint)
        dotPaint.color = color
        canvas.drawCircle(x, y, r * density, dotPaint)
    }

    private val pinPath = Path()
    private fun pin(canvas: Canvas, x: Float, y: Float, color: Int) {
        // Булавка чуть «подпрыгивает» в такт пульсу.
        val lift = (abs(sin(pulse * Math.PI)) * 3f * density).toFloat()
        val r = 11f * density
        val top = y - r * 2.6f - lift
        pinPath.reset()
        pinPath.moveTo(x, y - lift)
        pinPath.cubicTo(x - r * 0.4f, y - r * 1.2f - lift, x - r, y - r * 1.5f - lift, x - r, top + r)
        pinPath.arcTo(RectF(x - r, top, x + r, top + 2 * r), 180f, 180f)
        pinPath.cubicTo(x + r, y - r * 1.5f - lift, x + r * 0.4f, y - r * 1.2f - lift, x, y - lift)
        dotPaint.color = 0x33000000
        canvas.drawOval(RectF(x - r * 0.5f, y - 3 * density, x + r * 0.5f, y + 3 * density), dotPaint)
        dotPaint.color = Color.WHITE
        canvas.save(); canvas.scale(1.12f, 1.08f, x, top + r); canvas.drawPath(pinPath, dotPaint); canvas.restore()
        dotPaint.color = color
        canvas.drawPath(pinPath, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(x, top + r, r * 0.38f, dotPaint)
    }

    // ---------- жесты ----------

    private val scroller = OverScroller(context)
    private var lastFling = 0 to 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor.toDouble()
            val ns = (scale * f).coerceIn(minScale, maxScale)
            // Масштаб вокруг пальцев: точка под ними остаётся на месте.
            val fx = d.focusX - width / 2.0; val fy = d.focusY - height / 2.0
            cx += fx / scale - fx / ns
            cy -= fy / scale - fy / ns
            scale = ns
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); camAnim?.cancel(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            cx += dx / scale; cy -= dy / scale; invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            lastFling = 0 to 0
            scroller.fling(0, 0, -vx.toInt(), -vy.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
            postInvalidateOnAnimation()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val (x, y) = toWorld(e.x, e.y)
            animateTo(x, y, scale * 2.2)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onRoomTap?.invoke(roomAt(e.x, e.y))
            return true
        }
    })

    private fun toWorld(sx: Float, sy: Float) = (cx + (sx - width / 2.0) / scale) to (cy - (sy - height / 2.0) / scale)

    private fun roomAt(sx: Float, sy: Float): Room? {
        val s = site ?: return null
        val (x, y) = toWorld(sx, sy)
        grid?.let { g ->
            val id = g.room(s.grid.row(y), s.grid.col(x))
            if (id != 0) s.byId[floor to id]?.takeIf { !it.isGeneric }?.let { return it }
        }
        // Без сетки — ближайшая подпись в пределах пальца.
        val near = 28 * density / scale
        return rooms.minByOrNull { hypot(it.x - x, it.y - y) }?.takeIf { hypot(it.x - x, it.y - y) < max(near, 6.0) }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - lastFling.first
            val dy = scroller.currY - lastFling.second
            lastFling = scroller.currX to scroller.currY
            cx += dx / scale; cy -= dy / scale
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulseAnim.start() }
    override fun onDetachedFromWindow() { pulseAnim.cancel(); camAnim?.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && geo != null) fit(false)
    }

    companion object {
        private const val WALL = 0xFF3A3F4B.toInt()
    }
}

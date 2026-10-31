package app.nastavnik.ink

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

/**
 * Лист тетради. Стилус пишет (с нажимом), обратный конец стилуса или кнопка на нём — стирают.
 * Один палец двигает лист, два — увеличивают; ладонь, лежащая на экране во время письма, ничего не рисует.
 * Если стилуса нет, можно разрешить писать пальцем ([fingerDraws]) — тогда лист двигают двумя пальцами.
 */
@SuppressLint("ViewConstructor")
class InkView(context: Context) : View(context) {

    interface Listener {
        /** Штрих дописан или стёрт: страницу пора сохранить. */
        fun onPageEdited()

        /** Начали писать — например, чтобы отложить автопроверку. */
        fun onWritingStarted() {}

        /** Изменились возможности «Отменить / Вернуть». */
        fun onUndoStateChanged(canUndo: Boolean, canRedo: Boolean) {}
    }

    var listener: Listener? = null
    private val drawer = PageDrawer()
    private val density = resources.displayMetrics.density

    var page: InkPage? = null
        private set
    private var bgBitmap: Bitmap? = null
    private var undo = UndoStack()

    /** Чем пишем сейчас. */
    var tool: Tool = Tool.PEN
    var color: Int = 0xFF1F4FD1.toInt()
    var penWidth: Float = 3.2f
    var eraser: Boolean = false
    var fingerDraws: Boolean = false

    /** Отметки наставника и выбранная (её подсветка ярче). */
    var marks: List<PageMark> = emptyList()
        set(value) {
            field = value
            shownMarks = value
            invalidate()
        }
    var selectedMark: Int? = null
        set(value) { field = value; invalidate() }

    private val pageMatrix = Matrix()
    private val inverse = Matrix()
    private var fitted = false
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1416161A }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x1F16161A
    }

    /** Показать страницу; у каждой страницы своя история «Отменить». */
    fun setPage(p: InkPage, bg: Bitmap?, history: UndoStack) {
        page = p
        bgBitmap = bg
        undo = history
        marks = emptyList()
        selectedMark = null
        current = null
        fitted = false
        if (width > 0) fitWidth()
        notifyUndo()
        invalidate()
    }

    fun undo() {
        val p = page ?: return
        if (undo.undo(p)) edited()
    }

    fun redo() {
        val p = page ?: return
        if (undo.redo(p)) edited()
    }

    /** Стереть всю страницу (можно отменить). */
    fun clearPage() {
        val p = page ?: return
        if (p.strokes.isEmpty()) return
        undo.push(InkEdit.Cleared(p.strokes.toList()))
        p.strokes.clear()
        p.touch()
        edited()
    }

    private fun edited() {
        notifyUndo()
        invalidate()
        listener?.onPageEdited()
    }

    private fun notifyUndo() = listener?.onUndoStateChanged(undo.canUndo, undo.canRedo)

    // ---------------- масштаб ----------------

    private val scale: Float get() = FloatArray(9).also { pageMatrix.getValues(it) }[Matrix.MSCALE_X]

    private fun fitScale(): Float = (width - 2 * MARGIN * density) / PAGE_W

    /** Лист по ширине экрана, сверху небольшой отступ. */
    fun fitWidth() {
        val k = fitScale()
        pageMatrix.setScale(k, k)
        pageMatrix.postTranslate(MARGIN * density, MARGIN * density)
        fitted = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted || oldw != w) fitWidth()
    }

    /** Плавно показать место на странице (например, отметку из заметки) в середине экрана. */
    fun reveal(r: RectF) {
        if (page == null) return
        val k = scale
        val target = Matrix(pageMatrix)
        val vals = FloatArray(9).also { target.getValues(it) }
        val cx = r.centerX() * k + vals[Matrix.MTRANS_X]
        val cy = r.centerY() * k + vals[Matrix.MTRANS_Y]
        // По горизонтали двигаем, только если место за краем экрана; по вертикали — ставим на уровень глаз.
        val dx = if (cx in 0f..width.toFloat()) 0f else width / 2f - cx
        val dy = height * 0.4f - cy
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            var last = 0f
            addUpdateListener {
                val t = it.animatedValue as Float
                pageMatrix.postTranslate(dx * (t - last), dy * (t - last))
                last = t
                clampMatrix()
                invalidate()
            }
        }
        anim.start()
    }

    private fun clampMatrix() {
        val p = page ?: return
        val v = FloatArray(9).also { pageMatrix.getValues(it) }
        val k = v[Matrix.MSCALE_X]
        val pw = PAGE_W * k
        val ph = p.height * k
        val m = MARGIN * density
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (pw + 2 * m <= width) (width - pw) / 2 else tx.coerceIn(width - pw - m, m)
        ty = if (ph + 2 * m <= height) ty.coerceIn(m, height - ph - m) else ty.coerceIn(height - ph - m * 4, m)
        pageMatrix.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
    }

    // ---------------- рисование ----------------

    private val visible = RectF()

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        canvas.drawColor(DESK)
        canvas.save()
        canvas.concat(pageMatrix)
        val k = scale
        // Тень и край листа.
        canvas.drawRect(-2f / k, 2f / k, PAGE_W + 2f / k, p.height + 5f / k, shadow)
        pageMatrix.invert(inverse)
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        drawer.drawPage(canvas, p, bgBitmap, visible)
        outline.strokeWidth = 1f / k
        canvas.drawRect(0f, 0f, PAGE_W, p.height, outline)
        current?.let { s -> drawer.drawStroke(canvas, s.stroke(), drawer.toPath(StrokeGeometry.outline(s.stroke()))) }
        if (marks.isNotEmpty()) drawer.drawMarks(canvas, marks, selectedMark, k)
        canvas.restore()
    }

    // ---------------- ввод ----------------

    private enum class Mode { NONE, DRAW, ERASE, PAN }

    private var mode = Mode.NONE
    private var activePointer = -1

    /** Текущий штрих: точки в единицах страницы. */
    private inner class Live(val tool: Tool, val color: Int, val width: Float) {
        val pts = ArrayList<Float>(512)
        fun add(x: Float, y: Float, p: Float) {
            pts += x; pts += y; pts += p
        }
        fun stroke() = Stroke(tool, color, width, pts.toFloatArray())
    }

    private var current: Live? = null
    private var erased = mutableListOf<Pair<Int, Stroke>>()
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val pt = FloatArray(2)

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun toPage(x: Float, y: Float): FloatArray {
        pageMatrix.invert(inverse)
        pt[0] = x; pt[1] = y
        inverse.mapPoints(pt)
        return pt
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = page ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointer = e.getPointerId(0)
                val pen = isPen(e, 0)
                mode = when {
                    pen && (eraser || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) -> Mode.ERASE
                    pen -> Mode.DRAW
                    fingerDraws -> if (eraser) Mode.ERASE else Mode.DRAW
                    else -> Mode.PAN
                }
                // Стилус — без буферизации: линия появляется под пером без задержки.
                if (pen && Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
                lastX = e.x; lastY = e.y
                when (mode) {
                    Mode.DRAW -> {
                        listener?.onWritingStarted()
                        current = Live(tool, color, if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_W else penWidth).also { l ->
                            val q = toPage(e.x, e.y)
                            l.add(q[0], q[1], pressure(e, 0, pen))
                        }
                    }
                    Mode.ERASE -> {
                        listener?.onWritingStarted()
                        erased = mutableListOf()
                        eraseAt(p, e.x, e.y)
                    }
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val ai = e.actionIndex
                if (isPen(e, ai) && (mode == Mode.PAN || mode == Mode.NONE)) {
                    // Ладонь легла раньше пера: ладонь не двигает лист, пишет перо.
                    activePointer = e.getPointerId(ai)
                    val erase = eraser || e.getToolType(ai) == MotionEvent.TOOL_TYPE_ERASER
                    mode = if (erase) Mode.ERASE else Mode.DRAW
                    if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
                    if (mode == Mode.DRAW) {
                        listener?.onWritingStarted()
                        current = Live(tool, color, if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_W else penWidth).also { l ->
                            val q = toPage(e.getX(ai), e.getY(ai))
                            l.add(q[0], q[1], e.getPressure(ai))
                        }
                    } else {
                        erased = mutableListOf()
                    }
                    return true
                }
                // Второй палец во время письма пальцем — значит, хотят двигать или увеличивать лист.
                if (!isPen(e, e.findPointerIndex(activePointer).coerceAtLeast(0)) && (mode == Mode.DRAW || mode == Mode.ERASE || mode == Mode.PAN)) {
                    if (mode == Mode.DRAW && (current?.pts?.size ?: 0) < 3 * 6) current = null
                    else if (mode == Mode.DRAW) commitStroke(p)
                    mode = Mode.PAN
                    startTwoFinger(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAW -> {
                        val i = e.findPointerIndex(activePointer).takeIf { it >= 0 } ?: return true
                        val pen = isPen(e, i)
                        val l = current ?: return true
                        for (h in 0 until e.historySize) {
                            val q = toPage(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                            l.add(q[0], q[1], if (pen) e.getHistoricalPressure(i, h) else FINGER_PRESSURE)
                        }
                        val q = toPage(e.getX(i), e.getY(i))
                        l.add(q[0], q[1], pressure(e, i, pen))
                        invalidate()
                    }
                    Mode.ERASE -> {
                        val i = e.findPointerIndex(activePointer).takeIf { it >= 0 } ?: return true
                        for (h in 0 until e.historySize) eraseAt(p, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        eraseAt(p, e.getX(i), e.getY(i))
                    }
                    Mode.PAN -> {
                        if (e.pointerCount >= 2) {
                            twoFinger(e)
                        } else {
                            pageMatrix.postTranslate(e.x - lastX, e.y - lastY)
                            lastX = e.x; lastY = e.y
                            clampMatrix()
                        }
                        invalidate()
                    }
                    Mode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == activePointer && (mode == Mode.DRAW || mode == Mode.ERASE)) {
                // Перо подняли, а ладонь ещё лежит — штрих закончен.
                if (mode == Mode.DRAW) commitStroke(p) else if (erased.isNotEmpty()) {
                    undo.push(InkEdit.Erased(erased.toList()))
                    edited()
                }
                mode = Mode.NONE
            } else if (mode == Mode.PAN) {
                // Остался один палец — продолжаем двигать от него.
                val rest = (0 until e.pointerCount).firstOrNull { it != e.actionIndex } ?: 0
                lastX = e.getX(rest); lastY = e.getY(rest)
                if (e.pointerCount - 1 >= 2) startTwoFinger(e)
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.DRAW -> commitStroke(p)
                    Mode.ERASE -> if (erased.isNotEmpty()) {
                        undo.push(InkEdit.Erased(erased.toList()))
                        edited()
                    }
                    else -> Unit
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun pressure(e: MotionEvent, i: Int, pen: Boolean) = if (pen) e.getPressure(i) else FINGER_PRESSURE

    private fun commitStroke(p: InkPage) {
        val l = current ?: return
        current = null
        if (l.pts.isEmpty()) return
        val pts = StrokeGeometry.thin(l.pts.toFloatArray(), 0.6f)
        val s = Stroke(l.tool, l.color, l.width, pts)
        p.strokes += s
        p.touch()
        undo.push(InkEdit.Added(s))
        edited()
    }

    private fun eraseAt(p: InkPage, x: Float, y: Float) {
        val q = toPage(x, y)
        val r = ERASER_RADIUS * density / scale
        val hit = StrokeGeometry.hit(p.strokes, q[0], q[1], r)
        if (hit.isEmpty()) return
        for (s in hit) {
            val idx = p.strokes.indexOf(s)
            if (idx >= 0) {
                erased += idx to s
                p.strokes.removeAt(idx)
            }
        }
        p.touch()
        invalidate()
    }

    private fun startTwoFinger(e: MotionEvent) {
        if (e.pointerCount < 2) return
        lastFocusX = (e.getX(0) + e.getX(1)) / 2
        lastFocusY = (e.getY(0) + e.getY(1)) / 2
        lastSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun twoFinger(e: MotionEvent) {
        val fx = (e.getX(0) + e.getX(1)) / 2
        val fy = (e.getY(0) + e.getY(1)) / 2
        val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        if (lastSpan > 10f) {
            val k = scale
            val f = (span / lastSpan).coerceIn(0.5f, 2f)
            val nk = (k * f).coerceIn(fitScale() * MIN_ZOOM, fitScale() * MAX_ZOOM)
            pageMatrix.postScale(nk / k, nk / k, fx, fy)
        }
        pageMatrix.postTranslate(fx - lastFocusX, fy - lastFocusY)
        lastFocusX = fx; lastFocusY = fy; lastSpan = span
        clampMatrix()
    }

    companion object {
        /** Отметки на открытом листе — по ним тесты проверяют ответ наставника в тетради. */
        @Volatile
        var shownMarks: List<PageMark> = emptyList()
            private set

        /** Цвет «стола» вокруг листа. */
        const val DESK = 0xFFEDEAE4.toInt()
        private const val MARGIN = 16f
        private const val MIN_ZOOM = 0.6f
        private const val MAX_ZOOM = 6f
        private const val FINGER_PRESSURE = 0.55f
        private const val ERASER_RADIUS = 14f
        const val HIGHLIGHTER_W = 20f
    }
}

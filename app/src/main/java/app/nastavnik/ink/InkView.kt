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
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Лист тетради. Стилус пишет (с нажимом), обратный конец стилуса или кнопка на нём — стирают.
 * Один палец двигает лист (с инерцией), два — увеличивают; двойное касание пальцем — увеличить или
 * вернуть по ширине; касание двумя пальцами — «Отменить». Ладонь, лежащая на экране во время письма,
 * ничего не рисует. Если стилуса нет, можно писать пальцем ([fingerDraws]) — тогда лист двигают двумя.
 *
 * Готовая страница хранится картинкой размером с экран: пока пишешь, перерисовывается только
 * новый штрих, поэтому линия идёт за пером без задержек даже на исписанной странице.
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

        /** Масштаб изменился (1 — лист по ширине экрана). */
        fun onZoomChanged(zoom: Float) {}
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
        set(value) { field = value; invalidate() }
    var fingerDraws: Boolean = false

    /** Тёмная тема: тёмный стол и бумага, светлые чернила (только на экране). */
    var dark: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            drawer.dark = value
            desk = if (value) DESK_DARK else DESK
            invalidateCache()
        }
    private var desk = DESK

    /** Отметки наставника и выбранная (её подсветка ярче). Новые отметки проявляются плавно. */
    var marks: List<PageMark> = emptyList()
        set(value) {
            val appeared = value.isNotEmpty() && value != field
            field = value
            shownMarks = value
            if (appeared) animateMarks() else invalidate()
        }
    var selectedMark: Int? = null
        set(value) { field = value; invalidate() }
    private var marksAlpha = 1f

    private val pageMatrix = Matrix()
    private val inverse = Matrix()
    private var fitted = false
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1416161A }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x1F16161A
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ---------------- страница ----------------

    /**
     * Показать страницу; у каждой страницы своя история «Отменить».
     * [direction] — куда листали (1 — вперёд, −1 — назад, 0 — без анимации).
     */
    fun setPage(p: InkPage, bg: Bitmap?, history: UndoStack, direction: Int = 0) {
        val old = if (direction != 0 && width > 0 && page != null) snapshot() else null
        page = p
        bgBitmap = bg
        undo = history
        marks = emptyList()
        selectedMark = null
        current = null
        fitted = false
        if (width > 0) fitWidth()
        invalidateCache()
        notifyUndo()
        if (old != null) animatePageTurn(old, direction)
    }

    /** Подложка дорисовалась позже (большой PDF) — показать. */
    fun setBackground(bg: Bitmap?) {
        bgBitmap = bg
        invalidateCache()
    }

    fun undo() {
        val p = page ?: return
        if (undo.undo(p)) edited(full = true)
    }

    fun redo() {
        val p = page ?: return
        if (undo.redo(p)) edited(full = true)
    }

    /** Стереть всю страницу (можно отменить). */
    fun clearPage() {
        val p = page ?: return
        if (p.strokes.isEmpty()) return
        undo.push(InkEdit.Cleared(p.strokes.toList()))
        p.strokes.clear()
        p.touch()
        edited(full = true)
    }

    /** Поменялась бумага или другое оформление страницы. */
    fun pageChanged() = invalidateCache()

    private fun edited(full: Boolean) {
        notifyUndo()
        if (full) invalidateCache() else invalidate()
        listener?.onPageEdited()
    }

    private fun notifyUndo() = listener?.onUndoStateChanged(undo.canUndo, undo.canRedo)

    // ---------------- масштаб ----------------

    private val values = FloatArray(9)
    private val scale: Float get() = values.also { pageMatrix.getValues(it) }[Matrix.MSCALE_X]

    private fun fitScale(): Float = ((width - 2 * MARGIN * density) / PAGE_W).coerceAtLeast(0.01f)

    /** Лист по ширине экрана, сверху небольшой отступ. */
    fun fitWidth() {
        val k = fitScale()
        pageMatrix.setScale(k, k)
        pageMatrix.postTranslate(MARGIN * density, MARGIN * density)
        fitted = true
        zoomChanged()
        invalidateCache()
    }

    /** Плавно вернуть лист по ширине экрана. */
    fun animateFitWidth() {
        val target = Matrix().apply {
            val k = fitScale()
            setScale(k, k)
            val ty = values.also { pageMatrix.getValues(it) }[Matrix.MTRANS_Y] / scale * k
            postTranslate(MARGIN * density, ty)
        }
        animateMatrix(target)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted || oldw != w) fitWidth()
        invalidateCache()
    }

    private fun zoomChanged() = listener?.onZoomChanged(scale / fitScale())

    /** Плавно показать место на странице (например, отметку из заметки) в середине экрана. */
    fun reveal(r: RectF) {
        if (page == null) return
        val k = scale
        val target = Matrix(pageMatrix)
        val v = FloatArray(9).also { target.getValues(it) }
        val cx = r.centerX() * k + v[Matrix.MTRANS_X]
        val cy = r.centerY() * k + v[Matrix.MTRANS_Y]
        // По горизонтали двигаем, только если место за краем экрана; по вертикали — ставим на уровень глаз.
        val dx = if (cx in 0f..width.toFloat()) 0f else width / 2f - cx
        val dy = height * 0.4f - cy
        target.postTranslate(dx, dy)
        clamp(target)
        animateMatrix(target)
    }

    private var matrixAnim: ValueAnimator? = null

    /** Плавный переход к другому положению и масштабу листа. */
    private fun animateMatrix(target: Matrix) {
        matrixAnim?.cancel()
        scroller.forceFinished(true)
        val from = FloatArray(9).also { pageMatrix.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        val now = FloatArray(9)
        moving = true
        matrixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until 9) now[i] = from[i] + (to[i] - from[i]) * t
                pageMatrix.setValues(now)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    moving = false
                    zoomChanged()
                    invalidateCache()
                }
            })
            start()
        }
    }

    private fun clamp(m: Matrix) {
        val p = page ?: return
        val v = FloatArray(9).also { m.getValues(it) }
        val k = v[Matrix.MSCALE_X]
        val pw = PAGE_W * k
        val ph = p.height * k
        val mg = MARGIN * density
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (pw + 2 * mg <= width) (width - pw) / 2 else tx.coerceIn(width - pw - mg, mg)
        ty = if (ph + 2 * mg <= height) ty.coerceIn(mg, height - ph - mg) else ty.coerceIn(height - ph - mg * 4, mg)
        m.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
    }

    private fun clampMatrix() = clamp(pageMatrix)

    // ---------------- рисование ----------------

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private val cacheMatrix = Matrix()
    private var cacheValid = false

    /** Лист двигают или увеличивают — рисуем напрямую, без картинки. */
    private var moving = false

    private fun invalidateCache() {
        cacheValid = false
        invalidate()
    }

    private val visible = RectF()

    /** Стол, тень, лист, подложка, разлиновка и штрихи — в текущем положении листа. */
    private fun drawScene(c: Canvas) {
        val p = page ?: return
        c.drawColor(desk)
        c.save()
        c.concat(pageMatrix)
        val k = scale
        shadow.color = if (dark) 0x40000000 else 0x1416161A
        c.drawRect(-2f / k, 2f / k, PAGE_W + 2f / k, p.height + 5f / k, shadow)
        pageMatrix.invert(inverse)
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        drawer.drawPage(c, p, bgBitmap, visible)
        edge.strokeWidth = 1f / k
        edge.color = if (dark) 0x26FFFFFF else 0x1F16161A
        c.drawRect(0f, 0f, PAGE_W, p.height, edge)
        c.restore()
    }

    private fun renderCache(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        var b = cache
        if (b == null || b.width != width || b.height != height) {
            b?.recycle()
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cache = b
            cacheCanvas = Canvas(b)
        }
        drawScene(cacheCanvas!!)
        cacheMatrix.set(pageMatrix)
        cacheValid = true
        return b
    }

    /** Дорисовать готовый штрих прямо в картинку страницы — без полной перерисовки. */
    private fun drawIntoCache(s: Stroke) {
        val c = cacheCanvas ?: return
        if (!cacheValid || cacheMatrix != pageMatrix) {
            cacheValid = false
            return
        }
        if (s.tool == Tool.HIGHLIGHTER) {
            // Маркер ложится под ручку — проще перерисовать страницу целиком.
            cacheValid = false
            return
        }
        c.save()
        c.concat(pageMatrix)
        drawer.drawStroke(c, s)
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        if (page == null) {
            canvas.drawColor(desk)
            return
        }
        val turn = turning
        if (turn != null) {
            drawTurn(canvas, turn)
            return
        }
        if (moving) {
            drawScene(canvas)
        } else {
            val b = if (cacheValid && cacheMatrix == pageMatrix) cache else renderCache()
            if (b != null) canvas.drawBitmap(b, 0f, 0f, null) else drawScene(canvas)
        }
        canvas.save()
        canvas.concat(pageMatrix)
        current?.let { l -> drawer.drawStroke(canvas, l.stroke(), l.prepared()) }
        if (marks.isNotEmpty()) {
            if (marksAlpha < 1f) {
                val save = canvas.saveLayerAlpha(null, (marksAlpha * 255).toInt())
                drawer.drawMarks(canvas, marks, selectedMark, scale)
                canvas.restoreToCount(save)
            } else {
                drawer.drawMarks(canvas, marks, selectedMark, scale)
            }
        }
        canvas.restore()
        drawCursor(canvas)
    }

    private fun animateMarks() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                marksAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---------------- листание страниц ----------------

    private class Turn(val old: Bitmap, val direction: Int, var t: Float = 0f)

    private var turning: Turn? = null

    private fun snapshot(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (cacheValid && cacheMatrix == pageMatrix && cache != null) c.drawBitmap(cache!!, 0f, 0f, null) else drawScene(c)
        return b
    }

    private fun animatePageTurn(old: Bitmap, direction: Int) {
        val turn = Turn(old, direction)
        turning = turn
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                turn.t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (turning === turn) turning = null
                    old.recycle()
                    invalidate()
                }
            })
            start()
        }
    }

    private val turnPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Новая страница въезжает сбоку, старая уходит и тает. */
    private fun drawTurn(canvas: Canvas, turn: Turn) {
        val b = (if (cacheValid && cacheMatrix == pageMatrix) cache else renderCache()) ?: return
        val shift = width * 0.18f
        canvas.drawColor(desk)
        turnPaint.alpha = ((1 - turn.t) * 255).toInt()
        canvas.drawBitmap(turn.old, -turn.direction * shift * turn.t, 0f, turnPaint)
        turnPaint.alpha = (turn.t * 255).toInt()
        canvas.drawBitmap(b, turn.direction * shift * (1 - turn.t), 0f, turnPaint)
    }

    // ---------------- курсор пера ----------------

    private var hoverX = 0f
    private var hoverY = 0f
    private var hovering = false
    private var touchX = 0f
    private var touchY = 0f

    /** Над листом видно, куда коснётся перо: точка цвета ручки или круг ластика. */
    private fun drawCursor(c: Canvas) {
        val erasing = mode == Mode.ERASE
        if (!hovering && !erasing) return
        val x = if (erasing) touchX else hoverX
        val y = if (erasing) touchY else hoverY
        if (eraser || erasing) {
            val r = ERASER_RADIUS * density
            cursorFill.color = if (dark) 0x22FFFFFF else 0x1816161A
            c.drawCircle(x, y, r, cursorFill)
            cursorRing.color = if (dark) 0x99FFFFFF.toInt() else 0x8816161A.toInt()
            cursorRing.strokeWidth = 1.2f * density
            c.drawCircle(x, y, r, cursorRing)
        } else {
            val r = maxOf(penWidth * scale / 2, 1.6f * density)
            cursorFill.color = drawer.displayColor(color)
            cursorFill.alpha = if (tool == Tool.HIGHLIGHTER) 110 else 200
            c.drawCircle(x, y, if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_W * scale / 2 else r, cursorFill)
        }
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS && e.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                hoverX = e.x; hoverY = e.y
            }
            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
        }
        invalidate()
        return true
    }

    // ---------------- ввод ----------------

    private enum class Mode { NONE, DRAW, ERASE, PAN }

    private var mode = Mode.NONE
    private var activePointer = -1

    /** Текущий штрих: точки в единицах страницы. */
    private inner class Live(val tool: Tool, val color: Int, val width: Float) {
        val pts = ArrayList<Float>(512)
        private var preparedFor = -1
        private var preparedLines: StrokeLines? = null
        fun add(x: Float, y: Float, p: Float) {
            pts += x; pts += y; pts += p
        }
        fun stroke() = Stroke(tool, color, width, pts.toFloatArray())

        /** Отрезки пересчитываются, только когда добавились точки. */
        fun prepared(): StrokeLines? {
            if (tool == Tool.HIGHLIGHTER) return null
            if (preparedFor != pts.size) {
                preparedLines = InkCurve.lines(stroke())
                preparedFor = pts.size
            }
            return preparedLines
        }
    }

    private var current: Live? = null
    private var erased = mutableListOf<Pair<Int, Stroke>>()
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val pt = FloatArray(2)

    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)

    // Двойное касание пальцем и касание двумя пальцами.
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var twoFingerAt = 0L
    private var twoFingerMoved = false
    private var maxPointers = 0

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

    private fun newLive(): Live = Live(tool, color, if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_W else penWidth)

    /** Все точки события по указателю [i], включая промежуточные (перо шлёт их пачками). */
    private fun addPoints(l: Live, e: MotionEvent, i: Int, pen: Boolean) {
        for (h in 0 until e.historySize) {
            val q = toPage(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
            l.add(q[0], q[1], if (pen) e.getHistoricalPressure(i, h) else FINGER_PRESSURE)
        }
        val q = toPage(e.getX(i), e.getY(i))
        l.add(q[0], q[1], pressure(e, i, pen))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = page ?: return false
        if (turning != null && e.actionMasked == MotionEvent.ACTION_DOWN) turning = null
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                matrixAnim?.cancel()
                scroller.forceFinished(true)
                activePointer = e.getPointerId(0)
                maxPointers = 1
                hovering = false
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
                downAt = SystemClock.uptimeMillis(); downX = e.x; downY = e.y
                touchX = e.x; touchY = e.y
                when (mode) {
                    Mode.DRAW -> {
                        listener?.onWritingStarted()
                        current = newLive().also { l ->
                            val q = toPage(e.x, e.y)
                            l.add(q[0], q[1], pressure(e, 0, pen))
                        }
                    }
                    Mode.ERASE -> {
                        listener?.onWritingStarted()
                        erased = mutableListOf()
                        eraseAt(p, e.x, e.y)
                    }
                    Mode.PAN -> {
                        velocity?.recycle()
                        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    }
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                val ai = e.actionIndex
                if (isPen(e, ai) && (mode == Mode.PAN || mode == Mode.NONE)) {
                    // Ладонь легла раньше пера: ладонь не двигает лист, пишет перо.
                    endMoving()
                    activePointer = e.getPointerId(ai)
                    val erase = eraser || e.getToolType(ai) == MotionEvent.TOOL_TYPE_ERASER
                    mode = if (erase) Mode.ERASE else Mode.DRAW
                    if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
                    touchX = e.getX(ai); touchY = e.getY(ai)
                    if (mode == Mode.DRAW) {
                        listener?.onWritingStarted()
                        current = newLive().also { l ->
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
                    if (e.pointerCount == 2) {
                        twoFingerAt = SystemClock.uptimeMillis()
                        twoFingerMoved = false
                    }
                    startTwoFinger(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAW -> {
                        val i = e.findPointerIndex(activePointer).takeIf { it >= 0 } ?: return true
                        val l = current ?: return true
                        addPoints(l, e, i, isPen(e, i))
                        invalidate()
                    }
                    Mode.ERASE -> {
                        val i = e.findPointerIndex(activePointer).takeIf { it >= 0 } ?: return true
                        for (h in 0 until e.historySize) eraseAt(p, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        eraseAt(p, e.getX(i), e.getY(i))
                        touchX = e.getX(i); touchY = e.getY(i)
                        invalidate()
                    }
                    Mode.PAN -> {
                        velocity?.addMovement(e)
                        if (e.pointerCount >= 2) {
                            if (!twoFingerMoved) {
                                val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                                val shift = hypot((e.getX(0) + e.getX(1)) / 2 - lastFocusX, (e.getY(0) + e.getY(1)) / 2 - lastFocusY)
                                if (abs(span - lastSpan) > TOUCH_SLOP * density || shift > TOUCH_SLOP * density) twoFingerMoved = true
                            }
                            if (twoFingerMoved) {
                                moving = true
                                twoFinger(e)
                            }
                        } else {
                            if (!moving && hypot(e.x - downX, e.y - downY) < TOUCH_SLOP * density) return true
                            moving = true
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
                // Перо подняли, а ладонь ещё лежит — штрих закончен (с последними точками).
                if (mode == Mode.DRAW) {
                    current?.let { addPoints(it, e, e.actionIndex, isPen(e, e.actionIndex)) }
                    commitStroke(p)
                } else if (erased.isNotEmpty()) {
                    undo.push(InkEdit.Erased(erased.toList()))
                    edited(full = true)
                }
                mode = Mode.NONE
                invalidate()
            } else if (mode == Mode.PAN) {
                // Касание двумя пальцами без движения — «Отменить».
                if (e.pointerCount == 2 && maxPointers == 2 && !twoFingerMoved && SystemClock.uptimeMillis() - twoFingerAt < TAP_MS) {
                    undo()
                    twoFingerAt = 0
                }
                // Остался один палец — продолжаем двигать от него.
                val rest = (0 until e.pointerCount).firstOrNull { it != e.actionIndex } ?: 0
                lastX = e.getX(rest); lastY = e.getY(rest)
                downX = lastX; downY = lastY
                if (e.pointerCount - 1 >= 2) startTwoFinger(e)
                velocity?.clear()
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.DRAW -> {
                        // Последние точки штриха приходят вместе с отрывом пера — без них обрезаются хвосты букв.
                        current?.let { addPoints(it, e, 0, isPen(e, 0)) }
                        commitStroke(p)
                    }
                    Mode.ERASE -> if (erased.isNotEmpty()) {
                        undo.push(InkEdit.Erased(erased.toList()))
                        edited(full = true)
                    }
                    Mode.PAN -> {
                        velocity?.addMovement(e)
                        if (moving) fling() else tapped(e)
                    }
                    else -> Unit
                }
                velocity?.recycle()
                velocity = null
                mode = Mode.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                mode = Mode.NONE
                endMoving()
                invalidate()
            }
        }
        return true
    }

    /** Касание пальцем без движения: второе подряд — увеличить или вернуть по ширине. */
    private fun tapped(e: MotionEvent) {
        endMoving()
        val now = SystemClock.uptimeMillis()
        if (now - downAt > TAP_MS) return
        if (now - lastTapAt < DOUBLE_TAP_MS && hypot(e.x - lastTapX, e.y - lastTapY) < 40 * density) {
            lastTapAt = 0
            if (scale > fitScale() * 1.15f) {
                animateFitWidth()
            } else {
                val target = Matrix(pageMatrix).apply { postScale(2.2f, 2.2f, e.x, e.y) }
                clamp(target)
                animateMatrix(target)
            }
        } else {
            lastTapAt = now
            lastTapX = e.x; lastTapY = e.y
        }
    }

    /** Отпустили лист на ходу — он ещё немного едет и плавно останавливается. */
    private fun fling() {
        val vt = velocity ?: return endMoving()
        vt.computeCurrentVelocity(1000)
        val vx = vt.xVelocity
        val vy = vt.yVelocity
        if (hypot(vx, vy) < MIN_FLING * density) return endMoving()
        val v = FloatArray(9).also { pageMatrix.getValues(it) }
        val p = page ?: return endMoving()
        val k = v[Matrix.MSCALE_X]
        val mg = MARGIN * density
        val pw = PAGE_W * k
        val ph = p.height * k
        val minX = if (pw + 2 * mg <= width) v[Matrix.MTRANS_X] else width - pw - mg
        val maxX = if (pw + 2 * mg <= width) v[Matrix.MTRANS_X] else mg
        val minY = if (ph + 2 * mg <= height) v[Matrix.MTRANS_Y] else height - ph - mg * 4
        val maxY = if (ph + 2 * mg <= height) v[Matrix.MTRANS_Y] else mg
        scroller.fling(
            v[Matrix.MTRANS_X].toInt(), v[Matrix.MTRANS_Y].toInt(), vx.toInt(), vy.toInt(),
            minX.toInt(), maxX.toInt(), minY.toInt(), maxY.toInt(),
        )
        postOnAnimation(flingStep)
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) {
                endMoving()
                return
            }
            val v = FloatArray(9).also { pageMatrix.getValues(it) }
            pageMatrix.postTranslate(scroller.currX - v[Matrix.MTRANS_X], scroller.currY - v[Matrix.MTRANS_Y])
            clampMatrix()
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun endMoving() {
        if (!moving) return
        moving = false
        zoomChanged()
        invalidateCache()
    }

    private fun pressure(e: MotionEvent, i: Int, pen: Boolean) = if (pen) e.getPressure(i) else FINGER_PRESSURE

    private fun commitStroke(p: InkPage) {
        val l = current ?: return
        current = null
        if (l.pts.isEmpty()) return
        val pts = StrokeGeometry.thin(l.pts.toFloatArray(), 0.4f)
        val s = Stroke(l.tool, l.color, l.width, pts)
        p.strokes += s
        p.touch()
        undo.push(InkEdit.Added(s))
        drawIntoCache(s)
        edited(full = false)
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
        invalidateCache()
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(flingStep)
        matrixAnim?.cancel()
    }

    companion object {
        /** Отметки на открытом листе — по ним тесты проверяют ответ наставника в тетради. */
        @Volatile
        var shownMarks: List<PageMark> = emptyList()
            private set

        /** Цвет «стола» вокруг листа. */
        const val DESK = 0xFFEDEAE4.toInt()
        const val DESK_DARK = 0xFF141417.toInt()
        private const val MARGIN = 16f
        private const val MIN_ZOOM = 0.6f
        private const val MAX_ZOOM = 6f
        private const val FINGER_PRESSURE = 0.55f
        private const val ERASER_RADIUS = 14f
        private const val TOUCH_SLOP = 6f
        private const val TAP_MS = 250L
        private const val DOUBLE_TAP_MS = 320L
        private const val MIN_FLING = 400f
        const val HIGHLIGHTER_W = 20f
    }
}

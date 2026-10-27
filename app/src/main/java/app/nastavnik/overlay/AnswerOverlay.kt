package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.nastavnik.R
import app.nastavnik.ai.Mark
import app.nastavnik.ai.MarkKind
import app.nastavnik.geom.CalloutPlacer
import app.nastavnik.geom.IRect
import app.nastavnik.geom.Placement
import app.nastavnik.geom.Side
import app.nastavnik.notes.WebViews
import app.nastavnik.session.AnswerUi
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Ответ наставника прямо на экране: рамка вокруг места на записи и заметка рядом с ним.
 * Никакого отдельного окна — только то, что относится к задаче, там, где задача.
 */
class AnswerOverlay(baseContext: Context, private val wm: WindowManager, private val listener: Listener) {

    interface Listener {
        fun onMakeClean()

        /** Студент закрыл все заметки крестиками. */
        fun onAllClosed()
    }

    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private val main = Handler(Looper.getMainLooper())
    private val entries = mutableListOf<Entry>()
    private var seq = -1L
    private var offX = 0
    private var offY = 0
    private var captureHidden = false

    /** Прозрачность заметки, когда к ней подносят стилус. */
    var fadeAlpha = 0.35f

    /** Где сейчас кнопка — заметки её не закрывают. */
    var bubbleRect: () -> IRect? = { null }

    val isShowing: Boolean get() = entries.isNotEmpty()

    /** Номер ответа, который сейчас на экране. */
    val shownSeq: Long get() = if (entries.isEmpty()) -1L else seq

    private fun dp(v: Float) = OverlayWindows.dp(ctx, v)

    private inner class Entry(val index: Int, val mark: Mark, val anchor0: IRect?) {
        var markView: MarkView? = null
        var markParams: WindowManager.LayoutParams? = null
        val note = NoteWindow(mark.kind)
        var hidden = false
        fun anchor(): IRect? = anchor0?.offset(offX, offY)
    }

    // ---------------- отрисовка ответа ----------------

    fun render(a: AnswerUi?) {
        if (a == null || a.marks.isEmpty()) {
            clear()
            return
        }
        if (a.seq != seq) {
            clear()
            seq = a.seq
            offX = 0
            offY = 0
        }
        a.marks.forEachIndexed { i, m ->
            val streamingThis = a.streaming && i == a.marks.lastIndex
            val e = entries.getOrNull(i) ?: addEntry(i, m, a)
            e.note.setText(m.text, streamingThis)
        }
        entries.forEachIndexed { i, e -> e.note.setDone(a.done && !a.streaming && i == entries.lastIndex) }
        publish()
    }

    private fun addEntry(i: Int, m: Mark, a: AnswerUi): Entry {
        // Место в координатах снимка; если страницу уже прокрутили, рисуем со сдвигом.
        val anchor = m.box?.let { a.geometry?.toScreen(it) }
        val shifted = anchor?.offset(offX, offY)
        val e = Entry(i, m, anchor)
        val area = contentArea()
        if (shifted != null) {
            // Значок по виду заметки; к своей рамке заметку ведёт «хвостик».
            val mv = MarkView(ctx, m.kind)
            val p = OverlayWindows.absParams(
                shifted.left - mv.pad, shifted.top - mv.pad, shifted.width + 2 * mv.pad, shifted.height + 2 * mv.pad,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            ).apply { alpha = if (captureHidden) 0f else MARK_ALPHA }
            runCatching {
                wm.addView(mv, p)
                e.markView = mv
                e.markParams = p
                fixPosition(mv, p)
            }
        }
        val width = minOf(dp(if (m.kind == MarkKind.THEORY) 460f else 380f), (area.width * 0.45f).toInt().coerceAtLeast(dp(280f)))
        val height = dp(150f)
        val avoid = entries.flatMap { listOfNotNull(it.note.rect(), it.anchor()) } + listOfNotNull(bubbleRect())
        val thumb = a.thumb
        val placement = CalloutPlacer.place(area, shifted, width, height, dp(4f), avoid) { r ->
            thumb?.inkFraction(r.offset(-offX, -offY)) ?: 0f
        }
        e.note.show(placement, anchor, width)
        entries += e
        return e
    }

    private fun contentArea(): IRect {
        val r = OverlayWindows.contentRect(ctx)
        val m = dp(8f)
        return IRect(r.left + m, r.top + m, r.right - m, r.bottom - m)
    }

    /** Окно поверх приложения может оказаться сдвинутым (вырез, строка состояния) — подправляем по факту. */
    private fun fixPosition(view: View, p: WindowManager.LayoutParams) {
        view.post {
            if (!view.isAttachedToWindow) return@post
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val dx = p.x - loc[0]
            val dy = p.y - loc[1]
            if (dx != 0 || dy != 0) {
                p.x += dx
                p.y += dy
                runCatching { wm.updateViewLayout(view, p) }
            }
        }
    }

    // ---------------- слежение за страницей ----------------

    /**
     * Страницу прокрутили: всё, что нарисовано, едет вместе с записями.
     * [dx], [dy] — сдвиг относительно того, как было во время снимка.
     */
    fun setOffset(dx: Int, dy: Int) {
        val ddx = dx - offX
        val ddy = dy - offY
        if (ddx == 0 && ddy == 0) return
        offX = dx
        offY = dy
        val area = contentArea()
        for (e in entries) {
            e.markParams?.let { p ->
                p.x += ddx
                p.y += ddy
                e.markView?.let { runCatching { wm.updateViewLayout(it, p) } }
            }
            e.note.moveBy(ddx, ddy)
            // Место уехало за край экрана — прячем, вернётся — покажем снова.
            val a = e.anchor()
            val gone = a != null && (a.bottom < area.top || a.top > area.bottom || a.right < area.left || a.left > area.right)
            if (gone != e.hidden) {
                e.hidden = gone
                applyVisibility(e)
            }
        }
        publish()
    }

    private var suspended = false

    /** На экране что-то другое (шторка, другое приложение) — прячем всё, но не выбрасываем. */
    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        entries.forEach(::applyVisibility)
        publish()
    }

    private fun applyVisibility(e: Entry) {
        val visible = !e.hidden && !suspended
        e.markView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        e.note.setVisible(visible)
    }

    /** Все наши окна на экране: их не нужно сравнивать при слежении за страницей. */
    fun occupiedRects(): List<IRect> = entries.flatMap { e ->
        listOfNotNull(
            e.markParams?.let { p -> IRect(p.x, p.y, p.x + p.width, p.y + p.height) },
            e.note.rect(),
        )
    }

    fun setCaptureHidden(hidden: Boolean) {
        captureHidden = hidden
        for (e in entries) {
            e.markParams?.let { p ->
                p.alpha = if (hidden) 0f else MARK_ALPHA
                e.markView?.let { runCatching { wm.updateViewLayout(it, p) } }
            }
            e.note.setCaptureHidden(hidden)
        }
    }

    fun clear() {
        suspended = false
        entries.forEach { e ->
            e.markView?.let { runCatching { wm.removeView(it) } }
            e.note.remove()
        }
        entries.clear()
        publish()
    }

    private fun closeEntry(e: Entry) {
        e.markView?.let { runCatching { wm.removeView(it) } }
        e.markView = null
        e.markParams = null
        e.note.remove()
        entries.remove(e)
        publish()
        if (entries.isEmpty()) listener.onAllClosed()
    }

    // ---------------- окно заметки ----------------

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private inner class NoteWindow(val kind: MarkKind) {
        private val root = NoteRoot(ctx)
        private val bg = NoteBackground(ctx, kind)
        private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val web = WebView(ctx)
        private val footer = TextView(ctx)
        private var params: WindowManager.LayoutParams? = null
        private var side = Side.NONE
        private var anchor: IRect? = null
        private var width = 0
        private var webH = dp(24f)
        private var ready = false
        private var pendingText: String? = null
        private var pendingStreaming = false
        private var shownText: String? = null
        private var faded = false
        private val unfade = Runnable { setFaded(false) }

        private val headerH = dp(34f)
        private val footerH = dp(48f)
        private val padH = dp(14f)
        private val padV = dp(10f)

        init {
            root.background = bg
            root.onStylus = { setFaded(true) }
            root.contentDescription = "Заметка: ${kind.label}"
            val dot = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(NoteColors.of(kind))
                }
            }
            header.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(8f) })
            val title = TextView(ctx).apply {
                text = kind.label
                setTextColor(NoteColors.of(kind))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val close = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFF444746.toInt())
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                contentDescription = "Закрыть заметку"
                setOnClickListener { entries.firstOrNull { it.note === this@NoteWindow }?.let { closeEntry(it) } }
            }
            header.addView(close, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            body.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH))

            WebViews.setupLocal(web)
            web.setBackgroundColor(Color.TRANSPARENT)
            web.isVerticalScrollBarEnabled = true
            web.addJavascriptInterface(Bridge(), "NativeNote")
            web.loadUrl("file:///android_asset/note.html")
            body.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, webH))

            footer.apply {
                text = "Сделать чистовик"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(R.drawable.primary_btn_bg)
                isVisible = false
                setOnClickListener { listener.onMakeClean() }
            }
            body.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply {
                topMargin = dp(8f)
                gravity = Gravity.END
            })
            footer.setPadding(dp(18f), 0, dp(18f), 0)
            root.addView(body)
            setupDrag()
        }

        private inner class Bridge {
            @JavascriptInterface
            fun onReady() {
                root.post {
                    ready = true
                    pendingText?.let { push(it, pendingStreaming) }
                }
            }

            @JavascriptInterface
            fun onHeight(cssPx: Float) {
                root.post {
                    webH = (cssPx * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(dp(20f))
                    relayout()
                }
            }
        }

        fun show(p: Placement, anchor: IRect?, width: Int) {
            this.anchor = anchor
            this.width = width
            side = if (anchor == null) Side.NONE else p.side
            bg.side = side
            val lp = OverlayWindows.absParams(
                p.rect.left, p.rect.top, width, p.rect.height,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            ).apply { alpha = if (captureHidden) 0f else 1f }
            params = lp
            applyInsets()
            runCatching {
                wm.addView(root, lp)
                fixPosition(root, lp)
            }
            relayout()
        }

        private fun applyInsets() {
            val i = bg.insets()
            body.setPadding(padH + i[0], padV + i[1], padH + i[2], padV + i[3])
        }

        /** Высота по содержимому; если заметка растёт, она не должна уехать за экран или на место. */
        private fun relayout() {
            val p = params ?: return
            val area = contentArea()
            val i = bg.insets()
            val maxWeb = (area.height * 0.45f).toInt()
            val webShown = webH.coerceAtMost(maxWeb)
            (web.layoutParams as LinearLayout.LayoutParams).let {
                if (it.height != webShown) {
                    it.height = webShown
                    web.layoutParams = it
                }
            }
            val h = padV * 2 + i[1] + i[3] + headerH + webShown + if (footer.isVisible) footerH else 0
            if (side == Side.ABOVE) p.y = p.y + p.height - h
            p.height = h
            val clamped = IRect(p.x, p.y, p.x + p.width, p.y + h).clampInto(area)
            p.x = clamped.left
            p.y = clamped.top
            updateTail()
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        private fun updateTail() {
            val p = params ?: return
            val a = anchor?.offset(offX, offY) ?: return
            bg.tailAt = when (side) {
                Side.RIGHT, Side.LEFT -> (a.centerY - p.y).toFloat()
                Side.BELOW, Side.ABOVE -> (a.centerX - p.x).toFloat()
                Side.NONE -> 0f
            }
        }

        fun setText(text: String, streaming: Boolean) {
            if (shownText == text && pendingStreaming == streaming) return
            shownText = text
            pendingText = text
            pendingStreaming = streaming
            if (ready) push(text, streaming)
        }

        private fun push(text: String, streaming: Boolean) {
            web.evaluateJavascript("Note.set(${JsonPrimitive(text)}, $streaming)", null)
        }

        fun setDone(done: Boolean) {
            if (footer.isVisible == done) return
            footer.isVisible = done
            relayout()
        }

        fun moveBy(dx: Int, dy: Int) {
            val p = params ?: return
            p.x += dx
            p.y += dy
            updateTail()
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        fun setVisible(visible: Boolean) {
            root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }

        fun rect(): IRect? = params?.let { IRect(it.x, it.y, it.x + it.width, it.y + it.height) }

        fun setCaptureHidden(hidden: Boolean) {
            val p = params ?: return
            p.alpha = if (hidden) 0f else if (faded) fadeAlpha else 1f
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        /** Стилус рядом: заметка полупрозрачная и пропускает касания, через 2,5 с — снова обычная. */
        private fun setFaded(f: Boolean) {
            val p = params ?: return
            main.removeCallbacks(unfade)
            if (f) main.postDelayed(unfade, FADE_MS)
            if (faded == f) return
            faded = f
            p.alpha = if (captureHidden) 0f else if (f) fadeAlpha.coerceAtMost(OverlayWindows.MAX_PASS_THROUGH_ALPHA) else 1f
            p.flags = if (f) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        /** Заметку можно перетащить пальцем за заголовок. Тогда хвостик пропадает. */
        private fun setupDrag() {
            var sx = 0f
            var sy = 0f
            var px = 0
            var py = 0
            var dragging = false
            header.setOnTouchListener { _, e ->
                val p = params ?: return@setOnTouchListener false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; px = p.x; py = p.y; dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (!dragging && abs(dx) + abs(dy) > dp(8f)) {
                            dragging = true
                            if (side != Side.NONE) {
                                side = Side.NONE
                                bg.side = Side.NONE
                                applyInsets()
                            }
                        }
                        if (dragging) {
                            p.x = px + dx.toInt()
                            p.y = py + dy.toInt()
                            runCatching { wm.updateViewLayout(root, p) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) relayout()
                }
                true
            }
        }

        fun remove() {
            main.removeCallbacks(unfade)
            if (root.isAttachedToWindow) runCatching { wm.removeView(root) }
            web.destroy()
        }
    }

    /** Обновляет [shownMarks] — по ним тесты проверяют, что рамка легла на место. */
    private fun publish() {
        shownMarks = entries.filter { !it.hidden && !suspended }.mapNotNull { e ->
            e.markParams?.let { p -> IRect(p.x, p.y, p.x + p.width, p.y + p.height) }
        }
    }

    companion object {
        /**
         * Где сейчас рамки (окна рамок, с полями под значок). Окна рамок не принимают касаний,
         * поэтому средства тестирования их не видят — тесты смотрят сюда.
         */
        @Volatile
        var shownMarks: List<IRect> = emptyList()
            private set

        /** Рамки полупрозрачные: иначе Android 12+ не пропустит сквозь них стилус. */
        private const val MARK_ALPHA = 0.8f
        private const val FADE_MS = 2500L
    }
}

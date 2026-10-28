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
import android.widget.FrameLayout
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
 * Ответ наставника прямо на экране: отметка на самой записи и заметка рядом с ней.
 * Заметка едет вместе со страницей, когда её прокручивают, не уходит за край экрана и не исчезает сама —
 * только по «Понятно», крестику или новому вопросу. Перетащил её — она остаётся там, где оставили.
 */
class AnswerOverlay(baseContext: Context, private val wm: WindowManager, private val listener: Listener) {

    interface Listener {
        fun onMakeClean()

        /** «Ещё подсказка» под заметкой. */
        fun onMore()

        /** «Похожая задача» под заметкой с верным решением. */
        fun onSimilar()

        /** Студент закрыл все заметки. */
        fun onAllClosed()
    }

    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private val main = Handler(Looper.getMainLooper())
    private val entries = mutableListOf<Entry>()
    private var seq = -1L
    private var offX = 0
    private var offY = 0
    private var captureHidden = false
    private var marksSuspended = false

    /** Прозрачность заметки, когда к ней подносят стилус. */
    var fadeAlpha = 0.35f

    /** Где сейчас кнопка — заметки её не закрывают. */
    var bubbleRect: () -> IRect? = { null }

    val isShowing: Boolean get() = entries.isNotEmpty()

    private fun dp(v: Float) = OverlayWindows.dp(ctx, v)

    private inner class Entry(val mark: Mark, var anchor0: IRect?) {
        var markView: MarkView? = null
        var markParams: WindowManager.LayoutParams? = null
        val note = NoteWindow(mark.kind)

        /** Отмеченное место уехало за край экрана. */
        var offscreen = false

        /** Студент спрятал выделение «глазиком» на заметке. */
        var userHidden = false
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
            val e = entries.getOrNull(i) ?: addEntry(m, a)
            e.note.setContent(m.text, m.detail, streamingThis)
        }
        entries.forEachIndexed { i, e ->
            val last = i == entries.lastIndex
            e.note.setActions(show = last && !a.streaming, done = a.done)
        }
        publish()
    }

    private fun addEntry(m: Mark, a: AnswerUi): Entry {
        // Место в координатах снимка; если страницу уже прокрутили, рисуем со сдвигом.
        val anchor = m.box?.let { a.geometry?.toScreen(it) }
        val shifted = anchor?.offset(offX, offY)
        val e = Entry(m, anchor)
        val area = contentArea()
        if (shifted != null) {
            val mv = MarkView(ctx, m.kind)
            val p = OverlayWindows.absParams(
                shifted.left - mv.pad, shifted.top - mv.pad, shifted.width + 2 * mv.pad, shifted.height + 2 * mv.pad,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            ).apply { alpha = if (captureHidden) 0f else MARK_ALPHA }
            runCatching {
                wm.addView(mv, p)
                e.markView = mv
                e.markParams = p
                if (marksSuspended) mv.visibility = View.INVISIBLE
            }
        }
        // Короткая подсказка — узкая заметка: она закрывает как можно меньше записей. «Подробнее» её расширит.
        val width = minOf(dp(COMPACT_WIDTH_DP), (area.width * 0.4f).toInt().coerceAtLeast(dp(240f)))
        val height = dp(118f)
        val avoid = entries.flatMap { listOfNotNull(it.note.rect(), it.anchor()) } + listOfNotNull(bubbleRect())
        val thumb = a.thumb
        val placement = CalloutPlacer.place(area, shifted, width, height, 0, avoid) { r ->
            thumb?.inkFraction(r.offset(-offX, -offY)) ?: 0f
        }
        val wide = minOf(dp(if (m.kind == MarkKind.THEORY) 480f else 420f), (area.width * 0.5f).toInt().coerceAtLeast(width))
        e.note.show(placement, anchor, width, wide)
        entries += e
        return e
    }

    private fun contentArea(): IRect {
        val r = OverlayWindows.contentRect(ctx)
        val m = dp(4f)
        return IRect(r.left + m, r.top + m, r.right - m, r.bottom - m)
    }

    // ---------------- слежение за страницей ----------------

    /**
     * Страницу прокрутили: отметки и заметки едут вместе с записями.
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
            e.note.follow(ddx, ddy)
            // Отмеченное место ушло за край — отметку прячем, заметка остаётся у края экрана.
            val a = e.anchor()
            e.offscreen = a != null && (a.bottom < area.top || a.top > area.bottom || a.right < area.left || a.left > area.right)
            applyMarkVisibility(e)
        }
        publish()
    }

    /** На экране что-то другое (шторка, другая страница) — прячем отметки; заметки остаются. */
    fun setSuspended(value: Boolean) {
        if (marksSuspended == value) return
        marksSuspended = value
        entries.forEach(::applyMarkVisibility)
        publish()
    }

    private fun applyMarkVisibility(e: Entry) {
        e.markView?.visibility = if (!e.offscreen && !marksSuspended && !e.userHidden) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Страницу увеличили или уменьшили: по свежему снимку отмеченные строки нашлись в новых местах.
     * [anchors] — новые рамки на экране по порядку заметок (null — строку не нашли, выделение прячем).
     * Сдвиг для слежения отсчитывается заново от этого снимка.
     */
    fun relocate(anchors: List<IRect?>) {
        offX = 0
        offY = 0
        marksSuspended = false
        entries.forEachIndexed { i, e ->
            if (e.anchor0 == null) return@forEachIndexed
            val r = anchors.getOrNull(i)
            if (r == null) {
                e.offscreen = true
            } else {
                e.anchor0 = r
                e.offscreen = false
                val mv = e.markView
                val p = e.markParams
                if (mv != null && p != null) {
                    p.x = r.left - mv.pad
                    p.y = r.top - mv.pad
                    p.width = r.width + 2 * mv.pad
                    p.height = r.height + 2 * mv.pad
                    runCatching { wm.updateViewLayout(mv, p) }
                }
                val avoid = entries.filter { it !== e }.flatMap { listOfNotNull(it.note.rect(), it.anchor()) } + listOfNotNull(bubbleRect())
                e.note.reattach(r, avoid)
            }
            applyMarkVisibility(e)
        }
        publish()
    }

    private fun toggleMark(note: NoteWindow): Boolean {
        val e = entries.firstOrNull { it.note === note } ?: return false
        e.userHidden = !e.userHidden
        applyMarkVisibility(e)
        publish()
        return e.userHidden
    }

    /** Экран повернули или поменялся его размер — заметки возвращаем в его пределы. */
    fun onScreenChanged() {
        entries.forEach { it.note.follow(0, 0) }
        publish()
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
        marksSuspended = false
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

    /** Обновляет [shownMarks] — по ним тесты проверяют, что отметка легла на место. */
    private fun publish() {
        shownMarks = entries.filter { !it.offscreen && !marksSuspended && !it.userHidden }.mapNotNull { e ->
            e.markParams?.let { p -> IRect(p.x, p.y, p.x + p.width, p.y + p.height) }
        }
        shownNotes = entries.mapNotNull { it.note.rect() }
    }

    // ---------------- окно заметки ----------------

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private inner class NoteWindow(val kind: MarkKind) {
        private val root = NoteRoot(ctx)
        private val bg = NoteBackground(ctx)
        private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val web = WebView(ctx)
        private val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val eye = ImageView(ctx)
        private val primary = pill("", primary = true)
        private val secondary = pill("", primary = false)
        private val more = TextView(ctx)
        private var showActions = false
        private var shortText = ""
        private var detailText: String? = null
        private var expanded = false
        private var compactW = 0
        private var wideW = 0
        private var params: WindowManager.LayoutParams? = null
        private var side = Side.NONE
        private var anchor: IRect? = null
        private var webH = dp(24f)
        private var ready = false
        private var pendingText: String? = null
        private var pendingStreaming = false
        private var shownText: String? = null
        private var faded = false

        /** Студент сам поставил заметку — при прокрутке она больше не едет. */
        private var pinned = false
        private val unfade = Runnable { setFaded(false) }

        private val headerH = dp(36f)
        private val actionsH = dp(44f)
        private val padH = dp(14f)
        private val padTop = dp(4f)
        private val padBottom = dp(8f)

        init {
            root.background = bg
            root.onStylus = { setFaded(true) }
            root.dragZone = header
            root.contentDescription = "Заметка: ${kind.label}"

            // Метка вида: цветная «пилюля» с точкой — «Ошибка», «Следующий шаг», «Верно», «Теория».
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9f), 0, dp(11f), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    // Метка тоже стеклянная: цвет вида едва заметен.
                    setColor((NoteColors.soft(kind) and 0x00FFFFFF) or (0xB0 shl 24))
                }
            }
            val chipDot = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(NoteColors.of(kind))
                }
            }
            chip.addView(chipDot, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { marginEnd = dp(7f) })
            val title = TextView(ctx).apply {
                text = NoteColors.title(kind)
                setTextColor(NoteColors.text(kind))
                typeface = Fonts.semibold(ctx)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            }
            chip.addView(title)
            header.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(24f)))
            header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            eye.apply {
                setImageResource(R.drawable.ic_eye)
                setColorFilter(0xFF6B6A73.toInt())
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                contentDescription = "Скрыть выделение"
                setOnClickListener {
                    val hidden = toggleMark(this@NoteWindow)
                    setImageResource(if (hidden) R.drawable.ic_eye_off else R.drawable.ic_eye)
                    contentDescription = if (hidden) "Показать выделение" else "Скрыть выделение"
                }
            }
            header.addView(eye, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            header.addView(GripView(ctx), LinearLayout.LayoutParams(dp(20f), dp(32f)))
            val close = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFF6B6A73.toInt())
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
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

            // «Подробнее» — слева, кнопки действий — справа.
            more.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Fonts.semibold(ctx)
                setTextColor(0xFF2A2A31.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(10f), 0)
                setOnClickListener { setExpanded(!expanded) }
            }
            actions.addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34f)))
            actions.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            actions.addView(secondary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34f)).apply { marginEnd = dp(6f) })
            actions.addView(primary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34f)))
            actions.isVisible = false
            body.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, actionsH))
            actions.gravity = Gravity.CENTER_VERTICAL
            root.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setupDrag()
        }

        private fun pill(label: String, primary: Boolean) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Fonts.semibold(ctx)
            setTextColor(if (primary) Color.WHITE else 0xFF16161A.toInt())
            setBackgroundResource(if (primary) R.drawable.primary_btn_bg else R.drawable.glass_btn_bg)
            setPadding(dp(14f), 0, dp(14f), 0)
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

        fun show(p: Placement, anchor: IRect?, width: Int, wide: Int) {
            compactW = width
            wideW = wide
            this.anchor = anchor
            // «Глазик» нужен, только если на записи есть выделение.
            eye.isVisible = anchor != null
            side = if (anchor == null) Side.NONE else p.side
            bg.side = side
            val lp = OverlayWindows.absParams(
                p.rect.left, p.rect.top, width, p.rect.height,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            ).apply { alpha = if (captureHidden) 0f else 1f }
            params = lp
            applyInsets()
            runCatching { wm.addView(root, lp) }
            relayout()
        }

        private fun applyInsets() {
            val i = bg.insets()
            body.setPadding(padH + i[0], padTop + i[1], padH + i[2] - dp(4f), padBottom + i[3])
        }

        private fun setSide(s: Side) {
            if (side == s) return
            side = s
            bg.side = s
            applyInsets()
        }

        /** Высота по содержимому; растущая заметка не уходит за экран и не наезжает на своё место. */
        private fun relayout() {
            val p = params ?: return
            val area = contentArea()
            val i = bg.insets()
            val maxWeb = (area.height * 0.42f).toInt()
            val webShown = webH.coerceAtMost(maxWeb)
            (web.layoutParams as LinearLayout.LayoutParams).let {
                if (it.height != webShown) {
                    it.height = webShown
                    web.layoutParams = it
                }
            }
            val h = padTop + padBottom + i[1] + i[3] + headerH + webShown + if (actions.isVisible) actionsH else 0
            if (side == Side.ABOVE) p.y = p.y + p.height - h
            p.height = h
            keepOnScreen(p, area)
            updateTail()
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        /** Заметка всегда целиком на экране; если из-за этого она отошла от места — хвостик убираем. */
        private fun keepOnScreen(p: WindowManager.LayoutParams, area: IRect) {
            val r = IRect(p.x, p.y, p.x + p.width, p.y + p.height)
            val c = r.clampInto(area)
            if (c != r) {
                p.x = c.left
                p.y = c.top
                val a = anchor?.offset(offX, offY)
                val attached = a != null && when (side) {
                    Side.RIGHT, Side.LEFT -> a.centerY in c.top..c.bottom && abs((if (side == Side.RIGHT) c.left else c.right) - (if (side == Side.RIGHT) a.right else a.left)) < dp(24f)
                    Side.BELOW, Side.ABOVE -> a.centerX in c.left..c.right && abs((if (side == Side.BELOW) c.top else c.bottom) - (if (side == Side.BELOW) a.bottom else a.top)) < dp(24f)
                    Side.NONE -> false
                }
                if (!attached && side != Side.NONE) setSide(Side.NONE)
            }
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

        /** Короткая подсказка видна сразу; полная — по «Подробнее». */
        fun setContent(short: String, detail: String?, streaming: Boolean) {
            shortText = short
            detailText = detail
            refreshMore()
            setText(if (expanded && detail != null) "**$short**\n\n$detail" else short, streaming && (detail == null || expanded))
        }

        private fun setText(text: String, streaming: Boolean) {
            if (shownText == text && pendingStreaming == streaming) return
            shownText = text
            pendingText = text
            pendingStreaming = streaming
            if (ready) push(text, streaming)
        }

        private fun setExpanded(value: Boolean) {
            if (expanded == value || detailText == null) return
            expanded = value
            // Полная подсказка шире; свернули — заметка снова узкая и меньше закрывает.
            params?.let { p ->
                val w = if (value) wideW else compactW
                if (side == Side.LEFT) p.x += p.width - w
                p.width = w
            }
            refreshMore()
            setText(if (value) "**$shortText**\n\n${detailText.orEmpty()}" else shortText, false)
            relayout()
        }

        private fun refreshMore() {
            val has = detailText != null
            more.isVisible = has
            more.text = if (expanded) "Коротко  ˄" else "Подробнее  ˅"
            updateActionsRow()
        }

        private fun updateActionsRow() {
            val visible = showActions || detailText != null
            primary.isVisible = showActions
            secondary.isVisible = showActions && secondary.text.isNotEmpty()
            if (actions.isVisible != visible) {
                actions.isVisible = visible
                relayout()
            }
        }

        private fun push(text: String, streaming: Boolean) {
            web.evaluateJavascript("Note.set(${JsonPrimitive(text)}, $streaming)", null)
        }

        /** Кнопки под последней заметкой: «Ещё подсказка», а у верного решения — «Похожая задача» и «Сделать чистовик». */
        fun setActions(show: Boolean, done: Boolean) {
            showActions = show
            if (show) {
                if (done) {
                    primary.text = "Сделать чистовик"
                    primary.setOnClickListener { listener.onMakeClean() }
                    // Решено — можно закрепить на похожей задаче.
                    secondary.text = "Похожая задача"
                    secondary.setOnClickListener { listener.onSimilar() }
                } else {
                    primary.text = "Ещё подсказка"
                    primary.setOnClickListener { listener.onMore() }
                    // Закрыть заметку можно крестиком — лишняя кнопка «Понятно» только занимала место.
                    secondary.text = ""
                }
            }
            updateActionsRow()
        }

        /** Выделение нашлось в новом месте (страницу увеличили) — заметка встаёт рядом, если её не ставили вручную. */
        fun reattach(newAnchor: IRect, avoid: List<IRect>) {
            anchor = newAnchor
            val p = params ?: return
            if (!pinned) {
                val pl = CalloutPlacer.place(contentArea(), newAnchor, p.width, p.height, 0, avoid)
                p.x = pl.rect.left
                p.y = pl.rect.top
                setSide(pl.side)
            }
            relayout()
        }

        /** Прокрутка страницы: заметка едет за записями, если её не ставили вручную. */
        fun follow(dx: Int, dy: Int) {
            val p = params ?: return
            if (!pinned) {
                p.x += dx
                p.y += dy
            }
            keepOnScreen(p, contentArea())
            updateTail()
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        fun rect(): IRect? = params?.let { IRect(it.x, it.y, it.x + it.width, it.y + it.height) }

        fun setCaptureHidden(hidden: Boolean) {
            val p = params ?: return
            p.alpha = if (hidden) 0f else if (faded) fadeAlpha else 1f
            if (root.isAttachedToWindow) runCatching { wm.updateViewLayout(root, p) }
        }

        /** Стилус над текстом: заметка полупрозрачная и пропускает касания, через 2,5 с — снова обычная. */
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

        /** Заметку тянут за заголовок — пальцем или стилусом. Поставленная вручную, она больше не едет сама. */
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
                        if (!dragging && abs(dx) + abs(dy) > dp(6f)) {
                            dragging = true
                            pinned = true
                            setSide(Side.NONE)
                        }
                        if (dragging) {
                            val area = contentArea()
                            val r = IRect(px + dx.toInt(), py + dy.toInt(), px + dx.toInt() + p.width, py + dy.toInt() + p.height).clampInto(area)
                            p.x = r.left
                            p.y = r.top
                            runCatching { wm.updateViewLayout(root, p) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                        relayout()
                        publish()
                    }
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

    companion object {
        /**
         * Где сейчас отметки и заметки. Окна отметок не принимают касаний, поэтому средства
         * тестирования их не видят — тесты смотрят сюда.
         */
        @Volatile
        var shownMarks: List<IRect> = emptyList()
            private set

        @Volatile
        var shownNotes: List<IRect> = emptyList()
            private set

        /** Отметки полупрозрачные: иначе Android 12+ не пропустит сквозь них стилус. */
        private const val MARK_ALPHA = 0.8f
        private const val FADE_MS = 2500L

        /** Ширина свёрнутой заметки (короткая подсказка). */
        private const val COMPACT_WIDTH_DP = 300f
    }
}

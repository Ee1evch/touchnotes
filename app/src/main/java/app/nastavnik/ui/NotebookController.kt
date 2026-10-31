package app.nastavnik.ui

import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nastavnik.NastavnikApp
import app.nastavnik.ai.Prompts
import app.nastavnik.capture.ImageUtils
import app.nastavnik.geom.IRect
import app.nastavnik.ink.InkPage
import app.nastavnik.ink.InkView
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.ink.PAGE_W
import app.nastavnik.ink.PageMark
import app.nastavnik.ink.Paper
import app.nastavnik.ink.Tool
import app.nastavnik.ink.UndoStack
import app.nastavnik.session.AnswerUi
import app.nastavnik.session.AskKind
import app.nastavnik.session.MentorSession
import app.nastavnik.session.ShotGeometry
import app.nastavnik.session.ShotResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Всё, что происходит в открытой тетради: страницы, сохранение, инструменты и наставник, который видит
 * текущую страницу (с номерами строк) и остальные страницы тетради как контекст.
 */
class NotebookController(val app: NastavnikApp, val notebookId: Long, private val scope: CoroutineScope) : InkView.Listener {
    private val nbs = app.notebooks
    val ink = InkView(app).also { it.listener = this }

    var meta by mutableStateOf<NotebookMeta?>(null)
        private set
    var pages by mutableStateOf<List<InkPage>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set

    // Инструменты (запоминаются между тетрадями).
    var tool by mutableStateOf(Tool.PEN)
        private set
    var penColor by mutableIntStateOf(app.settings.penColor())
        private set
    var markerColor by mutableIntStateOf(MARKERS.first())
        private set
    var widthIndex by mutableIntStateOf(1)
        private set
    var eraser by mutableStateOf(false)
        private set
    var fingerDraws by mutableStateOf(app.settings.fingerDraws())
        private set

    /** Сам проверять страницу, когда студент перестал писать. */
    var autoCheck by mutableStateOf(app.settings.notebookAutoCheck())
        private set

    private val undos = HashMap<Long, UndoStack>()
    private var saveJob: Job? = null
    private var autoJob: Job? = null
    private var lastAutoAt = 0L

    val mentor = MentorSession(app, scope, bindToSettings = false) { conv -> rememberConversation(conv) }

    /** К какой странице относится ответ наставника и во сколько раз снимок меньше страницы. */
    private var answerPageId = 0L
    private var answerScale = 1f

    val page: InkPage? get() = pages.getOrNull(index)

    suspend fun load() {
        val m = withContext(Dispatchers.IO) { nbs.store.meta(notebookId) } ?: return
        val ps = withContext(Dispatchers.IO) { nbs.store.loadPages(m) }
        meta = m
        pages = ps
        mentor.useConversation(m.conversationId)
        applyTool()
        showPage(0)
        loaded = true
    }

    fun showPage(i: Int) {
        val p = pages.getOrNull(i) ?: return
        flushSave()
        index = i
        val stack = undos.getOrPut(p.id) { UndoStack() }
        scope.launch {
            val bg = nbs.background(notebookId, p)
            if (pages.getOrNull(index)?.id == p.id) {
                ink.setPage(p, bg, stack)
                refreshMarks()
            }
        }
    }

    // ---------------- страницы ----------------

    fun addPage(paper: Paper) {
        val m = meta ?: return
        val p = InkPage(nbs.store.newId(), paper)
        scope.launch {
            val nm = withContext(Dispatchers.IO) { nbs.store.addPage(m, p, index) }
            meta = nm
            pages = pages.toMutableList().apply { add(index + 1, p) }
            showPage(index + 1)
        }
    }

    fun addImages(uris: List<Uri>) = append { m -> nbs.appendImages(m, uris) }

    fun addPdf(uri: Uri) = append { m -> nbs.appendPdf(m, uri) }

    private fun append(block: suspend (NotebookMeta) -> NotebookMeta) {
        val m = meta ?: return
        scope.launch {
            flushSaveNow()
            val nm = block(m)
            val added = nm.pages.size - m.pages.size
            if (added <= 0) return@launch
            meta = nm
            pages = withContext(Dispatchers.IO) { nbs.store.loadPages(nm) }
            showPage(pages.size - added)
        }
    }

    fun deletePage() {
        val m = meta ?: return
        val p = page ?: return
        if (pages.size <= 1) return
        scope.launch {
            val nm = withContext(Dispatchers.IO) { nbs.store.removePage(m, p.id) }
            meta = nm
            pages = pages.filter { it.id != p.id }
            showPage(index.coerceAtMost(pages.lastIndex))
        }
    }

    fun setPaper(paper: Paper) {
        val p = page ?: return
        p.paper = paper
        p.touch()
        ink.invalidate()
        onPageEdited()
    }

    fun rename(title: String) {
        val m = meta ?: return
        scope.launch { meta = withContext(Dispatchers.IO) { nbs.store.rename(m, title) } }
    }

    fun clearPage() = ink.clearPage()
    fun undo() = ink.undo()
    fun redo() = ink.redo()

    // ---------------- инструменты ----------------

    fun pickPen(color: Int) {
        tool = Tool.PEN; eraser = false; penColor = color
        app.settings.setPenColor(color)
        applyTool()
    }

    fun pickMarker(color: Int) {
        tool = Tool.HIGHLIGHTER; eraser = false; markerColor = color
        applyTool()
    }

    fun pickEraser() {
        eraser = !eraser
        applyTool()
    }

    fun cycleWidth() {
        widthIndex = (widthIndex + 1) % WIDTHS.size
        applyTool()
    }

    fun toggleFinger() {
        fingerDraws = !fingerDraws
        app.settings.setFingerDraws(fingerDraws)
        applyTool()
    }

    fun toggleAutoCheck() {
        autoCheck = !autoCheck
        app.settings.setNotebookAutoCheck(autoCheck)
        if (!autoCheck) autoJob?.cancel()
    }

    private fun applyTool() {
        ink.tool = tool
        ink.color = if (tool == Tool.HIGHLIGHTER) markerColor else penColor
        ink.penWidth = WIDTHS[widthIndex]
        ink.eraser = eraser
        ink.fingerDraws = fingerDraws
    }

    // ---------------- сохранение ----------------

    override fun onUndoStateChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo
        this.canRedo = canRedo
    }

    override fun onWritingStarted() {
        autoJob?.cancel()
    }

    override fun onPageEdited() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            flushSaveNow()
        }
        scheduleAutoCheck()
    }

    private fun flushSave() {
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            scope.launch { flushSaveNow() }
        }
    }

    private suspend fun flushSaveNow() {
        val m = meta ?: return
        val p = page ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            nbs.store.savePage(m.id, p)
            val nm = m.copy(updatedAt = System.currentTimeMillis())
            nbs.store.saveMeta(nm)
            meta = nm
        }
    }

    /** Уходим из тетради: сохранить страницу и обновить превью. */
    fun close() {
        autoJob?.cancel()
        mentor.cancel()
        val m = meta ?: return
        val first = pages.firstOrNull() ?: return
        app.appScope.launch {
            flushSaveNow()
            runCatching { nbs.saveThumb(m, first) }
        }
    }

    private fun rememberConversation(conv: Long) {
        val m = meta ?: return
        if (m.conversationId == conv) return
        val nm = m.copy(conversationId = conv)
        meta = nm
        app.appScope.launch(Dispatchers.IO) { nbs.store.saveMeta(nm) }
    }

    // ---------------- наставник ----------------

    fun ask(question: String, kind: AskKind) {
        if (app.settings.notReadyReason() != null) return
        autoJob?.cancel()
        mentor.ask(question, kind) { shot(if (kind == AskKind.WATCH) WATCH_CONTEXT else CONTEXT_PAGES) }
    }

    /** Студент перестал писать — через паузу тихо проверяем страницу (только если включено). */
    private fun scheduleAutoCheck() {
        autoJob?.cancel()
        if (!autoCheck || app.settings.notReadyReason() != null) return
        autoJob = scope.launch {
            delay(AUTO_PAUSE_MS)
            if (System.currentTimeMillis() - lastAutoAt < AUTO_GAP_MS || mentor.isBusy) return@launch
            // Ответ на вопрос студента не перебиваем.
            val a = mentor.state.value.answer
            if (a != null && a.kind != AskKind.WATCH) return@launch
            lastAutoAt = System.currentTimeMillis()
            ask(Prompts.WATCH_PROMPT, AskKind.WATCH)
        }
    }

    /** Снимок текущей страницы (с номерами строк) и уменьшенные соседние страницы тетради. */
    private suspend fun shot(contextLimit: Int): ShotResult {
        val p = page ?: return ShotResult.Failed("Страница не открыта")
        flushSaveNow()
        val bmp = nbs.render(notebookId, p, ImageUtils.MAX_PIXELS)
        val prepared = try {
            withContext(Dispatchers.Default) { ImageUtils.prepare(bmp, Rect(0, 0, bmp.width, bmp.height), numberLines = true) }
        } finally {
            bmp.recycle()
        }
        val r = prepared.screenRect
        answerPageId = p.id
        answerScale = PAGE_W / r.width().coerceAtLeast(1)
        val geometry = ShotGeometry(IRect(r.left, r.top, r.right, r.bottom), prepared.width, prepared.height, prepared.gutter, prepared.lines, prepared.luma)
        ShotGeometry.last = geometry
        // Остальные страницы — по близости к текущей, в порядке тетради.
        val others = pages.withIndex().filter { it.index != index }
            .sortedBy { abs(it.index - index) }.take(contextLimit).sortedBy { it.index }
        val context = others.map { nbs.renderJpeg(notebookId, it.value, ImageUtils.SMALL_PIXELS, 78) }
        return ShotResult.Ok(prepared.jpeg, geometry, null, context)
    }

    /** Отметки ответа — на странице, к которой он относится. */
    fun refreshMarks(a: AnswerUi? = mentor.state.value.answer) {
        val p = page
        ink.marks = if (a == null || p == null || p.id != answerPageId) {
            emptyList()
        } else {
            val g = a.geometry
            a.marks.mapIndexedNotNull { i, m ->
                val b = m.box ?: return@mapIndexedNotNull null
                val s = g?.toScreen(b) ?: return@mapIndexedNotNull null
                PageMark(i + 1, m.kind, RectF(s.left * answerScale, s.top * answerScale, s.right * answerScale, s.bottom * answerScale))
            }
        }
    }

    /** Показать место заметки [number] на странице. */
    fun focusMark(number: Int) {
        ink.selectedMark = number
        ink.marks.firstOrNull { it.number == number }?.let { ink.reveal(it.rect) }
    }

    /** Все страницы тетради — картинками для чистовика. */
    suspend fun pageImages(): List<ByteArray> {
        flushSaveNow()
        return pages.map { nbs.renderJpeg(notebookId, it, ImageUtils.MAX_PIXELS, 85) }
    }

    companion object {
        /** Синяя ручка — как у большинства студентов, чёрная, красная для пометок, зелёная. */
        val PENS = listOf(0xFF1F4FD1.toInt(), 0xFF16161A.toInt(), 0xFFD93A3A.toInt(), 0xFF1F9D60.toInt())
        val MARKERS = listOf(0xFFFFE15A.toInt(), 0xFFFF9BCB.toInt(), 0xFF9CD8FF.toInt())
        val WIDTHS = floatArrayOf(2.2f, 3.2f, 5f)
        private const val SAVE_DELAY_MS = 800L
        private const val AUTO_PAUSE_MS = 7_000L
        private const val AUTO_GAP_MS = 40_000L
        private const val CONTEXT_PAGES = 8
        private const val WATCH_CONTEXT = 3
    }
}

package app.nastavnik.ui

import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nastavnik.NastavnikApp
import app.nastavnik.ai.Prompts
import app.nastavnik.capture.ImageUtils
import app.nastavnik.geom.IRect
import app.nastavnik.ink.InkPage
import app.nastavnik.ink.InkView
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.ink.NotebookStore
import app.nastavnik.ink.PageRenderer
import android.graphics.Bitmap
import app.nastavnik.ink.PAGE_W
import app.nastavnik.ink.PageMark
import app.nastavnik.ink.Paper
import app.nastavnik.ink.Tool
import app.nastavnik.ink.EditTool
import app.nastavnik.ink.ShapeKind
import app.nastavnik.ink.Stroke
import app.nastavnik.ink.TextBox
import app.nastavnik.ink.UndoStack
import app.nastavnik.session.AnswerUi
import app.nastavnik.session.AskKind
import app.nastavnik.session.MentorSession
import app.nastavnik.study.PracticePrompts
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
/**
 * На какой странице открыть тетрадь: тетрадь → страница. Действует 15 секунд после [set] — экран тетради при переходе
 * может собраться дважды, и оба раза должен открыться на этой странице.
 */
object NotebookStart {
    @Volatile private var target: Triple<Long, Long, Long>? = null

    fun set(notebookId: Long, pageId: Long) { target = Triple(notebookId, pageId, System.currentTimeMillis()) }

    fun take(notebookId: Long): Long? =
        target?.takeIf { it.first == notebookId && System.currentTimeMillis() - it.third < 15_000L }?.second
}

class NotebookController(val app: NastavnikApp, val notebookId: Long, private val scope: CoroutineScope) : InkView.Listener {
    private val nbs = app.notebooks
    val ink = InkView(app).also {
        it.listener = this
        it.contentDescription = "Лист тетради"
    }

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

    // Инструменты (цвет ручки и «писать пальцем» запоминаются между тетрадями).
    var editTool by mutableStateOf(EditTool.PEN)
        private set
    var penColor by mutableIntStateOf(app.settings.penColor())
        private set
    var markerColor by mutableIntStateOf(MARKERS.first())
        private set
    var penWidth by mutableStateOf(3.2f)
        private set
    var markerWidth by mutableStateOf(InkView.HIGHLIGHTER_W)
        private set
    var partialEraser by mutableStateOf(false)
        private set
    var shapeKind by mutableStateOf(ShapeKind.LINE)
        private set
    var textSize by mutableStateOf(TEXT_SIZES[1])
        private set
    var fingerDraws by mutableStateOf(app.settings.fingerDraws())
        private set

    /** Где плашка инструментов: у правого края или у левого, и на какой высоте (0 — сверху, 1 — снизу). */
    var railRight by mutableStateOf(app.settings.railRight())
        private set
    var railY by mutableStateOf(app.settings.railY())
        private set

    fun moveRail(right: Boolean, y: Float) {
        railRight = right
        railY = y
        app.settings.setRail(right, y)
    }

    /** Перо задержали — сейчас работает временный ластик (подсвечиваем его в палитре). */
    var tempEraser by mutableStateOf(false)
        private set

    /** Выделение лассо: рамка на экране и сколько выделено. */
    var selection by mutableStateOf<RectF?>(null)
        private set
    var selectionCount by mutableIntStateOf(0)
        private set

    /** Скопированное лассо (можно вставить на другую страницу). */
    private var clipboard: Pair<List<Stroke>, List<TextBox>>? = null
    var canPaste by mutableStateOf(false)
        private set

    /** Короткая подсказка поверх листа: что произошло или как пользоваться инструментом. */
    var hint by mutableStateOf<String?>(null)
        private set
    var hintSeq by mutableIntStateOf(0)
        private set

    /** Просят напечатать текст: место на странице и текст, который правят (или null — новый). */
    var textRequest by mutableStateOf<Triple<Float, Float, TextBox?>?>(null)
        private set

    /** Сам проверять страницу, когда студент перестал писать. */
    var autoCheck by mutableStateOf(app.settings.notebookAutoCheck())
        private set

    /** Контрольная: до какого момента идёт (0 — не идёт). Наставник молчит, пока её не сдадут. */
    var examEndsAt by mutableLongStateOf(0L)
        private set
    val examActive: Boolean get() = examEndsAt > 0L
    private var examJob: Job? = null

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
        mentor.extraSystem = PracticePrompts.variantForMentor(m.tasks)
        applyTool()
        // Открыли ради конкретной страницы (задача ДЗ) — сразу на ней.
        val start = NotebookStart.take(notebookId)?.let { id -> ps.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        showPage(start)
        loaded = true
    }

    fun showPage(i: Int) {
        val p = pages.getOrNull(i) ?: return
        flushSave()
        // Листаем с анимацией: вперёд — новая страница въезжает справа.
        val direction = if (!loaded || i == index) 0 else if (i > index) 1 else -1
        index = i
        val stack = undos.getOrPut(p.id) { UndoStack() }
        scope.launch {
            val bg = nbs.background(notebookId, p)
            if (pages.getOrNull(index)?.id == p.id) {
                ink.setPage(p, bg, stack, direction)
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

    /** Копия текущей страницы сразу после неё (вместе с подложкой). */
    fun duplicatePage() {
        val m = meta ?: return
        val p = page ?: return
        scope.launch {
            flushSaveNow()
            val copy = withContext(Dispatchers.IO) {
                val id = nbs.store.newId()
                val bg = p.background?.let { name ->
                    val newName = NotebookStore.backgroundName(id)
                    runCatching { nbs.store.background(m.id, name).copyTo(nbs.store.background(m.id, newName), overwrite = true) }
                        .map { newName }.getOrNull()
                }
                InkPage(id, p.paper, bg, p.height, p.strokes.toMutableList(), p.texts.toMutableList())
            }
            val nm = withContext(Dispatchers.IO) { nbs.store.addPage(m, copy, index) }
            meta = nm
            pages = pages.toMutableList().apply { add(index + 1, copy) }
            showPage(index + 1)
            showHint("Страница скопирована")
        }
    }

    /** Переставить страницу [from] на место [to]. */
    fun movePage(from: Int, to: Int) {
        val m = meta ?: return
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val current = page?.id
        val list = pages.toMutableList().apply { add(to, removeAt(from)) }
        pages = list
        index = list.indexOfFirst { it.id == current }.coerceAtLeast(0)
        val nm = m.copy(pages = list.map { it.id }, updatedAt = System.currentTimeMillis())
        meta = nm
        scope.launch(Dispatchers.IO) { nbs.store.saveMeta(nm) }
    }

    /** Миниатюра страницы для обзора тетради. */
    suspend fun thumbnail(p: InkPage, widthPx: Int): Bitmap = withContext(Dispatchers.Default) {
        val bg = nbs.background(notebookId, p)
        PageRenderer.render(p, bg, widthPx)
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
        ink.pageChanged()
        onPageEdited()
    }

    /** Одна бумага на всю тетрадь: все страницы без подложки (фото и PDF не трогаем). */
    fun setPaperAll(paper: Paper) {
        val m = meta ?: return
        pages.filter { it.background == null }.forEach { it.paper = paper; it.touch() }
        ink.pageChanged()
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                pages.forEach { nbs.store.savePage(m.id, it) }
                nbs.store.saveMeta(m.copy(paper = paper.name, updatedAt = System.currentTimeMillis()).also { meta = it })
            }
        }
    }

    /** Масштаб листа: 1 — по ширине экрана. */
    var zoom by mutableStateOf(1f)
        private set

    override fun onZoomChanged(zoom: Float) {
        this.zoom = zoom
    }

    fun fitWidth() = ink.animateFitWidth()

    fun rename(title: String) {
        val m = meta ?: return
        scope.launch { meta = withContext(Dispatchers.IO) { nbs.store.rename(m, title) } }
    }

    fun clearPage() = ink.clearPage()
    fun undo() = ink.undo()
    fun redo() = ink.redo()

    // ---------------- инструменты ----------------

    fun pickTool(t: EditTool) {
        if (editTool == t) return
        editTool = t
        applyTool()
    }

    fun pickPen(color: Int) {
        penColor = color
        app.settings.setPenColor(color)
        if (editTool != EditTool.SHAPE && editTool != EditTool.TEXT) editTool = EditTool.PEN
        applyTool()
    }

    fun pickMarker(color: Int) {
        markerColor = color
        editTool = EditTool.HIGHLIGHTER
        applyTool()
    }

    fun changePenWidth(w: Float) {
        penWidth = w
        applyTool()
    }

    fun changeMarkerWidth(w: Float) {
        markerWidth = w
        applyTool()
    }

    fun choosePartialEraser(on: Boolean) {
        partialEraser = on
        applyTool()
    }

    fun pickShape(k: ShapeKind) {
        shapeKind = k
        editTool = EditTool.SHAPE
        applyTool()
    }

    fun pickTextSize(size: Float) {
        textSize = size
    }

    fun toggleFinger() {
        fingerDraws = !fingerDraws
        app.settings.setFingerDraws(fingerDraws)
        applyTool()
        showHint(if (fingerDraws) "Пальцем пишешь, двумя — двигаешь лист" else "Пальцем двигаешь лист, пишешь пером")
    }

    fun showHint(text: String) {
        hint = text
        hintSeq++
    }

    // Выделение.
    fun deleteSelection() = ink.deleteSelection()
    fun duplicateSelection() = ink.duplicateSelection()
    fun recolorSelection(c: Int) = ink.recolorSelection(c)
    fun clearSelection() = ink.clearSelection()

    fun copySelection() {
        val (s, t) = ink.selectionCopy()
        if (s.isEmpty() && t.isEmpty()) return
        clipboard = s to t
        canPaste = true
        showHint("Скопировано — можно вставить на любую страницу")
    }

    fun paste() {
        val c = clipboard ?: return
        ink.paste(c.first, c.second)
        editTool = EditTool.LASSO
    }

    // Текст.
    fun submitText(text: String) {
        val r = textRequest ?: return
        textRequest = null
        ink.putText(r.first, r.second, text, r.third?.size ?: textSize, r.third?.color ?: penColor, r.third)
    }

    fun cancelText() {
        textRequest = null
    }

    fun toggleAutoCheck() {
        autoCheck = !autoCheck
        app.settings.setNotebookAutoCheck(autoCheck)
        if (!autoCheck) autoJob?.cancel()
    }

    private fun applyTool() {
        ink.editTool = editTool
        ink.color = if (editTool == EditTool.HIGHLIGHTER) markerColor else penColor
        ink.penWidth = penWidth
        ink.markerWidth = markerWidth
        ink.partialEraser = partialEraser
        ink.shapeKind = shapeKind
        ink.fingerDraws = fingerDraws
    }

    override fun onSelectionChanged(screen: RectF?, count: Int) {
        selection = screen
        selectionCount = count
    }

    override fun onTextRequest(x: Float, y: Float, existing: TextBox?) {
        textRequest = Triple(x, y, existing)
    }

    override fun onHint(text: String) = showHint(text)

    override fun onTemporaryEraser(active: Boolean) {
        tempEraser = active
    }

    // ---------------- сохранение ----------------

    override fun onUndoStateChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo
        this.canRedo = canRedo
    }

    /** Пишут прямо сейчас — палитра становится прозрачнее, чтобы не отвлекать. */
    var writing by mutableStateOf(false)
        private set
    private var writingJob: Job? = null

    override fun onWritingStarted() {
        autoJob?.cancel()
        writing = true
        writingJob?.cancel()
        writingJob = scope.launch {
            delay(1400)
            writing = false
        }
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
        examJob?.cancel()
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
        val pages = when {
            kind == AskKind.WATCH -> WATCH_CONTEXT
            app.settings.load().fastAnswers -> FAST_CONTEXT
            else -> CONTEXT_PAGES
        }
        mentor.ask(question, kind) { shot(pages) }
    }

    // ---------------- контрольная ----------------

    /** Начать контрольную на [minutes] минут: подсказок нет, по времени работа сдаётся сама. */
    fun startExam(minutes: Int) {
        autoJob?.cancel()
        mentor.dismiss()
        examEndsAt = System.currentTimeMillis() + minutes * 60_000L
        examJob?.cancel()
        examJob = scope.launch {
            delay(minutes * 60_000L)
            submitExam()
        }
    }

    /** Сдать работу: наставник проверяет всё и ставит оценку. */
    fun submitExam() {
        examJob?.cancel()
        examEndsAt = 0L
        ask(Prompts.EXAM_PROMPT, AskKind.USER)
    }

    fun cancelExam() {
        examJob?.cancel()
        examEndsAt = 0L
    }

    /** Студент перестал писать — через паузу тихо проверяем страницу (только если включено и не идёт контрольная). */
    private fun scheduleAutoCheck() {
        autoJob?.cancel()
        if (!autoCheck || examActive || app.settings.notReadyReason() != null) return
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
        val PENS = listOf(
            0xFF1F4FD1.toInt(), 0xFF16161A.toInt(), 0xFFD93A3A.toInt(), 0xFF1F9D60.toInt(),
            0xFFE07B1F.toInt(), 0xFF7A4BD1.toInt(),
        )
        val MARKERS = listOf(0xFFFFE15A.toInt(), 0xFFFF9BCB.toInt(), 0xFF9CD8FF.toInt(), 0xFFA6F0A0.toInt())
        val WIDTHS = floatArrayOf(2.2f, 3.2f, 5f)

        /** Размер печатного текста: мелкий, обычный, заголовок (в единицах страницы). */
        val TEXT_SIZES = floatArrayOf(16f, 22f, 32f)
        private const val SAVE_DELAY_MS = 800L
        private const val AUTO_PAUSE_MS = 7_000L
        private const val AUTO_GAP_MS = 40_000L
        private const val CONTEXT_PAGES = 8
        private const val WATCH_CONTEXT = 3
        private const val FAST_CONTEXT = 2
    }
}

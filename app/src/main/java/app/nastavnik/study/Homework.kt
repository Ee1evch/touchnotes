package app.nastavnik.study

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.nastavnik.NastavnikApp
import app.nastavnik.ai.AiException
import app.nastavnik.ai.ChatRequest
import app.nastavnik.ai.ChatTurn
import app.nastavnik.ai.ImagePart
import app.nastavnik.ai.Role
import app.nastavnik.app
import app.nastavnik.capture.ImageUtils
import app.nastavnik.data.CardEntity
import app.nastavnik.ink.InkPage
import app.nastavnik.ink.NotebookStore
import app.nastavnik.ink.PAGE_W
import app.nastavnik.ink.Paper
import app.nastavnik.study.HomeworkRules.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Где условие задачи в PDF: страница и полоса по высоте (доли 0…1). */
@Serializable
data class Crop(val page: Int, val top: Float, val bottom: Float)

/** Задача ДЗ: где условие, состояние проверки, страница в тетради с решением. */
@Serializable
data class HwTask(
    val id: String,
    /** «№ 245» или «Задача 3». */
    val label: String,
    val parts: List<Crop> = emptyList(),
    /** Условие текстом (если ИИ его прочитал) — для наставника и карточек. */
    val text: String = "",
    val status: Status = Status.TODO,
    /** Сколько раз проверка не приняла решение. */
    val failed: Int = 0,
    /** Последний ответ проверки. */
    val comment: String = "",
    val notebookId: Long = 0,
    val pageId: Long = 0,
    /** Карточки-задачи из этой задачи уже сделаны. */
    val cards: Boolean = false,
    /** Задачу не нашли в учебнике — номер есть, условия нет. */
    val missing: Boolean = false,
)

/** ДЗ по предмету к сроку. [source] — PDF (учебник или файл с заданиями). */
@Serializable
data class Homework(
    val id: String,
    val subject: String,
    /** Срок сдачи, 2026-11-05. */
    val due: String,
    val createdAt: Long,
    val source: String,
    val bookId: String = "",
    val tasks: List<HwTask> = emptyList(),
) {
    val statuses get() = tasks.map { it.status }
    val complete get() = HomeworkRules.complete(statuses)
    val dueDate: LocalDate get() = LocalDate.parse(due)
}

/** Номер задачи на странице учебника и где её условие (доли высоты). */
@Serializable
data class PageProblem(val n: String, val top: Float, val bottom: Float)

/** Учебник (задачник) предмета: PDF и уже просмотренные страницы (страница → задачи на ней; пусто — теория). */
@Serializable
data class Textbook(
    val id: String,
    val subject: String,
    val title: String,
    val pages: Int,
    val index: Map<Int, List<PageProblem>> = emptyMap(),
)

/**
 * Домашка: учебники и ДЗ (файлы в `files/homework` и `files/textbooks`), поиск задачи в учебнике делением пополам,
 * разбор файла с заданиями, страница в тетради с условием сверху, проверка решения и карточки-задачи — через ИИ.
 */
object Homeworks {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val lock = Mutex()
    private val _version = MutableStateFlow(0)
    /** Растёт при каждом изменении — экраны перечитывают. */
    val version: StateFlow<Int> = _version

    private fun dir(c: Context) = File(c.filesDir, "homework").apply { mkdirs() }
    private fun booksDir(c: Context) = File(c.filesDir, "textbooks").apply { mkdirs() }

    // ---------------- хранение ----------------

    fun list(c: Context): List<Homework> = dir(c).listFiles { f -> f.name.endsWith(".json") && f.name != "notebooks.json" }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString(Homework.serializer(), it.readText()) }.getOrNull() }
        .sortedWith(compareBy({ it.complete }, { it.due }, { it.createdAt }))

    fun get(c: Context, id: String): Homework? =
        runCatching { json.decodeFromString(Homework.serializer(), File(dir(c), "$id.json").readText()) }.getOrNull()

    fun save(c: Context, hw: Homework) {
        write(File(dir(c), "${hw.id}.json"), json.encodeToString(Homework.serializer(), hw))
        _version.value++
    }

    fun delete(c: Context, hw: Homework) {
        File(dir(c), "${hw.id}.json").delete()
        if (hw.bookId.isEmpty()) File(hw.source).delete()
        _version.value++
    }

    fun books(c: Context): List<Textbook> = booksDir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString(Textbook.serializer(), it.readText()) }.getOrNull() }
        .sortedBy { it.subject }

    fun book(c: Context, id: String): Textbook? =
        runCatching { json.decodeFromString(Textbook.serializer(), File(booksDir(c), "$id.json").readText()) }.getOrNull()

    fun bookFile(c: Context, id: String) = File(booksDir(c), "$id.pdf")

    private fun saveBook(c: Context, b: Textbook) {
        write(File(booksDir(c), "${b.id}.json"), json.encodeToString(Textbook.serializer(), b))
        _version.value++
    }

    fun deleteBook(c: Context, b: Textbook) {
        File(booksDir(c), "${b.id}.json").delete()
        bookFile(c, b.id).delete()
        _version.value++
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Задача ДЗ, решение которой на этой странице тетради. */
    fun taskForPage(c: Context, notebookId: Long, pageId: Long): Pair<Homework, HwTask>? {
        if (pageId == 0L) return null
        for (hw in list(c)) hw.tasks.firstOrNull { it.notebookId == notebookId && it.pageId == pageId }?.let { return hw to it }
        return null
    }

    private fun update(c: Context, hwId: String, taskId: String, f: (HwTask) -> HwTask): Homework? {
        val hw = get(c, hwId) ?: return null
        val nw = hw.copy(tasks = hw.tasks.map { if (it.id == taskId) f(it) else it })
        save(c, nw)
        return nw
    }

    /** ДЗ к вечеру [session]: на следующий день (и просроченные), с отметкой «сделано на 100%». */
    fun forEvening(c: Context, session: LocalDate): List<Homework> =
        list(c).filter { !it.dueDate.isAfter(session.plusDays(1)) && (!it.complete || it.dueDate.isAfter(session)) }

    /** Не сделанные на 100% ДЗ, которые надо закрыть в вечер [session]. */
    fun openFor(c: Context, session: LocalDate): List<Homework> =
        HomeworkRules.openFor(session, list(c), { it.dueDate }, { it.statuses })

    // ---------------- PDF ----------------

    private fun <T> pdf(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }

    fun pageCount(file: File): Int = runCatching { pdf(file) { it.pageCount } }.getOrDefault(0)

    /** Страница PDF картинкой шириной [width] на белом. */
    fun renderPage(file: File, index: Int, width: Int = 1400): Bitmap? = runCatching {
        pdf(file) { r ->
            r.openPage(index).use { p ->
                val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }.getOrNull()

    /** Условие задачи картинкой: полосы со страниц PDF одна под другой. */
    fun condition(file: File, parts: List<Crop>, width: Int = 1400): Bitmap? {
        val pieces = parts.mapNotNull { c ->
            val page = renderPage(file, c.page, width) ?: return@mapNotNull null
            val top = (c.top.coerceIn(0f, 1f) * page.height).toInt()
            val bottom = (c.bottom.coerceIn(0f, 1f) * page.height).toInt().coerceAtLeast(top + 20).coerceAtMost(page.height)
            Bitmap.createBitmap(page, 0, top, page.width, bottom - top)
        }
        if (pieces.isEmpty()) return null
        val out = Bitmap.createBitmap(width, pieces.sumOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var y = 0f
        pieces.forEach { canvas.drawBitmap(it, 0f, y, null); y += it.height; it.recycle() }
        return out
    }

    private fun copy(c: Context, uri: Uri, to: File): Boolean = runCatching {
        c.contentResolver.openInputStream(uri)!!.use { input -> to.outputStream().use { input.copyTo(it) } }
        true
    }.getOrDefault(false)

    // ---------------- ИИ ----------------

    private suspend fun ask(c: Context, system: String, text: String, images: List<ByteArray>, maxTokens: Int): Result<String> {
        val app = c.app
        val settings = app.settings.load()
        app.settings.notReadyReason(settings)?.let { return Result.failure(IllegalStateException(it)) }
        val key = app.settings.apiKey(settings.provider)
        val request = ChatRequest(
            modelId = settings.model,
            system = system,
            turns = listOf(ChatTurn(Role.USER, text, hadImage = images.isNotEmpty())),
            images = images.map { ImagePart(it) },
            maxTokens = maxTokens,
            jsonOutput = true,
        )
        return try {
            Result.success(app.ai(settings).complete(key, request).text)
        } catch (e: AiException) {
            Result.failure(IllegalStateException(e.userMessage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Не удалось связаться с нейросетью: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun obj(raw: String): JsonObject? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { json.parseToJsonElement(raw.substring(a, b + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.f(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull

    private fun jpeg(bmp: Bitmap): ByteArray = ImageUtils.toJpeg(ImageUtils.scaleDown(bmp), 85)

    private const val PAGE_SYSTEM = "Ты смотришь на страницу задачника по высшей математике. Найди на ней начала задач — " +
        "номера в начале условия (например «245.», «1.12.», «260а)», «№ 17»). Номера страниц, формул, пунктов теории и " +
        "ответов не считаются. Для каждой задачи укажи, где её условие на странице: top и bottom — доли высоты страницы " +
        "(0 — верх, 1 — низ). Если условие продолжается на следующей странице, bottom = 1. Ответ — только JSON: " +
        "{\"problems\":[{\"n\":\"245\",\"top\":0.12,\"bottom\":0.18}]}; если задач на странице нет — {\"problems\":[]}."

    /** Задачи на странице учебника (страница → номера и где они); запоминается в учебнике. */
    private suspend fun readPage(c: Context, book: Textbook, page: Int): Result<List<PageProblem>> {
        book.index[page]?.let { return Result.success(it) }
        val bmp = withContext(Dispatchers.IO) { renderPage(bookFile(c, book.id), page, 1300) } ?: return Result.failure(IllegalStateException("Не открылась страница ${page + 1} учебника"))
        val img = withContext(Dispatchers.Default) { jpeg(bmp).also { bmp.recycle() } }
        return ask(c, PAGE_SYSTEM, "Страница ${page + 1}.", listOf(img), 900).map { raw ->
            obj(raw)?.get("problems")?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val n = o.s("n").trim().trimEnd('.', ')').removePrefix("№").trim().lowercase()
                if (n.isEmpty()) null else PageProblem(n, o.f("top") ?: 0f, o.f("bottom") ?: 1f)
            }.sortedWith { a, b -> HomeworkRules.compare(a.n, b.n) }
        }
    }

    /** Найти задачу [n] в учебнике: делением пополам по страницам; просмотренные страницы запоминаются. */
    suspend fun findInBook(c: Context, bookId: String, n: String): Result<List<Crop>?> = lock.withLock {
        var book = withContext(Dispatchers.IO) { book(c, bookId) } ?: return Result.failure(IllegalStateException("Учебник не найден"))
        repeat(24) {
            val known = book.index.mapValues { (_, ps) -> ps.takeIf { it.isNotEmpty() }?.let { HomeworkRules.PageRange(it.first().n, it.last().n) } }
            when (val probe = HomeworkRules.nextProbe(known, n, book.pages)) {
                is HomeworkRules.Probe.Found -> {
                    val problems = book.index[probe.page].orEmpty()
                    val i = problems.indexOfFirst { HomeworkRules.compare(it.n, n) == 0 }
                    val p = problems.getOrNull(i) ?: problems.lastOrNull { HomeworkRules.compare(it.n, n) <= 0 } ?: return Result.success(null)
                    val next = problems.getOrNull(i + 1)
                    val parts = mutableListOf(Crop(probe.page, p.top, next?.top ?: maxOf(p.bottom, 0.97f)))
                    // Последняя на странице — условие могло продолжиться на следующей: до первой задачи там.
                    if (next == null && probe.page + 1 < book.pages) {
                        readPage(c, book, probe.page + 1).getOrNull()?.let { following ->
                            book = book.copy(index = book.index + (probe.page + 1 to following))
                            withContext(Dispatchers.IO) { saveBook(c, book) }
                            val until = following.firstOrNull()?.top ?: 0.35f
                            if (until > 0.04f) parts += Crop(probe.page + 1, 0f, until)
                        }
                    }
                    return Result.success(parts)
                }
                is HomeworkRules.Probe.Look -> {
                    val problems = readPage(c, book, probe.page).getOrElse { return Result.failure(it) }
                    book = book.copy(index = book.index + (probe.page to problems))
                    withContext(Dispatchers.IO) { saveBook(c, book) }
                }
                HomeworkRules.Probe.NotFound -> return Result.success(null)
            }
        }
        Result.success(null)
    }

    /** Загрузить учебник предмета (PDF копируется в приложение). */
    suspend fun importBook(c: Context, uri: Uri, subject: String): Result<Textbook> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(12)
        val f = bookFile(c, id)
        if (!copy(c, uri, f)) return@withContext Result.failure(IllegalStateException("Не удалось прочитать файл"))
        val pages = pageCount(f)
        if (pages == 0) { f.delete(); return@withContext Result.failure(IllegalStateException("Это не PDF или он защищён паролем")) }
        val name = c.app.notebooks.displayName(uri)?.removeSuffix(".pdf")?.ifBlank { null } ?: subject
        val b = Textbook(id, subject, name, pages)
        saveBook(c, b)
        Result.success(b)
    }

    /** Новое ДЗ из учебника: задачи по номерам (условия ищутся потом — [locate]). */
    fun fromBook(c: Context, book: Textbook, numbers: List<String>, due: LocalDate): Homework {
        val hw = Homework(
            id = UUID.randomUUID().toString().take(12), subject = book.subject, due = due.toString(),
            createdAt = System.currentTimeMillis(), source = bookFile(c, book.id).path, bookId = book.id,
            tasks = numbers.map { HwTask(UUID.randomUUID().toString().take(10), "№ $it") },
        )
        save(c, hw)
        return hw
    }

    /** Найти в учебнике условия задач ДЗ, у которых их ещё нет; [progress] — сколько готово. */
    suspend fun locate(c: Context, hwId: String, progress: (Int, Int) -> Unit = { _, _ -> }): Result<Unit> {
        val hw = withContext(Dispatchers.IO) { get(c, hwId) } ?: return Result.success(Unit)
        val todo = hw.tasks.filter { it.parts.isEmpty() && !it.missing }
        todo.forEachIndexed { i, t ->
            progress(i, todo.size)
            val n = t.label.removePrefix("№").trim()
            val parts = findInBook(c, hw.bookId, n).getOrElse { return Result.failure(it) }
            withContext(Dispatchers.IO) { update(c, hwId, t.id) { it.copy(parts = parts.orEmpty(), missing = parts == null) } }
        }
        progress(todo.size, todo.size)
        return Result.success(Unit)
    }

    private const val SPLIT_SYSTEM = "Ты смотришь на страницу домашнего задания по высшей математике. Раздели её на " +
        "отдельные задачи. Для каждой: label — номер или название, как в файле («1», «2а», «Задача 3»); top и bottom — " +
        "где условие на странице, доли высоты (0 — верх, 1 — низ); text — условие текстом, формулы в LaTeX ($…$). " +
        "Заголовки, фамилии и сроки — не задачи. Ответ — только JSON: {\"tasks\":[{\"label\":\"1\",\"top\":0.1,\"bottom\":0.2,\"text\":\"…\"}]}."

    /** Новое ДЗ из файла (PDF): страницы делятся на задачи через ИИ. */
    suspend fun fromFile(c: Context, uri: Uri, subject: String, due: LocalDate, progress: (Int, Int) -> Unit = { _, _ -> }): Result<Homework> {
        val id = UUID.randomUUID().toString().take(12)
        val f = File(dir(c), "$id.pdf")
        if (!withContext(Dispatchers.IO) { copy(c, uri, f) }) return Result.failure(IllegalStateException("Не удалось прочитать файл"))
        val pages = withContext(Dispatchers.IO) { pageCount(f) }.coerceAtMost(12)
        if (pages == 0) { f.delete(); return Result.failure(IllegalStateException("Это не PDF или он защищён паролем")) }
        val tasks = mutableListOf<HwTask>()
        for (p in 0 until pages) {
            progress(p, pages)
            val bmp = withContext(Dispatchers.IO) { renderPage(f, p, 1300) } ?: continue
            val img = withContext(Dispatchers.Default) { jpeg(bmp).also { bmp.recycle() } }
            val raw = ask(c, SPLIT_SYSTEM, "Страница ${p + 1} из $pages.", listOf(img), 2500).getOrElse { f.delete(); return Result.failure(it) }
            obj(raw)?.get("tasks")?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val label = o.s("label").ifBlank { "${tasks.size + 1}" }
                tasks += HwTask(
                    UUID.randomUUID().toString().take(10),
                    if (label.first().isDigit()) "Задача $label" else label,
                    listOf(Crop(p, o.f("top") ?: 0f, o.f("bottom") ?: 1f)),
                    text = o.s("text"),
                )
            }
        }
        progress(pages, pages)
        if (tasks.isEmpty()) { f.delete(); return Result.failure(IllegalStateException("В файле не нашлось задач — проверь, тот ли это файл")) }
        val hw = Homework(id, subject, due.toString(), System.currentTimeMillis(), f.path, tasks = tasks)
        withContext(Dispatchers.IO) { save(c, hw) }
        return Result.success(hw)
    }

    // ---------------- тетрадь ----------------

    /** Тетрадь ДЗ предмета («ДЗ · Матанализ») — одна на предмет. */
    private fun notebookFor(c: Context, subject: String): Long {
        val map = File(dir(c), "notebooks.json")
        val ser = MapSerializer(String.serializer(), Long.serializer())
        val known: MutableMap<String, Long> = runCatching { json.decodeFromString(ser, map.readText()) }.getOrDefault(emptyMap()).toMutableMap()
        val store = c.app.notebooks.store
        known[subject]?.takeIf { store.meta(it) != null }?.let { return it }
        val (m, first) = store.create("ДЗ · $subject", Paper.GRID)
        store.savePage(m.id, first)
        known[subject] = m.id
        write(map, json.encodeToString(ser, known.toMap()))
        return m.id
    }

    /**
     * Страница для решения задачи: условие сверху, ниже — клетка. Создаётся один раз (в тетради ДЗ предмета).
     * Возвращает тетрадь и страницу.
     */
    suspend fun pageFor(c: Context, hwId: String, taskId: String): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val hw = get(c, hwId) ?: return@withContext Result.failure(IllegalStateException("ДЗ не найдено"))
        val t = hw.tasks.firstOrNull { it.id == taskId } ?: return@withContext Result.failure(IllegalStateException("Задача не найдена"))
        val store = c.app.notebooks.store
        if (t.pageId != 0L && store.meta(t.notebookId)?.pages?.contains(t.pageId) == true) return@withContext Result.success(t.notebookId to t.pageId)
        val nb = notebookFor(c, hw.subject)
        val meta = store.meta(nb) ?: return@withContext Result.failure(IllegalStateException("Тетрадь не открылась"))
        val w = 1600
        val h = (w * 1.414f).toInt()
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bg)
        canvas.drawColor(Color.WHITE)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt(); textSize = 44f; isFakeBoldText = true }
        canvas.drawText("${hw.subject} · ${t.label}", 70f, 90f, label)
        var y = 120f
        condition(File(hw.source), t.parts, w - 140)?.let { cond ->
            val maxH = h * 0.45f
            val k = minOf(1f, maxH / cond.height)
            val dst = android.graphics.RectF(70f, y, 70f + cond.width * k, y + cond.height * k)
            canvas.drawBitmap(cond, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            y = dst.bottom
            cond.recycle()
        } ?: run {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16161A.toInt(); textSize = 38f }
            (t.text.ifBlank { "Условие — в учебнике (${t.label})" }).chunked(70).take(8).forEach { line -> y += 50f; canvas.drawText(line, 70f, y, p) }
        }
        y += 30f
        val line = Paint().apply { color = 0xFFDCE1EA.toInt(); strokeWidth = 2f }
        canvas.drawLine(40f, y, w - 40f, y, Paint().apply { color = 0xFF2563EB.toInt(); strokeWidth = 3f })
        // Клетка под решение.
        val step = 50f
        var gy = y + step
        while (gy < h) { canvas.drawLine(0f, gy, w.toFloat(), gy, line); gy += step }
        var gx = 0f
        while (gx < w) { canvas.drawLine(gx, y + 3f, gx, h.toFloat(), line); gx += step }
        val pageId = store.newId()
        val name = NotebookStore.backgroundName(pageId)
        store.background(nb, name).outputStream().use { bg.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bg.recycle()
        val page = InkPage(pageId, Paper.BLANK, name, PAGE_W * h / w)
        store.savePage(nb, page)
        store.addPage(meta, page)
        c.app.notebooks.forget(nb)
        update(c, hwId, taskId) { it.copy(notebookId = nb, pageId = pageId) }
        Result.success(nb to pageId)
    }

    // ---------------- проверка ----------------

    private const val CHECK_SYSTEM = "Ты строгий, но честный преподаватель высшей математики и проверяешь домашнее " +
        "задание. Первая картинка — условие задачи, вторая — страница тетради студента (условие сверху, ниже рукописное " +
        "решение). Засчитывай, только если задача решена полностью и верно: все шаги обоснованы, ответ правильный и " +
        "записан. Мелкие описки, которые не меняют ход решения и ответ, — не повод не засчитать. Ответ — только JSON: " +
        "{\"verdict\":\"correct\"|\"error\"|\"incomplete\",\"comment\":\"…\"}. comment — одно-два предложения по-русски: " +
        "для error — в каком месте ошибка (не исправляй её и не давай ответ), для incomplete — чего не хватает, для " +
        "correct — коротко, что хорошо. Формулы — в LaTeX ($…$)."

    data class Verdict(val status: Status, val comment: String, val homework: Homework)

    /** Проверить решение задачи на странице [solution] (снимок страницы тетради). */
    suspend fun check(c: Context, hwId: String, taskId: String, solution: ByteArray): Result<Verdict> {
        val hw = withContext(Dispatchers.IO) { get(c, hwId) } ?: return Result.failure(IllegalStateException("ДЗ не найдено"))
        val t = hw.tasks.firstOrNull { it.id == taskId } ?: return Result.failure(IllegalStateException("Задача не найдена"))
        val cond = withContext(Dispatchers.IO) { condition(File(hw.source), t.parts) }?.let { b -> withContext(Dispatchers.Default) { jpeg(b).also { b.recycle() } } }
        val text = buildString {
            append("Предмет: ${hw.subject}. Задача ${t.label}.")
            if (t.text.isNotBlank()) append("\nУсловие текстом: ${t.text}")
            if (cond == null) append("\nКартинки условия нет — условие на странице тетради сверху.")
        }
        val raw = ask(c, CHECK_SYSTEM, text, listOfNotNull(cond, solution), 600).getOrElse { return Result.failure(it) }
        val o = obj(raw) ?: return Result.failure(IllegalStateException("Нейросеть ответила не по формату — проверь ещё раз"))
        val status = when (o.s("verdict").lowercase()) {
            "correct" -> Status.DONE
            "error" -> Status.WRONG
            else -> Status.INCOMPLETE
        }
        val comment = o.s("comment")
        val nw = withContext(Dispatchers.IO) {
            update(c, hwId, taskId) { it.copy(status = status, comment = comment, failed = if (status == Status.DONE) it.failed else it.failed + 1) }
        } ?: return Result.failure(IllegalStateException("ДЗ не найдено"))
        if (status == Status.DONE) c.app.study.record(Learning.KIND_TASK, "${hw.subject} · ДЗ ${t.label}", 0)
        return Result.success(Verdict(status, comment, nw))
    }

    /** «Спорная»: проверка дважды не приняла, ты уверен — засчитать (не больше одной на ДЗ). */
    fun dispute(c: Context, hwId: String, taskId: String): Boolean {
        val hw = get(c, hwId) ?: return false
        val t = hw.tasks.firstOrNull { it.id == taskId } ?: return false
        if (!HomeworkRules.canDispute(t.status, t.failed, hw.statuses)) return false
        update(c, hwId, taskId) { it.copy(status = Status.DISPUTED) }
        return true
    }

    // ---------------- карточки ----------------

    private const val CARDS_SYSTEM = "Ты делаешь карточки для повторения по решённой задаче из домашнего задания " +
        "(первая картинка — условие, вторая — решение студента). Сделай ровно три карточки, все — задачи, которые надо " +
        "решить письменно: 1) «вариант» — та же идея на других числах или чуть изменённое условие; 2) «почему этот шаг» — " +
        "ключевой переход решения: спроси, почему он верен; 3) «найди ошибку» — короткое решение похожей задачи с одной " +
        "типичной ошибкой (лучше той, что бывает у студентов в этой теме), спроси, где ошибка. back — короткий ответ или " +
        "разбор. Формулы — в LaTeX ($…$). Ответ — только JSON: {\"cards\":[{\"front\":\"…\",\"back\":\"…\"}]}."

    /** Три карточки-задачи по засчитанной задаче (один раз на задачу). */
    suspend fun makeCards(c: Context, hwId: String, taskId: String, solution: ByteArray?): Result<Int> {
        val hw = withContext(Dispatchers.IO) { get(c, hwId) } ?: return Result.success(0)
        val t = hw.tasks.firstOrNull { it.id == taskId } ?: return Result.success(0)
        if (t.cards || t.status != Status.DONE) return Result.success(0)
        val cond = withContext(Dispatchers.IO) { condition(File(hw.source), t.parts) }?.let { b -> withContext(Dispatchers.Default) { jpeg(b).also { b.recycle() } } }
        val raw = ask(c, CARDS_SYSTEM, "Предмет: ${hw.subject}. Задача ${t.label}. ${t.text}", listOfNotNull(cond, solution), 2000).getOrElse { return Result.failure(it) }
        val list = obj(raw)?.get("cards")?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        val now = System.currentTimeMillis()
        val cards = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val front = o.s("front").trim(); val back = o.s("back").trim()
            if (front.isEmpty() || back.isEmpty()) null
            else CardEntity(
                noteId = null, subject = hw.subject, topic = "${hw.subject} · ДЗ ${t.label}",
                front = Learning.PROBLEM_PREFIX + front, back = back, dueAt = now + 24 * 3600_000L, createdAt = now,
            )
        }
        withContext(Dispatchers.IO) {
            if (cards.isNotEmpty()) c.app.db.cards().insertAll(cards)
            update(c, hwId, taskId) { it.copy(cards = true) }
        }
        return Result.success(cards.size)
    }

    // ---------------- план и напоминания ----------------

    private const val PLAN_SYSTEM = "Ты наставник студента. По списку несделанных домашних заданий составь короткий план " +
        "на сегодня: что решить сегодня (и примерно сколько это займёт), что оставить на завтра, с чего начать. 2–3 " +
        "предложения, по-русски, без воды, на «ты». Ответ — только JSON: {\"plan\":\"…\"}."

    /** План на сегодня от ИИ (один раз в день, кешируется); без ИИ — простой текст. */
    suspend fun plan(c: Context, open: List<Homework>): String {
        if (open.isEmpty()) return ""
        val today = LocalDate.now()
        val summary = open.joinToString("\n") { hw ->
            val left = hw.tasks.filter { it.status != Status.DONE && it.status != Status.DISPUTED }
            "${hw.subject}, срок ${hw.due}: осталось ${left.size} из ${hw.tasks.size} (${left.joinToString { it.label }})"
        }
        val cache = File(dir(c), "plan.txt")
        val key = "$today|${summary.hashCode()}"
        runCatching { cache.readText() }.getOrNull()?.let { if (it.substringBefore('\n') == key) return it.substringAfter('\n') }
        val fallback = open.joinToString(" ") { hw ->
            val left = hw.tasks.count { it.status != Status.DONE && it.status != Status.DISPUTED }
            "${hw.subject}: осталось $left из ${hw.tasks.size} к ${dayName(hw.dueDate)}."
        }
        val text = ask(c, PLAN_SYSTEM, "Сегодня $today.\n$summary", emptyList(), 400).getOrNull()?.let { obj(it)?.s("plan") }?.ifBlank { null } ?: return fallback
        runCatching { cache.writeText("$key\n$text") }
        return text
    }

    fun dayName(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "сегодня"
            today.plusDays(1) -> "завтра"
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.forLanguageTag("ru")))
        }
    }
}

package app.nastavnik.ink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Описание тетради: название, порядок страниц, разговор с наставником по ней. */
@Serializable
data class NotebookMeta(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<Long>,
    val paper: String = Paper.GRID.name,
    /** Разговор с наставником по этой тетради (0 — ещё не спрашивали). */
    val conversationId: Long = 0,
)

/**
 * Тетради в папке приложения: в каждой — notebook.json, страницы page_<id>.ink, подложки (фото, PDF)
 * и превью первой страницы thumb.jpg. Всё хранится только на планшете.
 */
class NotebookStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastId = 0L

    /** Уникальный id по времени: тетради и страницы, созданные в одну миллисекунду, не совпадут. */
    @Synchronized
    fun newId(now: Long = System.currentTimeMillis()): Long {
        val id = maxOf(now * 1000, lastId + 1)
        lastId = id
        return id
    }

    fun dir(id: Long): File = File(root, id.toString())

    fun list(): List<NotebookMeta> = root.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { d -> runCatching { readMeta(d) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun meta(id: Long): NotebookMeta? = runCatching { readMeta(dir(id)) }.getOrNull()

    private fun readMeta(d: File): NotebookMeta = json.decodeFromString(NotebookMeta.serializer(), File(d, META).readText())

    fun saveMeta(m: NotebookMeta) {
        val d = dir(m.id).apply { mkdirs() }
        val tmp = File(d, "$META.tmp")
        tmp.writeText(json.encodeToString(NotebookMeta.serializer(), m))
        tmp.renameTo(File(d, META))
    }

    /** Новая тетрадь с одной чистой страницей. */
    fun create(title: String, paper: Paper, now: Long = System.currentTimeMillis()): Pair<NotebookMeta, InkPage> {
        val page = InkPage(newId(now), paper)
        val m = NotebookMeta(newId(now), title, now, now, listOf(page.id), paper.name)
        saveMeta(m)
        savePage(m.id, page)
        return m to page
    }

    /** Новая тетрадь из готовых страниц (фото, PDF). */
    fun createWith(title: String, pages: List<InkPage>, now: Long = System.currentTimeMillis()): NotebookMeta {
        val m = NotebookMeta(newId(now), title, now, now, pages.map { it.id }, Paper.BLANK.name)
        saveMeta(m)
        pages.forEach { savePage(m.id, it) }
        return m
    }

    fun loadPages(m: NotebookMeta): List<InkPage> = m.pages.mapNotNull { pid ->
        runCatching { File(dir(m.id), pageFile(pid)).inputStream().use { InkFormat.read(it) } }.getOrNull()
    }

    fun savePage(notebookId: Long, page: InkPage) {
        val d = dir(notebookId).apply { mkdirs() }
        val tmp = File(d, pageFile(page.id) + ".tmp")
        tmp.outputStream().use { InkFormat.write(page, it) }
        tmp.renameTo(File(d, pageFile(page.id)))
    }

    /** Добавить страницу после [afterIndex] (или в конец). */
    fun addPage(m: NotebookMeta, page: InkPage, afterIndex: Int = m.pages.lastIndex, now: Long = System.currentTimeMillis()): NotebookMeta {
        savePage(m.id, page)
        val pages = m.pages.toMutableList().apply { add((afterIndex + 1).coerceIn(0, size), page.id) }
        return m.copy(pages = pages, updatedAt = now).also(::saveMeta)
    }

    fun removePage(m: NotebookMeta, pageId: Long, now: Long = System.currentTimeMillis()): NotebookMeta {
        if (m.pages.size <= 1) return m
        File(dir(m.id), pageFile(pageId)).delete()
        dir(m.id).listFiles { f -> f.name.startsWith("bg_$pageId") }?.forEach { it.delete() }
        return m.copy(pages = m.pages - pageId, updatedAt = now).also(::saveMeta)
    }

    fun rename(m: NotebookMeta, title: String): NotebookMeta = m.copy(title = title.trim().ifEmpty { m.title }).also(::saveMeta)

    fun delete(id: Long) {
        dir(id).deleteRecursively()
    }

    /** Файл подложки страницы. */
    fun background(notebookId: Long, name: String): File = File(dir(notebookId), name)

    fun thumb(notebookId: Long): File = File(dir(notebookId), THUMB)

    companion object {
        private const val META = "notebook.json"
        private const val THUMB = "thumb.jpg"
        fun pageFile(id: Long) = "page_$id.ink"
        fun backgroundName(pageId: Long) = "bg_$pageId.jpg"
    }
}

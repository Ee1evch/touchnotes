package app.nastavnik.geom

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Одна запомненная страница конспекта: снимок записей ([image] — для чистовика, [small] — для вопросов
 * наставнику) и уменьшенный кадр [thumb], по которому её узнают снова.
 * [chain] и [pos] — в каком непрерывном «пролистывании» снята страница и где в нём (пиксели экрана сверху):
 * по ним страницы идут в порядке конспекта, а не в порядке съёмки.
 */
class NotebookPage(
    val id: Long,
    val image: ByteArray,
    val small: ByteArray,
    val thumb: Thumb,
    val chain: Long,
    val pos: Int,
    val at: Long,
)

/** Что такое новый кадр по отношению к запомненным страницам. */
sealed interface PageMatch {
    /** Ничего похожего — новая страница. */
    data object New : PageMatch

    /** Та же страница (может, что-то дописали): [dy] — насколько сдвинута. */
    data class Same(val index: Int, val dy: Int) : PageMatch

    /** Большей частью уже видели: страницу чуть прокрутили. */
    data class Overlap(val index: Int, val dy: Int) : PageMatch
}

/**
 * Весь конспект, который видел наставник: страницы, на которых спрашивали, и всё, что студент пролистал
 * в той же тетради. Хранится в папке [dir], чтобы пережить перезапуск; старше [MAX_AGE_MS] — забывается.
 */
class NotebookPages(private val dir: File?) {
    private val list = mutableListOf<NotebookPage>()
    private var nextId = 1L

    val pages: List<NotebookPage> get() = list.sortedWith(compareBy({ it.chain }, { it.pos }))
    val size: Int get() = list.size

    /**
     * Сравнивает кадр со всеми страницами. Совпал почти целиком — [PageMatch.Same]; сдвинут меньше чем на
     * [NEW_CONTENT] высоты — [PageMatch.Overlap]; иначе — новая страница.
     */
    fun match(cur: Thumb, mask: BooleanArray?, rowMask: BooleanArray?, top: Int, bottom: Int): PageMatch {
        val h = (bottom - top).coerceAtLeast(1)
        var overlap: PageMatch.Overlap? = null
        // Сначала самые свежие: скорее всего, листаем рядом с ними.
        for ((i, p) in list.withIndex().reversed()) {
            when (val m = ScreenMotion.estimate(p.thumb, cur, mask, rowMask, top, bottom)) {
                is Motion.Same -> return PageMatch.Same(i, 0)
                is Motion.Moved -> {
                    if (abs(m.dx) > cur.screenW / 5) continue
                    if (abs(m.dy) < h * SAME_SHIFT) return PageMatch.Same(i, m.dy)
                    if (abs(m.dy) < h * NEW_CONTENT && overlap == null) overlap = PageMatch.Overlap(i, m.dy)
                }
                Motion.Replaced -> Unit
            }
        }
        return overlap ?: PageMatch.New
    }

    /** Где в «пролистывании» окажется кадр, совпавший со страницей [index] со сдвигом [dy]. */
    fun positionOf(index: Int, dy: Int): Pair<Long, Int> = list[index].let { it.chain to it.pos - dy }

    /** Добавить страницу; если конспект полон — забываем самую старую по времени съёмки. */
    fun add(image: ByteArray, small: ByteArray, thumb: Thumb, chain: Long, pos: Int, at: Long): NotebookPage {
        while (list.size >= MAX_PAGES) remove(list.minBy { it.at })
        val page = NotebookPage(nextId++, image, small, thumb, chain, pos, at)
        list += page
        save(page)
        return page
    }

    /** Та же страница, но свежее (дописали): заменяем снимок, место в конспекте сохраняем. */
    fun replace(index: Int, image: ByteArray, small: ByteArray, thumb: Thumb, at: Long): NotebookPage {
        val old = list[index]
        remove(old)
        val page = NotebookPage(nextId++, image, small, thumb, old.chain, old.pos, at)
        list.add(index.coerceAtMost(list.size), page)
        save(page)
        return page
    }

    /** Снимки для вопроса наставнику: страницы по порядку конспекта, кроме той, что сейчас на экране. */
    fun context(exclude: Long?, limit: Int): List<ByteArray> {
        val ordered = pages.filter { it.id != exclude }
        // Не влезают все — берём ближайшие по порядку к текущей странице.
        val cur = list.firstOrNull { it.id == exclude }
        val chosen = if (ordered.size <= limit || cur == null) {
            ordered.takeLast(limit)
        } else {
            ordered.sortedBy { distance(it, cur) }.take(limit).sortedWith(compareBy({ it.chain }, { it.pos }))
        }
        return chosen.map { it.small }
    }

    private fun distance(a: NotebookPage, b: NotebookPage): Long =
        if (a.chain == b.chain) abs(a.pos - b.pos).toLong() else 1_000_000L + abs(a.chain - b.chain)

    fun clear() {
        list.toList().forEach(::remove)
    }

    private fun remove(p: NotebookPage) {
        list.remove(p)
        dir?.let { d -> listOf("${p.id}.jpg", "${p.id}.s.jpg", "${p.id}.meta").forEach { File(d, it).delete() } }
    }

    // ---------------- хранение ----------------

    private fun save(p: NotebookPage) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            File(d, "${p.id}.jpg").writeBytes(p.image)
            File(d, "${p.id}.s.jpg").writeBytes(p.small)
            DataOutputStream(File(d, "${p.id}.meta").outputStream().buffered()).use { o ->
                o.writeInt(META_VERSION)
                o.writeLong(p.chain); o.writeInt(p.pos); o.writeLong(p.at)
                val t = p.thumb
                o.writeInt(t.cols); o.writeInt(t.rows); o.writeInt(t.screenW); o.writeInt(t.screenH)
                o.writeInt(t.luma.size); t.luma.forEach { o.writeByte(it) }
                o.writeInt(t.rowProfile.size); t.rowProfile.forEach { o.writeByte(it) }
            }
        }
    }

    /** Загружает сохранённые страницы, забывая слишком старые. */
    fun load(now: Long) {
        val d = dir ?: return
        list.clear()
        val metas = d.listFiles { f -> f.name.endsWith(".meta") }.orEmpty()
        for (m in metas) {
            val id = m.name.removeSuffix(".meta").toLongOrNull() ?: continue
            val page = runCatching {
                DataInputStream(m.inputStream().buffered()).use { i ->
                    require(i.readInt() == META_VERSION)
                    val chain = i.readLong(); val pos = i.readInt(); val at = i.readLong()
                    val cols = i.readInt(); val rows = i.readInt(); val sw = i.readInt(); val sh = i.readInt()
                    val luma = IntArray(i.readInt()) { i.readUnsignedByte() }
                    val profile = IntArray(i.readInt()) { i.readUnsignedByte() }
                    NotebookPage(
                        id, File(d, "$id.jpg").readBytes(), File(d, "$id.s.jpg").readBytes(),
                        Thumb(cols, rows, sw, sh, luma, profile), chain, pos, at,
                    )
                }
            }.getOrNull()
            if (page == null || now - page.at > MAX_AGE_MS) {
                listOf("$id.jpg", "$id.s.jpg", "$id.meta").forEach { File(d, it).delete() }
                continue
            }
            list += page
            nextId = maxOf(nextId, id + 1)
        }
    }

    companion object {
        /** Сколько страниц помнить. */
        const val MAX_PAGES = 16

        /** Сдвиг меньше этой доли высоты — та же страница. */
        const val SAME_SHIFT = 0.12f

        /** Сдвиг больше этой доли высоты — на экране достаточно нового, запоминаем как страницу. */
        const val NEW_CONTENT = 0.4f

        /** Конспект, к которому не возвращались полдня, — уже другой. */
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

        private const val META_VERSION = 1
    }
}

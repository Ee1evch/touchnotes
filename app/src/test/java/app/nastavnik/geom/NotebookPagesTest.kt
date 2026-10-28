package app.nastavnik.geom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class NotebookPagesTest {
    private val w = 800
    private val h = 600

    /** Длинная «тетрадь»: строки «рукописи» разной длины на белом фоне. */
    private val doc: Array<IntArray> = run {
        val rnd = Random(7)
        val height = 3000
        val white = 0xFFFFFFFF.toInt()
        val ink = 0xFF202030.toInt()
        Array(height) { IntArray(w) { white } }.also { rows ->
            var y = 30
            while (y < height - 30) {
                var x = 40 + rnd.nextInt(60)
                val end = 200 + rnd.nextInt(w - 260)
                while (x < end) {
                    val word = 20 + rnd.nextInt(70)
                    for (yy in y until y + 14) for (xx in x until minOf(x + word, end)) rows[yy][xx] = ink
                    x += word + 12 + rnd.nextInt(10)
                }
                y += 34 + rnd.nextInt(12)
            }
        }
    }

    private fun frame(offset: Int): Thumb {
        val b = ThumbBuilder(w, h)
        for (y in 0 until h) b.addRow(y, doc[offset + y])
        return b.build()
    }

    private fun bytes(tag: Int) = byteArrayOf(tag.toByte())

    @Test
    fun recognisesSamePageOverlapAndNewContent() {
        val nb = NotebookPages(null)
        val f0 = frame(0)
        assertEquals(PageMatch.New, nb.match(f0, null, null, 0, h))
        nb.add(bytes(0), bytes(0), f0, chain = 1, pos = 0, at = 1)

        // Чуть прокрутили — та же страница.
        assertTrue(nb.match(frame(40), null, null, 0, h) is PageMatch.Same)
        // Прокрутили на четверть — большей частью уже видели.
        val ov = nb.match(frame(150), null, null, 0, h)
        assertTrue("ожидали перекрытие, а вышло $ov", ov is PageMatch.Overlap)
        // Больше половины экрана нового — новая страница.
        assertEquals(PageMatch.New, nb.match(frame(420), null, null, 0, h))
        nb.add(bytes(2), bytes(2), frame(420), chain = 1, pos = 420, at = 2)
        // Совсем другое место тетради.
        assertEquals(PageMatch.New, nb.match(frame(2000), null, null, 0, h))
    }

    @Test
    fun contextIsInNotebookOrderWithoutCurrentPage() {
        val nb = NotebookPages(null)
        // Сняты не по порядку: сначала середина, потом начало и конец.
        val mid = nb.add(bytes(2), bytes(2), frame(600), chain = 1, pos = 600, at = 1)
        nb.add(bytes(1), bytes(1), frame(0), chain = 1, pos = 0, at = 2)
        nb.add(bytes(3), bytes(3), frame(1200), chain = 1, pos = 1200, at = 3)
        nb.add(bytes(9), bytes(9), frame(2200), chain = 2, pos = 0, at = 4)
        val ctx = nb.context(exclude = mid.id, limit = 10).map { it[0].toInt() }
        assertEquals(listOf(1, 3, 9), ctx)
        // Не влезают все — берём ближайшие к текущей странице.
        assertEquals(listOf(1, 3), nb.context(exclude = mid.id, limit = 2).map { it[0].toInt() })
    }

    @Test
    fun survivesRestartAndForgetsOldPages() {
        val dir = Files.createTempDirectory("nb").toFile()
        val nb = NotebookPages(dir)
        nb.add(byteArrayOf(1, 2, 3), byteArrayOf(4), frame(0), chain = 5, pos = 10, at = 1_000)
        nb.add(byteArrayOf(7), byteArrayOf(8), frame(900), chain = 5, pos = 900, at = 2_000)

        val again = NotebookPages(dir)
        again.load(now = 3_000)
        assertEquals(2, again.size)
        val p = again.pages.first()
        assertEquals(listOf<Byte>(1, 2, 3), p.image.toList())
        assertEquals(10, p.pos)
        // Восстановленный кадр узнаёт ту же страницу.
        assertTrue(again.match(frame(0), null, null, 0, h) is PageMatch.Same)

        val later = NotebookPages(dir)
        later.load(now = 2_000 + NotebookPages.MAX_AGE_MS + 1)
        assertEquals(0, later.size)
        dir.deleteRecursively()
    }
}

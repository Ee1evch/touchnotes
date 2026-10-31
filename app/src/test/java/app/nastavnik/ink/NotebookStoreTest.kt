package app.nastavnik.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NotebookStoreTest {
    @Test
    fun createWriteReloadReorderAndDelete() {
        val root = Files.createTempDirectory("nbs").toFile()
        val store = NotebookStore(root)
        val (m, first) = store.create("Матан", Paper.GRID, now = 1_000)
        first.strokes += Stroke(Tool.PEN, 1, 3f, floatArrayOf(1f, 2f, 0.5f, 3f, 4f, 0.6f))
        store.savePage(m.id, first)

        val second = InkPage(store.newId(2_000), Paper.LINED)
        val m2 = store.addPage(m, second, afterIndex = 0, now = 2_000)
        val third = InkPage(store.newId(3_000), Paper.DOTS)
        val m3 = store.addPage(m2, third, afterIndex = 0, now = 3_000) // между первой и второй

        val reloaded = store.meta(m.id)!!
        assertEquals(listOf(first.id, third.id, second.id), reloaded.pages)
        val pages = store.loadPages(reloaded)
        assertEquals(1, pages[0].strokes.size)
        assertEquals(Paper.DOTS, pages[1].paper)

        // Последнюю страницу удалить нельзя, остальные — можно.
        val m4 = store.removePage(m3, third.id, now = 4_000)
        assertEquals(listOf(first.id, second.id), m4.pages)

        val other = store.createWith("Фото", listOf(InkPage(store.newId(), Paper.BLANK, "bg.jpg", 800f)), now = 5_000)
        assertEquals(listOf(other.id, m.id), store.list().map { it.id }) // свежие сверху
        assertEquals("Линал", store.rename(other, "  Линал ").title)

        store.delete(m.id)
        assertNull(store.meta(m.id))
        assertTrue(store.list().size == 1)
        root.deleteRecursively()
    }

    @Test
    fun idsAreUniqueEvenInTheSameMillisecond() {
        val store = NotebookStore(Files.createTempDirectory("ids").toFile())
        val ids = (1..100).map { store.newId(42) }
        assertEquals(100, ids.toSet().size)
    }
}

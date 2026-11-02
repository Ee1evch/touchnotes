package app.nastavnik.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncEngineTest {
    /** Облако в памяти. */
    class MemoryStore : SyncStore {
        val files = HashMap<String, ByteArray>()
        var writes = 0
        override suspend fun read(path: String) = files[path]
        override suspend fun write(path: String, bytes: ByteArray) { files[path] = bytes; writes++ }
        override suspend fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") && !it.removePrefix("$dir/").contains('/') }.map { it.removePrefix("$dir/") }
        override suspend fun ensureDir(dir: String) = Unit
    }

    /** Устройство: записи «заметок» (текст) и «тетрадей» (с файлом). */
    class Device(val name: String, val store: SyncStore, var clock: Long = 1000) {
        val dir: File = Files.createTempDirectory("sync-$name").toFile()
        val notes = LinkedHashMap<String, String>()
        val books = LinkedHashMap<String, File>()
        var state = SyncState()
        val source = object : SyncSource {
            override suspend fun export() = notes.map { (id, t) -> LocalItem("note", id, buildJsonObject { put("text", t) }) } +
                books.map { (id, f) -> LocalItem("book", id, buildJsonObject { put("title", id) }, mapOf("page.ink" to f)) }
            override suspend fun apply(record: SyncRecord, files: Map<String, File>) {
                when (record.kind) {
                    "note" -> notes[record.id] = record.data!!.jsonObject["text"]!!.jsonPrimitive.content
                    "book" -> { val f = File(dir, "${record.id}.ink"); files.getValue("page.ink").copyTo(f, overwrite = true); books[record.id] = f }
                }
            }
            override suspend fun delete(kind: String, id: String) { if (kind == "note") notes.remove(id) else books.remove(id) }
        }
        fun engine() = SyncEngine(store, source, name, name, "phone", File(dir, "tmp").apply { mkdirs() }) { clock }
        fun sync(): SyncReport = runBlocking { val (s, r) = engine().run(state); state = s; r }
    }

    @Test fun twoDevicesConverge() {
        val cloud = MemoryStore()
        val tablet = Device("tablet", cloud)
        val phone = Device("phone", cloud)
        tablet.notes["a"] = "интеграл"
        tablet.books["b1"] = File(tablet.dir, "b1.src").apply { writeText("штрихи") }
        tablet.sync()
        val r = phone.sync()
        assertEquals(2, r.received)
        assertEquals("интеграл", phone.notes["a"])
        assertEquals("штрихи", phone.books["b1"]!!.readText())

        // Телефон правит заметку — планшет получает правку.
        phone.clock = 2000
        phone.notes["a"] = "интеграл по частям"
        phone.notes["c"] = "карточка"
        phone.sync()
        tablet.clock = 3000
        tablet.sync()
        assertEquals("интеграл по частям", tablet.notes["a"])
        assertEquals("карточка", tablet.notes["c"])

        // Повторная синхронизация без изменений ничего не гоняет туда-обратно.
        val again = tablet.sync()
        assertEquals(0, again.received)
        val againPhone = phone.sync()
        assertEquals(0, againPhone.received)
    }

    @Test fun deletionsPropagateAndNewerEditWins() {
        val cloud = MemoryStore()
        val a = Device("a", cloud)
        val b = Device("b", cloud)
        a.notes["x"] = "1"; a.notes["y"] = "2"
        a.sync(); b.sync()
        // a удаляет x, b правит y позже, чем a.
        a.clock = 2000; a.notes.remove("x"); a.notes["y"] = "старое"; a.sync()
        b.clock = 3000; b.notes["y"] = "новое"; b.sync()
        a.clock = 4000; a.sync()
        assertFalse("x" in b.notes)
        assertEquals("новое", a.notes["y"])
        assertEquals("новое", b.notes["y"])
    }

    @Test fun blobsAreUploadedOnceAndBeforeSnapshot() {
        val cloud = MemoryStore()
        val a = Device("a", cloud)
        a.books["k"] = File(a.dir, "k.src").apply { writeText("x".repeat(1000)) }
        a.sync()
        val blobs = cloud.files.keys.filter { it.startsWith("Nastavnik/blobs/") }
        assertEquals(1, blobs.size)
        val before = cloud.writes
        a.clock = 2000
        a.sync()
        // Без изменений — только свой снимок.
        assertEquals(before + 1, cloud.writes)
        assertTrue(cloud.files.containsKey("Nastavnik/devices/a.json.gz"))
    }

    @Test fun cloudReaderTakesNewestAndWritesNothing() {
        val cloud = MemoryStore()
        val tablet = Device("tablet", cloud)
        val phone = Device("phone", cloud)
        tablet.notes["a"] = "старое"
        tablet.sync()
        phone.sync()
        phone.clock = 5000
        phone.notes["a"] = "новое"
        phone.notes["gone"] = "x"
        phone.sync()
        phone.clock = 6000
        phone.notes.remove("gone")
        phone.sync()
        val writes = cloud.writes
        val got = runBlocking { CloudReader(cloud).latest(setOf("note/a", "note/gone", "note/none")) }
        assertEquals("новое", got.getValue("note/a").data!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse("удалённое не возвращается", "note/gone" in got)
        assertEquals("читатель ничего не пишет", writes, cloud.writes)
    }

    @Test(expected = java.io.IOException::class) fun cloudReaderEmptyCloudExplains() {
        runBlocking { CloudReader(MemoryStore()).latest(setOf("note/a")) }
    }

    @Test fun recordHashIsStable() {
        val d = buildJsonObject { put("t", JsonPrimitive(1)) }
        assertEquals(SyncEngine.recordHash(d, mapOf("a" to "1", "b" to "2")), SyncEngine.recordHash(d, mapOf("b" to "2", "a" to "1")))
    }
}

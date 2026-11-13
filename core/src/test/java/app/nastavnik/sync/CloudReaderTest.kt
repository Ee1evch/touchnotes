package app.nastavnik.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CloudReaderTest {

    class MemoryStore : SyncStore {
        val files = HashMap<String, ByteArray>()
        override suspend fun read(path: String) = files[path]
        override suspend fun write(path: String, bytes: ByteArray) { files[path] = bytes }
        override suspend fun list(dir: String) = files.keys
            .filter { it.startsWith("$dir/") && !it.removePrefix("$dir/").contains('/') }
            .map { it.removePrefix("$dir/") }
        override suspend fun ensureDir(dir: String) = Unit
    }

    class StudentDevice(val store: SyncStore, var passphrase: String = "") {
        val dir: File = Files.createTempDirectory("student-sync").toFile()
        val records = LinkedHashMap<String, String>()
        val source = object : SyncSource {
            override suspend fun export() = records.map { (id, v) ->
                LocalItem("grades", id, buildJsonObject { put("mark", v) })
            }
            override suspend fun apply(record: SyncRecord, files: Map<String, File>) {}
            override suspend fun delete(kind: String, id: String) {}
        }
        fun sync() = runBlocking {
            val engine = SyncEngine(store, source, "student_tablet", "Планшет", "tablet", dir, passphrase = { passphrase })
            engine.run(SyncState())
        }
    }

    @Test
    fun parentWithPassphraseReadsEncryptedSnapshot() = runBlocking {
        val store = MemoryStore()
        val student = StudentDevice(store, passphrase = "студент_пароль_123")
        student.records["math"] = "5"
        student.records["physics"] = "4"
        student.sync()

        // Проверяем, что в облаке снимок действительно зашифрован (конверт E2E)
        val cloudFiles = store.list("${SyncEngine.ROOT}/devices")
        assertEquals(1, cloudFiles.size)
        val bytes = store.read("${SyncEngine.ROOT}/devices/${cloudFiles.first()}")!!
        assertTrue(SyncCrypto.isEncrypted(bytes))

        // Родитель с верной парольной фразой читает зашифрованный снимок
        val parentReader = CloudReader(store, passphrase = { "студент_пароль_123" })
        val latest = parentReader.latest(setOf("grades/math", "grades/physics"))

        assertEquals(2, latest.size)
        val markMath = latest["grades/math"]?.data?.jsonObject?.get("mark")?.jsonPrimitive?.content
        assertEquals("5", markMath)
        val markPhys = latest["grades/physics"]?.data?.jsonObject?.get("mark")?.jsonPrimitive?.content
        assertEquals("4", markPhys)
    }

    @Test
    fun parentWithoutPassphraseThrowsClearErrorNotSilence() = runBlocking {
        val store = MemoryStore()
        val student = StudentDevice(store, passphrase = "студент_пароль_123")
        student.records["math"] = "5"
        student.sync()

        // Родитель без фразы не должен получать пустые данные (тишину) — должна быть понятная ошибка
        val parentReader = CloudReader(store, passphrase = { "" })
        try {
            parentReader.latest(setOf("grades/math"))
            fail("Ожидалось исключение BadPassphraseException при отсутствии фразы")
        } catch (e: BadPassphraseException) {
            val msg = e.message.orEmpty()
            assertTrue("Сообщение должно указывать на необходимость ввода парольной фразы: $msg",
                msg.contains("пароль") || msg.contains("фраза") || msg.contains("E2E"))
        }
    }

    @Test
    fun parentWithWrongPassphraseThrowsClearError() = runBlocking {
        val store = MemoryStore()
        val student = StudentDevice(store, passphrase = "студент_пароль_123")
        student.records["math"] = "5"
        student.sync()

        // Родитель с неверной фразой получает ошибку расшифровки, а не тишину
        val parentReader = CloudReader(store, passphrase = { "неверный_пароль" })
        try {
            parentReader.latest(setOf("grades/math"))
            fail("Ожидалось исключение BadPassphraseException при неверной фразе")
        } catch (e: BadPassphraseException) {
            val msg = e.message.orEmpty()
            assertTrue("Сообщение должно указывать на ошибку расшифровки/парольной фразы: $msg",
                msg.contains("расшифровать") || msg.contains("пароль") || msg.contains("неверная"))
        }
    }

    @Test
    fun parentReadsUnencryptedSnapshotWithoutPassphrase() = runBlocking {
        val store = MemoryStore()
        val student = StudentDevice(store, passphrase = "")
        student.records["history"] = "5"
        student.sync()

        // Незашифрованный снимок читается родителем без парольной фразы
        val parentReader = CloudReader(store, passphrase = { "" })
        val latest = parentReader.latest(setOf("grades/history"))
        assertEquals(1, latest.size)
        assertNotNull(latest["grades/history"])
    }
}

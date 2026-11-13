package app.nastavnik.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Синхронизация между устройствами без своего сервера — через любую папку в облаке (WebDAV: Яндекс Диск, Nextcloud…).
 *
 * Каждое устройство пишет только свой файл `devices/<id>.json.gz` — полное состояние, каким его видит.
 * Файлы (страницы тетрадей, подложки, PDF) лежат рядом в `blobs/<sha256>`: имя — хеш содержимого,
 * поэтому файл записывается один раз и никогда не перезаписывается. Итого никто ни с кем не конфликтует
 * за один файл, а слить состояния просто: по каждой записи берём самую свежую версию (удаление — тоже версия).
 *
 * Что изменилось на этом устройстве, движок узнаёт сам — сравнивая с тем, что было после прошлой
 * синхронизации, — так что остальному приложению не нужно помечать каждое изменение.
 */
@Serializable
data class SyncRecord(
    val kind: String,
    val id: String,
    /** Когда версия появилась (мс) — по нему выбираем свежую. */
    val at: Long,
    val deleted: Boolean = false,
    val data: JsonElement? = null,
    /** Имя файла внутри записи → sha256 содержимого. */
    val blobs: Map<String, String> = emptyMap(),
    /** Устройство, на котором появилась версия. */
    val by: String = "",
    /** Хеш содержимого (данные + файлы) — чтобы понять, отличается ли версия. */
    val hash: String = "",
) {
    val key: String get() = "$kind/$id"
}

@Serializable
data class DeviceSnapshot(
    val device: String,
    val name: String,
    /** «phone» или «tablet». */
    val type: String,
    val at: Long,
    val records: List<SyncRecord>,
)

/** Запись этого устройства: что есть сейчас. Файлы — по имени. */
class LocalItem(val kind: String, val id: String, val data: JsonElement, val files: Map<String, File> = emptyMap())

/** Хранилище в облаке: пути относительные, с «/». */
interface SyncStore {
    suspend fun read(path: String): ByteArray?
    suspend fun write(path: String, bytes: ByteArray)
    suspend fun upload(path: String, file: File) = write(path, file.readBytes())
    suspend fun download(path: String, to: File): Boolean {
        val bytes = read(path) ?: return false
        to.parentFile?.mkdirs()
        val tmp = File(to.path + ".part")
        tmp.writeBytes(bytes)
        return tmp.renameTo(to) || run { to.delete(); tmp.renameTo(to) }
    }
    /** Имена файлов в папке (без подпапок); пустой список, если папки нет. */
    suspend fun list(dir: String): List<String>
    suspend fun ensureDir(dir: String)
}

/** Данные этого устройства для синхронизации. */
interface SyncSource {
    suspend fun export(): List<LocalItem>
    /** Принять чужую версию записи; файлы уже скачаны и лежат в [files] (имя → файл). */
    suspend fun apply(record: SyncRecord, files: Map<String, File>)
    suspend fun delete(kind: String, id: String)
}

/** Что запомнили после прошлой синхронизации. */
@Serializable
data class SyncState(
    /** Ключ записи → версия, как её видели в конце прошлой синхронизации. */
    val known: Map<String, Known> = emptyMap(),
    /** Кеш хешей файлов: путь → «размер:время изменения:sha». */
    val fileHashes: Map<String, String> = emptyMap(),
    val lastSyncAt: Long = 0,
) {
    @Serializable
    data class Known(val at: Long, val hash: String, val deleted: Boolean = false, val by: String = "")
}

data class SyncReport(val received: Int, val sent: Int, val devices: List<DeviceSnapshot>, val removed: Int)

class SyncEngine(
    private val store: SyncStore,
    private val source: SyncSource,
    private val device: String,
    private val deviceName: String,
    private val deviceType: String,
    /** Папка для скачанных файлов, пока запись не применена. */
    private val tempDir: File,
    private val passphrase: () -> String = { "" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun run(state: SyncState): Pair<SyncState, SyncReport> {
        val t = now()
        val hashes = state.fileHashes.toMutableMap()
        // 1. Текущее состояние этого устройства.
        val items = source.export()
        val localFiles = HashMap<String, File>()
        val local = HashMap<String, SyncRecord>()
        for (item in items) {
            val blobs = item.files.mapValues { (_, f) -> fileHash(f, hashes).also { localFiles[it] = f } }
            val hash = recordHash(item.data, blobs)
            val key = "${item.kind}/${item.id}"
            val prev = state.known[key]
            val changed = prev == null || prev.deleted || prev.hash != hash
            local[key] = SyncRecord(
                item.kind, item.id,
                at = if (changed) t else prev!!.at,
                data = item.data, blobs = blobs,
                by = if (changed) device else prev!!.by,
                hash = hash,
            )
        }
        // Пропало с прошлого раза — значит, удалили здесь.
        for ((key, k) in state.known) {
            if (key in local) continue
            val (kind, id) = key.split('/', limit = 2)
            local[key] = if (k.deleted) {
                SyncRecord(kind, id, k.at, deleted = true, by = k.by, hash = k.hash)
            } else {
                SyncRecord(kind, id, t, deleted = true, by = device, hash = "deleted")
            }
        }

        // 2. Что видят другие устройства.
        store.ensureDir(ROOT)
        store.ensureDir("$ROOT/devices")
        store.ensureDir("$ROOT/blobs")
        val snapshots = store.list("$ROOT/devices")
            .filter { it.endsWith(".json.gz") && it.removeSuffix(".json.gz") != device }
            .mapNotNull { name -> runCatching { decode(store.read("$ROOT/devices/$name")!!, passphrase()) }.getOrNull() }

        // 3. Слияние: по каждой записи — самая свежая версия.
        val merged = HashMap<String, SyncRecord>(local)
        for (snap in snapshots) for (r in snap.records) {
            val mine = merged[r.key]
            if (mine == null || newer(r, mine)) merged[r.key] = r
        }

        // 4. Применяем чужие версии.
        var received = 0
        var removed = 0
        for ((key, r) in merged) {
            val mine = local[key]
            if (mine != null && mine.hash == r.hash && mine.deleted == r.deleted) continue
            if (r.deleted) {
                if (mine != null && !mine.deleted) { source.delete(r.kind, r.id); removed++ }
                continue
            }
            val files = HashMap<String, File>()
            var ok = true
            for ((name, sha) in r.blobs) {
                val have = localFiles[sha]
                if (have != null && have.exists()) { files[name] = have; continue }
                val f = File(tempDir, sha)
                if (!f.exists()) {
                    val downloaded = store.read("$ROOT/blobs/$sha")
                    if (downloaded == null) { ok = false; break }
                    val pass = passphrase()
                    val plain = if (SyncCrypto.isEncrypted(downloaded)) {
                        runCatching { SyncCrypto.decrypt(downloaded, pass) }.getOrNull() ?: run { ok = false; break }
                    } else downloaded
                    f.writeBytes(plain)
                }
                files[name] = f
            }
            if (!ok) {
                // Файл ещё не долетел — оставляем свою версию, попробуем в следующий раз.
                if (mine != null) merged[key] = mine else merged.remove(key)
                continue
            }
            source.apply(r, files)
            received++
        }
        tempDir.listFiles()?.forEach { it.delete() }

        // 5. Отправляем недостающие файлы, потом — своё состояние (файлы раньше, чтобы ссылки не повисли).
        val remote = store.list("$ROOT/blobs").toHashSet()
        var sent = 0
        for (r in merged.values) for (sha in r.blobs.values) {
            if (sha in remote) continue
            val f = localFiles[sha] ?: continue
            val pass = passphrase()
            val raw = f.readBytes()
            val payload = if (pass.isNotBlank()) SyncCrypto.encrypt(raw, pass) else raw
            store.write("$ROOT/blobs/$sha", payload)
            remote += sha
            sent++
        }
        // Удаления помним 90 дней — за это время их увидят все устройства.
        val keep = merged.values.filter { !it.deleted || t - it.at < TOMBSTONE_MS }
        val snapshot = DeviceSnapshot(device, deviceName, deviceType, t, keep.sortedBy { it.key })
        store.write("$ROOT/devices/$device.json.gz", encode(snapshot))

        // 6. Запоминаем, что видели. Хеш — как запись выглядит здесь после применения.
        val after = HashMap<String, SyncState.Known>()
        for (r in keep) after[r.key] = SyncState.Known(r.at, r.hash, r.deleted, r.by)
        val applied = merged.filter { (key, r) -> !r.deleted && local[key]?.hash != r.hash }
        if (applied.isNotEmpty()) {
            // Своё представление записи может чуть отличаться (порядок полей, локальные мелочи) — запоминаем его,
            // иначе в следующий раз примем это за правку и отправим обратно.
            for (item in source.export()) {
                val key = "${item.kind}/${item.id}"
                val r = applied[key] ?: continue
                val blobs = item.files.mapValues { (_, f) -> fileHash(f, hashes) }
                after[key] = SyncState.Known(r.at, recordHash(item.data, blobs), false, r.by)
            }
        }
        val liveFiles = hashes.filterKeys { File(it).exists() }
        return SyncState(after, liveFiles, t) to SyncReport(received, sent, snapshots, removed)
    }

    private fun fileHash(f: File, cache: MutableMap<String, String>): String {
        val stamp = "${f.length()}:${f.lastModified()}"
        cache[f.path]?.let { if (it.substringBeforeLast(':') == stamp) return it.substringAfterLast(':') }
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        cache[f.path] = "$stamp:$sha"
        return sha
    }

    private fun encode(s: DeviceSnapshot): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToString(DeviceSnapshot.serializer(), s).toByteArray()) }
        val raw = out.toByteArray()
        val pass = passphrase()
        return if (pass.isNotBlank()) SyncCrypto.encrypt(raw, pass) else raw
    }

    companion object {
        const val ROOT = "Nastavnik"
        const val TOMBSTONE_MS = 90L * 24 * 3600 * 1000
        private val reader = Json { ignoreUnknownKeys = true }

        /** Снимок устройства из `devices/<id>.json.gz`. */
        fun decode(b: ByteArray, passphrase: String = ""): DeviceSnapshot {
            val raw = if (SyncCrypto.isEncrypted(b)) SyncCrypto.decrypt(b, passphrase) else b
            return reader.decodeFromString(DeviceSnapshot.serializer(), GZIPInputStream(ByteArrayInputStream(raw)).readBytes().decodeToString())
        }

        /** Какая версия записи свежее (при равенстве — удаление, потом по устройству — одинаково везде). */
        fun newer(a: SyncRecord, b: SyncRecord): Boolean = when {
            a.at != b.at -> a.at > b.at
            a.deleted != b.deleted -> a.deleted
            else -> a.by > b.by
        }

        fun recordHash(data: JsonElement?, blobs: Map<String, String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(data.toString().toByteArray())
            blobs.toSortedMap().forEach { (k, v) -> md.update("\n$k=$v".toByteArray()) }
            return md.digest().joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}

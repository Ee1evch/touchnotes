package app.nastavnik.sync

import android.os.Build
import app.nastavnik.NastavnikApp
import app.nastavnik.data.ActivityEntity
import app.nastavnik.data.CardEntity
import app.nastavnik.data.CleanNoteEntity
import app.nastavnik.data.ReminderEntity
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.study.Alarms
import app.nastavnik.study.StudyWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Что синхронизируется: тетради (с файлами страниц и подложек), карточки, учебные события (прогресс),
 * чистовики с PDF, напоминания, учебные настройки (группа, преподаватель языка, экзамены) и журнал оценок ЛКС
 * (с устройства, где вход, — на остальные: вход только на телефоне, оценки везде).
 * Разговоры с наставником остаются на своём устройстве — они привязаны к нему и к ключу API.
 */
class AppSyncSource(private val app: NastavnikApp) : SyncSource {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

    override suspend fun export(): List<LocalItem> = withContext(Dispatchers.IO) {
        val db = app.db
        val out = ArrayList<LocalItem>()
        val notes = db.cleanNotes().all()
        val noteUid = notes.associate { it.id to it.uid }
        for (c in db.cards().all()) out += LocalItem("card", c.uid, buildJsonObject {
            put("subject", c.subject); put("topic", c.topic); put("front", c.front); put("back", c.back)
            put("box", c.box); put("due", c.dueAt); put("created", c.createdAt); put("reviews", c.reviews); put("lapses", c.lapses)
            put("note", c.noteId?.let { noteUid[it] }.orEmpty())
        })
        for (a in db.activity().all()) out += LocalItem("act", a.uid, buildJsonObject {
            put("kind", a.kind); put("topic", a.topic); put("errors", a.errors); put("at", a.at)
        })
        for (n in notes) {
            val files = LinkedHashMap<String, File>()
            n.pdfPath?.let(::File)?.takeIf { it.exists() }?.let { files["note.pdf"] = it }
            n.pngPaths?.split('\n')?.filter { it.isNotBlank() }?.forEachIndexed { i, p -> File(p).takeIf { it.exists() }?.let { files["p$i.png"] = it } }
            out += LocalItem("clean", n.uid, buildJsonObject {
                put("subject", n.subject); put("title", n.title); put("json", n.json); put("created", n.createdAt)
            }, files)
        }
        for (r in db.reminders().all()) out += LocalItem("rem", r.uid, buildJsonObject {
            put("title", r.title); put("note", r.note); put("at", r.at); put("repeat", r.repeat); put("done", r.done); put("created", r.createdAt)
        })
        val store = app.notebooks.store
        for (m in store.list()) {
            val dir = store.dir(m.id)
            // Файлы тетради читаем строго: если что-то не прочиталось, лучше не синхронизировать, чем «удалить» на других.
            val files = dir.listFiles()!!.filter { it.isFile && (it.name.endsWith(".ink") || it.name.startsWith("bg_")) }
                .filter { !it.name.endsWith(".tmp") }
                .associateBy { it.name }
            out += LocalItem("book", m.id.toString(), json.encodeToJsonElement(NotebookMeta.serializer(), m.copy(conversationId = 0)), files)
        }
        app.grades.journalForSync()?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull()?.let { out += LocalItem("grades", "lks", it) }
        }
        val st = app.settings
        if (st.studyStamp() > 0 || st.scheduleGroup().isNotBlank() || !st.examsJson().isNullOrBlank()) {
            out += LocalItem("prefs", "study", buildJsonObject {
                put("group", st.scheduleGroup()); put("lang", st.languageTeacher()); put("exams", st.examsJson().orEmpty())
            })
        }
        out
    }

    override suspend fun apply(record: SyncRecord, files: Map<String, File>) = withContext(Dispatchers.IO) {
        val d = record.data?.jsonObject ?: return@withContext
        val db = app.db
        when (record.kind) {
            "card" -> {
                val noteId = d.s("note").takeIf { it.isNotEmpty() }?.let { db.cleanNotes().byUid(it)?.id }
                val c = CardEntity(
                    id = db.cards().byUid(record.id)?.id ?: 0, noteId = noteId, subject = d.s("subject"), topic = d.s("topic"),
                    front = d.s("front"), back = d.s("back"), box = d.i("box"), dueAt = d.l("due"), createdAt = d.l("created"),
                    reviews = d.i("reviews"), lapses = d.i("lapses"), uid = record.id,
                )
                if (c.id == 0L) db.cards().insertAll(listOf(c)) else db.cards().update(c)
            }
            "act" -> if (db.activity().countUid(record.id) == 0) {
                db.activity().insert(ActivityEntity(kind = d.s("kind"), topic = d.s("topic"), errors = d.i("errors"), at = d.l("at"), uid = record.id))
            }
            "clean" -> {
                val dir = File(app.filesDir, "notes/sync_${record.id}").apply { mkdirs() }
                val pdf = files["note.pdf"]?.let { f -> File(dir, "note.pdf").also { f.copyTo(it, overwrite = true) } }
                val pngs = files.keys.filter { it.startsWith("p") && it.endsWith(".png") }.sortedBy { it.removePrefix("p").removeSuffix(".png").toIntOrNull() ?: 0 }
                    .map { name -> File(dir, name).also { files.getValue(name).copyTo(it, overwrite = true) } }
                val old = db.cleanNotes().byUid(record.id)
                val n = CleanNoteEntity(
                    id = old?.id ?: 0, conversationId = old?.conversationId, subject = d.s("subject"), title = d.s("title"), json = d.s("json"),
                    pdfPath = pdf?.path ?: old?.pdfPath, pngPaths = pngs.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.path } ?: old?.pngPaths,
                    publicPath = old?.publicPath, createdAt = d.l("created"), uid = record.id,
                )
                if (old == null) db.cleanNotes().insert(n) else db.cleanNotes().update(n)
            }
            "rem" -> db.reminders().put(
                ReminderEntity(record.id, d.s("title"), d.s("note"), d.l("at"), d.s("repeat").ifEmpty { "none" }, d.b("done"), d.l("created")),
            )
            "book" -> {
                val meta = json.decodeFromJsonElement(NotebookMeta.serializer(), d)
                val store = app.notebooks.store
                val dir = store.dir(meta.id).apply { mkdirs() }
                for ((name, f) in files) {
                    val target = File(dir, name)
                    if (f.canonicalPath != target.canonicalPath) f.copyTo(target, overwrite = true)
                }
                // Страницы и подложки, которых в новой версии нет, — удалены там.
                dir.listFiles()?.filter { (it.name.endsWith(".ink") || it.name.startsWith("bg_")) && it.name !in files }?.forEach { it.delete() }
                val local = store.meta(meta.id)
                store.saveMeta(meta.copy(conversationId = local?.conversationId ?: 0))
                store.thumb(meta.id).delete()
                app.notebooks.forget(meta.id)
            }
            "grades" -> app.grades.applySynced(d.toString())
            "prefs" -> {
                val st = app.settings
                st.setScheduleGroup(d.s("group"))
                st.setLanguageTeacher(d.s("lang"))
                d.s("exams").takeIf { it.isNotEmpty() }?.let { st.setExamsJson(it) }
            }
        }
    }

    override suspend fun delete(kind: String, id: String) = withContext(Dispatchers.IO) {
        val db = app.db
        when (kind) {
            "card" -> db.cards().deleteUid(id)
            "act" -> db.activity().deleteUid(id)
            "clean" -> db.cleanNotes().byUid(id)?.let { db.cleanNotes().delete(it.id) } ?: Unit
            "rem" -> db.reminders().delete(id)
            "book" -> id.toLongOrNull()?.let { app.notebooks.store.delete(it) } ?: Unit
            "grades" -> app.grades.removeSynced()
            else -> Unit
        }
    }
}

/** Когда и как синхронизироваться; состояние — для экранов. */
class SyncManager(private val app: NastavnikApp) {
    data class Status(val running: Boolean = false, val lastAt: Long = 0, val ok: Boolean = true, val message: String = "", val devices: List<String> = emptyList())

    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<Status> = _status
    private val lock = Mutex()
    private var pending: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val stateFile get() = File(app.filesDir, "sync/state.json")

    val configured: Boolean get() = app.settings.syncConfigured

    private fun loadStatus(): Status {
        val raw = app.settings.syncStatus().split('|', limit = 4)
        if (raw.size < 3) return Status()
        return Status(lastAt = raw[0].toLongOrNull() ?: 0, ok = raw[1] == "ok", message = raw[2], devices = raw.getOrNull(3)?.split(';')?.filter { it.isNotBlank() }.orEmpty())
    }

    /** Синхронизироваться через [delayMs] (повторные вызовы сдвигают срок — правки собираются в одну отправку). */
    fun soon(delayMs: Long = 4000) {
        if (!configured) return
        pending?.cancel()
        pending = app.appScope.launch {
            delay(delayMs)
            runCatching { now() }
        }
    }

    /** При открытии приложения — если давно не было. */
    fun onForeground() {
        if (configured && System.currentTimeMillis() - _status.value.lastAt > 90_000) soon(500)
    }

    suspend fun now(): Status = lock.withLock {
        if (!configured) return Status(ok = false, message = "Облако не подключено")
        _status.value = _status.value.copy(running = true)
        val st = app.settings
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val store: SyncStore = if (st.syncUrl() == YandexDiskStore.MARKER) YandexDiskStore(st.syncPassword())
                else WebDavStore(st.syncUrl(), st.syncLogin(), st.syncPassword())
                val engine = SyncEngine(
                    store, AppSyncSource(app), st.deviceId(), Build.MODEL ?: "Устройство",
                    if (app.isTablet()) "tablet" else "phone",
                    File(app.cacheDir, "sync-tmp").apply { mkdirs() },
                    passphrase = { st.syncPassphrase() },
                )
                val state = runCatching { json.decodeFromString(SyncState.serializer(), stateFile.readText()) }.getOrDefault(SyncState())
                val (next, report) = engine.run(state)
                stateFile.parentFile?.mkdirs()
                val tmp = File(stateFile.path + ".tmp")
                tmp.writeText(json.encodeToString(SyncState.serializer(), next))
                tmp.renameTo(stateFile)
                report
            }
        }
        val status = result.fold(
            onSuccess = { r ->
                if (r.received > 0 || r.removed > 0) {
                    app.schedule.changed()
                    Alarms.rescheduleAll(app)
                    StudyWidget.refresh(app)
                }
                val others = r.devices.sortedByDescending { it.at }.map { "${it.name}·${it.type}·${it.at}" }
                Status(false, System.currentTimeMillis(), true, when {
                    r.received > 0 -> "Получено изменений: ${r.received}"
                    else -> "Всё синхронизировано"
                }, others)
            },
            onFailure = { e -> Status(false, System.currentTimeMillis(), false, e.message ?: "Не удалось синхронизироваться", _status.value.devices) },
        )
        st.setSyncStatus("${status.lastAt}|${if (status.ok) "ok" else "err"}|${status.message.replace('|', ' ')}|${status.devices.joinToString(";")}")
        _status.value = status
        status
    }

    /** Отключить облако: забываем пароль и что видели (данные на устройстве остаются). */
    fun disconnect() {
        app.settings.clearSync()
        stateFile.delete()
        _status.value = Status()
    }
}

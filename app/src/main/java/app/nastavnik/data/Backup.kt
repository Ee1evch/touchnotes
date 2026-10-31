package app.nastavnik.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.nastavnik.NastavnikApp
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Резервная копия: база (задачи, чистовики, карточки, прогресс), тетради, PDF чистовиков и настройки.
 * API-ключи хранятся отдельно в зашифрованном виде и в копию не попадают — после восстановления их вводят заново.
 */
object Backup {
    suspend fun write(app: NastavnikApp, out: OutputStream): Int = withContext(Dispatchers.IO) {
        // Сбрасываем журнал WAL в основной файл базы, чтобы копия была целой.
        runCatching { app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val dataDir = app.filesDir.parentFile!!
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, file: File) {
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            zip.putNextEntry(ZipEntry(BackupLayout.MANIFEST))
            zip.write("${BackupLayout.MAGIC}\n${System.currentTimeMillis()}\n".toByteArray())
            zip.closeEntry()
            File(dataDir, "databases").listFiles()?.filter { it.isFile && it.name.startsWith(AppDatabase.DB_NAME) }?.forEach { put("db/${it.name}", it) }
            File(dataDir, "shared_prefs/${BackupLayout.PREFS_FILE}").takeIf { it.exists() }?.let { put("prefs/${BackupLayout.PREFS_FILE}", it) }
            for (dir in BackupLayout.FILE_DIRS) {
                val root = File(app.filesDir, dir)
                root.walkTopDown().filter { it.isFile }.forEach { f -> put("files/$dir/" + f.relativeTo(root).invariantSeparatorsPath, f) }
            }
        }
        count
    }

    /** Проверка без распаковки: это наша копия? */
    fun looksValid(input: InputStream): Boolean = ZipInputStream(input).use { zip ->
        var e = zip.nextEntry
        while (e != null) {
            if (e.name == BackupLayout.MANIFEST) return@use zip.readBytes().decodeToString().startsWith(BackupLayout.MAGIC)
            e = zip.nextEntry
        }
        false
    }

    /**
     * Восстановить: старые данные заменяются копией. После этого приложение перезапускается —
     * открытая база и настройки в памяти больше не годятся.
     */
    suspend fun restore(app: NastavnikApp, open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        if (open()?.let { looksValid(it) } != true) return@withContext false
        val dataDir = app.filesDir.parentFile!!
        runCatching { app.db.close() }
        File(dataDir, "databases").listFiles()?.filter { it.name.startsWith(AppDatabase.DB_NAME) }?.forEach { it.delete() }
        BackupLayout.FILE_DIRS.forEach { File(app.filesDir, it).deleteRecursively() }
        ZipInputStream(open() ?: return@withContext false).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val rel = if (e.isDirectory) null else BackupLayout.target(e.name)
                if (rel != null) {
                    val f = File(dataDir, rel)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zip.copyTo(it) }
                }
                e = zip.nextEntry
            }
        }
        true
    }

    /** Перезапуск приложения через полсекунды — так подхватятся восстановленные база и настройки. */
    fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 500, pi)
        exitProcess(0)
    }
}

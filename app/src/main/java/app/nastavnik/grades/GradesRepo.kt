package app.nastavnik.grades

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.data.SettingsRepo
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Успеваемость: последний снимок с сайта (без сети показываем его), обновление и уведомление о новых оценках.
 * Пароль и токен — в зашифрованных настройках; оценки на другие устройства не отправляются.
 */
class GradesRepo(private val context: Context, private val settings: SettingsRepo) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = File(context.filesDir, "grades/snapshot.json")
    private val lock = Mutex()
    private val _snapshot = MutableStateFlow<GradesSnapshot?>(null)
    val snapshot: StateFlow<GradesSnapshot?> = _snapshot
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    val configured get() = settings.gradesConfigured

    suspend fun load() = withContext(Dispatchers.IO) {
        if (_snapshot.value == null) _snapshot.value = runCatching { json.decodeFromString(GradesSnapshot.serializer(), file.readText()) }.getOrNull()
    }

    /** Вход: проверяем пароль на сайте и запоминаем токен. */
    suspend fun connect(url: String, login: String, password: String) {
        val token = MoodleClient(url).login(login, password)
        settings.setGrades(url, login, password, token)
        _error.value = null
        refresh(force = true)
    }

    fun disconnect() {
        settings.clearGrades()
        file.delete()
        _snapshot.value = null
        _error.value = null
    }

    /** Обновить, если давно (или [force]). Новые оценки — уведомлением. */
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        if (!configured) return@withLock
        load()
        val last = _snapshot.value?.loadedAt ?: 0
        if (!force && System.currentTimeMillis() - last < 60 * 60 * 1000L) return@withLock
        _loading.value = true
        try {
            val client = MoodleClient(settings.gradesUrl())
            val snap = try {
                client.snapshot(settings.gradesToken())
            } catch (e: GradesException) {
                // Токен устарел — входим заново тем же паролем.
                if (!e.badLogin || settings.gradesPassword().isEmpty()) throw e
                val token = client.login(settings.gradesLogin(), settings.gradesPassword())
                settings.setGradesToken(token)
                client.snapshot(token)
            }
            val old = _snapshot.value
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(GradesSnapshot.serializer(), snap))
                tmp.renameTo(file)
            }
            _snapshot.value = snap
            _error.value = null
            notifyNew(MoodleClient.changes(old, snap))
        } catch (e: Exception) {
            _error.value = e.message ?: "Не удалось обновить успеваемость"
        } finally {
            _loading.value = false
        }
    }

    private fun notifyNew(changes: List<Pair<CourseGrades, GradeItem>>) {
        if (changes.isEmpty() || !Permissions.notifications(context)) return
        val open = PendingIntent.getActivity(
            context, 7730,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_GRADES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (c, i) = changes.first()
        val title = if (changes.size == 1) "Новая оценка: ${i.grade}" else "Новых оценок: ${changes.size}"
        val text = if (changes.size == 1) "${c.name} — ${i.name}" else changes.take(4).joinToString("\n") { (cc, ii) -> "${cc.name}: ${ii.name} — ${ii.grade}" }
        val n = NotificationCompat.Builder(context, NastavnikApp.CHANNEL_GRADES)
            .setSmallIcon(R.drawable.ic_chart)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(7731, n) }
    }
}

package app.nastavnik.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.nastavnik.BuildConfig
import app.nastavnik.notes.NoteApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Обновление из приложения: проверить GitHub Releases, скачать APK и открыть системный установщик. */
class Updater(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    /** null — обновлений нет. Бросает [IOException], если GitHub недоступен. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(UpdateCheck.LATEST_URL).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw IOException("GitHub ответил ${resp.code}")
            UpdateCheck.parse(resp.body?.string().orEmpty(), currentBuild)
        }
    }

    /** Скачать APK в кэш; [progress] — доля от 0 до 1. */
    suspend fun download(info: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "nastavnik-${info.build}.apk")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Не удалось скачать: ${resp.code}")
            val body = resp.body ?: throw IOException("Пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** Можно ли ставить APK: Android спрашивает разрешение один раз для каждого приложения. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(apk: File) {
        val uri = NoteApps.uriFor(context, apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

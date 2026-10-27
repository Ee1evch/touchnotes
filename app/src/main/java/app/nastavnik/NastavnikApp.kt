package app.nastavnik

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.webkit.WebView
import app.nastavnik.claude.ClaudeClient
import app.nastavnik.data.AppDatabase
import app.nastavnik.data.SettingsRepo

class NastavnikApp : Application() {
    val settings: SettingsRepo by lazy { SettingsRepo(this) }
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val claude: ClaudeClient by lazy {
        ClaudeClient(ClaudeClient.defaultHttp()).apply {
            if (BuildConfig.DEBUG) baseUrlOverride = { settings.debugBaseUrl() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Нужно до создания первого WebView: иначе при экспорте в PDF
        // WebView рисует только видимую часть страницы.
        WebView.enableSlowWholeDocumentDraw()
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            "Наставник рядом",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Постоянное уведомление, пока плавающая кнопка включена"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
    }
}

val Context.app: NastavnikApp get() = applicationContext as NastavnikApp

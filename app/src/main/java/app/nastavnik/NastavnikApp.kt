package app.nastavnik

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.webkit.WebView
import app.nastavnik.ai.AiBackend
import app.nastavnik.ai.AiProvider
import app.nastavnik.ai.ClaudeClient
import app.nastavnik.ai.GeminiClient
import app.nastavnik.ai.OpenAiClient
import app.nastavnik.data.AppDatabase
import app.nastavnik.data.AppSettings
import app.nastavnik.data.SettingsRepo

class NastavnikApp : Application() {
    val settings: SettingsRepo by lazy { SettingsRepo(this) }
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    private val http by lazy { ClaudeClient.defaultHttp() }

    val claude: ClaudeClient by lazy {
        ClaudeClient(http).apply {
            if (BuildConfig.DEBUG) baseUrlOverride = { settings.debugBaseUrl() }
        }
    }
    private val gemini: GeminiClient by lazy { GeminiClient(http) }

    /** Клиент выбранного в настройках провайдера. */
    fun ai(s: AppSettings = settings.load()): AiBackend = when (s.provider) {
        AiProvider.ANTHROPIC -> claude
        AiProvider.GEMINI -> gemini
        AiProvider.OPENAI_COMPAT -> OpenAiClient(http, s.openAiBaseUrl)
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

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
import app.nastavnik.ai.MeteredBackend
import app.nastavnik.ai.OpenAiClient
import app.nastavnik.data.AppDatabase
import app.nastavnik.data.AppSettings
import app.nastavnik.data.AppUsage
import app.nastavnik.data.SettingsRepo
import app.nastavnik.ink.Notebooks
import app.nastavnik.session.CleanSession
import app.nastavnik.study.Reminders
import app.nastavnik.study.Speaker
import app.nastavnik.study.StudyRepo
import app.nastavnik.study.Trainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

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

    /** Живёт, пока живёт процесс: чистовик продолжает сохраняться, даже если экран закрыли. */
    val appScope: CoroutineScope = MainScope()

    /** Читает подсказки вслух. */
    val speaker: Speaker by lazy { Speaker(this) }

    /** Тетради, в которых пишут прямо в приложении (и фото, и PDF). */
    val notebooks: Notebooks by lazy { Notebooks(this) }

    /** Карточки повторения и учебные события для «Прогресса». */
    val study: StudyRepo by lazy { StudyRepo(this) }

    /** Тренировочные варианты, мини-тесты и карточки из подсказок. */
    val trainer: Trainer by lazy { Trainer(this) }

    /** Чистовик общий для кнопки, тетрадей и экрана предпросмотра. */
    val clean: CleanSession by lazy { CleanSession(this, appScope) }

    /** Клиент выбранного в настройках провайдера. */
    fun ai(s: AppSettings = settings.load()): AiBackend = MeteredBackend(
        when (s.provider) {
            AiProvider.ANTHROPIC -> claude
            AiProvider.GEMINI -> gemini
            AiProvider.OPENAI_COMPAT -> OpenAiClient(http, s.openAiBaseUrl)
            AiProvider.ANTHROPIC_COMPAT -> ClaudeClient(http, s.anthropicCompatBaseUrl, AiProvider.ANTHROPIC_COMPAT, s.authMode)
        },
        usage,
    )

    /** Расходы на нейросеть и дневной лимит. */
    val usage: AppUsage by lazy { AppUsage(settings) }

    override fun onCreate() {
        super.onCreate()
        // Нужно до создания первого WebView: иначе при экспорте в PDF
        // WebView рисует только видимую часть страницы.
        WebView.enableSlowWholeDocumentDraw()
        createChannels()
        Reminders.schedule(this)
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Повторение карточек", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Раз в день вечером, если есть карточки к повторению и ты ещё не занимался"
            },
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_REMINDERS = "reminders"
    }
}

val Context.app: NastavnikApp get() = applicationContext as NastavnikApp

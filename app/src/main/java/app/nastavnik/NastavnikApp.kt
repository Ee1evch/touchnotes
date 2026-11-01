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
import app.nastavnik.study.Alarms
import app.nastavnik.ui.Palettes
import app.nastavnik.schedule.ScheduleRepo
import app.nastavnik.campus.CampusRepo
import app.nastavnik.sync.SyncManager
import app.nastavnik.grades.GradesRepo
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

    /** Расписание своей группы с сайта Сириуса (с кешем). */
    val schedule: ScheduleRepo by lazy { ScheduleRepo(this, settings) }

    /** Карта кампуса: этажи, поиск аудиторий, маршруты. */
    val campus: CampusRepo by lazy { CampusRepo(this) }

    /** Успеваемость из личного кабинета (Moodle). */
    val grades: GradesRepo by lazy { GradesRepo(this, settings) }

    /** Синхронизация с другими устройствами через облако. */
    val sync: SyncManager by lazy { SyncManager(this) }

    /**
     * Планшет — тетради для стилуса и наставник поверх записей; телефон — расписание, карта, карточки, напоминания.
     * По умолчанию по ширине экрана, но можно выбрать в настройках.
     */
    fun isTablet(): Boolean = when (settings.deviceRole()) {
        "phone" -> false
        "tablet" -> true
        else -> resources.configuration.smallestScreenWidthDp >= 600
    }

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
        settings.forgetLksPassword()
        Palettes.installWallpaper(this)
        Reminders.schedule(this)
        Alarms.rescheduleAll(this)
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PERSONAL, "Мои напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Напоминания, которые ты создал сам — на любом из своих устройств"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GRADES, "Новые оценки", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Когда в личном кабинете появляется новая оценка"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Будильник к первой паре и проверка «Ты встал?». Звук играет сам будильник"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SLEEP, "Сон", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Вечером: когда лечь, чтобы проснуться в конце цикла сна"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LESSONS, "Скоро пара", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "За несколько минут до пары: что и в какой аудитории, с маршрутом по карте"
            },
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_PERSONAL = "personal"
        const val CHANNEL_LESSONS = "lessons"
        const val CHANNEL_GRADES = "grades"
        const val CHANNEL_WAKE = "wake"
        const val CHANNEL_SLEEP = "sleep"
    }
}

val Context.app: NastavnikApp get() = applicationContext as NastavnikApp

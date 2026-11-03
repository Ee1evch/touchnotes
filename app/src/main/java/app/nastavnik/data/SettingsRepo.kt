package app.nastavnik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.nastavnik.ai.AiProvider
import app.nastavnik.ai.AuthMode
import app.nastavnik.ai.ProxyConfig
import app.nastavnik.ai.ModelPresets
import app.nastavnik.ai.OpenAiCompatPresets
import app.nastavnik.ai.Strictness
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

enum class BubbleSide { LEFT, RIGHT }

data class AppSettings(
    /** Какая нейросеть отвечает. */
    val provider: AiProvider,
    /** Модель наставника у выбранного провайдера. */
    val model: String,
    /** Модель для чистовика у выбранного провайдера. */
    val cleanModel: String,
    /** Адрес OpenAI-совместимого сервиса (для других провайдеров не используется). */
    val openAiBaseUrl: String,
    /** Адрес Claude-совместимого прокси (Claudex, CLIProxyAPI…), как ANTHROPIC_BASE_URL. */
    val anthropicCompatBaseUrl: String,
    /** Как передавать ключ Claude-совместимому прокси. */
    val authMode: AuthMode,
    /** Тихая проверка в фоне: через сколько секунд после того, как перестал писать (0 — выключена). */
    val watchPauseSec: Int,
    /** Насколько прозрачными становятся заметки, когда к ним подносят стилус (0.15…0.8). */
    val noteFadeAlpha: Float,
    /** Во сколько напомнить о карточках (час, -1 — не напоминать). */
    val reminderHour: Int,
    /** Наставник видит весь конспект: страницы, которые студент пролистал, уходят вместе с вопросом. */
    val seeNotebook: Boolean,
    val strictness: Strictness,
    val bubbleAlpha: Float,
    val bubbleSizeDp: Int,
    val bubbleSide: BubbleSide,
    val bubbleYFraction: Float,
    /** "auto" — приложение для записей, если найдено; "chooser" — всегда спрашивать; иначе "pkg/activity|action". */
    val sendTarget: String,
    val onboardingDone: Boolean,
    val projectionExplained: Boolean,
    val activeConversationId: Long,
    /** Тема: SYSTEM / LIGHT / DARK. Здесь, чтобы смена темы сразу доходила до экранов через [SettingsRepo.flow]. */
    val themeMode: String = "LIGHT",
    /** Быстрые ответы: меньше «размышлений» модели и меньше картинок в запросе. */
    val fastAnswers: Boolean = true,
    /** Оформление: светлая и тёмная тема, свой акцент (0 — как в теме), фон и размер текста. */
    val lightTheme: String = "paper",
    val darkTheme: String = "graphite",
    val accent: Int = 0,
    val backdrop: String = "PLAIN",
    val textScale: Float = 1f,
    /** Режим устройства (auto / phone / tablet) — здесь, чтобы смена сразу перестраивала экраны. */
    val deviceRole: String = "auto",
)

/**
 * Настройки приложения. Ключ API лежит отдельно в EncryptedSharedPreferences,
 * всё остальное — в обычных SharedPreferences.
 */
class SettingsRepo(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure: SharedPreferences? by lazy { openSecure() }

    fun provider(): AiProvider = AiProvider.byName(prefs.getString(K_PROVIDER, null))

    fun modelFor(p: AiProvider): String =
        prefs.getString("${K_MODEL}_${p.name}", null)
            ?: (if (p == AiProvider.ANTHROPIC) prefs.getString(K_MODEL, null) else null)
            ?: ModelPresets.defaultMentor(p)

    fun cleanModelFor(p: AiProvider): String =
        prefs.getString("${K_CLEAN_MODEL}_${p.name}", null)
            ?: (if (p == AiProvider.ANTHROPIC) prefs.getString(K_CLEAN_MODEL, null) else null)
            ?: ModelPresets.defaultClean(p)

    fun load(): AppSettings = loadFor(provider())

    private fun loadFor(p: AiProvider) = AppSettings(
        provider = p,
        model = modelFor(p),
        cleanModel = cleanModelFor(p),
        openAiBaseUrl = prefs.getString(K_OPENAI_BASE, null) ?: OpenAiCompatPresets.OPENAI,
        anthropicCompatBaseUrl = prefs.getString(K_ANTHROPIC_COMPAT_BASE, null).orEmpty(),
        authMode = AuthMode.byName(prefs.getString(K_AUTH_MODE, null)),
        watchPauseSec = prefs.getInt(K_WATCH_PAUSE, 0).coerceAtLeast(0),
        noteFadeAlpha = prefs.getFloat(K_NOTE_FADE, 0.35f).coerceIn(0.15f, 0.8f),
        reminderHour = prefs.getInt(K_REMINDER, DEFAULT_REMINDER_HOUR).coerceIn(-1, 23),
        seeNotebook = prefs.getBoolean(K_NOTEBOOK, true),
        strictness = runCatching { Strictness.valueOf(prefs.getString(K_STRICTNESS, null)!!) }.getOrDefault(Strictness.HINTS),
        bubbleAlpha = prefs.getFloat(K_ALPHA, 0.7f).coerceIn(MIN_ALPHA, MAX_ALPHA),
        bubbleSizeDp = prefs.getInt(K_SIZE, 56).coerceIn(40, 72),
        bubbleSide = if (prefs.getString(K_SIDE, "RIGHT") == "LEFT") BubbleSide.LEFT else BubbleSide.RIGHT,
        bubbleYFraction = prefs.getFloat(K_Y, 0.35f).coerceIn(0f, 1f),
        sendTarget = prefs.getString(K_TARGET, TARGET_AUTO) ?: TARGET_AUTO,
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        projectionExplained = prefs.getBoolean(K_PROJ_EXPLAINED, false),
        activeConversationId = prefs.getLong(K_ACTIVE_CONV, 0L),
        themeMode = themeMode(),
        fastAnswers = prefs.getBoolean(K_FAST, true),
        lightTheme = prefs.getString(K_LIGHT, "paper").orEmpty(),
        darkTheme = prefs.getString(K_DARK, "graphite").orEmpty(),
        accent = prefs.getInt(K_ACCENT, 0),
        backdrop = prefs.getString(K_BACKDROP, "PLAIN").orEmpty(),
        textScale = prefs.getFloat(K_TEXT_SCALE, 1f),
        deviceRole = prefs.getString(K_ROLE, "auto").orEmpty(),
    )

    /**
     * Один раз — на оформление как в приложении для родителя: светлая и тёмная темы по умолчанию, свой акцент
     * сброшен, ровный фон. Дальше выбор в «Оформлении» остаётся за студентом.
     */
    fun migrateToParentDesign() {
        if (prefs.getBoolean(K_DESIGN_PARENT, false)) return
        prefs.edit {
            putString(K_LIGHT, "paper"); putString(K_DARK, "graphite"); putInt(K_ACCENT, 0)
            putString(K_BACKDROP, "PLAIN"); putBoolean(K_DESIGN_PARENT, true)
        }
    }

    fun setLightTheme(id: String) = prefs.edit { putString(K_LIGHT, id) }
    fun setDarkTheme(id: String) = prefs.edit { putString(K_DARK, id) }
    fun setAccent(c: Int) = prefs.edit { putInt(K_ACCENT, c) }
    fun setBackdrop(name: String) = prefs.edit { putString(K_BACKDROP, name) }
    fun setTextScale(k: Float) = prefs.edit { putFloat(K_TEXT_SCALE, k.coerceIn(0.9f, 1.25f)) }

    /** Плашка инструментов в тетради: у какого края и на какой высоте. */
    fun railRight(): Boolean = prefs.getBoolean(K_RAIL_RIGHT, false)
    fun railY(): Float = prefs.getFloat(K_RAIL_Y, 0.15f)
    fun setRail(right: Boolean, y: Float) = prefs.edit { putBoolean(K_RAIL_RIGHT, right); putFloat(K_RAIL_Y, y.coerceIn(0f, 1f)) }

    /** Подсказки по жестам листа показаны (один раз). */
    fun editorTipsShown(): Boolean = prefs.getBoolean(K_TIPS, false)
    fun setEditorTipsShown() = prefs.edit { putBoolean(K_TIPS, true) }

    fun setFastAnswers(on: Boolean) = prefs.edit { putBoolean(K_FAST, on) }

    /** Поток настроек: выдаёт текущее значение и каждое изменение. */
    fun flow(): Flow<AppSettings> = callbackFlow {
        trySend(load())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(load()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun setProvider(p: AiProvider) = prefs.edit { putString(K_PROVIDER, p.name) }
    fun setModel(id: String, p: AiProvider = provider()) = prefs.edit { putString("${K_MODEL}_${p.name}", id.trim()) }
    fun setCleanModel(id: String, p: AiProvider = provider()) = prefs.edit { putString("${K_CLEAN_MODEL}_${p.name}", id.trim()) }
    fun setOpenAiBaseUrl(url: String) = prefs.edit { putString(K_OPENAI_BASE, url.trim()) }
    fun setAnthropicCompatBaseUrl(url: String) = prefs.edit { putString(K_ANTHROPIC_COMPAT_BASE, url.trim()) }
    fun setAuthMode(m: AuthMode) = prefs.edit { putString(K_AUTH_MODE, m.name) }
    fun setWatchPauseSec(sec: Int) = prefs.edit { putInt(K_WATCH_PAUSE, sec.coerceAtLeast(0)) }
    fun setNoteFadeAlpha(a: Float) = prefs.edit { putFloat(K_NOTE_FADE, a.coerceIn(0.15f, 0.8f)) }
    fun setReminderHour(h: Int) = prefs.edit { putInt(K_REMINDER, h.coerceIn(-1, 23)) }
    fun setSeeNotebook(on: Boolean) = prefs.edit { putBoolean(K_NOTEBOOK, on) }

    /** Память о типичных ошибках студента (JSON, см. MistakeMemory). */
    fun mistakesJson(): String? = prefs.getString(K_MISTAKES, null)
    fun setMistakesJson(json: String) = prefs.edit { putString(K_MISTAKES, json) }

    /** Озвучивать подсказки вслух. */
    fun speakHints(): Boolean = prefs.getBoolean(K_SPEAK, false)
    fun setSpeakHints(on: Boolean) = prefs.edit { putBoolean(K_SPEAK, on) }

    /** Приложение для записей, выбранное студентом (пакет), или пусто — определять самим. */
    fun notesApp(): String = prefs.getString(K_NOTES_APP, "").orEmpty()
    fun setNotesApp(pkg: String) = prefs.edit { putString(K_NOTES_APP, pkg) }

    // Тетради: последний цвет ручки, письмо пальцем, автопроверка.
    fun penColor(): Int = prefs.getInt(K_PEN_COLOR, 0xFF1F4FD1.toInt())
    fun setPenColor(c: Int) = prefs.edit { putInt(K_PEN_COLOR, c) }
    fun fingerDraws(): Boolean = prefs.getBoolean(K_FINGER, false)
    fun setFingerDraws(on: Boolean) = prefs.edit { putBoolean(K_FINGER, on) }
    /** Тема: SYSTEM / LIGHT / DARK. */
    fun themeMode(): String = prefs.getString(K_THEME, "LIGHT").orEmpty()
    fun setThemeMode(v: String) = prefs.edit { putString(K_THEME, v) }

    /** Журнал расходов по дням (JSON) и дневной лимит в долларах (0 — без лимита). */
    fun usageJson(): String? = prefs.getString(K_USAGE, null)
    fun setUsageJson(json: String) = prefs.edit { putString(K_USAGE, json) }
    fun dailyLimitUsd(): Float = prefs.getFloat(K_LIMIT, 0f)
    fun setDailyLimitUsd(v: Float) = prefs.edit { putFloat(K_LIMIT, v.coerceAtLeast(0f)) }

    /** Экзамены (JSON списка [app.nastavnik.study.Exam]). */
    fun examsJson(): String? = prefs.getString(K_EXAMS, null)
    fun setExamsJson(json: String) = prefs.edit { putString(K_EXAMS, json) }

    fun notebookAutoCheck(): Boolean = prefs.getBoolean(K_NB_AUTO, false)
    /** Наставник в тетради: выключен — ни панели, ни автопроверки, ни обращений к нейросети из тетради. */
    fun notebookMentorOn(): Boolean = prefs.getBoolean(K_NB_MENTOR, true)
    fun setNotebookMentorOn(on: Boolean) = prefs.edit { putBoolean(K_NB_MENTOR, on) }
    fun setNotebookAutoCheck(on: Boolean) = prefs.edit { putBoolean(K_NB_AUTO, on) }

    /** Подставить настройки, вставленные из прокси (Claudex и т.п.). */
    fun apply(c: ProxyConfig) {
        setProvider(c.provider)
        c.baseUrl?.let {
            when (c.provider) {
                AiProvider.OPENAI_COMPAT -> setOpenAiBaseUrl(it)
                AiProvider.ANTHROPIC_COMPAT -> setAnthropicCompatBaseUrl(it)
                else -> Unit
            }
        }
        c.authMode?.let(::setAuthMode)
        c.apiKey?.let { setApiKey(it, c.provider) }
        c.model?.let {
            setModel(it, c.provider)
            setCleanModel(it, c.provider)
        }
    }

    /**
     * Готов ли выбранный провайдер к работе. null — готов, иначе текст, что сделать.
     * Прокси и своим серверам ключ может быть не нужен, зато нужен адрес.
     */
    fun notReadyReason(s: AppSettings = load()): String? = when (s.provider) {
        AiProvider.ANTHROPIC, AiProvider.GEMINI ->
            if (hasApiKey(s.provider)) null else "Не задан API-ключ (${s.provider.title})"
        AiProvider.OPENAI_COMPAT -> when {
            s.openAiBaseUrl.isBlank() -> "Не задан адрес OpenAI-совместимого сервиса"
            !hasApiKey(s.provider) && ("api.openai.com" in s.openAiBaseUrl || "openrouter.ai" in s.openAiBaseUrl) ->
                "Не задан API-ключ (${s.provider.title})"
            else -> null
        }
        AiProvider.ANTHROPIC_COMPAT ->
            if (s.anthropicCompatBaseUrl.isBlank()) "Не задан адрес Claude-совместимого прокси" else null
    }
    fun setStrictness(s: Strictness) = prefs.edit { putString(K_STRICTNESS, s.name) }
    fun setBubbleAlpha(a: Float) = prefs.edit { putFloat(K_ALPHA, a.coerceIn(MIN_ALPHA, MAX_ALPHA)) }
    fun setBubbleSize(dp: Int) = prefs.edit { putInt(K_SIZE, dp.coerceIn(40, 72)) }
    fun setBubbleSide(side: BubbleSide) = prefs.edit { putString(K_SIDE, side.name) }
    fun setBubblePosition(side: BubbleSide, yFraction: Float) = prefs.edit {
        putString(K_SIDE, side.name)
        putFloat(K_Y, yFraction.coerceIn(0f, 1f))
    }
    fun setSendTarget(v: String) = prefs.edit { putString(K_TARGET, v) }
    fun setOnboardingDone(v: Boolean) = prefs.edit { putBoolean(K_ONBOARDING, v) }
    fun setProjectionExplained(v: Boolean) = prefs.edit { putBoolean(K_PROJ_EXPLAINED, v) }
    fun setActiveConversation(id: Long) = prefs.edit { putLong(K_ACTIVE_CONV, id) }

    /** Только для отладочной сборки: адрес мок-сервера вместо api.anthropic.com (используют тесты). */
    fun debugBaseUrl(): String? = prefs.getString(K_DEBUG_BASE_URL, null)?.takeIf { it.isNotBlank() }
    fun setDebugBaseUrl(url: String?) = prefs.edit { putString(K_DEBUG_BASE_URL, url) }

    // --- API-ключ ---

    /** false, если зашифрованное хранилище не открылось (тогда ключ живёт только до перезапуска). */
    val secureStorageOk: Boolean get() = secure != null

    /** Ключи на случай, если зашифрованное хранилище не открылось. */
    private val memoryKeys = java.util.concurrent.ConcurrentHashMap<AiProvider, String>()
    @Volatile private var memorySyncPassword: String? = null

    private fun keyName(p: AiProvider) = "${K_API_KEY}_${p.name}"

    /** Ключ провайдера [p] (по умолчанию — выбранного сейчас). */
    fun apiKey(p: AiProvider = provider()): String {
        val s = secure
        val stored = s?.getString(keyName(p), null)
            // Ключ Anthropic из первой версии хранился без суффикса.
            ?: (if (p == AiProvider.ANTHROPIC) s?.getString(K_API_KEY, null) else null)
            ?: memoryKeys[p]
        return stored.orEmpty().trim()
    }

    fun setApiKey(key: String, p: AiProvider = provider()) {
        val clean = key.trim()
        val s = secure
        if (s != null) s.edit { putString(keyName(p), clean) } else memoryKeys[p] = clean
        // Дёргаем обычные настройки, чтобы подписчики flow() узнали об изменении.
        prefs.edit { putLong(K_KEY_STAMP, System.currentTimeMillis()) }
    }

    fun hasApiKey(p: AiProvider = provider()): Boolean = apiKey(p).isNotEmpty()

    // ---------- расписание ----------
    /** Группа в расписании Сириуса (пусто — не выбрана). */
    fun scheduleGroup(): String = prefs.getString(K_GROUP, "").orEmpty()
    fun setScheduleGroup(g: String) = prefs.edit { putString(K_GROUP, g.trim()); putLong(K_STUDY_STAMP, System.currentTimeMillis()) }
    /** Преподаватель иностранного языка: показывать пары только с ним. */
    fun languageTeacher(): String = prefs.getString(K_LANG_TEACHER, "").orEmpty()
    fun setLanguageTeacher(t: String) = prefs.edit { putString(K_LANG_TEACHER, t.trim()); putLong(K_STUDY_STAMP, System.currentTimeMillis()) }
    /** За сколько минут до пары напомнить (0 — не напоминать). */
    fun lessonAlertMin(): Int = prefs.getInt(K_LESSON_ALERT, 10)
    fun setLessonAlertMin(m: Int) = prefs.edit { putInt(K_LESSON_ALERT, m.coerceIn(0, 60)) }
    /** Живое уведомление: вид (minimal, timeline, card, moments, companion) и обновление (boundary, interval, service). */
    /** Раскладка главного экрана (конструктор), своя для телефона и планшета. */
    fun homeLayout(tablet: Boolean): String = prefs.getString(if (tablet) "home_tablet" else "home_phone", "").orEmpty()
    fun setHomeLayout(tablet: Boolean, v: String) = prefs.edit { putString(if (tablet) "home_tablet" else "home_phone", v) }
    fun liveMode(): String = prefs.getString("live_mode", "interval").orEmpty()
    fun setLiveMode(v: String) = prefs.edit { putString("live_mode", v) }
    /** «Тихо на парах» — на этом устройстве (не синхронизируется: на планшете может быть не нужно). */
    /** Фото преподавателей в расписании — по умолчанию выключено (без пустых кружков). */
    fun teacherPhotos(): Boolean = prefs.getBoolean(K_TEACHER_PHOTOS, false)
    fun setTeacherPhotos(on: Boolean) = prefs.edit { putBoolean(K_TEACHER_PHOTOS, on) }

    fun quietLessons(): Boolean = prefs.getBoolean(K_QUIET, false)
    fun setQuietLessons(on: Boolean) = prefs.edit { putBoolean(K_QUIET, on) }
    fun quietRuleId(): String = prefs.getString(K_QUIET_RULE, "").orEmpty()
    fun setQuietRuleId(id: String) = prefs.edit { putString(K_QUIET_RULE, id) }

    // ---------- успеваемость (ЛКС) ----------
    fun gradesUrl(): String = prefs.getString(K_GRADES_URL, "").orEmpty()
    fun gradesLogin(): String = prefs.getString(K_GRADES_LOGIN, "").orEmpty()
    /** Пароль ЛКС и выданный сайтом токен — только в зашифрованных настройках. */
    fun gradesPassword(): String = secure?.getString(K_GRADES_PASSWORD, null).orEmpty()
    fun gradesToken(): String = secure?.getString(K_GRADES_TOKEN, null).orEmpty()
    fun setGrades(url: String, login: String, password: String, token: String) {
        prefs.edit { putString(K_GRADES_URL, url.trim()); putString(K_GRADES_LOGIN, login.trim()) }
        secure?.edit { putString(K_GRADES_PASSWORD, password); putString(K_GRADES_TOKEN, token) }
    }
    fun setGradesToken(token: String) { secure?.edit { putString(K_GRADES_TOKEN, token) } }
    fun clearGrades() {
        prefs.edit { remove(K_GRADES_URL); remove(K_GRADES_LOGIN) }
        secure?.edit { remove(K_GRADES_PASSWORD); remove(K_GRADES_TOKEN) }
    }
    val gradesConfigured: Boolean get() = gradesUrl().isNotEmpty() && gradesToken().isNotEmpty()

    /** ЛКС Сириуса: вход на сайт во встроенном окне (он пускает только с российских адресов — то есть с телефона). */
    fun lksLogin(): String = prefs.getString(K_LKS_LOGIN, "").orEmpty()
    fun lksPassword(): String = secure?.getString(K_LKS_PASSWORD, null).orEmpty()
    /** Адрес страницы успеваемости, которую человек показал один раз. */
    fun lksPage(): String = prefs.getString(K_LKS_PAGE, "").orEmpty()
    fun setLks(login: String, password: String) {
        prefs.edit { putString(K_LKS_LOGIN, login.trim()) }
        secure?.edit { putString(K_LKS_PASSWORD, password) }
    }
    fun setLksPage(url: String) = prefs.edit { putString(K_LKS_PAGE, url) }
    /** Куки сессии ЛКС (зашифрованно) — чтобы не входить при каждом открытии. */
    fun lksCookies(): String = secure?.getString(K_LKS_COOKIES, null).orEmpty()
    fun setLksCookies(v: String) { secure?.edit { putString(K_LKS_COOKIES, v) } }
    fun clearLks() {
        prefs.edit { remove(K_LKS_LOGIN); remove(K_LKS_PAGE) }
        secure?.edit { remove(K_LKS_PASSWORD); remove(K_LKS_COOKIES) }
    }
    /** ЛКС подключён, если есть сессия (вход делает человек в окне сайта — там капча). */
    val lksConfigured: Boolean get() = lksCookies().isNotEmpty()
    /** Пароль ЛКС больше не храним: стереть, если остался от старой версии. */
    fun forgetLksPassword() { if (secure?.contains(K_LKS_PASSWORD) == true) secure?.edit { remove(K_LKS_PASSWORD) } }


    // ---------- устройство ----------
    /** "auto", "phone" или "tablet": как вести себя — с тетрадями для стилуса или без. */
    fun deviceRole(): String = prefs.getString(K_ROLE, "auto").orEmpty()
    fun setDeviceRole(r: String) = prefs.edit { putString(K_ROLE, r) }
    /** Постоянный идентификатор этого устройства для синхронизации. */
    fun deviceId(): String = prefs.getString(K_DEVICE_ID, null) ?: newUid().take(12).also { id -> prefs.edit { putString(K_DEVICE_ID, id) } }

    // ---------- синхронизация ----------
    fun syncUrl(): String = prefs.getString(K_SYNC_URL, "").orEmpty()
    fun syncLogin(): String = prefs.getString(K_SYNC_LOGIN, "").orEmpty()
    /** Пароль облака — только в зашифрованных настройках, как и ключ API. */
    fun syncPassword(): String = (secure?.getString(K_SYNC_PASSWORD, null) ?: memorySyncPassword).orEmpty()
    fun setSync(url: String, login: String, password: String) {
        prefs.edit { putString(K_SYNC_URL, url.trim()); putString(K_SYNC_LOGIN, login.trim()) }
        val s = secure
        if (s != null) s.edit { putString(K_SYNC_PASSWORD, password) } else memorySyncPassword = password
    }
    fun clearSync() {
        prefs.edit { remove(K_SYNC_URL); remove(K_SYNC_LOGIN); remove(K_SYNC_STATUS) }
        secure?.edit { remove(K_SYNC_PASSWORD) }
        memorySyncPassword = null
    }
    val syncConfigured: Boolean get() = syncUrl().isNotEmpty() && syncLogin().isNotEmpty() && syncPassword().isNotEmpty()
    /** Последний итог синхронизации для показа: «время|ok|текст». */
    fun syncStatus(): String = prefs.getString(K_SYNC_STATUS, "").orEmpty()
    fun setSyncStatus(v: String) = prefs.edit { putString(K_SYNC_STATUS, v) }
    /** Когда менялись учебные настройки (группа, экзамены) — чтобы синхронизация видела правку. */
    fun studyStamp(): Long = prefs.getLong(K_STUDY_STAMP, 0L)
    fun touchStudy() = prefs.edit { putLong(K_STUDY_STAMP, System.currentTimeMillis()) }

    private fun openSecure(): SharedPreferences? {
        fun create(): SharedPreferences {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (_: Exception) {
            // Повреждённое хранилище (например, после восстановления из бэкапа) — пересоздаём.
            runCatching {
                context.deleteSharedPreferences(SECURE_PREFS)
                create()
            }.getOrNull()
        }
    }

    companion object {
        const val TARGET_AUTO = "auto"
        const val TARGET_CHOOSER = "chooser"
        const val MIN_ALPHA = 0.3f

        /** Не выше 0.8: иначе Android 12+ блокирует касания, проходящие сквозь окно кнопки. */
        const val MAX_ALPHA = 0.8f

        private const val PREFS = "settings"
        private const val SECURE_PREFS = "secure_settings"
        private const val K_EXAMS = "exams"
        private const val K_GROUP = "schedule_group"
        private const val K_GRADES_URL = "grades_url"
        private const val K_LKS_LOGIN = "lks_login"
        private const val K_LKS_PASSWORD = "lks_password"
        private const val K_LKS_PAGE = "lks_page"
        private const val K_LKS_COOKIES = "lks_cookies"
        private const val K_GRADES_LOGIN = "grades_login"
        private const val K_GRADES_PASSWORD = "grades_password"
        private const val K_GRADES_TOKEN = "grades_token"
        private const val K_LANG_TEACHER = "language_teacher"
        private const val K_LESSON_ALERT = "lesson_alert_min"
        private const val K_QUIET = "quiet_lessons"
        private const val K_NB_MENTOR = "notebook_mentor_on"
        private const val K_TEACHER_PHOTOS = "teacher_photos"
        private const val K_QUIET_RULE = "quiet_rule_id"
        private const val K_ROLE = "device_role"
        private const val K_DEVICE_ID = "device_id"
        private const val K_SYNC_URL = "sync_url"
        private const val K_SYNC_LOGIN = "sync_login"
        private const val K_SYNC_PASSWORD = "sync_password"
        private const val K_SYNC_STATUS = "sync_status"
        private const val K_STUDY_STAMP = "study_stamp"
        private const val K_USAGE = "usage_log"
        private const val K_THEME = "theme_mode"
        private const val K_FAST = "fast_answers"
        private const val K_TIPS = "editor_tips_shown"
        private const val K_RAIL_RIGHT = "rail_right"
        private const val K_RAIL_Y = "rail_y"
        private const val K_LIGHT = "theme_light"
        private const val K_DARK = "theme_dark"
        private const val K_ACCENT = "theme_accent"
        private const val K_BACKDROP = "theme_backdrop"
        private const val K_DESIGN_PARENT = "design_parent_v1"
        private const val K_TEXT_SCALE = "text_scale"
        private const val K_LIMIT = "daily_limit_usd"
        private const val K_PROVIDER = "provider"
        private const val K_OPENAI_BASE = "openai_base_url"
        private const val K_ANTHROPIC_COMPAT_BASE = "anthropic_compat_base_url"
        private const val K_AUTH_MODE = "anthropic_compat_auth"
        private const val K_WATCH_PAUSE = "watch_pause_sec"
        private const val K_NOTE_FADE = "note_fade_alpha"
        private const val K_REMINDER = "reminder_hour"
        private const val K_NOTEBOOK = "see_notebook"
        private const val K_PEN_COLOR = "pen_color"
        private const val K_NOTES_APP = "notes_app"
        private const val K_MISTAKES = "mistakes"
        private const val K_SPEAK = "speak_hints"
        private const val K_FINGER = "finger_draws"
        private const val K_NB_AUTO = "notebook_auto_check"
        const val DEFAULT_REMINDER_HOUR = 18
        private const val K_MODEL = "model"
        private const val K_CLEAN_MODEL = "clean_model"
        private const val K_STRICTNESS = "strictness"
        private const val K_ALPHA = "bubble_alpha"
        private const val K_SIZE = "bubble_size"
        private const val K_SIDE = "bubble_side"
        private const val K_Y = "bubble_y"
        private const val K_TARGET = "send_target"
        private const val K_ONBOARDING = "onboarding_done"
        private const val K_PROJ_EXPLAINED = "projection_explained"
        private const val K_ACTIVE_CONV = "active_conversation"
        private const val K_API_KEY = "api_key"
        private const val K_KEY_STAMP = "api_key_stamp"
        private const val K_DEBUG_BASE_URL = "debug_base_url"
    }
}

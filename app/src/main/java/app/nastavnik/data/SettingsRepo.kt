package app.nastavnik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.nastavnik.ai.AiProvider
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
    val strictness: Strictness,
    val subject: String,
    val bubbleAlpha: Float,
    val bubbleSizeDp: Int,
    val bubbleSide: BubbleSide,
    val bubbleYFraction: Float,
    val attachShotToTyped: Boolean,
    /** "auto" — Touchnotes, если найден; "chooser" — всегда спрашивать; иначе "pkg/activity|action". */
    val sendTarget: String,
    val onboardingDone: Boolean,
    val projectionExplained: Boolean,
    val activeConversationId: Long,
)

/**
 * Настройки приложения. Ключ API лежит отдельно в EncryptedSharedPreferences,
 * всё остальное — в обычных SharedPreferences.
 */
class SettingsRepo(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure: SharedPreferences? by lazy { openSecure() }

    val subjects = listOf(
        "Математический анализ",
        "Линейная алгебра",
        "Математическая логика",
        "Физика",
    )

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
        strictness = runCatching { Strictness.valueOf(prefs.getString(K_STRICTNESS, null)!!) }.getOrDefault(Strictness.HINTS),
        subject = prefs.getString(K_SUBJECT, null) ?: subjects.first(),
        bubbleAlpha = prefs.getFloat(K_ALPHA, 0.7f).coerceIn(MIN_ALPHA, MAX_ALPHA),
        bubbleSizeDp = prefs.getInt(K_SIZE, 52).coerceIn(40, 72),
        bubbleSide = if (prefs.getString(K_SIDE, "RIGHT") == "LEFT") BubbleSide.LEFT else BubbleSide.RIGHT,
        bubbleYFraction = prefs.getFloat(K_Y, 0.35f).coerceIn(0f, 1f),
        attachShotToTyped = prefs.getBoolean(K_ATTACH, true),
        sendTarget = prefs.getString(K_TARGET, TARGET_AUTO) ?: TARGET_AUTO,
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        projectionExplained = prefs.getBoolean(K_PROJ_EXPLAINED, false),
        activeConversationId = prefs.getLong(K_ACTIVE_CONV, 0L),
    )

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
    fun setStrictness(s: Strictness) = prefs.edit { putString(K_STRICTNESS, s.name) }
    fun setSubject(s: String) = prefs.edit { putString(K_SUBJECT, s.trim()) }
    fun setBubbleAlpha(a: Float) = prefs.edit { putFloat(K_ALPHA, a.coerceIn(MIN_ALPHA, MAX_ALPHA)) }
    fun setBubbleSize(dp: Int) = prefs.edit { putInt(K_SIZE, dp.coerceIn(40, 72)) }
    fun setBubbleSide(side: BubbleSide) = prefs.edit { putString(K_SIDE, side.name) }
    fun setBubblePosition(side: BubbleSide, yFraction: Float) = prefs.edit {
        putString(K_SIDE, side.name)
        putFloat(K_Y, yFraction.coerceIn(0f, 1f))
    }
    fun setAttachShot(v: Boolean) = prefs.edit { putBoolean(K_ATTACH, v) }
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
        private const val K_PROVIDER = "provider"
        private const val K_OPENAI_BASE = "openai_base_url"
        private const val K_MODEL = "model"
        private const val K_CLEAN_MODEL = "clean_model"
        private const val K_STRICTNESS = "strictness"
        private const val K_SUBJECT = "subject"
        private const val K_ALPHA = "bubble_alpha"
        private const val K_SIZE = "bubble_size"
        private const val K_SIDE = "bubble_side"
        private const val K_Y = "bubble_y"
        private const val K_ATTACH = "attach_shot"
        private const val K_TARGET = "send_target"
        private const val K_ONBOARDING = "onboarding_done"
        private const val K_PROJ_EXPLAINED = "projection_explained"
        private const val K_ACTIVE_CONV = "active_conversation"
        private const val K_API_KEY = "api_key"
        private const val K_KEY_STAMP = "api_key_stamp"
        private const val K_DEBUG_BASE_URL = "debug_base_url"
    }
}

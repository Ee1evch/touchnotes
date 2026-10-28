package app.nastavnik.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nastavnik.app
import app.nastavnik.overlay.BubbleService

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(SCREEN_HOME)

    /** Экран открыла кнопка поверх Touchnotes — закрыв его, возвращаемся туда, где писали. */
    private var fromOverlay = false

    /** Растёт при каждом onResume — экраны перечитывают состояние разрешений. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Фон всегда светлый — значки строки состояния тёмные и при тёмной теме системы.
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?: screenFrom(intent)
            ?: if (app.settings.load().onboardingDone) SCREEN_HOME else SCREEN_ONBOARDING
        setContent {
            NastavnikTheme {
                val go: (String) -> Unit = {
                    fromOverlay = false
                    screen = it
                }
                val home = {
                    screen = SCREEN_HOME
                    if (fromOverlay) moveTaskToBack(true)
                    fromOverlay = false
                }
                BackHandler(enabled = screen != SCREEN_HOME) { if (screen == SCREEN_CLEAN_TEST) go(SCREEN_CLEAN) else home() }
                when (screen) {
                    SCREEN_ONBOARDING -> OnboardingScreen(resumeTick, onDone = { screen = SCREEN_HOME; moveTaskToBack(true) }, onSkip = { screen = SCREEN_HOME })
                    SCREEN_SETTINGS -> SettingsScreen(onBack = home)
                    SCREEN_HISTORY -> HistoryScreen(onBack = home)
                    SCREEN_CLEAN_TEST -> CleanTestScreen(onBack = { go(SCREEN_CLEAN) })
                    SCREEN_REVIEW -> ReviewScreen(onBack = home, onExample = { go(SCREEN_CLEAN_TEST) })
                    SCREEN_PROGRESS -> ProgressScreen(onBack = home, onReview = { go(SCREEN_REVIEW) })
                    SCREEN_CLEAN -> CleanScreen(
                        // Чистовик открыла кнопка наставника — закрыв его, возвращаемся в Touchnotes.
                        onClose = home,
                        onSettings = { go(SCREEN_SETTINGS) },
                        onExample = { go(SCREEN_CLEAN_TEST) },
                    )
                    else -> HomeScreen(resumeTick, go)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screenFrom(intent)?.let { screen = it }
    }

    /**
     * Какой экран открыть по интенту. Экран, открытый кнопкой поверх Touchnotes, по «Назад» возвращает туда.
     * Ярлык «Запустить наставника» включает его и сразу уходит в фон — к Touchnotes.
     */
    private fun screenFrom(intent: Intent): String? {
        val s = intent.getStringExtra(EXTRA_SCREEN) ?: return null
        fromOverlay = intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)
        if (s == ACTION_START_MENTOR) {
            if (Permissions.overlay(this)) {
                BubbleService.start(this)
                moveTaskToBack(true)
            }
            return SCREEN_HOME
        }
        return s
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HOME = "home"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_HISTORY = "history"
        const val SCREEN_ONBOARDING = "onboarding"
        const val SCREEN_CLEAN_TEST = "clean_test"
        const val SCREEN_CLEAN = "clean"
        const val SCREEN_REVIEW = "review"
        const val SCREEN_PROGRESS = "progress"
        const val EXTRA_FROM_OVERLAY = "from_overlay"

        /** Ярлык на значке приложения: включить наставника. */
        const val ACTION_START_MENTOR = "start"
        private const val KEY_SCREEN = "screen"
    }
}

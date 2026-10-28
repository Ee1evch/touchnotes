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
            ?: intent.getStringExtra(EXTRA_SCREEN)?.also { fromOverlay = true }
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
        intent.getStringExtra(EXTRA_SCREEN)?.let {
            screen = it
            fromOverlay = true
        }
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
        private const val KEY_SCREEN = "screen"
    }
}

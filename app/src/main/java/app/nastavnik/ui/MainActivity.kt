package app.nastavnik.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nastavnik.app

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(SCREEN_HOME)

    /** Растёт при каждом onResume — экраны перечитывают состояние разрешений. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?: intent.getStringExtra(EXTRA_SCREEN)
            ?: if (app.settings.load().onboardingDone) SCREEN_HOME else SCREEN_ONBOARDING
        setContent {
            NastavnikTheme {
                BackHandler(enabled = screen != SCREEN_HOME) { screen = SCREEN_HOME }
                val go: (String) -> Unit = { screen = it }
                when (screen) {
                    SCREEN_ONBOARDING -> OnboardingScreen(resumeTick, onDone = { screen = SCREEN_HOME; moveTaskToBack(true) }, onSkip = { screen = SCREEN_HOME })
                    SCREEN_SETTINGS -> SettingsScreen(onBack = { screen = SCREEN_HOME })
                    SCREEN_HISTORY -> HistoryScreen(onBack = { screen = SCREEN_HOME })
                    SCREEN_CLEAN_TEST -> CleanTestScreen(onBack = { screen = SCREEN_HOME })
                    SCREEN_CLEAN -> CleanScreen(
                        // Закрыли чистовик — возвращаемся туда, где писали (в Touchnotes).
                        onClose = { screen = SCREEN_HOME; moveTaskToBack(true) },
                        onSettings = { screen = SCREEN_SETTINGS },
                    )
                    else -> HomeScreen(resumeTick, go)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SCREEN)?.let { screen = it }
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

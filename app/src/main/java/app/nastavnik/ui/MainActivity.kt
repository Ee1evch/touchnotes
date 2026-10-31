package app.nastavnik.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.nastavnik.app
import app.nastavnik.study.StudyWidget
import kotlinx.coroutines.launch
import app.nastavnik.overlay.BubbleService

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(SCREEN_HOME)

    /** Экран открыла кнопка поверх приложения для записей — закрыв его, возвращаемся туда, где писали. */
    private var fromOverlay = false

    /** Открытая тетрадь (для экрана [SCREEN_NOTEBOOK]). */
    private var notebookId by mutableLongStateOf(0L)

    /** Пока из «Поделиться» готовятся страницы — показываем, что идёт работа. */
    private var importing by mutableStateOf(false)

    /** Растёт при каждом onResume — экраны перечитывают состояние разрешений. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Фон всегда светлый — значки строки состояния тёмные и при тёмной теме системы.
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        notebookId = savedInstanceState?.getLong(KEY_NOTEBOOK) ?: 0L
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?: screenFrom(intent)
            ?: if (app.settings.load().onboardingDone) SCREEN_HOME else SCREEN_ONBOARDING
        if (savedInstanceState == null) receiveShared(intent)

        setContent {
            val prefs by remember { app.settings.flow() }.collectAsStateWithLifecycle(initialValue = null)
            // Тема входит в настройки: поменяли — поток выдаёт новые настройки, и экран сразу перекрашивается.
            val mode = runCatching { ThemeMode.valueOf(prefs?.themeMode ?: app.settings.themeMode()) }.getOrDefault(ThemeMode.LIGHT)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            NastavnikTheme(mode) {
                val go: (String) -> Unit = {
                    fromOverlay = false
                    screen = it
                }
                val home = {
                    screen = SCREEN_HOME
                    if (fromOverlay) moveTaskToBack(true)
                    fromOverlay = false
                }
                val openNotebook: (Long) -> Unit = { id ->
                    notebookId = id
                    go(SCREEN_NOTEBOOK)
                }
                BackHandler(enabled = screen != SCREEN_HOME) { if (screen == SCREEN_CLEAN_TEST) go(SCREEN_CLEAN) else home() }
                if (importing) {
                    ImportingScreen()
                    return@NastavnikTheme
                }
                when (screen) {
                    SCREEN_NOTEBOOKS -> NotebooksScreen(resumeTick, onBack = home, onOpen = openNotebook)
                    SCREEN_NOTEBOOK -> if (notebookId != 0L) {
                        NotebookScreen(
                            notebookId,
                            onBack = { go(SCREEN_NOTEBOOKS) },
                            onCleanReady = { go(SCREEN_CLEAN) },
                            onSettings = { go(SCREEN_SETTINGS) },
                        )
                    } else {
                        NotebooksScreen(resumeTick, onBack = home, onOpen = openNotebook)
                    }
                    SCREEN_TRAINING, SCREEN_EXAMS -> TrainingScreen(
                        initialTab = if (screen == SCREEN_EXAMS) TrainTab.EXAMS else TrainTab.VARIANT,
                        onBack = home,
                        onOpenNotebook = openNotebook,
                        onReview = { go(SCREEN_REVIEW) },
                        onSettings = { go(SCREEN_SETTINGS) },
                    )
                    SCREEN_ONBOARDING -> OnboardingScreen(resumeTick, onDone = { screen = SCREEN_HOME; moveTaskToBack(true) }, onSkip = { screen = SCREEN_HOME })
                    SCREEN_SETTINGS -> SettingsScreen(onBack = home)
                    SCREEN_HISTORY -> HistoryScreen(onBack = home, onCleanReady = { go(SCREEN_CLEAN) })
                    SCREEN_SEARCH -> SearchScreen(
                        onBack = home,
                        onOpenNotebook = openNotebook,
                        onHistory = { go(SCREEN_HISTORY) },
                    )
                    SCREEN_CLEAN_TEST -> CleanTestScreen(onBack = { go(SCREEN_CLEAN) })
                    SCREEN_REVIEW -> ReviewScreen(onBack = home, onExample = { go(SCREEN_CLEAN_TEST) })
                    SCREEN_PROGRESS -> ProgressScreen(onBack = home, onReview = { go(SCREEN_REVIEW) })
                    SCREEN_CLEAN -> CleanScreen(
                        // Чистовик открыла кнопка наставника — закрыв его, возвращаемся к записям.
                        onClose = home,
                        onSettings = { go(SCREEN_SETTINGS) },
                        onExample = { go(SCREEN_CLEAN_TEST) },
                    )
                    else -> HomeScreen(resumeTick, go, openNotebook)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screenFrom(intent)?.let { screen = it }
        receiveShared(intent)
    }

    /**
     * Фото или PDF из любого приложения («Поделиться», «Открыть с помощью») — новая тетрадь:
     * поверх можно писать, а наставник видит все страницы.
     */
    private fun receiveShared(intent: Intent) {
        val uris = sharedUris(intent)
        if (uris.isEmpty()) return
        val type = intent.type ?: uris.firstOrNull()?.let { contentResolver.getType(it) }.orEmpty()
        importing = true
        lifecycleScope.launch {
            val nbs = app.notebooks
            val meta = if (type.contains("pdf")) nbs.importPdf(uris.first()) else nbs.importImages(uris)
            importing = false
            if (meta != null) {
                fromOverlay = false
                notebookId = meta.id
                screen = SCREEN_NOTEBOOK
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }

    /**
     * Какой экран открыть по интенту. Экран, открытый кнопкой поверх записей, по «Назад» возвращает туда.
     * Ярлык «Запустить наставника» включает его и сразу уходит в фон — к записям.
     */
    private fun screenFrom(intent: Intent): String? {
        val s = intent.getStringExtra(EXTRA_SCREEN) ?: return null
        fromOverlay = intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)
        intent.getLongExtra(EXTRA_NOTEBOOK, 0L).takeIf { it != 0L }?.let { notebookId = it }
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
        StudyWidget.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen)
        outState.putLong(KEY_NOTEBOOK, notebookId)
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
        const val SCREEN_NOTEBOOKS = "notebooks"
        const val SCREEN_TRAINING = "training"
        const val SCREEN_SEARCH = "search"
        const val SCREEN_EXAMS = "exams"
        const val SCREEN_NOTEBOOK = "notebook"
        const val EXTRA_NOTEBOOK = "notebook_id"
        const val EXTRA_FROM_OVERLAY = "from_overlay"

        /** Ярлык на значке приложения: включить наставника. */
        const val ACTION_START_MENTOR = "start"
        private const val KEY_SCREEN = "screen"
        private const val KEY_NOTEBOOK = "notebook"
    }
}

/** Пока из присланных фото или PDF готовятся страницы. */
@Composable
private fun ImportingScreen() {
    Box(Modifier.fillMaxSize().paperBackdrop(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Brand.Ink, trackColor = Brand.Ink.copy(alpha = 0.1f))
            Text("Готовлю страницы тетради…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

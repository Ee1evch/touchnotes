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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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

    /** Телефон: открытая вкладка и аудитория, которую показать на карте. */
    private var phoneTab by mutableStateOf(PhoneTab.TODAY)
    private var mapRoom by mutableStateOf<String?>(null)

    /** Растёт при каждом onResume — экраны перечитывают состояние разрешений. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Фон всегда светлый — значки строки состояния тёмные и при тёмной теме системы.
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        notebookId = savedInstanceState?.getLong(KEY_NOTEBOOK) ?: 0L
        savedInstanceState?.getString(KEY_TAB)?.let { t -> PhoneTab.entries.firstOrNull { it.name == t }?.let { phoneTab = it } }
        mapRoom = savedInstanceState?.getString(KEY_ROOM)
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?: screenFrom(intent)
            // Первая настройка — про кнопку поверх записей; телефону она не нужна.
            ?: if (app.settings.load().onboardingDone || !app.isTablet()) SCREEN_HOME else SCREEN_ONBOARDING
        if (savedInstanceState == null) receiveShared(intent)

        setContent {
            val prefs by remember { app.settings.flow() }.collectAsStateWithLifecycle(initialValue = null)
            // Оформление входит в настройки: поменяли — поток выдаёт новые настройки, и экран сразу перекрашивается.
            val st = prefs ?: app.settings.load()
            val appearance = Appearance(
                mode = runCatching { ThemeMode.valueOf(st.themeMode) }.getOrDefault(ThemeMode.LIGHT),
                light = st.lightTheme,
                dark = st.darkTheme,
                accent = st.accent,
                backdrop = runCatching { Backdrop.valueOf(st.backdrop) }.getOrDefault(Backdrop.BLOBS),
                textScale = st.textScale,
            )
            val dark = appearance.resolve(isSystemInDarkTheme()).dark
            LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val tablet = remember(st.deviceRole) { app.isTablet() }
            NastavnikTheme(appearance) {
                val go: (String) -> Unit = { target ->
                    fromOverlay = false
                    // На телефоне расписание и карта — вкладки главного экрана.
                    when {
                        !tablet && target == SCREEN_SCHEDULE -> { phoneTab = PhoneTab.SCHEDULE; screen = SCREEN_HOME }
                        !tablet && target == SCREEN_MAP -> { phoneTab = PhoneTab.MAP; screen = SCREEN_HOME }
                        else -> screen = target
                    }
                }
                val showRoom: (String) -> Unit = { room ->
                    mapRoom = room
                    go(SCREEN_MAP)
                }
                val home = {
                    screen = SCREEN_HOME
                    if (fromOverlay) moveTaskToBack(true)
                    fromOverlay = false
                }
                val openNotebook: (Long) -> Unit = { id ->
                    notebookId = id
                    // Телефон — только просмотр: пишут стилусом на планшете.
                    go(if (tablet) SCREEN_NOTEBOOK else SCREEN_VIEWER)
                }
                BackHandler(enabled = screen != SCREEN_HOME || (!tablet && phoneTab != PhoneTab.TODAY)) {
                    when (screen) {
                        SCREEN_HOME -> phoneTab = PhoneTab.TODAY
                        SCREEN_CLEAN_TEST -> go(SCREEN_CLEAN)
                        SCREEN_APPEARANCE -> go(SCREEN_SETTINGS)
                        SCREEN_VIEWER -> go(SCREEN_NOTEBOOKS)
                        SCREEN_LKS -> go(SCREEN_GRADES)
                        else -> home()
                    }
                }
                if (importing) {
                    ImportingScreen()
                    return@NastavnikTheme
                }
                // Экраны сменяются мягко: новый проявляется и чуть «подъезжает», старый тает.
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // «Сквозное проявление» Material 3: старый экран быстро гаснет, новый проявляется с лёгким приближением.
                        (fadeIn(Motion.enter(300, delay = 60)) + scaleIn(Motion.enter(300, delay = 60), initialScale = 0.96f))
                            .togetherWith(fadeOut(Motion.exit(90)))
                    },
                    label = "screen",
                ) { target ->
                    when (target) {
                        SCREEN_NOTEBOOKS -> NotebooksScreen(resumeTick, onBack = home, onOpen = openNotebook, readOnly = !tablet)
                        SCREEN_VIEWER -> NotebookViewer(notebookId, onBack = { go(SCREEN_NOTEBOOKS) })
                        SCREEN_SCHEDULE -> ScheduleScreen(onBack = home, onRoom = showRoom)
                        SCREEN_MAP -> MapScreen(initialRoom = mapRoom, onBack = home)
                        SCREEN_REMINDERS -> RemindersScreen(onBack = home)
                        SCREEN_GRADES -> GradesScreen(onBack = home, onSettings = { go(SCREEN_SETTINGS) }, onLks = { go(SCREEN_LKS) })
                        SCREEN_LKS -> LksScreen(onBack = { go(SCREEN_GRADES) }, onDone = { go(SCREEN_GRADES) })
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
                            initialTab = if (target == SCREEN_EXAMS) TrainTab.EXAMS else TrainTab.VARIANT,
                            onBack = home,
                            onOpenNotebook = openNotebook,
                            onReview = { go(SCREEN_REVIEW) },
                            onSettings = { go(SCREEN_SETTINGS) },
                        )
                        SCREEN_ONBOARDING -> OnboardingScreen(resumeTick, onDone = { screen = SCREEN_HOME; moveTaskToBack(true) }, onSkip = { screen = SCREEN_HOME })
                        SCREEN_SETTINGS -> SettingsScreen(onBack = home, onAppearance = { go(SCREEN_APPEARANCE) }, onOnboarding = { go(SCREEN_ONBOARDING) }, onLks = { go(SCREEN_LKS) })
                        SCREEN_APPEARANCE -> AppearanceScreen(onBack = { go(SCREEN_SETTINGS) })
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
                        else -> if (tablet) HomeScreen(resumeTick, go, openNotebook, onRoom = showRoom)
                        else PhoneShell(phoneTab, onTab = { phoneTab = it; if (it != PhoneTab.MAP) mapRoom = null }, mapRoom = mapRoom, onMapRoom = showRoom, resumeTick = resumeTick, go = go)
                    }
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
                screen = if (app.isTablet()) SCREEN_NOTEBOOK else SCREEN_VIEWER
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
        intent.getStringExtra(EXTRA_ROOM)?.let { mapRoom = it }
        // Телефон: расписание и карта — вкладки.
        if (!app.isTablet()) when (s) {
            SCREEN_MAP -> { phoneTab = PhoneTab.MAP; return SCREEN_HOME }
            SCREEN_SCHEDULE -> { phoneTab = PhoneTab.SCHEDULE; return SCREEN_HOME }
        }
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
        app.sync.onForeground()
        // Оценки — не чаще раза в час; новые придут уведомлением.
        app.appScope.launch { app.grades.refresh(); app.grades.refreshLks() }
    }

    override fun onStop() {
        super.onStop()
        // Ушли из приложения — отправляем сделанное на другие устройства.
        app.sync.soon(300)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen)
        outState.putLong(KEY_NOTEBOOK, notebookId)
        outState.putString(KEY_TAB, phoneTab.name)
        mapRoom?.let { outState.putString(KEY_ROOM, it) }
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
        const val SCREEN_APPEARANCE = "appearance"
        const val SCREEN_EXAMS = "exams"
        const val SCREEN_NOTEBOOK = "notebook"
        const val SCREEN_SCHEDULE = "schedule"
        const val SCREEN_MAP = "map"
        const val SCREEN_REMINDERS = "reminders"
        const val SCREEN_VIEWER = "viewer"
        const val SCREEN_GRADES = "grades"
        const val SCREEN_LKS = "lks"
        const val EXTRA_ROOM = "room"
        const val EXTRA_NOTEBOOK = "notebook_id"
        const val EXTRA_FROM_OVERLAY = "from_overlay"

        /** Ярлык на значке приложения: включить наставника. */
        const val ACTION_START_MENTOR = "start"
        private const val KEY_SCREEN = "screen"
        private const val KEY_NOTEBOOK = "notebook"
        private const val KEY_TAB = "phone_tab"
        private const val KEY_ROOM = "map_room"
    }
}

/** Пока из присланных фото или PDF готовятся страницы. */
@Composable
private fun ImportingScreen() {
    Box(Modifier.fillMaxSize().paperBackdrop(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Brand.Accent, trackColor = Brand.Accent.copy(alpha = 0.12f))
            Text("Готовлю страницы тетради…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

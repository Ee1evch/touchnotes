package app.nastavnik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app

/**
 * «Наставник · Студент» — лёгкая версия: только расписание и оценки из ЛКС; в настройках — оформление, живое
 * уведомление, «Тихо на парах», группа английского и вход в ЛКС. Без наставника, тетрадей, ДЗ, замка и сирены.
 */
@Composable
fun StudentShell() {
    var tab by rememberSaveable { mutableStateOf(PhoneTab.SCHEDULE) }
    // Поверх вкладок: настройки, оформление, вход в ЛКС.
    var screen by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = screen.isNotEmpty() || tab != PhoneTab.SCHEDULE) {
        screen = when (screen) {
            MainActivity.SCREEN_APPEARANCE -> MainActivity.SCREEN_SETTINGS
            else -> ""
        }
        if (screen.isEmpty() && tab != PhoneTab.SCHEDULE) tab = PhoneTab.SCHEDULE
    }
    AnimatedContent(screen, transitionSpec = { fadeIn(Motion.enter(260)) togetherWith fadeOut(Motion.exit(120)) }, label = "student") { s ->
        when (s) {
            MainActivity.SCREEN_SETTINGS -> StudentSettings(
                onBack = { screen = "" },
                onAppearance = { screen = MainActivity.SCREEN_APPEARANCE },
                onLks = { screen = MainActivity.SCREEN_LKS },
            )
            MainActivity.SCREEN_APPEARANCE -> AppearanceScreen(onBack = { screen = MainActivity.SCREEN_SETTINGS })
            MainActivity.SCREEN_LKS -> LksScreen(onBack = { screen = ""; tab = PhoneTab.GRADES }, onDone = { screen = ""; tab = PhoneTab.GRADES })
            else -> Box(Modifier.fillMaxSize().paperBackdrop()) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 80.dp)) {
                    when (tab) {
                        PhoneTab.GRADES -> GradesScreen(onBack = null, onSettings = { screen = MainActivity.SCREEN_SETTINGS }, onLks = { screen = MainActivity.SCREEN_LKS })
                        else -> ScheduleScreen(onBack = null)
                    }
                }
                StudentTabBar(tab, onTab = { tab = it }, onSettings = { screen = MainActivity.SCREEN_SETTINGS }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** Нижняя панель лёгкой версии: «Расписание», «Оценки» и «Настройки». */
@Composable
private fun StudentTabBar(tab: PhoneTab, onTab: (PhoneTab) -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    TabRowBar(
        items = listOf(
            Triple(PhoneTab.SCHEDULE.title, PhoneTab.SCHEDULE.icon, tab == PhoneTab.SCHEDULE),
            Triple(PhoneTab.GRADES.title, PhoneTab.GRADES.icon, tab == PhoneTab.GRADES),
            Triple("Настройки", R.drawable.ic_tune, false),
        ),
        onClick = { i -> when (i) { 0 -> onTab(PhoneTab.SCHEDULE); 1 -> onTab(PhoneTab.GRADES); else -> onSettings() } },
        modifier = modifier,
    )
}

/** Настройки лёгкой версии: оформление, живое уведомление и «Тихо на парах», английский, оценки. */
@Composable
private fun StudentSettings(onBack: () -> Unit, onAppearance: () -> Unit, onLks: () -> Unit) {
    val context = LocalContext.current
    ScreenScaffold(title = "Настройки", onBack = onBack) {
        SectionCard("Оформление") {
            FieldLabel("Тема, акцент и размер текста", "Светлая и тёмная, ещё восемь тем и цвета из обоев.")
            SoftButton("Открыть оформление", icon = R.drawable.ic_palette_dot, onClick = onAppearance)
        }
        DeviceSection(tablet = false, showRole = false)
        LanguageSection()
        TeacherPhotosSection()
        SectionCard("Оценки") {
            val lks = context.app.settings.lksConfigured
            FieldLabel(if (lks) "Личный кабинет подключён" else "Личный кабинет", "Оценки и пропуски из ЛКС Сириуса — обновляются сами, о новых придёт уведомление.")
            SoftButton(if (lks) "Войти заново" else "Войти в ЛКС", icon = R.drawable.ic_chart, onClick = onLks)
        }
        UpdateSection()
        Text("Наставник · Студент — расписание и оценки. Полная версия с наставником — «Наставник».", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    }
}

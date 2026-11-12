package app.nastavnik.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleNow
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class PhoneTab(val title: String, val icon: Int) {
    TODAY("Сегодня", R.drawable.ic_home),
    SCHEDULE("Расписание", R.drawable.ic_calendar),
    GRADES("Оценки", R.drawable.ic_chart),
    STUDY("Учёба", R.drawable.ic_cards),
}

/**
 * Телефон: только то, что нужно в дороге и между парами — сегодня, расписание, карта кампуса и повторение.
 * Тетради для стилуса, наставник поверх записей и чистовики — на планшете.
 */
@Composable
fun PhoneShell(tab: PhoneTab, onTab: (PhoneTab) -> Unit, resumeTick: Int, go: (String) -> Unit) {
    Box(Modifier.fillMaxSize().paperBackdrop()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                // Вкладки — мягкий сдвиг в сторону перехода с пружинкой и проявление.
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(240, delayMillis = 30)) + slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 12 * dir })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 18 * dir })
            },
            label = "tab",
            modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 80.dp),
        ) { t ->
            when (t) {
                PhoneTab.TODAY -> TodayTab(resumeTick, go, onTab = onTab)
                PhoneTab.SCHEDULE -> ScheduleScreen(onBack = null)
                PhoneTab.GRADES -> GradesScreen(onBack = null, onSettings = { go(MainActivity.SCREEN_SETTINGS) }, onLks = { go(MainActivity.SCREEN_LKS) })
                PhoneTab.STUDY -> StudyTab(resumeTick, go)
            }
        }
        TabBar(tab, onTab, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Нижняя панель, как в приложении для родителя: во всю ширину, цвета карточки; у выбранной вкладки — подложка
 * под значком и цвет акцента.
 */
@Composable
private fun TabBar(tab: PhoneTab, onTab: (PhoneTab) -> Unit, modifier: Modifier) {
    TabRowBar(
        items = PhoneTab.entries.map { Triple(it.title, it.icon, it == tab) },
        onClick = { onTab(PhoneTab.entries[it]) },
        modifier = modifier,
    )
}

/** Панель вкладок: подпись, значок, выбрана ли; [onClick] — номер вкладки. */
@Composable
fun TabRowBar(items: List<Triple<String, Int, Boolean>>, onClick: (Int) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(Brand.Glass).navigationBarsPadding().height(80.dp)) {
        items.forEachIndexed { i, (title, icon, sel) ->
            val fg by animateColorAsState(if (sel) Brand.Accent else Brand.InkSoft, tween(220), label = "tabColor")
            val pill by animateColorAsState(if (sel) Brand.Accent.copy(alpha = 0.14f) else Color.Transparent, tween(220), label = "tabPill")
            val iconScale by animateFloatAsState(if (sel) 1.08f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "tabIconScale")
            val pillWidth by animateDpAsState(if (sel) 56.dp else 44.dp, spring(dampingRatio = 0.75f, stiffness = 450f), label = "pillWidth")
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .pressable(source)
                    .clickable(source, indication = null) { onClick(i) }
                    .semantics { contentDescription = title; selected = sel },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(width = pillWidth, height = 30.dp).clip(RoundedCornerShape(15.dp)).background(pill), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                }
                Text(title, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** «Сегодня»: шапка с тем, что сейчас, и блоки из конструктора (кнопка «Изменить главную»). */
@Composable
private fun TodayTab(resumeTick: Int, go: (String) -> Unit, onTab: (PhoneTab) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val version by app.schedule.version.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now(), resumeTick) { while (true) { value = LocalDateTime.now(); delay(30_000) } }
    val lessons by produceState<List<Lesson>?>(null, version, resumeTick, now.toLocalDate()) { value = app.schedule.day(LocalDate.now()) }
    val sync by app.sync.status.collectAsStateWithLifecycle()
    val hasGroup = app.settings.scheduleGroup().isNotBlank()
    var editing by remember { mutableStateOf(false) }

    ScreenScaffold(title = "", onBack = null) {
        HomeHeader(now, dayStatus(lessons, now, hasGroup)) {
            RoundIconButton(R.drawable.ic_pen, "Изменить главную") { editing = !editing }
            if (app.sync.configured) SyncDot(sync.running, sync.ok) { app.sync.soon(0) }
            RoundIconButton(R.drawable.ic_tune, "Настройки") { go(MainActivity.SCREEN_SETTINGS) }
        }
        HomeBoard(
            tablet = false,
            editing = editing,
            onDoneEditing = { editing = false },
            resumeTick = resumeTick,
            go = go,
            onSchedule = { onTab(PhoneTab.SCHEDULE) },
        )
    }
}

/** Число, которое «докручивается» до значения. */
@Composable
fun AnimatedCount(n: Int) {
    val shown by animateFloatAsState(n.toFloat(), tween(700), label = "count")
    Text(shown.toInt().toString(), style = MaterialTheme.typography.displaySmall)
}

/** Точка синхронизации: дышит, пока идёт; зелёная — всё хорошо, красная — ошибка. */
@Composable
private fun SyncDot(running: Boolean, ok: Boolean, onClick: () -> Unit) {
    val pulse by animateFloatAsState(if (running) 1.4f else 1f, spring(dampingRatio = 0.3f, stiffness = 120f), label = "syncPulse")
    Box(Modifier.size(44.dp).glass(CircleShape, 6.dp, strong = true).clickable(onClick = onClick).semantics { contentDescription = "Синхронизировать" }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_cloud), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Box(
            Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(CircleShape).background(if (running) Brand.Accent else if (ok) Brand.Ok else Brand.Error),
        )
    }
}

/** «Учёба»: повторение, прогресс, напоминания и конспекты с планшета. */
@Composable
private fun StudyTab(resumeTick: Int, go: (String) -> Unit) {
    val context = LocalContext.current
    val nowMs = remember(resumeTick) { System.currentTimeMillis() }
    val due by remember(nowMs) { context.app.db.cards().observeDueCount(nowMs) }.collectAsStateWithLifecycle(initialValue = 0)
    val total by remember { context.app.db.cards().observeCount() }.collectAsStateWithLifecycle(initialValue = 0)
    ScreenScaffold(title = "Учёба", onBack = null) {
        Column(
            Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(26.dp), 12.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Карточки", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AnimatedCount(due)
                    Text("на сегодня из $total", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                }
                InkButton(if (due > 0) "Повторить" else "Открыть", icon = R.drawable.ic_cards) { go(MainActivity.SCREEN_REVIEW) }
            }
        }
        // Сгруппированный список, как в настройках Android 16–17.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val rows = listOf(
                Triple(R.drawable.ic_pen, "Домашка", "ДЗ до 100%: решаешь в тетради, наставник проверяет") to MainActivity.SCREEN_HOMEWORK,
                Triple(R.drawable.ic_chart, "Успеваемость", "Оценки из личного кабинета") to MainActivity.SCREEN_GRADES,
                Triple(R.drawable.ic_chart, "Прогресс", "Активность и темы с ошибками") to MainActivity.SCREEN_PROGRESS,
                Triple(R.drawable.ic_notebook, "Конспекты", "Тетради с планшета — посмотреть") to MainActivity.SCREEN_NOTEBOOKS,
                Triple(R.drawable.ic_history, "Чистовики", "PDF аккуратных конспектов") to MainActivity.SCREEN_HISTORY,
            )
            rows.forEachIndexed { i, (spec, screen) ->
                // Плашка «проседает» под пальцем с пружинкой.
                val src = remember(screen) { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().enter().pressable(src).glass(groupShape(i, rows.size), 8.dp)
                        .clickable(src, indication = null) { go(screen) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(spec.first, 40)
                    Column(Modifier.weight(1f)) {
                        Text(spec.second, style = MaterialTheme.typography.titleSmall)
                        Text(spec.third, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


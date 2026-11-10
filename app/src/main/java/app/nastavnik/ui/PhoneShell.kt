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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    MAP("Карта", R.drawable.ic_map),
    STUDY("Учёба", R.drawable.ic_cards),
}

/**
 * Телефон: только то, что нужно в дороге и между парами — сегодня, расписание, карта кампуса и повторение.
 * Тетради для стилуса, наставник поверх записей и чистовики — на планшете.
 */
@Composable
fun PhoneShell(tab: PhoneTab, onTab: (PhoneTab) -> Unit, mapRoom: String?, onMapRoom: (String) -> Unit, resumeTick: Int, go: (String) -> Unit) {
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
            modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
        ) { t ->
            when (t) {
                PhoneTab.TODAY -> TodayTab(resumeTick, go, onRoom = onMapRoom, onTab = onTab)
                PhoneTab.SCHEDULE -> ScheduleScreen(onBack = null, onRoom = onMapRoom)
                PhoneTab.MAP -> MapScreen(initialRoom = mapRoom, onBack = null)
                PhoneTab.STUDY -> StudyTab(resumeTick, go)
            }
        }
        TabBar(tab, onTab, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Нижняя панель: округлая стеклянная «пилюля», парящая над краем экрана. Подсветка выбранной вкладки
 * переезжает с пружинкой, значок мягко подпрыгивает.
 */
@Composable
private fun TabBar(tab: PhoneTab, onTab: (PhoneTab) -> Unit, modifier: Modifier) {
    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).height(66.dp)
            .glass(RoundedCornerShape(33.dp), 18.dp, strong = true),
    ) {
        val cell = maxWidth / PhoneTab.entries.size
        val x by animateDpAsState(cell * tab.ordinal, spring(dampingRatio = 0.68f, stiffness = 420f), label = "tabX")
        Box(Modifier.offset(x = x).width(cell).height(66.dp).padding(6.dp).clip(RoundedCornerShape(27.dp)).background(Brand.Accent.copy(alpha = 0.16f)))
        Row(Modifier.fillMaxSize()) {
            PhoneTab.entries.forEach { t ->
                val sel = t == tab
                val k by animateFloatAsState(if (sel) 1.14f else 1f, spring(dampingRatio = 0.42f, stiffness = 650f), label = "tabIcon")
                val lift by animateDpAsState(if (sel) (-1).dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = 600f), label = "tabLift")
                val fg by animateColorAsState(if (sel) Brand.Accent else Brand.InkSoft, tween(220), label = "tabColor")
                Column(
                    Modifier.width(cell).fillMaxSize().clip(RoundedCornerShape(27.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onTab(t) }
                        .semantics { contentDescription = t.title; selected = sel },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(t.icon), contentDescription = null, tint = fg, modifier = Modifier.offset(y = lift).size(22.dp).graphicsLayer { scaleX = k; scaleY = k })
                    Text(t.title, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** «Сегодня»: что сейчас и что дальше по расписанию, напоминания, карточки и серия. */
@Composable
private fun TodayTab(resumeTick: Int, go: (String) -> Unit, onRoom: (String) -> Unit, onTab: (PhoneTab) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val version by app.schedule.version.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now(), resumeTick) { while (true) { value = LocalDateTime.now(); delay(30_000) } }
    val lessons by produceState<List<Lesson>?>(null, version, resumeTick, now.toLocalDate()) { value = app.schedule.day(LocalDate.now()) }
    val tomorrow by produceState<List<Lesson>>(emptyList(), version, resumeTick) { value = app.schedule.day(LocalDate.now().plusDays(1)) }
    val nowMs = remember(resumeTick) { System.currentTimeMillis() }
    val due by remember(nowMs) { app.db.cards().observeDueCount(nowMs) }.collectAsStateWithLifecycle(initialValue = 0)
    val sync by app.sync.status.collectAsStateWithLifecycle()

    ScreenScaffold(title = "", onBack = null) {
        // Шапка: приветствие, дата, синхронизация и настройки.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                Text(when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" }, style = MaterialTheme.typography.headlineMedium)
                Text(
                    SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru")).format(Date()).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
                )
            }
            if (app.sync.configured) SyncDot(sync.running, sync.ok) { app.sync.soon(0) }
            Spacer(Modifier.width(10.dp))
            RoundIconButton(R.drawable.ic_tune, "Настройки") { go(MainActivity.SCREEN_SETTINGS) }
        }

        NowCard(lessons, tomorrow, now, hasGroup = app.settings.scheduleGroup().isNotBlank(), onRoom = onRoom, onSchedule = { onTab(PhoneTab.SCHEDULE) })

        GradesCard(onOpen = { go(MainActivity.SCREEN_GRADES) })

        Row(
            Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(24.dp), 10.dp).clickable { go(MainActivity.SCREEN_REVIEW) }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                if (due > 0) {
                    AnimatedCount(due)
                    Text(ruPlural(due, "карточка ждёт", "карточки ждут", "карточек ждут"), style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                } else {
                    Text("Всё повторено", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (due > 0) InkButton("Повторить", icon = R.drawable.ic_cards) { go(MainActivity.SCREEN_REVIEW) }
        }
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

/** Что сейчас: идущая пара, следующая, или «на сегодня всё» с первой парой завтра. */
@Composable
internal fun NowCard(lessons: List<Lesson>?, tomorrow: List<Lesson>, now: LocalDateTime, hasGroup: Boolean, onRoom: (String) -> Unit, onSchedule: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(26.dp), 12.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasGroup) {
            Text("Расписание", style = MaterialTheme.typography.titleMedium)
            Text("Выбери свою группу — пары появятся здесь, а перед каждой придёт уведомление с маршрутом до аудитории.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            InkButton("Выбрать группу", icon = R.drawable.ic_calendar, onClick = onSchedule)
            return@Column
        }
        val state = remember(lessons, now) { ScheduleNow.at(lessons.orEmpty(), now) }
        val title = when {
            lessons == null -> "Загружаю…"
            state.current != null -> "Сейчас"
            state.next != null -> "Дальше · через ${durationText(state.minutesToNext ?: 0)}"
            lessons.isEmpty() -> "Сегодня пар нет"
            else -> "На сегодня всё"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LinkText("Неделя", onClick = onSchedule)
        }
        state.current?.let { LessonCard(it, now, compact = true, onRoom = onRoom) }
        state.next?.let { n ->
            if (state.current != null) Text("Потом · ${n.start}", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft, modifier = Modifier.padding(start = 4.dp))
            LessonCard(n, now, index = 1, compact = true, onRoom = onRoom)
        }
        if (lessons != null && state.current == null && state.next == null) {
            tomorrow.firstOrNull()?.let { f ->
                Text("Завтра первая — в ${f.start}", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft, modifier = Modifier.padding(start = 4.dp))
                LessonCard(f, now, compact = true, onRoom = onRoom)
            }
        }
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


package app.nastavnik.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.study.Alarms
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ru = Locale.forLanguageTag("ru")

/** Цвет полоски по типу занятия — чтобы лекцию от практики отличать взглядом. */
fun lessonColor(type: String): Color = when (type) {
    "Лекция" -> Color(0xFF4C7DF0)
    "Практика" -> Color(0xFF1FA463)
    "Семинар" -> Color(0xFFE08A1E)
    "Лабораторная" -> Color(0xFF9B5DE5)
    else -> Color(0xFF7C8596)
}

/**
 * Расписание группы: неделя полоской дней, пары карточками, текущая — с полосой «сколько осталось»,
 * между парами — окна. Аудитория открывает маршрут на карте. Работает и без сети — из кеша.
 */
@Composable
fun ScheduleScreen(onBack: (() -> Unit)?, onRoom: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(app.settings.scheduleGroup()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(group.isBlank()) }
    val version by app.schedule.version.collectAsStateWithLifecycle()
    val monday = ScheduleText.monday(day)
    val week by produceState<List<Lesson>?>(null, group, monday, version) { value = app.schedule.cached(monday)?.lessons ?: emptyList() }

    // Каждую минуту — пересчитать «идёт сейчас».
    val now by produceState(LocalDateTime.now()) { while (true) { delay(30_000); value = LocalDateTime.now() } }

    fun refresh(force: Boolean) {
        if (group.isBlank()) return
        scope.launch {
            if (!force && !app.schedule.stale(monday)) return@launch
            loading = true
            error = null
            runCatching { app.schedule.refresh(monday, if (monday == ScheduleText.monday(LocalDate.now())) 2 else 1) }
                .onFailure { error = it.message ?: "Не удалось обновить расписание" }
                .onSuccess { Alarms.rescheduleAll(context) }
            loading = false
        }
    }
    LaunchedEffect(group, monday) { refresh(force = false) }

    if (picking) {
        GroupPicker(
            current = group,
            onBack = if (group.isNotBlank()) ({ picking = false }) else onBack,
            onPick = { g ->
                app.settings.setScheduleGroup(g)
                group = g
                picking = false
                app.sync.soon()
                refresh(force = true)
            },
        )
        return
    }

    ScreenScaffold(title = "", onBack = null, scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
            Column(Modifier.weight(1f)) {
                Text("Расписание", style = MaterialTheme.typography.headlineSmall)
                Text(group, style = MaterialTheme.typography.bodyMedium, color = Brand.Accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { picking = true })
            }
            RefreshButton(loading) { refresh(force = true) }
        }

        WeekStrip(day, week.orEmpty(), onPick = { day = it }, onShift = { day = day.plusWeeks(it.toLong()) })

        AnimatedVisibility(error != null) {
            StatusLine(error.orEmpty(), Tone.ERROR)
        }

        AnimatedContent(
            targetState = day,
            transitionSpec = {
                val dir = if (targetState.isAfter(initialState)) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { it / 5 * dir } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 6 * dir } + fadeOut(tween(160)))
            },
            label = "day",
            modifier = Modifier.pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragEnd = {
                    if (acc < -80) day = day.plusDays(1) else if (acc > 80) day = day.minusDays(1)
                    acc = 0f
                }) { _, d -> acc += d }
            },
        ) { d ->
            val lessons = week?.filter { it.date == d.toString() }?.sortedBy { it.start }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                when {
                    lessons == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
                    lessons.isEmpty() -> EmptyDay(loading)
                    else -> lessons.forEachIndexed { i, l ->
                        if (i > 0) Gap(lessons[i - 1], l)
                        LessonCard(l, now, index = i, onRoom = onRoom)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LinkText("Сменить группу", onClick = { picking = true })
        Text(
            "Расписание — с сайта schedule.siriusuniversity.ru (на основе Lecturial 2). Неофициально; сверяйся с сайтом при сомнениях.",
            style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
        )
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.size(44.dp).glass(CircleShape, 6.dp, strong = true).clickable(enabled = !loading, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_refresh), contentDescription = "Обновить расписание", tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (loading) angle else 0f },
        )
    }
}

/** Неделя: семь дней, выбранный — на скользящей подложке, под днями с парами — точка. */
@Composable
private fun WeekStrip(day: LocalDate, lessons: List<Lesson>, onPick: (LocalDate) -> Unit, onShift: (Int) -> Unit) {
    val monday = ScheduleText.monday(day)
    val today = LocalDate.now()
    val busy = lessons.map { it.date }.toSet()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_chevron_left), "Прошлая неделя", tint = Brand.InkSoft, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onShift(-1) }.padding(4.dp))
        BoxWithConstraints(Modifier.weight(1f).glass(RoundedCornerShape(22.dp), 8.dp)) {
            val cell = maxWidth / 7
            val idx = (day.dayOfWeek.value - 1)
            val x by androidx.compose.animation.core.animateDpAsState(cell * idx, spring(dampingRatio = 0.7f, stiffness = 520f), label = "dayX")
            Box(Modifier.offset(x = x).width(cell).height(64.dp).padding(4.dp).clip(RoundedCornerShape(18.dp)).background(Brand.Accent))
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { i ->
                    val d = monday.plusDays(i.toLong())
                    val sel = d == day
                    Column(
                        Modifier.width(cell).height(64.dp).clip(RoundedCornerShape(18.dp)).clickable { onPick(d) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val fg = if (sel) Brand.OnAccent else if (d == today) Brand.Accent else MaterialTheme.colorScheme.onSurface
                        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, ru).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                        Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = fg)
                        Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (d.toString() in busy) (if (sel) Brand.OnAccent else Brand.Accent) else Color.Transparent))
                    }
                }
            }
        }
        Icon(painterResource(R.drawable.ic_chevron), "Следующая неделя", tint = Brand.InkSoft, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onShift(1) }.padding(4.dp))
    }
}

@Composable
private fun EmptyDay(loading: Boolean) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), 8.dp).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (loading) "Загружаю…" else "Пар нет", style = MaterialTheme.typography.titleMedium)
        if (!loading) Text("Можно повторить карточки или отдохнуть", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
    }
}

/** Окно между парами — если больше 20 минут. */
@Composable
private fun Gap(prev: Lesson, next: Lesson) {
    val a = prev.endTime ?: return
    val b = next.startTime ?: return
    val min = Duration.between(a, b).toMinutes()
    if (min < 20) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(Brand.Line))
        Text("окно ${durationText(min)}", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft)
        Box(Modifier.weight(1f).height(1.dp).background(Brand.Line))
    }
}

fun durationText(min: Long): String = when {
    min < 60 -> "$min мин"
    min % 60 == 0L -> "${min / 60} ч"
    else -> "${min / 60} ч ${min % 60} мин"
}

/** Карточка пары; появляется с лёгким каскадом. Идущая сейчас — с полосой прогресса. */
@Composable
fun LessonCard(l: Lesson, now: LocalDateTime, index: Int = 0, compact: Boolean = false, onRoom: (String) -> Unit) {
    val start = l.startsAt()
    val end = l.endsAt() ?: start?.plusMinutes(90)
    val live = start != null && end != null && !now.isBefore(start) && now.isBefore(end)
    val past = end != null && !now.isBefore(end)
    var shown by remember(l.id) { mutableStateOf(false) }
    LaunchedEffect(l.id) { delay(40L * index); shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "appear")
    val color = lessonColor(l.type)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear * (if (past) 0.62f else 1f); translationY = (1 - appear) * 24f }
            .glass(RoundedCornerShape(22.dp), if (live) 14.dp else 8.dp, strong = live)
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.End) {
            Text(l.start, style = MaterialTheme.typography.titleSmall)
            Text(l.end, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        }
        Box(Modifier.width(4.dp).height(if (compact) 44.dp else 58.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(l.discipline, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (l.type.isNotBlank()) Text(l.type, style = MaterialTheme.typography.labelMedium, color = color)
                l.classroom?.let { room ->
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { onRoom(room) }.background(Brand.Accent.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_location), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(13.dp))
                        Text(room, style = MaterialTheme.typography.labelMedium, color = Brand.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!compact) l.teachersShort?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (live && start != null && end != null) {
                val total = Duration.between(start, end).toMinutes().coerceAtLeast(1)
                val passed = Duration.between(start, now).toMinutes().coerceIn(0, total)
                val p by animateFloatAsState(passed.toFloat() / total, tween(800), label = "progress")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Brand.Line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                    Text("ещё ${durationText(total - passed)}", style = MaterialTheme.typography.labelSmall, color = Brand.InkSoft)
                }
            }
        }
    }
}

/** Выбор группы: поиск на сайте расписания (терпит «иоп ит» вместо «ИОП-ИТ»). */
@Composable
private fun GroupPicker(current: String, onBack: (() -> Unit)?, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(current) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        delay(450)
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        loading = true
        error = null
        runCatching { context.app.schedule.groups(query) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: "Сайт расписания недоступен" }
        loading = false
    }
    ScreenScaffold(title = "Твоя группа", onBack = onBack) {
        Text(
            "Начни вводить название, как на сайте расписания Сириуса: например, «ИОП-ИТ-24». Группа сохранится и на других твоих устройствах.",
            style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
        )
        AppTextField(query, { query = it }, label = "Группа", placeholder = "ИОП-ИТ-24/1")
        if (loading) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally), color = Brand.Accent, strokeWidth = 2.dp)
        error?.let { StatusLine(it, Tone.ERROR) }
        if (results.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 8.dp)) {
                results.take(40).forEach { g ->
                    Text(
                        g, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(g) }.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            }
        } else if (!loading && query.isNotBlank() && error == null) {
            Text("Такой группы не нашлось", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        }
    }
}

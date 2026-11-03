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
import app.nastavnik.study.ruPlural
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
 * между парами — окна. Работает и без сети — из кеша.
 */
@Composable
fun ScheduleScreen(onBack: (() -> Unit)?) {
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

    val askNotifications = rememberAskNotifications()
    if (picking) {
        GroupPicker(
            current = group,
            onBack = if (group.isNotBlank()) ({ picking = false }) else onBack,
            onPick = { g ->
                app.settings.setScheduleGroup(g)
                group = g
                askNotifications()
                picking = false
                app.sync.soon()
                refresh(force = true)
            },
        )
        return
    }

    ScreenScaffold(title = "", onBack = null, scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Расписание", style = MaterialTheme.typography.headlineMedium)
                Text(group, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { picking = true })
            }
            RefreshButton(loading) { refresh(force = true) }
            RoundIconButton(R.drawable.ic_tune, "Сменить группу") { picking = true }
        }

        WeekStrip(day, week.orEmpty(), onPick = { day = it }, onShift = { day = day.plusWeeks(it.toLong()) })

        AnimatedVisibility(error != null) {
            StatusLine(error.orEmpty(), Tone.ERROR)
        }

        AnimatedContent(
            targetState = day,
            transitionSpec = {
                val dir = if (targetState.isAfter(initialState)) 1 else -1
                (slideInHorizontally(Motion.enter(340)) { it / 12 * dir } + fadeIn(Motion.enter(260)))
                    .togetherWith(slideOutHorizontally(Motion.exit(160)) { -it / 16 * dir } + fadeOut(Motion.exit(120)))
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
                Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge)
                    if (!lessons.isNullOrEmpty()) Text(
                        "${lessons.size} ${ruPlural(lessons.size, "пара", "пары", "пар")} · ${lessons.first().start}–${lessons.last().end}",
                        style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                    )
                }
                when {
                    lessons == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
                    lessons.isEmpty() -> EmptyDay(loading)
                    else -> lessons.forEachIndexed { i, l ->
                        if (i > 0) Gap(lessons[i - 1], l)
                        LessonCard(l, now, index = i)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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

/** Неделя: «‹ 2 нояб. — 8 нояб. ›» и семь плиток дней, выбранный — цветом акцента, под днями с парами — точка. */
@Composable
private fun WeekStrip(day: LocalDate, lessons: List<Lesson>, onPick: (LocalDate) -> Unit, onShift: (Int) -> Unit) {
    val monday = ScheduleText.monday(day)
    val today = LocalDate.now()
    val busy = lessons.map { it.date }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(R.drawable.ic_chevron_left, "Прошлая неделя") { onShift(-1) }
            val end = monday.plusDays(6)
            Text(
                "${monday.dayOfMonth} ${monday.month.getDisplayName(TextStyle.SHORT, ru)} — ${end.dayOfMonth} ${end.month.getDisplayName(TextStyle.SHORT, ru)}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            RoundIconButton(R.drawable.ic_chevron, "Следующая неделя") { onShift(1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..6).forEach { i ->
                val d = monday.plusDays(i.toLong())
                val sel = d == day
                val bg by androidx.compose.animation.animateColorAsState(if (sel) Brand.Accent else Brand.Glass, Motion.enter(220), label = "dayBg")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg).clickable { onPick(d) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fg = if (sel) Brand.OnAccent else MaterialTheme.colorScheme.onSurface
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, ru).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    Text(
                        d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = fg,
                        fontWeight = if (d == today) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (d.toString() in busy) (if (sel) Brand.OnAccent else Brand.Accent) else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun EmptyDay(loading: Boolean) {
    Text(
        if (loading) "Загружаю расписание…" else "Пар нет — выходной.",
        style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(20.dp),
    )
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

/**
 * Карточка пары, как в приложении родителя: время слева, полоса цвета типа во всю высоту, предмет, тип, аудитория
 * в «таблетке», преподаватель; идущая — «Идёт сейчас · ещё N мин» и полоса. Появляется с лёгким каскадом.
 */
@Composable
fun LessonCard(l: Lesson, now: LocalDateTime, index: Int = 0, compact: Boolean = false) {
    val start = l.startsAt()
    val end = l.endsAt() ?: start?.plusMinutes(90)
    val live = start != null && end != null && !now.isBefore(start) && now.isBefore(end)
    var shown by remember(l.id) { mutableStateOf(false) }
    LaunchedEffect(l.id) { delay(30L * index); shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, Motion.enter(380), label = "appear")
    val color = lessonColor(l.type)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear; translationY = (1 - appear) * 10.dp.toPx() }
            .glass(RoundedCornerShape(24.dp))
            .padding(16.dp),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Column(Modifier.widthIn(min = 48.dp), horizontalAlignment = Alignment.End) {
                Text(l.start, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(l.end, style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            }
            Box(Modifier.padding(horizontal = 14.dp).width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(l.discipline, style = MaterialTheme.typography.titleMedium, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (l.type.isNotBlank()) Text(l.type, style = MaterialTheme.typography.bodyMedium, color = color)
                    val place = l.classroom?.takeIf { it.isNotBlank() } ?: if (!l.online.isNullOrBlank()) "Онлайн" else null
                    if (place != null) Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_location), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(place, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!compact) l.teachersShort?.let {
                    // Фото преподавателя — только если включено в настройках и фото есть.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TeacherAvatar(l.teachers, 28.dp)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (live && start != null && end != null) {
            val total = Duration.between(start, end).toMinutes().coerceAtLeast(1)
            val passed = Duration.between(start, now).toMinutes().coerceIn(0, total)
            val p by animateFloatAsState(passed.toFloat() / total, tween(800), label = "progress")
            Spacer(Modifier.height(12.dp))
            Text("Идёт сейчас · ещё ${durationText(total - passed)}", style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(2.dp)).background(color))
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


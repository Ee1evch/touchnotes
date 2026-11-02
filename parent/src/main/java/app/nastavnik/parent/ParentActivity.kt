package app.nastavnik.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.grades.LksEntry
import app.nastavnik.grades.LksLesson
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** «Наставник · Родитель»: две вкладки — расписание с посещаемостью и успеваемость; настройки — токен, зачётка. */
class ParentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ParentTheme { ParentRoot() } }
    }
}

private val ru = Locale.forLanguageTag("ru")

private enum class Tab(val label: String, val icon: Int) {
    SCHEDULE("Расписание", R.drawable.ic_calendar),
    GRADES("Успеваемость", R.drawable.ic_chart),
}

@Composable
private fun ParentRoot() {
    val context = LocalContext.current
    val repo = remember { ParentRepo.get(context) }
    val state by repo.state.collectAsStateWithLifecycle()
    var configured by remember { mutableStateOf(repo.configured) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.SCHEDULE) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configured) {
        repo.load()
        // Обновляем сами, если снимок старше 15 минут (без сети остаётся последний).
        val at = repo.state.value.student?.loadedAt ?: 0
        if (configured && System.currentTimeMillis() - at > 15 * 60_000L) repo.refresh()
    }

    if (!configured || settings) {
        BackHandler(enabled = configured) { settings = false }
        SettingsScreen(
            repo = repo,
            first = !configured,
            onDone = { configured = true; settings = false; scope.launch { repo.refresh() } },
            onBack = if (configured) ({ settings = false }) else null,
            onDisconnect = { repo.disconnect(); configured = false; settings = false },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), null, Modifier.size(22.dp)) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
            Header(
                title = tab.label,
                subtitle = listOfNotNull(
                    repo.group(state.student).takeIf { it.isNotBlank() },
                    state.student?.loadedAt?.takeIf { it > 0 }?.let { "обновлено ${updated(it)}" },
                ).joinToString(" · "),
                loading = state.loading,
                onRefresh = { scope.launch { repo.refresh() } },
                onSettings = { settings = true },
            )
            state.error?.let { ErrorLine(it) }
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.SCHEDULE -> ScheduleTab(repo, state)
                    Tab.GRADES -> GradesTab(state)
                }
            }
        }
    }
}

private fun updated(ms: Long): String {
    val t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
    val time = "%02d:%02d".format(t.hour, t.minute)
    return if (t.toLocalDate() == LocalDate.now()) "в $time" else "${t.dayOfMonth} ${t.month.getDisplayName(JTextStyle.SHORT, ru)}, $time"
}

@Composable
private fun Header(title: String, subtitle: String, loading: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundButton(R.drawable.ic_refresh, "Обновить", enabled = !loading, onClick = onRefresh) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(R.drawable.ic_tune, "Настройки", onClick = onSettings)
    }
}

@Composable
private fun RoundButton(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit, overlay: (@Composable () -> Unit)? = null) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (overlay != null && !enabled) overlay()
        else Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = Tone.Bad,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(Tone.Bad.copy(alpha = 0.1f)).padding(12.dp),
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) { content() }
}

// ———————————————————————— Расписание ————————————————————————

@Composable
private fun ScheduleTab(repo: ParentRepo, state: ParentRepo.State) {
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(day)
    val monday = ScheduleText.monday(date)
    val lessons = repo.day(date, state)
    val journal = state.student?.journal
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { WeekStrip(monday, date, state) { day = it.toString() } }
        item { DayTitle(date, lessons, journal) }
        if (repo.group(state.student).isBlank()) {
            item { Hint("Группа не известна: включи синхронизацию в «Наставнике» студента или впиши группу в настройках.") }
        } else if (state.weeks[monday.toString()] == null) {
            item { Hint(if (state.loading) "Загружаю расписание…" else "Этой недели нет в снимке — обнови, когда будет интернет.") }
        } else if (lessons.isEmpty()) {
            item { Hint("Пар нет — выходной.") }
        }
        items(lessons) { l -> LessonCard(l, ParentData.entryFor(l, journal)) }
    }
}

@Composable
private fun WeekStrip(monday: LocalDate, selected: LocalDate, state: ParentRepo.State, onPick: (LocalDate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_chevron_left, "Прошлая неделя", onClick = { onPick(selected.minusWeeks(1)) })
            val end = monday.plusDays(6)
            Text(
                "${monday.dayOfMonth} ${monday.month.getDisplayName(JTextStyle.SHORT, ru)} — ${end.dayOfMonth} ${end.month.getDisplayName(JTextStyle.SHORT, ru)}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            RoundButton(R.drawable.ic_chevron, "Следующая неделя", onClick = { onPick(selected.plusWeeks(1)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0L..6L).forEach { i ->
                val d = monday.plusDays(i)
                val on = d == selected
                val today = d == LocalDate.now()
                val has = state.weeks[monday.toString()]?.on(d)?.isNotEmpty() == true
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .clickable { onPick(d) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    Text(d.dayOfWeek.getDisplayName(JTextStyle.SHORT, ru).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = if (today) FontWeight.Bold else FontWeight.SemiBold)
                    Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (has) (if (on) fg else Tone.Accent) else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun DayTitle(date: LocalDate, lessons: List<Lesson>, journal: app.nastavnik.grades.LksJournal?) {
    val name = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)).replaceFirstChar { it.uppercase() }
    val missed = lessons.count { ParentData.entryFor(it, journal)?.absent == true }
    val sub = buildList {
        if (lessons.isNotEmpty()) add("${lessons.size} ${plural(lessons.size, "пара", "пары", "пар")} · ${lessons.first().start}–${lessons.last().end}")
        if (missed > 0) add("пропущено: $missed")
    }.joinToString(" · ")
    Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (missed > 0) Tone.Bad else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Card { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
}

/** Карточка пары: время слева, полоса цвета типа, предмет, тип, аудитория, преподаватель; внизу — посещаемость и оценки. */
@Composable
private fun LessonCard(l: Lesson, entry: LksEntry?) {
    val tint = Tone.lesson(l.type)
    val now = LocalDateTime.now()
    val start = l.startsAt()
    val end = l.endsAt() ?: start?.plusMinutes(90)
    val live = start != null && end != null && !now.isBefore(start) && now.isBefore(end)
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Column(Modifier.widthIn(min = 48.dp), horizontalAlignment = Alignment.End) {
                    Text(l.start, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(l.end, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.padding(horizontal = 14.dp).width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(tint))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(l.discipline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (l.type.isNotBlank()) Text(l.type, style = MaterialTheme.typography.bodyMedium, color = tint)
                        val place = l.classroom?.takeIf { it.isNotBlank() } ?: if (!l.online.isNullOrBlank()) "Онлайн" else null
                        if (place != null) Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_location), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(5.dp))
                            Text(place, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    l.teachersShort?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (live && start != null && end != null) {
                val total = Duration.between(start, end).toMinutes().coerceAtLeast(1)
                val passed = Duration.between(start, now).toMinutes().coerceIn(0, total)
                Spacer(Modifier.height(12.dp))
                Text("Идёт сейчас · ещё ${total - passed} мин", style = MaterialTheme.typography.labelMedium, color = tint)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { passed.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = tint, trackColor = tint.copy(alpha = 0.18f), drawStopIndicator = {},
                )
            }
            if (entry != null && (entry.absent || entry.marks.isNotEmpty())) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.absent) Badge(if (entry.absentValid == true) "Пропуск · уважительная" else "Пропуск", if (entry.absentValid == true) Tone.Warn else Tone.Bad)
                    else Badge("Был", Tone.Ok)
                    entry.marks.forEach { m -> MarkChip(m.value) }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun MarkChip(value: String) {
    val c = Tone.mark(value)
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = c, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ———————————————————————— Успеваемость ————————————————————————

@Composable
private fun GradesTab(state: ParentRepo.State) {
    val journal = state.student?.journal
    if (journal == null) {
        Column(Modifier.padding(16.dp)) {
            Hint(
                if (state.loading) "Загружаю оценки…"
                else "Оценок пока нет. Они появятся, когда студент войдёт в ЛКС в «Наставнике» на телефоне и там включена синхронизация с Яндекс Диском.",
            )
        }
        return
    }
    val subjects = journal.lessons.sortedWith(compareBy({ it.avgMark == null && it.entries.none { e -> e.marks.isNotEmpty() } }, { it.title }))
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { GradesSummary(journal) }
        items(subjects) { SubjectCard(it) }
    }
}

@Composable
private fun GradesSummary(j: app.nastavnik.grades.LksJournal) {
    val avgs = j.lessons.mapNotNull { it.avgMark?.replace(',', '.')?.toDoubleOrNull() }
    val avg = avgs.takeIf { it.isNotEmpty() }?.average()
    val (missed, valid) = ParentData.absences(j)
    Card {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat(avg?.let { "%.2f".format(ru, it) } ?: "—", "средний балл", avg?.let { Tone.mark("%.1f".format(Locale.US, it)) } ?: MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Stat("$missed", if (valid > 0) "пропусков, $valid уваж." else plural(missed, "пропуск", "пропуска", "пропусков"), if (missed - valid > 0) Tone.Bad else Tone.Ok, Modifier.weight(1f))
            Stat("${j.lessons.size}", plural(j.lessons.size, "предмет", "предмета", "предметов"), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
    }
    if (j.period.isNotBlank()) Text(j.period, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubjectCard(s: LksLesson) {
    var open by rememberSaveable(s.title) { mutableStateOf(false) }
    val marks = s.entries.flatMap { e -> e.marks.map { e.date to it.value } }
    val missed = s.entries.count { it.absent }
    Card(Modifier.animateContentSize().clickable { open = !open }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(
                        "${marks.size} ${plural(marks.size, "оценка", "оценки", "оценок")}",
                        missed.takeIf { it > 0 }?.let { "$it ${plural(it, "пропуск", "пропуска", "пропусков")}" },
                        s.rating?.takeIf { it.isNotBlank() }?.let { "рейтинг $it" },
                    ).joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = if (missed > 0) Tone.Bad else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val avg = s.avgMark?.takeIf { it.isNotBlank() }
                if (avg != null) Text(avg, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp), color = Tone.mark(avg))
            }
            if (marks.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(marks.takeLast(20).reversed()) { (_, v) -> MarkChip(v) } }
            if (open) s.entries.reversed().filter { it.absent || it.marks.isNotEmpty() || !it.comment.isNullOrBlank() }.forEach { e -> EntryRow(e) }
        }
    }
}

@Composable
private fun EntryRow(e: LksEntry) {
    val d = runCatching { LocalDate.parse(e.date.take(10)) }.getOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(d?.format(DateTimeFormatter.ofPattern("d MMM, EE", ru)) ?: e.date, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        if (e.absent) Badge(if (e.absentValid == true) "Пропуск · уваж." else "Пропуск", if (e.absentValid == true) Tone.Warn else Tone.Bad)
        e.marks.forEach { MarkChip(it.value) }
        e.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
    }
}

// ———————————————————————— Настройки ————————————————————————

@Composable
private fun SettingsScreen(repo: ParentRepo, first: Boolean, onDone: () -> Unit, onBack: (() -> Unit)?, onDisconnect: () -> Unit) {
    var token by rememberSaveable { mutableStateOf(repo.token) }
    var book by rememberSaveable { mutableStateOf(repo.recordBook) }
    var group by rememberSaveable { mutableStateOf(repo.groupOverride) }
    var show by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { RoundButton(R.drawable.ic_arrow_back, "Назад", onClick = onBack); Spacer(Modifier.width(12.dp)) }
            Text(if (first) "Подключение" else "Настройки", style = MaterialTheme.typography.headlineMedium)
        }
        if (first) Text(
            "Здесь видно расписание студента с посещаемостью и его успеваемость. Данные приходят с Яндекс Диска студента — " +
                "туда их кладёт приложение «Наставник».",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Токен Яндекс Диска студента", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Тот же, что вписан в «Наставнике» (Настройки → Синхронизация). Приложение только читает папку «Nastavnik» и ничего не меняет на Диске. Токен хранится зашифрованным.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    token, { token = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Токен Диска" },
                    singleLine = true, placeholder = { Text("y0_…") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Icon(painterResource(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye), if (show) "Скрыть" else "Показать",
                            Modifier.size(22.dp).clickable { show = !show })
                    },
                )
            }
        }
        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Номер зачётной книжки", style = MaterialTheme.typography.titleSmall)
                Text("Для посещаемости, которую отмечает староста.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(book, { book = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Номер зачётки" }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            }
        }
        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Группа", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Обычно не нужна: берётся из «Наставника» студента" + (repo.state.value.student?.group?.takeIf { it.isNotBlank() }?.let { " (сейчас $it)" } ?: "") + ". Впиши, только если хочешь другую.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(group, { group = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Группа" }, singleLine = true, placeholder = { Text("как у студента") })
            }
        }
        Button(
            onClick = { repo.save(token, book, group); onDone() },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Text(if (first) "Подключить" else "Сохранить") }
        if (!first) TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Отключить и забыть токен", color = Tone.Bad)
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

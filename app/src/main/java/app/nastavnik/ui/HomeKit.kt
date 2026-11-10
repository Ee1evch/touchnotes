package app.nastavnik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleNow
import app.nastavnik.study.Exam
import app.nastavnik.study.ExamPlan
import app.nastavnik.study.DayPlan
import app.nastavnik.study.StudyLock
import app.nastavnik.study.StudyLockRules
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Главная — одинаковая по смыслу на телефоне и планшете, сверху вниз от важного:
 *  1. шапка: дата и одна фраза о том, что сейчас («Сейчас физика — ещё 35 мин»);
 *  2. карточка дня: пара, которая важна сейчас (идёт или следующая), и лента всех пар дня;
 *  3. три плитки: карточки, серия, будильник (или экзамен);
 *  4. напоминания;
 *  5. на планшете — тетради, наставник и разделы сеткой.
 */

private val ru = Locale.forLanguageTag("ru")

/** Одна фраза о дне для шапки. */
fun dayStatus(lessons: List<Lesson>?, now: LocalDateTime, hasGroup: Boolean): String {
    if (!hasGroup) return "Выбери группу — здесь будет твой день"
    if (lessons == null) return "Загружаю расписание…"
    val s = ScheduleNow.at(lessons, now)
    val current = s.current
    val next = s.next
    return when {
        current != null -> "Сейчас ${current.discipline.lowercaseFirst()} — ещё ${durationText(s.minutesLeft ?: 0)}"
        next != null && (s.minutesToNext ?: 0) <= 120 -> "Через ${durationText(s.minutesToNext ?: 0)} — ${next.discipline.lowercaseFirst()}"
        next != null -> "Следующая пара в ${next.start}"
        lessons.isEmpty() -> "Сегодня пар нет"
        else -> "Пары на сегодня закончились"
    }
}

/** «Физика» → «физика», но «ИТ в…» остаётся как есть. */
private fun String.lowercaseFirst(): String =
    if (length > 1 && this[0].isUpperCase() && this[1].isLowerCase()) this[0].lowercase(ru) + substring(1) else this

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

/** Шапка: дата с приветствием мелко, крупно — что сейчас; справа кнопки. */
@Composable
fun HomeHeader(now: LocalDateTime, status: String, actions: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)).replaceFirstChar { it.titlecase(ru) } + " · " + greeting(now.hour),
                style = MaterialTheme.typography.labelLarge, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/**
 * Карточка дня: главная пара (идёт сейчас или следующая; вечером — первая завтра) и лента всех пар дня,
 * прокрученная к текущей.
 */
@Composable
fun DayCard(lessons: List<Lesson>?, tomorrow: List<Lesson>, now: LocalDateTime, hasGroup: Boolean, onSchedule: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(28.dp), 12.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasGroup) {
            Text("Расписание", style = MaterialTheme.typography.titleMedium)
            Text("Выбери свою группу — пары появятся здесь, а перед каждой придёт уведомление с аудиторией.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            InkButton("Выбрать группу", icon = R.drawable.ic_calendar, onClick = onSchedule)
            return@Column
        }
        val day = lessons.orEmpty().filter { it.startsAt() != null }.sortedBy { it.start }
        val state = remember(day, now) { ScheduleNow.at(day, now) }
        val focus = state.current ?: state.next
        val evening = lessons != null && focus == null
        val title = when {
            lessons == null -> "Загружаю…"
            state.current != null -> "Сейчас"
            state.next != null -> "Дальше · в ${state.next!!.start}"
            evening && tomorrow.isNotEmpty() -> "Завтра первая · в ${tomorrow.first().start}"
            day.isEmpty() -> "Сегодня свободно"
            else -> "На сегодня всё"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            LinkText("Неделя", onClick = onSchedule)
        }
        when {
            focus != null -> LessonCard(focus, now, compact = false)
            evening && tomorrow.isNotEmpty() -> LessonCard(tomorrow.first(), now, compact = false)
        }
        // Лента дня — только если пар больше одной: видно весь день и где ты в нём.
        if (day.size > 1) DayStrip(day, now, focus)
    }
}

@Composable
private fun DayStrip(day: List<Lesson>, now: LocalDateTime, focus: Lesson?) {
    val list = rememberLazyListState()
    val target = day.indexOf(focus).takeIf { it >= 0 } ?: day.lastIndex
    LaunchedEffect(target) { list.animateScrollToItem((target - 1).coerceAtLeast(0)) }
    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(day, key = { _, l -> l.id }) { _, l ->
            val start = l.startsAt()
            val end = l.endsAt() ?: start?.plusMinutes(90)
            val past = end != null && !now.isBefore(end)
            val live = start != null && end != null && !now.isBefore(start) && now.isBefore(end)
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .width(124.dp)
                    .clip(shape)
                    .background(if (live) Brand.Accent.copy(alpha = 0.12f) else Brand.Ink.copy(alpha = if (past) 0.02f else 0.04f))
                    .border(if (live) 1.2.dp else 0.dp, if (live) Brand.Accent else Brand.Line.copy(alpha = 0f), shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val ink = if (past) Brand.InkSoft else Brand.Ink
                Text("${l.start}–${l.end}", style = MaterialTheme.typography.labelMedium, color = if (live) Brand.Accent else Brand.InkSoft)
                Text(l.discipline, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(l.classroom ?: l.type, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Плитка: значок, крупное значение, подпись; «проседает» под пальцем с пружинкой. */
@Composable
fun HomeTile(
    modifier: Modifier,
    icon: Int,
    big: String,
    caption: String,
    accent: Boolean,
    onClick: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.enter().pressable(src).glass(RoundedCornerShape(24.dp), 10.dp)
            .clickable(src, indication = null, onClick = onClick).padding(14.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (accent) Brand.Accent else Brand.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), contentDescription = null, tint = if (accent) Brand.OnAccent else Brand.Accent, modifier = Modifier.size(17.dp)) }
        Text(big, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        extra()
    }
}

/** Плитка «Карточки»: сколько ждёт повторения. */
@Composable
fun CardsTile(modifier: Modifier, due: Int, go: (String) -> Unit) = HomeTile(
    modifier = modifier,
    icon = R.drawable.ic_cards,
    big = if (due > 0) due.toString() else "✓",
    caption = if (due > 0) ruPlural(due, "карточка ждёт", "карточки ждут", "карточек ждут") else "всё повторено",
    accent = due > 0,
    onClick = { go(MainActivity.SCREEN_REVIEW) },
)

/** Ближайший экзамен. */
@Composable
fun ExamTile(modifier: Modifier, exam: Exam?, go: (String) -> Unit) {
    val onClick = { go(MainActivity.SCREEN_EXAMS) }
    if (exam == null) {
        HomeTile(modifier, R.drawable.ic_target, "+", "добавь экзамен", false, onClick)
        return
    }
    val days = ExamPlan.daysLeft(exam.at, System.currentTimeMillis())
    val label = if (days == 0) "сегодня" else "$days ${ruPlural(days, "день", "дня", "дней")}"
    HomeTile(modifier, R.drawable.ic_target, label, exam.subject, days <= 3, onClick)
}

/** Разделы сеткой из компактных плиток — не длинным списком. */
@Composable
fun SectionsGrid(go: (String) -> Unit, columns: Int, items: List<Triple<Int, String, String>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, title, screen) ->
                    val src = remember(screen) { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f).enter().pressable(src).glass(RoundedCornerShape(22.dp), 8.dp)
                            .clickable(src, indication = null) { go(screen) }.padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconBadge(icon, 36)
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Разделы планшета для сетки. */
val TABLET_SECTIONS = listOf(
    Triple(R.drawable.ic_pen, "Домашка", MainActivity.SCREEN_HOMEWORK),
    Triple(R.drawable.ic_calendar, "Расписание", MainActivity.SCREEN_SCHEDULE),
    Triple(R.drawable.ic_chart, "Оценки", MainActivity.SCREEN_GRADES),
    Triple(R.drawable.ic_target, "Тренировка", MainActivity.SCREEN_TRAINING),
    Triple(R.drawable.ic_chart, "Прогресс", MainActivity.SCREEN_PROGRESS),
    Triple(R.drawable.ic_calendar, "Экзамены", MainActivity.SCREEN_EXAMS),
    Triple(R.drawable.ic_history, "История", MainActivity.SCREEN_HISTORY),
    Triple(R.drawable.ic_note, "Чистовик", MainActivity.SCREEN_CLEAN),
)

/**
 * Вечерняя учёба на главной: видна вечером (с 16:00), пока не сделана, и всегда — пока замок закрыт.
 */
@Composable
fun EveningCard(resumeTick: Int, go: (String) -> Unit) {
    val context = LocalContext.current
    val status by produceState<StudyLock.Status?>(null, resumeTick) {
        value = StudyLock.status(context)
    }
    val s = status ?: return
    val show = s.locked || (!s.allDone && LocalDateTime.now().hour >= 16) || (s.configured && !s.allDone && s.date != java.time.LocalDate.now())
    if (!show) return
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().enter().pressable(src)
            .glass(RoundedCornerShape(24.dp), 12.dp, strong = s.locked)
            .clickable(src, indication = null) { go(MainActivity.SCREEN_EVENING) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (s.locked) Brand.Error else Brand.Accent),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_cards), contentDescription = null, tint = Brand.OnAccent, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(if (s.locked) "Приложения закрыты" else "Вечерняя учёба", style = MaterialTheme.typography.titleMedium)
            Text(
                "${s.steps} из 4 · карточки, что было сегодня, подготовка и ДЗ к завтра",
                style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        InkButton(if (s.steps == 0) "Начать" else "Дальше") { go(MainActivity.SCREEN_EVENING) }
    }
}

/**
 * План на день от наставника: 3–4 дела по сводке учёбы и времени в телефоне. Один запрос в день;
 * без нейросети карточки нет.
 */
@Composable
fun PlanCard(resumeTick: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val plan by produceState<List<String>?>(null, resumeTick, refresh) { value = DayPlan.today(context, refresh = refresh > 0) }
    val items = plan ?: return
    if (items.isEmpty()) return
    val usage = remember(resumeTick) { DayPlan.usageAccess(context) }
    Column(
        Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(26.dp), 10.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(18.dp))
            Text("План на сегодня", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            LinkText("Обновить") { refresh++ }
        }
        items.forEachIndexed { i, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = Brand.Accent)
                Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        if (!usage) LinkText("Учитывать время в телефоне") {
            StudyLock.openSettings(context, DayPlan.usageIntent())
        }
    }
}

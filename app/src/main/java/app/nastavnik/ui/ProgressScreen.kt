package app.nastavnik.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.study.Event
import app.nastavnik.study.Leitner
import app.nastavnik.study.Progress
import app.nastavnik.study.ProgressSummary
import app.nastavnik.study.ruPlural
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Учебный прогресс: серия дней подряд, активность за две недели, итоги и темы с ошибками. */
@Composable
fun ProgressScreen(onBack: () -> Unit, onReview: () -> Unit) {
    val context = LocalContext.current
    val db = context.app.db
    val rows by remember { db.activity().observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    val learned by remember { db.cards().observeLearned(Leitner.LEARNED_BOX) }.collectAsStateWithLifecycle(initialValue = 0)
    val allCards by remember { db.cards().observeCount() }.collectAsStateWithLifecycle(initialValue = 0)

    ScreenScaffold(title = "Прогресс", onBack = onBack) {
        val list = rows ?: return@ScreenScaffold
        val now = remember(list) { System.currentTimeMillis() }
        val s = remember(list) { Progress.summarize(list.map { Event(it.kind, it.topic, it.errors, it.at) }, now) }
        if (list.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass()
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconBadge(R.drawable.ic_chart, 52)
                Text("Здесь появится твой прогресс", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Каждый вопрос наставнику, найденная ошибка, чистовик и повторённая карточка считаются. " +
                        "Занимайся хотя бы немного каждый день — и смотри, как растёт серия.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ScreenScaffold
        }
        StreakCard(s, todayActive = s.days.last() > 0)
        ActivityChart(s.days, now)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(s.questions.toString(), ruPlural(s.questions, "вопрос наставнику", "вопроса наставнику", "вопросов наставнику"), R.drawable.ic_bulb, Modifier.weight(1f))
            Stat(s.errorsFound.toString(), ruPlural(s.errorsFound, "ошибка найдена", "ошибки найдены", "ошибок найдено"), R.drawable.ic_search, Modifier.weight(1f))
            Stat(s.cleanNotes.toString(), ruPlural(s.cleanNotes, "чистовик", "чистовика", "чистовиков"), R.drawable.ic_note, Modifier.weight(1f))
            Stat("$learned/$allCards", "карточек выучено", R.drawable.ic_cards, Modifier.weight(1f), onClick = onReview)
        }
        if (s.weakTopics.isNotEmpty()) WeakTopics(s.weakTopics)
    }
}

@Composable
private fun StreakCard(s: ProgressSummary, todayActive: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), 14.dp)
            .padding(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        IconBadge(R.drawable.ic_flame, 64)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (s.streak > 0) "${s.streak} ${ruPlural(s.streak, "день", "дня", "дней")} подряд" else "Начни новую серию",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                when {
                    s.streak == 0 -> "Один вопрос наставнику или пара карточек — и серия начнётся."
                    !todayActive -> "Сегодня ещё не занимался — пара карточек сохранит серию."
                    else -> "Сегодня уже занимался. Так держать!"
                } + if (s.bestStreak > 1) " Лучшая серия: ${s.bestStreak} ${ruPlural(s.bestStreak, "день", "дня", "дней")}." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Столбики за 14 дней, сегодняшний — справа. Касание столбика показывает число занятий в тот день. */
@Composable
private fun ActivityChart(days: List<Int>, now: Long) {
    var selected by remember(days) { mutableIntStateOf(days.lastIndex) }
    val max = (days.maxOrNull() ?: 0).coerceAtLeast(1)
    val dates = remember(now, days.size) {
        days.indices.map { i ->
            Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, i - days.lastIndex)
            }.time
        }
    }
    val dayFmt = remember { SimpleDateFormat("d MMMM", Locale("ru")) }
    val accent = Brand.Accent
    val track = Brand.Ink.copy(alpha = 0.1f)
    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Последние 2 недели", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val n = days[selected]
            val label = if (selected == days.lastIndex) "сегодня" else dayFmt.format(dates[selected])
            Text(
                "$label · $n ${ruPlural(n, "занятие", "занятия", "занятий")}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .semantics { contentDescription = "Занятия по дням за две недели: " + days.joinToString(", ") }
                .pointerInput(days) {
                    detectTapGestures { p ->
                        val slot = size.width / days.size
                        selected = (p.x / slot).toInt().coerceIn(0, days.lastIndex)
                    }
                },
        ) {
            val slot = size.width / days.size
            val gap = 6.dp.toPx()
            val w = slot - gap
            val r = 4.dp.toPx()
            days.forEachIndexed { i, v ->
                val x = i * slot + gap / 2
                if (v == 0) {
                    // Пустой день — тонкая серая чёрточка у основания.
                    drawRoundRect(track, Offset(x, size.height - 3.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(r, r))
                } else {
                    val h = (size.height * v / max).coerceAtLeast(6.dp.toPx())
                    val color = if (i == selected) accent else accent.copy(alpha = 0.45f)
                    // Скругление только сверху: нижний край ровно на основании.
                    drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(r, r))
                    drawRect(color, Offset(x, size.height - minOf(h, r)), Size(w, minOf(h, r)))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            dates.forEachIndexed { i, d ->
                val c = Calendar.getInstance().apply { time = d }
                Text(
                    if (i == days.lastIndex) "сег." else c.get(Calendar.DAY_OF_MONTH).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, icon: Int, modifier: Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .glass()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, 36)
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Темы, где чаще всего находились ошибки: по ним стоит повторить теорию. */
@Composable
private fun WeakTopics(topics: List<Pair<String, Int>>) {
    val max = topics.maxOf { it.second }.coerceAtLeast(1)
    SectionCard("Где чаще ошибаешься · 30 дней") {
        topics.forEachIndexed { i, (topic, n) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(topic, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("$n ${ruPlural(n, "ошибка", "ошибки", "ошибок")}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Brand.Ink.copy(alpha = 0.1f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(n.toFloat() / max)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Brand.Error),
                    )
                }
            }
            if (i < topics.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(
            "Перед следующей задачей по этой теме нажми «Теория» в меню наставника — он объяснит на простом примере.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

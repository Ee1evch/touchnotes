package app.nastavnik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CardEntity
import app.nastavnik.study.LockPrefs
import app.nastavnik.study.QuizQuestion
import app.nastavnik.study.TrainResult
import app.nastavnik.study.StudyLock
import app.nastavnik.study.StudyLockRules
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale.forLanguageTag("ru")
private fun dayName(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru))

/**
 * Вечерняя учёба: карточки на сегодня, «что было сегодня» (пара предложений по каждой паре — станет
 * карточкой) и «что будет завтра» (прошлый конспект и карточки предмета). Пока не сделано — учебный
 * замок держит приложения закрытыми.
 */
@Composable
fun EveningScreen(resumeTick: Int, onBack: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val status by produceState<StudyLock.Status?>(null, resumeTick, version) {
        // Пока экран открыт — пересчитываем (карточки повторяются на другом экране, время идёт).
        while (true) { value = StudyLock.status(context); delay(15_000) }
    }
    // Во время замка «Назад» не уводит в другие приложения — только на главную «Наставника».
    BackHandler(enabled = status?.locked == true) { onBack() }
    ScreenScaffold(title = "Вечерняя учёба", onBack = onBack) {
        val s = status
        if (s == null) {
            Text("Считаю, что осталось…", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            return@ScreenScaffold
        }
        // Шапка: за какой день, замок, прогресс.
        Column(
            Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(26.dp), 12.dp, strong = s.locked).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("За ${dayName(s.date)}", style = MaterialTheme.typography.labelLarge, color = Brand.InkSoft)
            Text(
                when {
                    s.allDone -> "Готово — свободен до завтрашнего вечера"
                    s.locked -> "Приложения закрыты, пока не сделаешь"
                    s.configured -> "В ${s.startAt.format(DateTimeFormatter.ofPattern("H:mm"))} (через 40 минут после пар) приложения закроются — сделай заранее"
                    else -> "Повтори день и подготовься к завтра"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            val p by animateFloatAsState(s.steps / 4f, label = "steps")
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brand.Line)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(4.dp)).background(if (s.allDone) Brand.Ok else Brand.Accent))
            }
            Text("${s.steps} из 4", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft)
            if (s.locked) EmergencyLink { version++ }
        }

        // 1. Карточки.
        Step(1, "Карточки на сегодня", s.cardsDone) {
            if (s.cardsDone) Text("Все повторены", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.due} ${ruPlural(s.due, "карточка ждёт", "карточки ждут", "карточек ждут")}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                InkButton("Повторить", icon = R.drawable.ic_cards) { go(MainActivity.SCREEN_REVIEW) }
            }
        }

        // 2. Что было сегодня.
        Step(2, "Что было ${if (s.date == LocalDate.now()) "сегодня" else dayName(s.date)}", s.summariesDone) {
            if (s.summaries.isEmpty()) Text("Пар не было — этот шаг пропускаем", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            s.summaries.forEach { (subject, done) ->
                SummaryRow(s.date, subject, done) {
                    scope.launch { StudyLock.saveSummary(context, s.date, subject, it); version++ }
                }
            }
        }

        // 3. Что будет завтра.
        Step(3, "Готовлюсь к ${if (s.nextDate == s.date.plusDays(1)) "завтра" else dayName(s.nextDate)}", s.prepDone) {
            if (s.prep.isEmpty()) Text("Пар нет — этот шаг пропускаем", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            s.prep.forEach { (subject, done) ->
                PrepRow(s.date, subject, done) { scope.launch { StudyLock.markPrepared(context, s.date, subject); version++ } }
            }
        }

        // 4. ДЗ к завтра — на 100%: каждая задача проверена.
        Step(4, "ДЗ к ${if (s.nextDate == s.date.plusDays(1)) "завтра" else dayName(s.nextDate)}", s.homeworkDone) {
            if (s.homework.isEmpty()) Text("Домашки к этому дню нет", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            s.homework.forEach { (label, done) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (done) Brand.Ok else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (done) Icon(painterResource(R.drawable.ic_check), contentDescription = "Сделано", tint = Brand.Ok, modifier = Modifier.size(18.dp))
                }
            }
            if (!s.homeworkDone) InkButton("Решать ДЗ", icon = R.drawable.ic_pen) { go(MainActivity.SCREEN_HOMEWORK) }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(24.dp), 10.dp).padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (done) Brand.Ok else Brand.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(painterResource(R.drawable.ic_check), contentDescription = "Сделано", tint = Brand.OnAccent, modifier = Modifier.size(16.dp))
                else Text("$n", style = MaterialTheme.typography.labelLarge, color = Brand.Accent)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        content()
    }
}

/** Конспект пары: пара предложений своими словами — станет карточкой «вспомни главное». */
@Composable
private fun SummaryRow(date: LocalDate, subject: String, done: Boolean, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var open by remember(subject) { mutableStateOf(false) }
    var text by remember(subject, date) { mutableStateOf(StudyLock.evening(context, date).summaries[subject].orEmpty()) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Ink.copy(alpha = 0.03f))) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(subject, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (done) "✓" else "написать", style = MaterialTheme.typography.labelMedium, color = if (done) Brand.Ok else Brand.Accent)
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    label = "Главное с пары своими словами",
                    placeholder = "Что разбирали, какое определение, формула, пример…",
                    modifier = Modifier.fillMaxWidth(),
                )
                val left = StudyLockRules.SUMMARY_MIN_CHARS - text.trim().length
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (left > 0) "Ещё $left ${ruPlural(left, "символ", "символа", "символов")}" else "Станет карточкой на завтра",
                        style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.weight(1f),
                    )
                    InkButton("Сохранить", enabled = left <= 0) { onSave(text); open = false }
                }
            }
        }
    }
}

/**
 * Подготовка к паре: прошлый конспект и карточки предмета; без материалов — 90 секунд на учебник.
 * Если настроена нейросеть — ещё 3 вопроса по следующей теме ДО лекции (попытка ответить заранее помогает
 * запомнить лекцию); после пары они вернутся карточками.
 */
@Composable
private fun PrepRow(date: LocalDate, subject: String, done: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(subject) { mutableStateOf(false) }
    var pre by remember(subject) { mutableStateOf<List<QuizQuestion>?>(null) }
    val picked = remember(subject) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(open) {
        if (open && pre == null && !done) {
            val last = StudyLock.materials(context, date, subject).first
            pre = (context.app.trainer.pretest(subject, last) as? TrainResult.Ok)?.value.orEmpty()
        }
    }
    val materials by produceState<Pair<String?, List<CardEntity>>?>(null, open) {
        if (open) value = StudyLock.materials(context, date, subject)
    }
    var flipped by remember(subject) { mutableStateOf(setOf<Long>()) }
    var seconds by remember(subject) { mutableIntStateOf(0) }
    LaunchedEffect(open) { while (open) { delay(1000); seconds++ } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Ink.copy(alpha = 0.03f))) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(subject, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (done) "✓" else "открыть", style = MaterialTheme.typography.labelMedium, color = if (done) Brand.Ok else Brand.Accent)
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val m = materials
                if (m == null) {
                    Text("Собираю материалы…", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                } else {
                    m.first?.let {
                        Text("Прошлый раз ты записал:", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (m.second.isNotEmpty()) Text("Ответь про себя и переверни:", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft)
                    m.second.forEach { c ->
                        val shown = c.id in flipped
                        Column(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp), 4.dp).clickable { flipped = flipped + c.id }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(c.front, style = MaterialTheme.typography.bodyMedium)
                            AnimatedVisibility(shown) { Text(c.back, style = MaterialTheme.typography.bodySmall, color = Brand.Accent) }
                        }
                    }
                    pre?.takeIf { it.isNotEmpty() }?.let { qs ->
                        Text("Вопросы до лекции — попробуй ответить, даже если не знаешь:", style = MaterialTheme.typography.labelMedium, color = Brand.InkSoft)
                        qs.forEachIndexed { qi, q ->
                            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp), 4.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MathText(q.q, 15)
                                q.options.forEachIndexed { oi, option ->
                                    val chosen = picked[qi]
                                    val color = when {
                                        chosen == null -> Brand.Ink.copy(alpha = 0.04f)
                                        oi == q.correct -> Brand.OkSoft
                                        oi == chosen -> Brand.ErrorSoft
                                        else -> Brand.Ink.copy(alpha = 0.02f)
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color)
                                            .clickable(enabled = chosen == null) { picked[qi] = oi }.padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) { MathText(option, 14) }
                                }
                                if (picked[qi] != null && q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                            }
                        }
                    }
                    val needPre = pre?.let { picked.size < it.size } ?: false
                    val needCards = m.second.isNotEmpty() && flipped.size < m.second.size
                    val needTime = m.first == null && m.second.isEmpty() && seconds < StudyLockRules.PREP_MIN_SECONDS
                    if (m.first == null && m.second.isEmpty()) {
                        Text(
                            "По предмету ещё нет конспектов и карточек. Открой лекцию или учебник по следующей теме и пролистай — " +
                                if (needTime) "ещё ${StudyLockRules.PREP_MIN_SECONDS - seconds} с" else "можно отмечать.",
                            style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                needCards -> "Переверни все карточки (${flipped.size}/${m.second.size})"
                                needPre -> "Ответь на вопросы до лекции (${picked.size}/${pre?.size ?: 0})"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.weight(1f),
                        )
                        InkButton(if (done) "Готов" else "Готов к паре", enabled = !done && !needCards && !needTime && !needPre) {
                            // Вопросы до лекции вернутся карточками на следующий вечер — уже после пары.
                            pre?.takeIf { it.isNotEmpty() }?.let { qs ->
                                scope.launch {
                                    val now = System.currentTimeMillis()
                                    context.app.db.cards().insertAll(
                                        qs.map { q ->
                                            val answer = q.options.getOrNull(q.correct).orEmpty()
                                            CardEntity(
                                                noteId = null, subject = subject, topic = "До лекции", front = q.q,
                                                back = if (q.why.isBlank()) answer else "$answer\n\n${q.why}",
                                                dueAt = now + 20 * 3_600_000L, createdAt = now,
                                            )
                                        },
                                    )
                                }
                            }
                            onDone()
                            open = false
                        }
                    }
                }
            }
        }
    }
}

/** Экстренные 15 минут раз в неделю — на случай, если правда нужно (такси, карта, банк). */
@Composable
private fun EmergencyLink(onUsed: () -> Unit) {
    val context = LocalContext.current
    val lp = remember { LockPrefs.of(context) }
    val available = StudyLockRules.emergencyAvailable(lp.emergencyAt, System.currentTimeMillis())
    var confirm by remember { mutableStateOf(false) }
    if (!available) {
        Text("Экстренный доступ уже был на этой неделе", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        return
    }
    if (!confirm) {
        LinkText("Экстренно: 15 минут (раз в неделю)") { confirm = true }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Точно? Следующий — только через 7 дней.", style = MaterialTheme.typography.bodySmall, color = Brand.Error, modifier = Modifier.weight(1f))
            SoftButton("Да") { StudyLock.startEmergency(context); confirm = false; onUsed() }
        }
    }
}

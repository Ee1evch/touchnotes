package app.nastavnik.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.BuildConfig
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.grades.CourseGrades
import app.nastavnik.grades.GradeItem
import app.nastavnik.grades.GradesSnapshot
import app.nastavnik.grades.MoodleClient
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Цвет оценки: высокий процент — зелёный, средний — жёлтый, низкий — красный. */
fun gradeColor(i: GradeItem?): Color {
    val r = i?.raw; val m = i?.max
    if (r == null || m == null || m <= 0) return Brand.InkSoft
    val p = r / m
    return when {
        p >= 0.85 -> Brand.Ok
        p >= 0.6 -> Color(0xFFE0A21E)
        else -> Brand.Error
    }
}

/** Успеваемость: курсы с итогами; касание раскрывает оценки за задания и отзывы преподавателя. */
@Composable
fun GradesScreen(onBack: (() -> Unit)?, onSettings: () -> Unit, onLks: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = context.app.grades
    val scope = rememberCoroutineScope()
    val snap by repo.snapshot.collectAsStateWithLifecycle()
    val error by repo.error.collectAsStateWithLifecycle()
    val loading by repo.loading.collectAsStateWithLifecycle()
    val app = context.app
    val journal by repo.journal.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { repo.load(); repo.loadJournal(); repo.refresh(); repo.refreshLks() }

    ScreenScaffold(title = "", onBack = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
            Column(Modifier.weight(1f)) {
                Text("Успеваемость", style = MaterialTheme.typography.headlineMedium)
                snap?.let { s ->
                    Text(
                        "${s.student.ifBlank { "Личный кабинет" }} · обновлено ${SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(s.loadedAt))}",
                        style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                    )
                }
            }
            if (repo.configured) {
                Box(
                    Modifier.size(44.dp).glass(CircleShape, 6.dp, strong = true).clickable(enabled = !loading) {
                        scope.launch {
                            // Здесь журнал — копия с телефона: обновить = синхронизироваться.
                            if (repo.synced) app.sync.now() else if (repo.viaLks) repo.refreshLks(force = true) else repo.refresh(force = true)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Accent, strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_refresh), "Обновить", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (!repo.configured) {
            Column(Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(28.dp), 10.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Подключи личный кабинет", style = MaterialTheme.typography.titleMedium)
                Text("Войди в ЛКС один раз (почта, пароль и капча) — оценки появятся здесь и будут обновляться сами, о новых придёт уведомление.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                if (!BuildConfig.STUDENT) Text("Вход нужен только на одном устройстве: с облаком (Настройки → Синхронизация) оценки придут на остальные сами.", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                InkButton("Войти в ЛКС", onClick = onLks)
            }
            return@ScreenScaffold
        }
        error?.let { StatusLine(it, Tone.ERROR) }
        val needLogin by repo.needLogin.collectAsStateWithLifecycle()
        if (repo.viaLks && needLogin) {
            Column(Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(28.dp), 10.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Войди в ЛКС снова", style = MaterialTheme.typography.titleMedium)
                Text("ЛКС закончил сессию. Войди ещё раз (с капчей) — и оценки продолжат обновляться сами.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                InkButton("Войти в ЛКС", onClick = onLks)
            }
        }
        if (repo.viaLks) {
            val j = journal
            if (j == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
                return@ScreenScaffold
            }
            LksJournalView(j)
            return@ScreenScaffold
        }
        val s = snap ?: run {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
            return@ScreenScaffold
        }
        Summary(s)
        val courses = s.courses.sortedWith(compareBy({ it.total == null && it.items.none { i -> i.grade != "-" } }, { it.name }))
        // Сгруппированный список, как в настройках Android 16: общий контейнер, скруглённые края у первого и последнего.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            courses.forEachIndexed { i, c ->
                val shape = groupShape(i, courses.size)
                CourseRow(c, shape)
            }
        }
    }
}

/** Пункты списка — отдельные округлые плашки (как раньше), а не слитая группа. */
@Suppress("UNUSED_PARAMETER")
fun groupShape(i: Int, n: Int): RoundedCornerShape = RoundedCornerShape(22.dp)

@Composable
private fun Summary(s: GradesSnapshot) {
    val avg = MoodleClient.averagePercent(s)
    val graded = s.courses.sumOf { c -> c.items.count { it.grade != "-" } }
    val shown by animateFloatAsState((avg ?: 0.0).toFloat(), Motion.enter(700), label = "avg")
    Row(
        Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(28.dp), 10.dp).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (avg != null) "${shown.toInt()} %" else "—", style = MaterialTheme.typography.displaySmall)
            Text("средний результат по заданиям", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${s.courses.size}", style = MaterialTheme.typography.titleLarge)
            Text("курсов · оценок $graded", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        }
    }
}

@Composable
private fun CourseRow(c: CourseGrades, shape: RoundedCornerShape) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.enter(260), label = "courseArrow")
    Column(
        Modifier.fillMaxWidth().enter().clip(shape).background(Brand.Glass.copy(alpha = if (Brand.dark) 1f else 0.85f))
            .clickable { open = !open }.animateContentSize(Motion.enter(320)).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val done = c.items.count { it.grade != "-" }
                Text(if (c.items.isEmpty()) "оценок пока нет" else "оценок $done из ${c.items.size}", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            }
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.Accent.copy(alpha = if (c.total != null) 0.14f else 0.05f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(c.total ?: "—", style = MaterialTheme.typography.titleMedium, color = if (c.total != null) Brand.Accent else Brand.InkSoft)
            }
            Icon(painterResource(R.drawable.ic_chevron), null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
        }
        if (open) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                c.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                item.max?.let { "из ${formatNum(it)}" },
                                item.gradedAt.takeIf { it > 0 }?.let { SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(Date(it)) },
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = Brand.InkSoft)
                            item.feedback?.let { Text("«$it»", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                        Text(item.grade, style = MaterialTheme.typography.titleSmall, color = if (item.grade == "-") Brand.InkSoft else gradeColor(item))
                    }
                }
            }
        }
    }
}

private fun formatNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)

/** Короткая сводка на «Сегодня»: последние выставленные оценки. */
@Composable
fun GradesCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.grades
    val snap by repo.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { repo.load(); repo.refresh() }
    if (!repo.configured) return
    LaunchedEffect(Unit) { repo.refreshLks() }
    val recent = snap?.courses.orEmpty().flatMap { c -> c.items.filter { it.gradedAt > 0 && it.grade != "-" }.map { c to it } }
        .sortedByDescending { it.second.gradedAt }.take(3)
    val journal by repo.journal.collectAsStateWithLifecycle()
    val avg = journal?.averageMark ?: snap?.courses?.flatMap { it.items }?.mapNotNull { it.raw }?.takeIf { it.isNotEmpty() }?.average()
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().enter().glass(RoundedCornerShape(26.dp), 10.dp).clickable(onClick = onOpen).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Успеваемость", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            avg?.let {
                val str = "%.2f".format(Locale.forLanguageTag("ru"), it)
                Text("средний $str", style = MaterialTheme.typography.titleMedium, color = markColor("%.1f".format(Locale.US, it)))
            } ?: snap?.let { MoodleClient.averagePercent(it) }?.let { Text("${it.toInt()} %", style = MaterialTheme.typography.titleMedium, color = Brand.Accent) }
        }
        if (recent.isEmpty()) Text(if (snap == null) "Загружаю…" else "Новых оценок нет", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        recent.forEach { (c, i) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(gradeColor(i)))
                Text("${c.name} · ${if (Regex("""\d{4}-\d{2}-\d{2}""").matches(i.name)) fullDate(i.name) else i.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(i.grade, style = MaterialTheme.typography.titleSmall, color = gradeColor(i))
            }
        }
    }
}

/** Настройки: ЛКС — логин и пароль, вход во встроенном окне; Moodle — запасной вариант. */
@Composable
fun GradesSettingsSection(onLks: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var lks by remember { mutableStateOf(app.settings.lksConfigured) }
    var moodle by remember { mutableStateOf(app.settings.gradesConfigured) }
    var showMoodle by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(app.settings.lksLogin()) }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(app.settings.gradesUrl().ifEmpty { MoodleClient.SIRIUS }) }
    var mLogin by remember { mutableStateOf(app.settings.gradesLogin()) }
    var mPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("Успеваемость (ЛКС)") {
        if (lks) {
            Text("ЛКС подключён", style = MaterialTheme.typography.titleSmall)
            Text("Оценки обновляются сами при открытии", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkText("Войти заново", onClick = onLks)
                LinkText("Отключить") { app.grades.disconnectLks(); lks = false }
            }
        } else {
            FieldLabel("Вход в ЛКС", "Откроется сайт ЛКС: войди как обычно — почта, пароль и «я не робот». Это нужно один раз: дальше оценки приложение забирает само. Пароль приложение не хранит.")
            InkButton("Войти в ЛКС", onClick = onLks)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (moodle) {
            Text("Moodle: ${app.settings.gradesLogin()}", style = MaterialTheme.typography.bodyMedium)
            LinkText("Отключить Moodle") { app.grades.disconnect(); moodle = false }
            return@SectionCard
        }
        LinkText(if (showMoodle) "Скрыть" else "Другой сайт (Moodle)") { showMoodle = !showMoodle }
        AnimatedVisibility(showMoodle, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(url, { url = it }, label = "Адрес сайта", placeholder = MoodleClient.SIRIUS)
                AppTextField(mLogin, { mLogin = it }, label = "Логин")
                AppTextField(
                    mPassword, { mPassword = it }, label = "Пароль",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { StatusLine(it, Tone.ERROR) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InkButton("Подключить Moodle", enabled = !busy && url.isNotBlank() && mLogin.isNotBlank() && mPassword.isNotBlank()) {
                        busy = true; error = null
                        scope.launch {
                            runCatching { app.grades.connect(url, mLogin, mPassword) }
                                .onSuccess { moodle = true; mPassword = "" }
                                .onFailure { error = it.message ?: "Не удалось войти" }
                            busy = false
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Accent, strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Цвет оценки ЛКС по значению: 5 — зелёный, 4 — салатовый, 3 — янтарный, 2 — красный; зачёт — зелёный. */
fun lksMarkColor(v: String): Color {
    val t = v.lowercase()
    return when {
        t.startsWith("5") || t.startsWith("отл") || t.startsWith("зач") -> Color(0xFF1FA463)
        t.startsWith("4") || t.startsWith("хор") -> Color(0xFF7CB82F)
        t.startsWith("3") || t.startsWith("уд") -> Color(0xFFE0A21E)
        t.startsWith("2") || t.startsWith("неуд") || t.startsWith("незач") -> Color(0xFFE5484D)
        else -> Color(0xFF4C7DF0)
    }
}

/** Журнал ЛКС, как в приложении для родителя: сводка (средний балл, пропуски, предметы) и карточки предметов. */
@Composable
fun LksJournalView(j: app.nastavnik.grades.LksJournal) {
    val avg = j.averageMark
    val all = j.lessons.flatMap { it.entries }.filter { it.absent }
    val missed = all.size
    val valid = all.count { it.absentValid == true }
    val shown by animateFloatAsState((avg ?: 0.0).toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 120f), label = "avg")
    Row(Modifier.fillMaxWidth().enter().glass().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat(if (avg != null) "%.2f".format(Locale.forLanguageTag("ru"), shown) else "—", "средний балл",
            avg?.let { markColor("%.1f".format(Locale.US, it)) } ?: MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        Stat("$missed", ruPlural(missed, "пропуск", "пропуска", "пропусков") + if (valid > 0) ", $valid по уваж." else "",
            if (missed - valid > 0) Brand.Error else Brand.Ok, Modifier.weight(1f))
        Stat("${j.lessons.size}", ruPlural(j.lessons.size, "предмет", "предмета", "предметов"), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
    if (j.period.isNotBlank()) Text(j.period, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.padding(start = 6.dp))
    val lessons = j.lessons.sortedWith(compareBy({ it.displayAvg == null && it.entries.none { e -> e.marks.isNotEmpty() } }, { it.title }))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lessons.forEach { l -> LksLessonCard(l) }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    }
}

/** Цвет оценки, как у родителя: 5 — зелёный, 4 — синий, 3 — жёлтый, 2 — красный; зачёт — зелёный. */
private fun markColor(v: String): Color {
    val n = v.replace(',', '.').toDoubleOrNull() ?: return lksMarkColor(v)
    return when {
        n >= 4.5 -> Brand.Ok
        n >= 3.5 -> Color(0xFF2563EB)
        n >= 2.5 -> Color(0xFFE0A21E)
        else -> Brand.Error
    }
}

/** Карточка предмета: средний справа, оценки фишками (свежие первыми); касание — по датам с комментариями и заданиями. */
@Composable
private fun LksLessonCard(l: app.nastavnik.grades.LksLesson) {
    var open by remember { mutableStateOf(false) }
    val marks = l.entries.flatMap { e -> e.marks.map { it.value } }
    val missed = l.entries.count { it.absent }
    val unexcused = l.entries.count { it.absent && it.absentValid != true }
    Column(
        Modifier.fillMaxWidth().enter().glass()
            .clickable { open = !open }
            .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 380f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    "${marks.size} ${ruPlural(marks.size, "оценка", "оценки", "оценок")}",
                    missed.takeIf { it > 0 }?.let { "$it ${ruPlural(it, "пропуск", "пропуска", "пропусков")}" },
                    l.rating?.takeIf { it.isNotBlank() }?.let { "рейтинг $it" },
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = when { unexcused > 0 -> Brand.Error; missed > 0 -> Color(0xFFE0A21E); else -> Brand.InkSoft })
            }
            l.displayAvg?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp), color = markColor(it)) }
        }
        if (marks.isNotEmpty()) {
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                marks.takeLast(20).reversed().forEach { MarkChip(it) }
            }
        }
        if (open) {
            l.entries.reversed().filter { it.absent || it.marks.isNotEmpty() || !it.comment.isNullOrBlank() || !it.hometask.isNullOrBlank() }.forEach { e -> EntryRow(e) }
        }
    }
}

@Composable
private fun MarkChip(value: String) {
    val c = markColor(value)
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = c, maxLines = 1)
    }
}

@Composable
private fun AbsenceBadge(valid: Boolean) {
    val c = if (valid) Color(0xFFE0A21E) else Brand.Error
    Text(
        if (valid) "Пропуск · уваж." else "Пропуск", style = MaterialTheme.typography.labelMedium, color = c,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Строка даты в раскрытой карточке: пропуск, оценки, комментарий; задание — строкой ниже. */
@Composable
private fun EntryRow(e: app.nastavnik.grades.LksEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entryDate(e.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
            if (e.absent) AbsenceBadge(e.absentValid == true)
            e.marks.forEach { MarkChip(it.value + (it.second?.let { s -> "/$s" } ?: "")) }
            e.comment?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        e.hometask?.takeIf { it.isNotBlank() }?.let { Text("Задание: $it", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft) }
    }
}

private fun entryDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, EE", Locale.forLanguageTag("ru")))
}.getOrDefault(iso)

private fun fullDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))
}.getOrDefault(iso)

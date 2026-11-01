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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.grades.CourseGrades
import app.nastavnik.grades.GradeItem
import app.nastavnik.grades.GradesSnapshot
import app.nastavnik.grades.MoodleClient
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
fun GradesScreen(onBack: (() -> Unit)?, onSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.grades
    val scope = rememberCoroutineScope()
    val snap by repo.snapshot.collectAsStateWithLifecycle()
    val error by repo.error.collectAsStateWithLifecycle()
    val loading by repo.loading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { repo.load(); repo.refresh() }

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
                Box(Modifier.size(44.dp).glass(CircleShape, 6.dp, strong = true).clickable(enabled = !loading) { scope.launch { repo.refresh(force = true) } }, contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Accent, strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_refresh), "Обновить", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (!repo.configured) {
            Column(Modifier.fillMaxWidth().enter().glass(RoundedCornerShape(28.dp), 10.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Подключи личный кабинет", style = MaterialTheme.typography.titleMedium)
                Text("Введи логин и пароль от ЛКС в настройках — оценки появятся здесь, а о новых придёт уведомление.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                InkButton("Открыть настройки", onClick = onSettings)
            }
            return@ScreenScaffold
        }
        error?.let { StatusLine(it, Tone.ERROR) }
        val s = snap ?: run {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
            return@ScreenScaffold
        }
        Summary(s)
        val courses = s.courses.sortedWith(compareBy({ it.total == null && it.items.none { i -> i.grade != "-" } }, { it.name }))
        // Сгруппированный список, как в настройках Android 16: общий контейнер, скруглённые края у первого и последнего.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            courses.forEachIndexed { i, c ->
                val shape = groupShape(i, courses.size)
                CourseRow(c, shape)
            }
        }
    }
}

/** Скругления элемента в группе: большие снаружи, маленькие между соседями (Material 3 Expressive). */
fun groupShape(i: Int, n: Int): RoundedCornerShape {
    val big = 24.dp; val small = 6.dp
    return RoundedCornerShape(
        topStart = if (i == 0) big else small, topEnd = if (i == 0) big else small,
        bottomStart = if (i == n - 1) big else small, bottomEnd = if (i == n - 1) big else small,
    )
}

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
    val recent = snap?.courses.orEmpty().flatMap { c -> c.items.filter { it.gradedAt > 0 && it.grade != "-" }.map { c to it } }
        .sortedByDescending { it.second.gradedAt }.take(3)
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().enter().glass(RoundedCornerShape(26.dp), 10.dp).clickable(onClick = onOpen).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Успеваемость", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            snap?.let { MoodleClient.averagePercent(it) }?.let { Text("${it.toInt()} %", style = MaterialTheme.typography.titleMedium, color = Brand.Accent) }
        }
        if (recent.isEmpty()) Text(if (snap == null) "Загружаю…" else "Новых оценок нет", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        recent.forEach { (c, i) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(gradeColor(i)))
                Text("${c.name} · ${i.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(i.grade, style = MaterialTheme.typography.titleSmall, color = gradeColor(i))
            }
        }
    }
}

/** Настройки: адрес ЛКС, логин и пароль; проверка входа сразу. */
@Composable
fun GradesSettingsSection() {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(app.grades.configured) }
    var url by remember { mutableStateOf(app.settings.gradesUrl().ifEmpty { MoodleClient.SIRIUS }) }
    var login by remember { mutableStateOf(app.settings.gradesLogin()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("Успеваемость (ЛКС)") {
        if (configured) {
            Text("Подключено: ${app.settings.gradesLogin()}", style = MaterialTheme.typography.titleSmall)
            Text(app.settings.gradesUrl(), style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            LinkText("Отключить") { app.grades.disconnect(); configured = false }
            return@SectionCard
        }
        FieldLabel("Оценки из личного кабинета", "Логин и пароль хранятся зашифрованными только на этом устройстве и уходят только на сайт ЛКС.")
        AppTextField(url, { url = it }, label = "Адрес сайта", placeholder = MoodleClient.SIRIUS)
        AppTextField(login, { login = it }, label = "Логин")
        AppTextField(
            password, { password = it }, label = "Пароль",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { StatusLine(error.orEmpty(), Tone.ERROR) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("Подключить", enabled = !busy && url.isNotBlank() && login.isNotBlank() && password.isNotBlank()) {
                busy = true; error = null
                scope.launch {
                    runCatching { app.grades.connect(url, login, password) }
                        .onSuccess { configured = true; password = "" }
                        .onFailure { error = it.message ?: "Не удалось войти" }
                    busy = false
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Accent, strokeWidth = 2.dp)
        }
    }
}

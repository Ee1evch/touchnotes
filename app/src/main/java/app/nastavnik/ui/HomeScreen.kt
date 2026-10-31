package app.nastavnik.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.ink.PageDrawer
import app.nastavnik.ink.Paper
import app.nastavnik.notes.NoteApps
import app.nastavnik.overlay.BubbleService
import app.nastavnik.study.Exam
import app.nastavnik.study.ExamPlan
import app.nastavnik.study.Streaks
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Главный экран — панель на сегодня, без длинных текстов:
 * слева тетради (продолжить с того места или начать новую) и наставник поверх своего приложения,
 * справа «Сегодня» (карточки, серия, экзамен) и остальные разделы списком.
 * На планшете — две колонки, на телефоне — одна.
 */
@Composable
fun HomeScreen(resumeTick: Int, go: (String) -> Unit, openNotebook: (Long) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val overlay = remember(resumeTick, tick) { Permissions.overlay(context) }
    val notReady = remember(resumeTick, tick) { context.app.settings.notReadyReason() }

    // Учебный день: карточки, серия, экзамен.
    val db = context.app.db
    val now = remember(resumeTick) { System.currentTimeMillis() }
    val due by remember(now) { db.cards().observeDueCount(now) }.collectAsStateWithLifecycle(initialValue = 0)
    val events by remember { db.activity().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = Streaks.day(now)
    val perDay = remember(events) { events.groupingBy { Streaks.day(it.at) }.eachCount() }
    val streak = remember(perDay, today) { Streaks.current(perDay.keys, today) }
    val week = remember(perDay, today) { Streaks.lastDays(perDay, today, 7) }
    val exam = remember(resumeTick) { context.app.study.nextExam() }

    Box(
        Modifier
            .fillMaxSize()
            .paperBackdrop()
            .systemBarsPadding(),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            val wide = maxWidth >= 860.dp
            Column(
                Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxWidth()
                    .padding(horizontal = if (wide) 32.dp else 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Header(now, go)

                if (!overlay) {
                    Banner("Разреши «Поверх других окон», чтобы кнопка наставника появлялась над записями.", "Разрешить") {
                        context.startActivity(Permissions.overlaySettingsIntent(context))
                    }
                }
                if (notReady != null) Banner("$notReady — без этого наставник не ответит.", "Настроить") { go(MainActivity.SCREEN_SETTINGS) }

                val todayPanel = @Composable { TodayPanel(due, streak, week, exam, go) }
                val sections = @Composable { SectionsList(go) }
                val notebooks = @Composable { NotebooksSection(resumeTick, go, openNotebook) }
                val mentor = @Composable { MentorStrip(resumeTick, tick, overlay) { tick++ } }

                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1.65f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            notebooks()
                            mentor()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            todayPanel()
                            sections()
                        }
                    }
                } else {
                    todayPanel()
                    notebooks()
                    mentor()
                    sections()
                }

                Text(
                    "Как это работает",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .clickable { go(MainActivity.SCREEN_ONBOARDING) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Шапка: приветствие по времени суток, дата, поиск и настройки. */
@Composable
private fun Header(now: Long, go: (String) -> Unit) {
    val hour = remember(now) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
    val date = remember(now) {
        SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru")).format(Date(now)).replaceFirstChar { it.uppercase() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(46.dp)
                .glass(RoundedCornerShape(15.dp), 6.dp, strong = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.headlineSmall)
            Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundIconButton(R.drawable.ic_search, "Поиск") { go(MainActivity.SCREEN_SEARCH) }
        Spacer(Modifier.width(10.dp))
        RoundIconButton(R.drawable.ic_tune, "Настройки") { go(MainActivity.SCREEN_SETTINGS) }
    }
}

/** Короткая плашка «нужно внимание» в одну строку. */
@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Error))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        InkButton(action, onClick = onAction)
    }
}

/** Заголовок раздела с действием справа. */
@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) LinkText(action, onClick = onAction)
    }
}

/** Тетради: «Новая» и последние — с превью страницы, чтобы сразу продолжить. */
@Composable
private fun NotebooksSection(resumeTick: Int, go: (String) -> Unit, openNotebook: (Long) -> Unit) {
    val context = LocalContext.current
    val nbs = context.app.notebooks
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val recent by produceState(emptyList<NotebookMeta>(), resumeTick) {
        value = withContext(Dispatchers.IO) { nbs.store.list().take(6) }
    }
    val importers = rememberImporters(
        onImages = { uris -> scope.launch { busy = true; nbs.importImages(uris)?.let { openNotebook(it.id) }; busy = false } },
        onPdf = { uri -> scope.launch { busy = true; nbs.importPdf(uri)?.let { openNotebook(it.id) }; busy = false } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Тетради", if (recent.isNotEmpty()) "Все тетради" else null) { go(MainActivity.SCREEN_NOTEBOOKS) }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NewNotebookCard(
                busy = busy,
                onNew = {
                    scope.launch {
                        val (m, _) = withContext(Dispatchers.IO) { nbs.store.create(nbs.defaultTitle(), Paper.GRID) }
                        openNotebook(m.id)
                    }
                },
                onPhoto = { importers.camera() },
                onPdf = { importers.pdf() },
            )
            recent.forEach { m -> RecentNotebook(m) { openNotebook(m.id) } }
        }
    }
}

private val CARD_W = 168.dp

/** Карточка «Новая тетрадь» с быстрым импортом фото и PDF. */
@Composable
private fun NewNotebookCard(busy: Boolean, onNew: () -> Unit, onPhoto: () -> Unit, onPdf: () -> Unit) {
    Column(Modifier.width(CARD_W), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.25f)
                .clip(RoundedCornerShape(20.dp))
                .background(Brand.Ink)
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Brand.OnInk, trackColor = Brand.OnInk.copy(alpha = 0.15f))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Brand.OnInk.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Brand.OnInk, modifier = Modifier.size(26.dp))
                    }
                    Text("Новая тетрадь", style = MaterialTheme.typography.titleSmall, color = Brand.OnInk)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction(R.drawable.ic_camera, "Фото", Modifier.weight(1f), onPhoto)
            MiniAction(R.drawable.ic_pdf, "PDF", Modifier.weight(1f), onPdf)
        }
    }
}

@Composable
private fun MiniAction(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(36.dp)
            .glass(CircleShape, 3.dp, strong = true)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru"))

/** Последняя тетрадь: превью первой страницы, название и когда писали. */
@Composable
private fun RecentNotebook(m: NotebookMeta, onOpen: () -> Unit) {
    val context = LocalContext.current
    val nbs = context.app.notebooks
    val thumb by produceState<ImageBitmap?>(null, m.id, m.updatedAt) {
        value = withContext(Dispatchers.IO) {
            val file = nbs.store.thumb(m.id)
            // Превью ещё нет (тетрадь из фото или PDF, в которой не писали) — рисуем его сейчас.
            if (!file.exists()) runCatching { nbs.store.loadPages(m).firstOrNull()?.let { nbs.saveThumb(m, it) } }
            file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    Column(
        Modifier
            .width(CARD_W)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.25f)
                .glass(RoundedCornerShape(20.dp), 8.dp, strong = true)
                .padding(6.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color(PageDrawer.PAPER))
                .border(0.6.dp, Color(0x1416161A), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.ic_notebook), contentDescription = null, tint = Color(0x4D16161A), modifier = Modifier.size(30.dp))
            }
            if (m.tasks.isNotEmpty()) {
                Text(
                    "Вариант",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF16161A))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${m.pages.size} стр. · ${dateFmt.format(Date(m.updatedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Наставник поверх своего приложения для записей: одна строка — состояние и кнопка. */
@Composable
private fun MentorStrip(resumeTick: Int, tick: Int, canStart: Boolean, changed: () -> Unit) {
    val context = LocalContext.current
    val running by BubbleService.runningFlow.collectAsStateWithLifecycle()
    val notesApp = remember(resumeTick) { NoteApps.chosen(context) }
    val missing = remember(resumeTick, tick) {
        listOfNotNull(
            "уведомления".takeIf { !Permissions.notifications(context) },
            "микрофон".takeIf { !Permissions.mic(context) },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), 10.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box {
                IconBadge(R.drawable.ic_sparkle, 46)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(Brand.Glass)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (running) Brand.Ok else Color(0xFFA3A2AB)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "Наставник включён" else "Наставник поверх ${notesApp?.label ?: "своего приложения"}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (running) "Нажми пальцем кнопку у края экрана — подсказка прямо на записях"
                    else "Кнопка у края экрана, стилусу не мешает",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (running) {
                SoftButton("Выключить", icon = R.drawable.ic_power) { BubbleService.stop(context); changed() }
            } else {
                InkButton("Запустить", enabled = canStart, icon = R.drawable.ic_power) { BubbleService.start(context); changed() }
            }
        }
        if (notesApp != null || missing.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (missing.isNotEmpty()) {
                    Text(
                        "Нет доступа: ${missing.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.Error,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (notesApp != null) ChipButton("Открыть ${notesApp.label}") { NoteApps.open(context, notesApp) }
            }
        }
    }
}

/** «Сегодня»: карточки на повторение, серия за неделю и ближайший экзамен. */
@Composable
private fun TodayPanel(due: Int, streak: Int, week: List<Int>, exam: Exam?, go: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), 12.dp)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Сегодня", style = MaterialTheme.typography.titleLarge)

        // Карточки: большое число и одна кнопка.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { go(MainActivity.SCREEN_REVIEW) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                if (due > 0) {
                    Text(due.toString(), style = MaterialTheme.typography.displaySmall)
                    Text(
                        ruPlural(due, "карточка ждёт повторения", "карточки ждут повторения", "карточек ждут повторения"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Всё повторено", style = MaterialTheme.typography.titleMedium)
                    Text("Новые карточки появятся из чистовиков", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (due > 0) InkButton("Повторить", icon = R.drawable.ic_cards) { go(MainActivity.SCREEN_REVIEW) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Серия: семь кружков — дни недели, когда занимался.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { go(MainActivity.SCREEN_PROGRESS) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = if (streak > 0) Brand.Theory else Brand.InkSoft, modifier = Modifier.size(22.dp))
            Text(
                if (streak > 0) "$streak ${ruPlural(streak, "день", "дня", "дней")} подряд" else "Начни серию сегодня",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                week.forEachIndexed { i, n ->
                    val isToday = i == week.lastIndex
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (n > 0) Brand.Ink else Brand.Line)
                            .then(if (isToday && n == 0) Modifier.border(1.2.dp, Brand.InkSoft, CircleShape) else Modifier),
                    )
                }
            }
        }

        // Экзамен — или приглашение добавить.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { go(MainActivity.SCREEN_EXAMS) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(22.dp))
            if (exam != null) {
                val days = ExamPlan.daysLeft(exam.at, System.currentTimeMillis())
                Text(
                    if (days == 0) "Сегодня экзамен: ${exam.subject}" else "${exam.subject}: экзамен через $days ${ruPlural(days, "день", "дня", "дней")}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (days <= 3) Brand.Error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text("Добавь экзамен — повторение подстроится", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
        }
    }
}

/** Остальные разделы — компактным списком, а не россыпью плиток. */
@Composable
private fun SectionsList(go: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), 8.dp)
            .padding(vertical = 6.dp),
    ) {
        val rows = listOf(
            Triple(R.drawable.ic_target, "Тренировка", "Вариант контрольной, мини-тест") to MainActivity.SCREEN_TRAINING,
            Triple(R.drawable.ic_chart, "Прогресс", "Серия и темы с ошибками") to MainActivity.SCREEN_PROGRESS,
            Triple(R.drawable.ic_history, "История", "Задачи с наставником и чистовики") to MainActivity.SCREEN_HISTORY,
            Triple(R.drawable.ic_note, "Чистовик", "Записи → аккуратный PDF") to MainActivity.SCREEN_CLEAN,
        )
        rows.forEachIndexed { i, (spec, screen) ->
            SectionRow(spec.first, spec.second, spec.third) { go(screen) }
            if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ColumnScope.SectionRow(icon: Int, title: String, caption: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, 40)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
    }
}

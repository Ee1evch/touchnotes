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
import app.nastavnik.schedule.Lesson
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

    var editing by remember { mutableStateOf(false) }

    val enterOrder = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    androidx.compose.runtime.CompositionLocalProvider(LocalEnterOrder provides enterOrder) {
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
                val app = context.app
                val version by app.schedule.version.collectAsStateWithLifecycle()
                val clock by produceState(java.time.LocalDateTime.now(), resumeTick) { while (true) { value = java.time.LocalDateTime.now(); kotlinx.coroutines.delay(30_000) } }
                val lessons by produceState<List<Lesson>?>(null, version, resumeTick, clock.toLocalDate()) { value = app.schedule.day(java.time.LocalDate.now()) }
                val tomorrow by produceState<List<Lesson>>(emptyList(), version, resumeTick) { value = app.schedule.day(java.time.LocalDate.now().plusDays(1)) }
                val hasGroup = app.settings.scheduleGroup().isNotBlank()
                HomeHeader(clock, dayStatus(lessons, clock, hasGroup)) {
                    RoundIconButton(R.drawable.ic_pen, "Изменить главную") { editing = !editing }
                    RoundIconButton(R.drawable.ic_search, "Поиск") { go(MainActivity.SCREEN_SEARCH) }
                    RoundIconButton(R.drawable.ic_tune, "Настройки") { go(MainActivity.SCREEN_SETTINGS) }
                }

                InAppUpdateBanner()

                if (!overlay) {
                    Banner("Разреши «Поверх других окон», чтобы кнопка наставника появлялась над записями.", "Разрешить") {
                        context.startActivity(Permissions.overlaySettingsIntent(context))
                    }
                }
                if (notReady != null) Banner("$notReady — без этого наставник не ответит.", "Настроить") { go(MainActivity.SCREEN_SETTINGS) }
                // Блоки — из конструктора: порядок и размер правятся кнопкой «Изменить главную».
                HomeBoard(
                    tablet = true,
                    editing = editing,
                    onDoneEditing = { editing = false },
                    resumeTick = resumeTick,
                    go = go,
                    onSchedule = { go(MainActivity.SCREEN_SCHEDULE) },
                    extra = mapOf(
                        "notebooks" to { NotebooksSection(resumeTick, go, openNotebook) },
                        "mentor" to { MentorStrip(resumeTick, tick, overlay) { tick++ } },
                    ),
                )
            }
        }
    }
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
internal fun NotebooksSection(resumeTick: Int, go: (String) -> Unit, openNotebook: (Long) -> Unit) {
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
                .background(Brand.Accent)
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
                .enter().glass(RoundedCornerShape(20.dp), 8.dp, strong = true)
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
internal fun MentorStrip(resumeTick: Int, tick: Int, canStart: Boolean, changed: () -> Unit) {
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
            .enter().glass(RoundedCornerShape(26.dp), 10.dp)
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

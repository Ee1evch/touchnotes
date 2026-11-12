package app.nastavnik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nastavnik.R
import app.nastavnik.ai.Mark
import app.nastavnik.ai.QuickAction
import app.nastavnik.app
import app.nastavnik.ink.PageDrawer
import app.nastavnik.ink.Paper
import app.nastavnik.ink.Tool
import app.nastavnik.overlay.NoteColors
import app.nastavnik.overlay.SpeechInput
import app.nastavnik.session.AnswerUi
import app.nastavnik.session.AskKind
import app.nastavnik.study.ruPlural
import app.nastavnik.study.TrainResult
import app.nastavnik.study.PracticeTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Тетрадь: пишешь стилусом прямо в приложении, а наставник рядом — в панели справа. Его заметки не закрывают
 * записи: на листе только подсветка и номер, а текст — в панели под тем же номером.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookScreen(notebookId: Long, onBack: () -> Unit, onCleanReady: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctl = remember(notebookId) { NotebookController(context.app, notebookId, scope) }
    var tips by remember { mutableStateOf(false) }
    LaunchedEffect(notebookId) {
        ctl.load()
        // Первый раз — коротко, как устроен лист.
        if (!context.app.settings.editorTipsShown()) tips = true
        // Прогреваем движок WebView (им рисуются формулы в подсказках): первый запуск долгий,
        // пусть он случится сейчас, а не в момент, когда пришёл ответ наставника.
        delay(600)
        runCatching { android.webkit.WebView(context).destroy() }
    }
    DisposableEffect(ctl) { onDispose { ctl.close() } }
    val close = {
        ctl.close()
        onBack()
    }
    BackHandler(onBack = close)

    val state by ctl.mentor.state.collectAsState()
    LaunchedEffect(state.answer, ctl.index) { ctl.refreshMarks(state.answer) }

    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var panel by rememberSaveable { mutableStateOf(false) }
    var pagesOpen by rememberSaveable { mutableStateOf(false) }
    val importers = rememberImporters(onImages = { ctl.addImages(it) }, onPdf = { ctl.addPdf(it) })
        // Лист всегда белый, как бумага; от темы зависит только стол вокруг него.
    val dark = Brand.dark
    val desk = Brand.Desk.toArgb()
    SideEffect { ctl.ink.setTheme(dark, desk) }
    // Ответ на явный вопрос открывает панель; фоновая проверка не закрывает лист.
    LaunchedEffect(state.answer?.seq) { if (state.answer != null && state.answer?.kind != app.nastavnik.session.AskKind.WATCH) panel = true }

    if (tips) EditorTips { tips = false; context.app.settings.setEditorTipsShown() }
    if (pagesOpen) PagesOverview(ctl, onAdd = { ctl.addPage(ctl.page?.paper ?: Paper.GRID) }) { pagesOpen = false }
    TextDialog(ctl)

    Box(
        Modifier
            .fillMaxSize()
            .paperBackdrop()
            .systemBarsPadding(),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                NotebookTopBar(ctl, panel, onBack = close, onTogglePanel = { panel = !panel }, onPages = { pagesOpen = true }, importers = importers)
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = if (panel && wide) 0.dp else 12.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    AndroidView(factory = { ctl.ink }, modifier = Modifier.fillMaxSize())
                    SelectionMenu(ctl)
                    EditorHint(ctl, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    // Лист увеличен — маленькая плашка с масштабом; нажатие возвращает по ширине.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = abs(ctl.zoom - 1f) > 0.04f,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                        enter = fadeIn() + scaleIn(initialScale = 0.8f),
                        exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    ) {
                        Text(
                            "${(ctl.zoom * 100).roundToInt()} %",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .glass(CircleShape, 6.dp, strong = true)
                                .clickable { ctl.fitWidth() }
                                .semantics { contentDescription = "По ширине листа" }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    // Страница задачи ДЗ — плашка с проверкой.
                    HomeworkBar(ctl, Modifier.align(Alignment.BottomCenter).padding(start = 72.dp, end = 16.dp, bottom = 56.dp))
                    // Инструменты — плашкой сбоку, цвета открываются по касанию.
                    EditorRail(ctl, maxHeight)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !ctl.loaded,
                        modifier = Modifier.align(Alignment.Center),
                        exit = fadeOut(),
                    ) { CircularProgressIndicator(color = Brand.Accent) }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = panel && wide,
                enter = expandHorizontally(expandFrom = Alignment.Start, animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)) + fadeIn(tween(240, delayMillis = 60)),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start, animationSpec = spring(dampingRatio = 0.88f, stiffness = 480f)) + fadeOut(tween(160)),
            ) {
                MentorPanel(
                    ctl, state.answer, onCleanReady = onCleanReady, onSettings = onSettings,
                    modifier = Modifier.width(390.dp).fillMaxHeight().padding(12.dp),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = panel && !wide,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { it } + fadeOut(),
        ) {
            MentorPanel(
                ctl, state.answer, onCleanReady = onCleanReady, onSettings = onSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                    .padding(12.dp),
            )
        }
    }
}

// ---------------- верхняя панель ----------------

@Composable
private fun NotebookTopBar(
    ctl: NotebookController,
    panel: Boolean,
    onBack: () -> Unit,
    onTogglePanel: () -> Unit,
    onPages: () -> Unit,
    importers: Importers,
) {
    var renaming by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var paperDialog by remember { mutableStateOf(false) }
    var examDialog by remember { mutableStateOf(false) }
    if (paperDialog) PaperDialog(ctl) { paperDialog = false }
    if (examDialog) ExamDialog(onStart = { m -> examDialog = false; ctl.startExam(m) }, onDismiss = { examDialog = false })
    val meta = ctl.meta
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
        Text(
            meta?.title ?: "Тетрадь",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable { renaming = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        // Страницы: стрелки и номер; номер открывает обзор всех страниц.
        Row(
            Modifier.glass(CircleShape, 4.dp, strong = true).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(R.drawable.ic_chevron_left, "Предыдущая страница", enabled = ctl.index > 0) { ctl.showPage(ctl.index - 1) }
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onPages)
                    .semantics { contentDescription = "Все страницы" }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                AnimatedContent(
                    targetState = ctl.index,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInVertically { h -> h * dir } + fadeIn()) togetherWith (slideOutVertically { h -> -h * dir } + fadeOut())
                    },
                    label = "page",
                ) { i ->
                    Text("${i + 1} / ${ctl.pages.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelLarge)
                }
            }
            BarIcon(R.drawable.ic_chevron, "Следующая страница", enabled = ctl.index < ctl.pages.lastIndex) { ctl.showPage(ctl.index + 1) }
        }
        BarButton(R.drawable.ic_undo, "Отменить", enabled = ctl.canUndo) { ctl.undo() }
        BarButton(R.drawable.ic_redo, "Вернуть", enabled = ctl.canRedo) { ctl.redo() }
        // Всё остальное — в одном меню: страницы, бумага, контрольная, проверка.
        Box {
            BarButton(R.drawable.ic_more, "Ещё") { moreMenu = true }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                MenuItem("Новая страница", R.drawable.ic_plus) { moreMenu = false; ctl.addPage(ctl.page?.paper ?: Paper.GRID) }
                MenuItem("Фото или PDF как страница", R.drawable.ic_camera) { moreMenu = false; addMenu = true }
                MenuItem("Все страницы", R.drawable.ic_pages) { moreMenu = false; onPages() }
                MenuItem("Бумага…", R.drawable.ic_notebook) { moreMenu = false; paperDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MenuItem("Контрольная на время…", R.drawable.ic_timer) { moreMenu = false; examDialog = true }
                MenuItem((if (ctl.autoCheck) "✓ " else "") + "Проверять, когда остановлюсь", R.drawable.ic_eye) { moreMenu = false; ctl.toggleAutoCheck() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MenuItem("Переименовать", R.drawable.ic_pen) { moreMenu = false; renaming = true }
                MenuItem("Очистить страницу", R.drawable.ic_eraser) { moreMenu = false; ctl.clearPage() }
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                MenuItem("Сфотографировать", R.drawable.ic_camera) { addMenu = false; importers.camera() }
                MenuItem("Фото из галереи", R.drawable.ic_image) { addMenu = false; importers.gallery() }
                MenuItem("Страницы из PDF", R.drawable.ic_pdf) { addMenu = false; importers.pdf() }
            }
        }
        val mentorAction = if (panel) "Скрыть наставника" else "Показать наставника"
        BarButton(R.drawable.ic_sparkle, mentorAction, active = panel, onClick = onTogglePanel)
    }
    if (renaming && meta != null) {
        var text by remember { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Название тетради") },
            text = { AppTextField(text, { text = it }, "Например: Матанализ, пределы") },
            confirmButton = { InkButton("Сохранить") { ctl.rename(text); renaming = false } },
            dismissButton = { SoftButton("Отмена") { renaming = false } },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** Круглая стеклянная кнопка панели; [active] — включённое состояние (графитовая). */
@Composable
private fun BarButton(icon: Int, description: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        active -> Brand.OnInk
        enabled -> Brand.Ink
        else -> Brand.Ink.copy(alpha = 0.3f)
    }
    Box(
        Modifier
            .size(44.dp)
            .then(if (active) Modifier.clip(CircleShape).background(Brand.Accent) else Modifier.glass(CircleShape, 4.dp, strong = true))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun BarIcon(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (enabled) Brand.Ink else Brand.Ink.copy(alpha = 0.25f), modifier = Modifier.size(20.dp))
    }
}

/** Бумага: превью и выбор — для этой страницы или для всей тетради. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaperDialog(ctl: NotebookController, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    val current = ctl.page?.paper
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Бумага") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Paper.entries.forEach { p ->
                        val on = p == current
                        Column(
                            Modifier
                                .width(92.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (all) ctl.setPaperAll(p) else ctl.setPaper(p)
                                    onDismiss()
                                }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f / 1.414f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(PageDrawer.PAPER))
                                    .border(if (on) 2.dp else 1.dp, if (on) Brand.Accent else Brand.Line, RoundedCornerShape(10.dp)),
                            ) { PaperPreview(p) }
                            Text(p.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { all = !all }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Для всей тетради", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    AppSwitch(all) { all = it }
                }
                if (ctl.page?.background != null) {
                    Text("На фото и страницах PDF разлиновка рисуется поверх картинки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { SoftButton("Закрыть", onClick = onDismiss) },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(22.dp).background(Brand.Ink.copy(alpha = 0.12f)))
}

// ---------------- наставник ----------------

/** Быстрые вопросы наставнику: значок, название и что он сделает. */
private class Ask(val action: QuickAction, val icon: Int, val title: String, val hint: String)

private val ASKS = listOf(
    Ask(QuickAction.NEXT_STEP, R.drawable.ic_bulb, "Подсказка", "что дальше"),
    Ask(QuickAction.FIND_ERROR, R.drawable.ic_search, "Где ошибка?", "отметит её"),
    Ask(QuickAction.CHECK_ANSWER, R.drawable.ic_check, "Проверить", "мой ответ"),
    Ask(QuickAction.THEORY, R.drawable.ic_book, "Теория", "с примером"),
    Ask(QuickAction.SHOW_STEP, R.drawable.ic_chevron, "Покажи шаг", "один шаг"),
    Ask(QuickAction.SIMILAR, R.drawable.ic_refresh, "Похожая", "для тренировки"),
)

/**
 * Панель наставника. Два состояния: «Вопросы» (что спросить) и «Ответ». Пока есть ответ, панель целиком
 * отдана ему — без кнопок и полей; стрелка «Вопросы» вверху возвращает к вопросам, а ответ остаётся
 * под рукой («Последний ответ»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MentorPanel(ctl: NotebookController, answer: AnswerUi?, onCleanReady: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    // Новый ответ — сразу открываем его; «Вопросы» — возвращаемся.
    var showAnswer by remember { mutableStateOf(false) }
    LaunchedEffect(answer?.seq) { showAnswer = answer != null }

    fun ask(text: String, kind: AskKind) {
        val reason = context.app.settings.notReadyReason()
        if (reason != null) {
            problem = "$reason. Открой настройки → «Нейросеть»."
            return
        }
        problem = null
        ctl.ask(text, kind)
    }

    Box(modifier.glass(RoundedCornerShape(28.dp), 14.dp)) {
        AnimatedContent(
            targetState = showAnswer && answer != null,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(260)))
                        .togetherWith(slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(180)))
                }
            },
            label = "mentorMode",
        ) { answerMode ->
            if (answerMode && answer != null) {
                AnswerView(ctl, answer, onBack = { showAnswer = false }, onSettings = onSettings, onRetry = { ask(answer.question, answer.kind) })
            } else {
                AskView(ctl, answer, problem, onSettings, onCleanReady, onOpenAnswer = { showAnswer = true }, ask = ::ask)
            }
        }
    }
}

/** Только ответ: на всю панель, сверху — вернуться к вопросам. */
@Composable
private fun AnswerView(ctl: NotebookController, answer: AnswerUi, onBack: () -> Unit, onSettings: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .semantics { contentDescription = "К вопросам" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(20.dp))
            Text("Вопросы", style = MaterialTheme.typography.labelLarge)
        }
        if (answer.marks.any { it.box != null }) {
            SoftButton(if (ctl.marksVisible) "Скрыть выделение" else "Показать выделение", icon = if (ctl.marksVisible) R.drawable.ic_eye_off else R.drawable.ic_eye) {
                ctl.toggleMarks()
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                answer.errorText != null -> {
                    StatusLine(answer.errorText, Tone.ERROR)
                    if (answer.error?.needsSettings == true) LinkText("Открыть настройки", onClick = onSettings)
                    else SoftButton("Повторить", icon = R.drawable.ic_refresh, onClick = onRetry)
                }
                else -> {
                    if (answer.streaming && answer.marks.isEmpty()) ThinkingRow(answer.seq, answer.status)
                    answer.marks.forEachIndexed { i, m ->
                        NoteCard(i + 1, m, topic = ctl.meta?.title.orEmpty(), onLocate = { ctl.focusMark(i + 1) })
                    }
                    if (answer.streaming && answer.marks.isNotEmpty()) ThinkingRow(answer.seq, "Дописываю…")
                }
            }
        }
    }
}

/** Вопросы наставнику: плитки с понятными подписями, свой вопрос, проверка «когда остановлюсь». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskView(
    ctl: NotebookController,
    answer: AnswerUi?,
    problem: String?,
    onSettings: () -> Unit,
    onCleanReady: () -> Unit,
    onOpenAnswer: () -> Unit,
    ask: (String, AskKind) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speechProblem by remember { mutableStateOf<String?>(null) }
    var selectPages by remember { mutableStateOf(false) }
    val speech = remember {
        SpeechInput(context, object : SpeechInput.Callback {
            override fun onListening(active: Boolean) { listening = active }
            override fun onPartial(text: String) { question = text }
            override fun onResult(text: String) { question = text }
            override fun onError(message: String, needsPermission: Boolean) { speechProblem = message }
        })
    }
    DisposableEffect(Unit) { onDispose { speech.destroy() } }
    val busy = answer?.streaming == true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(R.drawable.ic_sparkle, 32)
            Text("Наставник", style = MaterialTheme.typography.titleMedium)
        }
        if (ctl.examActive) {
            ExamCard(ctl)
            return@Column
        }
        ctl.meta?.tasks?.takeIf { it.isNotEmpty() }?.let { VariantCard(it) }
        (problem ?: speechProblem)?.let { p ->
            StatusLine(p, Tone.ERROR)
            if (p.contains("настройки")) LinkText("Открыть настройки", onClick = onSettings)
        }
        // Последний ответ под рукой — одно касание, и он снова на всю панель.
        if (answer != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brand.Line)
                    .clickable(onClick = onOpenAnswer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (busy) "Наставник отвечает…" else "Последний ответ", style = MaterialTheme.typography.labelLarge)
                    answer.marks.firstOrNull()?.text?.let {
                        Text(it.replace(Regex("[$*_]"), ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
            }
            // Решение верное — дальше чистовик или такая же задача.
            if (answer.done && !busy) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Сделать чистовик", icon = R.drawable.ic_note) {
                        scope.launch {
                            val images = ctl.pageImages()
                            val clean = context.app.clean
                            clean.cancel()
                            clean.addImages(images)
                            clean.build()
                            onCleanReady()
                        }
                    }
                }
            }
        }
        SoftButton("Страницы для наставника", icon = R.drawable.ic_note) { selectPages = true }
        // Плитки вопросов: два столбца, понятные подписи.
        ASKS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a -> AskTile(a, enabled = !busy, modifier = Modifier.weight(1f)) { ask(a.action.prompt, AskKind.QUICK) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                AppTextField(
                    question, { question = it }, if (listening) "Говори…" else "Свой вопрос",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (question.isNotBlank()) { ask(question.trim(), AskKind.USER); question = "" } }),
                )
            }
            BarButton(R.drawable.ic_mic, if (listening) "Остановить запись" else "Сказать голосом", active = listening) { speech.toggle() }
            BarButton(R.drawable.ic_send, "Отправить", enabled = question.isNotBlank() && !busy) {
                ask(question.trim(), AskKind.USER)
                question = ""
            }
        }
    }
    if (selectPages) {
        AlertDialog(
            onDismissRequest = { selectPages = false },
            title = { Text("Страницы для наставника") },
            text = {
                Column {
                    Text("Текущая страница отправляется всегда.")
                    Row {
                        LinkText("Все", onClick = { ctl.selectAllContextPages() })
                        Spacer(Modifier.width(24.dp))
                        LinkText("Снять все", onClick = { ctl.selectNoContextPages() })
                    }
                    Column(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                        ctl.pages.forEachIndexed { i, p ->
                            val current = i == ctl.index
                            val checked = current || ctl.selectedContextPages?.contains(p.id) != false
                            Row(Modifier.fillMaxWidth().clickable(enabled = !current) { ctl.selectContextPage(p.id, !checked) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, enabled = !current, onCheckedChange = { ctl.selectContextPage(p.id, it) })
                                Text("Страница ${i + 1}" + if (current) " · текущая" else "")
                            }
                        }
                    }
                }
            },
            confirmButton = { LinkText("Готово", onClick = { selectPages = false }) },
        )
    }
}

/** Плитка вопроса: значок, название и коротко — что будет. Нажатие «проседает». */
@Composable
private fun AskTile(a: Ask, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "tile")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(18.dp))
            .background(Brand.Glass.copy(alpha = 0.7f))
            .border(0.6.dp, Brand.Line, RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = a.title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(a.icon), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(a.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Наставник думает: три «дышащие» точки и секунды — видно, что работа идёт. */
@Composable
private fun ThinkingRow(seq: Long, status: String?) {
    val start = remember(seq) { System.currentTimeMillis() }
    val seconds by produceState(0, seq) {
        while (true) {
            value = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(250)
        }
    }
    val pulse = rememberInfiniteTransition(label = "thinking")
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), 4.dp, strong = true)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                val a by pulse.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 160, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = a; scaleX = 0.7f + 0.3f * a; scaleY = 0.7f + 0.3f * a }
                        .clip(CircleShape)
                        .background(Brand.Ink),
                )
            }
        }
        Text(status ?: "Думаю…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (seconds > 0) Text("$seconds с", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyMentor() {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Пиши на листе, а когда застрянешь — нажми «Подсказка».", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Наставник отметит нужную строку прямо на листе, а текст подсказки будет здесь, под тем же номером — " +
                "записи он не закроет. Решать за тебя он не будет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Заметка наставника в панели: номер как на листе, вид, короткая подсказка и «Подробнее». */
@Composable
private fun NoteCard(number: Int, m: Mark, topic: String, onLocate: () -> Unit) {
    val context = LocalContext.current
    val speak: (String) -> Unit = context.app.speaker::speak
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(number, m.text) { mutableStateOf(false) }
    // «В карточки»: null — не нажимали, иначе итог (добавлено или почему нет).
    var carded by remember(m.text) { mutableStateOf<String?>(null) }
    var carding by remember(m.text) { mutableStateOf(false) }
    val kindColor = Color(PageDrawer.kindColor(m.kind))
    // Заметка въезжает снизу и проявляется — видно, что пришло новое.
    val appear = remember(number) { Animatable(0f) }
    LaunchedEffect(number) { appear.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1 - appear.value) * 18.dp.toPx()
            }
            .animateContentSize(tween(220))
            .glass(RoundedCornerShape(20.dp), 4.dp, strong = true)
            .clickable(enabled = m.box != null, onClick = onLocate)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(kindColor), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Text(NoteColors.title(m.kind), style = MaterialTheme.typography.labelLarge, color = kindColor)
            Spacer(Modifier.weight(1f))
            if (m.box != null) Text("показать ↗", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { speak(if (expanded && m.detail != null) "${m.text}. ${m.detail}" else m.text) }
                    .semantics { contentDescription = "Прочитать вслух" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_volume), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
            }
        }
        RichText(m.text, sizePx = 16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.detail != null) LinkText(if (expanded) "Коротко ↑" else "Подробнее ↓") { expanded = !expanded }
            Spacer(Modifier.weight(1f))
            when {
                carding -> Text("Делаю карточку…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                carded != null -> Text(carded!!, style = MaterialTheme.typography.labelMedium, color = if (carded!!.startsWith("✓")) Brand.Ok else Brand.Error)
                else -> LinkText("В карточки +") {
                    carding = true
                    scope.launch {
                        val hint = if (m.detail != null) "${m.text}\n\n${m.detail}" else m.text
                        carded = when (val r = context.app.trainer.cardFromHint(hint, topic)) {
                            is TrainResult.Ok -> "✓ В повторении"
                            is TrainResult.Failed -> r.message
                        }
                        carding = false
                    }
                }
            }
        }
        if (m.detail != null && expanded) RichText(m.detail, sizePx = 15)
    }
}

/** Задачи тренировочного варианта: условия всегда под рукой, ответы знает только наставник. */
@Composable
private fun VariantCard(tasks: List<PracticeTask>) {
    var open by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), 4.dp, strong = true)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Вариант · ${tasks.size} ${ruPlural(tasks.size, "задача", "задачи", "задач")}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(if (open) "свернуть" else "показать", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            tasks.forEachIndexed { i, t ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
                    RichText(t.task, sizePx = 15)
                }
            }
            Text(
                "Решай по порядку на листе. «Проверь ответ» сверит с верным ответом, а «Подсказка» не выдаст его раньше времени.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Идёт контрольная: сколько осталось, «Сдать работу». Подсказок нет — как на настоящей контрольной. */
@Composable
private fun ExamCard(ctl: NotebookController) {
    val left by produceState(ctl.examEndsAt - System.currentTimeMillis(), ctl.examEndsAt) {
        while (true) {
            value = (ctl.examEndsAt - System.currentTimeMillis()).coerceAtLeast(0)
            delay(1000)
        }
    }
    val min = left / 60_000
    val sec = (left / 1000) % 60
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brand.Accent)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_timer), contentDescription = null, tint = Brand.OnInk, modifier = Modifier.size(22.dp))
            Text("Контрольная · осталось %d:%02d".format(min, sec), style = MaterialTheme.typography.titleMedium, color = Brand.OnInk)
        }
        Text(
            "Наставник молчит, пока не сдашь работу. Потом он проверит всё целиком и поставит оценку.",
            style = MaterialTheme.typography.bodySmall,
            color = Brand.OnInk.copy(alpha = 0.75f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Brand.OnInk)
                    .clickable { ctl.submitExam() }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) { Text("Сдать работу", style = MaterialTheme.typography.labelLarge, color = Brand.Ink) }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Brand.OnInk.copy(alpha = 0.14f))
                    .clickable { ctl.cancelExam() }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) { Text("Прервать", style = MaterialTheme.typography.labelLarge, color = Brand.OnInk) }
        }
    }
}

@Composable
private fun ExamDialog(onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(45) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Контрольная") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Реши задачи в тетради без подсказок. Когда время выйдет или ты нажмёшь «Сдать работу», наставник " +
                        "проверит всё целиком, отметит ошибки и поставит оценку от 2 до 5.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Segmented(listOf(20 to "20 мин", 45 to "45 мин", 60 to "60 мин", 90 to "90 мин"), minutes) { minutes = it }
            }
        },
        confirmButton = { InkButton("Начать", icon = R.drawable.ic_timer) { onStart(minutes) } },
        dismissButton = { SoftButton("Отмена", onClick = onDismiss) },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    )
}

/** Переключатель в общем стиле: графитовый, когда включён. */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = Brand.Accent,
            checkedThumbColor = Brand.OnInk,
            uncheckedTrackColor = Brand.Accent.copy(alpha = 0.12f),
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Brand.Ink.copy(alpha = 0.2f),
        ),
    )
}

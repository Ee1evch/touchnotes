package app.nastavnik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    LaunchedEffect(notebookId) { ctl.load() }
    DisposableEffect(ctl) { onDispose { ctl.close() } }
    val close = {
        ctl.close()
        onBack()
    }
    BackHandler(onBack = close)

    val state by ctl.mentor.state.collectAsState()
    LaunchedEffect(state.answer, ctl.index) { ctl.refreshMarks(state.answer) }

    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var panel by rememberSaveable { mutableStateOf(wide) }
    val importers = rememberImporters(onImages = { ctl.addImages(it) }, onPdf = { ctl.addPdf(it) })

    Box(
        Modifier
            .fillMaxSize()
            .paperBackdrop()
            .systemBarsPadding(),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                NotebookTopBar(ctl, panel, onBack = close, onTogglePanel = { panel = !panel }, importers = importers)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = if (panel && wide) 0.dp else 12.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    AndroidView(factory = { ctl.ink }, modifier = Modifier.fillMaxSize())
                    ToolPalette(ctl, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                    if (!ctl.loaded) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Brand.Ink)
                }
            }
            if (panel && wide) {
                MentorPanel(
                    ctl, state.answer, onCleanReady = onCleanReady, onSettings = onSettings,
                    modifier = Modifier.width(380.dp).fillMaxHeight().padding(12.dp),
                )
            }
        }
        if (panel && !wide) {
            MentorPanel(
                ctl, state.answer, onCleanReady = onCleanReady, onSettings = onSettings,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                    .padding(12.dp),
            )
        }
    }
}

// ---------------- верхняя панель ----------------

@Composable
private fun NotebookTopBar(ctl: NotebookController, panel: Boolean, onBack: () -> Unit, onTogglePanel: () -> Unit, importers: Importers) {
    var renaming by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
        // Страницы.
        Row(
            Modifier.glass(CircleShape, 4.dp, strong = true).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(R.drawable.ic_chevron_left, "Предыдущая страница", enabled = ctl.index > 0) { ctl.showPage(ctl.index - 1) }
            Text("${ctl.index + 1} / ${ctl.pages.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
            BarIcon(R.drawable.ic_chevron, "Следующая страница", enabled = ctl.index < ctl.pages.lastIndex) { ctl.showPage(ctl.index + 1) }
        }
        Box {
            BarButton(R.drawable.ic_plus, "Добавить страницу") { addMenu = true }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Paper.entries.forEach { p ->
                    MenuItem("Лист: ${p.label.lowercase()}", R.drawable.ic_notebook) { addMenu = false; ctl.addPage(p) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MenuItem("Сфотографировать", R.drawable.ic_camera) { addMenu = false; importers.camera() }
                MenuItem("Фото из галереи", R.drawable.ic_image) { addMenu = false; importers.gallery() }
                MenuItem("Страницы из PDF", R.drawable.ic_pdf) { addMenu = false; importers.pdf() }
            }
        }
        BarButton(R.drawable.ic_undo, "Отменить", enabled = ctl.canUndo) { ctl.undo() }
        BarButton(R.drawable.ic_redo, "Вернуть", enabled = ctl.canRedo) { ctl.redo() }
        Box {
            BarButton(R.drawable.ic_more, "Ещё") { moreMenu = true }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Paper.entries.forEach { p ->
                    MenuItem(
                        (if (ctl.page?.paper == p) "✓ " else "") + "Бумага: ${p.label.lowercase()}",
                        R.drawable.ic_notebook,
                    ) { moreMenu = false; ctl.setPaper(p) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MenuItem("Переименовать", R.drawable.ic_pen) { moreMenu = false; renaming = true }
                MenuItem("Очистить страницу", R.drawable.ic_eraser) { moreMenu = false; ctl.clearPage() }
                if (ctl.pages.size > 1) MenuItem("Удалить страницу", R.drawable.ic_trash) { moreMenu = false; confirmDelete = true }
            }
        }
        BarButton(R.drawable.ic_sparkle, if (panel) "Скрыть наставника" else "Показать наставника", active = panel, onClick = onTogglePanel)
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
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить страницу?") },
            text = { Text("Всё, что на ней написано, пропадёт.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { InkButton("Удалить", danger = true) { ctl.deletePage(); confirmDelete = false } },
            dismissButton = { SoftButton("Отмена") { confirmDelete = false } },
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
            .then(if (active) Modifier.clip(CircleShape).background(Brand.Ink) else Modifier.glass(CircleShape, 4.dp, strong = true))
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

// ---------------- палитра ----------------

@Composable
private fun ToolPalette(ctl: NotebookController, modifier: Modifier) {
    Row(
        modifier
            .glass(CircleShape, 12.dp, strong = true)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NotebookController.PENS.forEach { c ->
            Swatch(Color(c), selected = ctl.tool == Tool.PEN && !ctl.eraser && ctl.penColor == c, description = penName(c)) { ctl.pickPen(c) }
        }
        Divider()
        NotebookController.MARKERS.forEach { c ->
            Swatch(Color(c), selected = ctl.tool == Tool.HIGHLIGHTER && !ctl.eraser && ctl.markerColor == c, description = "Маркер", marker = true) { ctl.pickMarker(c) }
        }
        Divider()
        ToolIcon(R.drawable.ic_eraser, "Ластик", ctl.eraser) { ctl.pickEraser() }
        // Толщина: три точки разного размера.
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { ctl.cycleWidth() }
                .semantics { contentDescription = "Толщина ручки" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size((5 + ctl.widthIndex * 4).dp).clip(CircleShape).background(Brand.Ink))
        }
        ToolIcon(R.drawable.ic_hand, if (ctl.fingerDraws) "Пальцем пишу" else "Пальцем двигаю лист", ctl.fingerDraws) { ctl.toggleFinger() }
    }
}

private fun penName(c: Int) = when (c) {
    NotebookController.PENS[0] -> "Синяя ручка"
    NotebookController.PENS[1] -> "Чёрная ручка"
    NotebookController.PENS[2] -> "Красная ручка"
    else -> "Зелёная ручка"
}

@Composable
private fun Swatch(color: Color, selected: Boolean, description: String, marker: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 30.dp else 24.dp)
                .clip(if (marker) RoundedCornerShape(7.dp) else CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(3.dp, Color.White, if (marker) RoundedCornerShape(7.dp) else CircleShape) else Modifier),
        )
        if (selected) Box(Modifier.size(34.dp).border(1.5.dp, Brand.Ink, if (marker) RoundedCornerShape(9.dp) else CircleShape))
    }
}

@Composable
private fun ToolIcon(icon: Int, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) Brand.Ink else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (active) Brand.OnInk else Brand.Ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(22.dp).background(Brand.Ink.copy(alpha = 0.12f)))
}

// ---------------- наставник ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MentorPanel(ctl: NotebookController, answer: AnswerUi?, onCleanReady: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var examDialog by remember { mutableStateOf(false) }
    if (examDialog) {
        ExamDialog(onStart = { m -> examDialog = false; ctl.startExam(m) }, onDismiss = { examDialog = false })
    }
    val speech = remember {
        SpeechInput(context, object : SpeechInput.Callback {
            override fun onListening(active: Boolean) { listening = active }
            override fun onPartial(text: String) { question = text }
            override fun onResult(text: String) { question = text }
            override fun onError(message: String, needsPermission: Boolean) { problem = message }
        })
    }
    DisposableEffect(Unit) { onDispose { speech.destroy() } }

    fun ask(text: String, kind: AskKind) {
        val reason = context.app.settings.notReadyReason()
        if (reason != null) {
            problem = "$reason. Открой настройки → «Нейросеть»."
            return
        }
        problem = null
        ctl.ask(text, kind)
    }

    Column(
        modifier
            .glass(RoundedCornerShape(28.dp), 14.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(R.drawable.ic_sparkle, 36)
            Column(Modifier.weight(1f)) {
                Text("Наставник", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Видит всю тетрадь: ${ctl.pages.size} ${ruPlural(ctl.pages.size, "страницу", "страницы", "страниц")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ctl.examActive) {
            ExamCard(ctl)
        } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                QuickAction.NEXT_STEP, QuickAction.FIND_ERROR, QuickAction.CHECK_ANSWER,
                QuickAction.THEORY, QuickAction.SHOW_STEP, QuickAction.SIMILAR,
            ).forEach { a ->
                ChipButton(a.short, enabled = answer?.streaming != true) { ask(a.prompt, AskKind.QUICK) }
            }
            ChipButton("Контрольная", enabled = answer?.streaming != true) { examDialog = true }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                AppTextField(
                    question, { question = it }, if (listening) "Говори…" else "Спросить своими словами",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (question.isNotBlank()) { ask(question.trim(), AskKind.USER); question = "" } }),
                )
            }
            BarButton(R.drawable.ic_mic, if (listening) "Остановить запись" else "Сказать голосом", active = listening) { speech.toggle() }
            BarButton(R.drawable.ic_send, "Отправить", enabled = question.isNotBlank()) {
                ask(question.trim(), AskKind.USER)
                question = ""
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { ctl.toggleAutoCheck() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Проверять, когда остановлюсь", style = MaterialTheme.typography.labelLarge)
                Text("Перестал писать на 7 с — наставник тихо проверит страницу и отметит ошибку.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppSwitch(ctl.autoCheck) { ctl.toggleAutoCheck() }
        }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ctl.meta?.tasks?.takeIf { it.isNotEmpty() }?.let { VariantCard(it) }
            problem?.let { p ->
                StatusLine(p, Tone.ERROR)
                if (p.contains("настройки")) LinkText("Открыть настройки", onClick = onSettings)
            }
            when {
                answer == null -> EmptyMentor()
                answer.errorText != null -> {
                    StatusLine(answer.errorText, Tone.ERROR)
                    if (answer.error?.needsSettings == true) LinkText("Открыть настройки", onClick = onSettings)
                    else SoftButton("Повторить", icon = R.drawable.ic_refresh) { ask(answer.question, answer.kind) }
                }
                else -> {
                    if (answer.streaming && answer.marks.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = Brand.Ink, trackColor = Brand.Ink.copy(alpha = 0.1f))
                            Text(answer.status ?: "Думаю…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    answer.marks.forEachIndexed { i, m ->
                        NoteCard(i + 1, m, topic = ctl.meta?.title.orEmpty(), onLocate = { ctl.focusMark(i + 1) })
                    }
                    if (!answer.streaming && answer.marks.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (answer.done) {
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
                                SoftButton("Похожая задача", icon = R.drawable.ic_refresh) { ask(QuickAction.SIMILAR.prompt, AskKind.QUICK) }
                            } else {
                                SoftButton("Ещё подсказка", icon = R.drawable.ic_bulb) { ask(QuickAction.NEXT_STEP.prompt, AskKind.QUICK) }
                            }
                        }
                    }
                }
            }
        }
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
    Column(
        Modifier
            .fillMaxWidth()
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
            .background(Brand.Ink)
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
            checkedTrackColor = Brand.Ink,
            checkedThumbColor = Brand.OnInk,
            uncheckedTrackColor = Brand.Ink.copy(alpha = 0.12f),
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Brand.Ink.copy(alpha = 0.2f),
        ),
    )
}

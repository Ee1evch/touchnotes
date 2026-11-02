package app.nastavnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.session.AskKind
import app.nastavnik.ai.QuickAction
import app.nastavnik.app
import app.nastavnik.capture.ImageUtils
import app.nastavnik.study.Homework
import app.nastavnik.study.HomeworkRules
import app.nastavnik.study.HomeworkRules.Status
import app.nastavnik.study.Homeworks
import app.nastavnik.study.HwTask
import app.nastavnik.study.StudyLockRules
import app.nastavnik.study.Textbook
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Цвет и подпись состояния задачи ДЗ. */
private fun statusView(s: Status): Pair<String, Color> = when (s) {
    Status.TODO -> "не решена" to Color(0xFF7C8596)
    Status.WRONG -> "есть ошибка" to Color(0xFFE5484D)
    Status.INCOMPLETE -> "не дорешана" to Color(0xFFE0A21E)
    Status.DONE -> "засчитана" to Color(0xFF1F9D60)
    Status.DISPUTED -> "спорная, засчитана" to Color(0xFFE0A21E)
}

/** Формулы из ответа проверки — без долларов (короткий текст в плашке). */
private fun plain(s: String) = s.replace("$", "").replace("\\", "")

/**
 * «Домашка»: план на сегодня от ИИ, ДЗ по предметам с прогрессом, задачи — «Решать» открывает страницу тетради
 * с условием сверху (проверка — там же). Добавить: номера из учебника или файл с заданиями.
 */
@Composable
fun HomeworkScreen(
    resumeTick: Int,
    onBack: (() -> Unit)?,
    openNotebook: (Long) -> Unit,
    sharedFile: Uri? = null,
    onSharedUsed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val version by Homeworks.version.collectAsStateWithLifecycle()
    val list by produceState(emptyList<Homework>(), version, resumeTick) { value = withContext(Dispatchers.IO) { Homeworks.list(context) } }
    var adding by remember { mutableStateOf(sharedFile != null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val open = list.filter { !it.complete && !it.dueDate.isBefore(today.minusDays(1)) }
    val plan by produceState<String?>(null, open.map { it.id to it.statuses }) {
        value = if (open.isEmpty()) null else runCatching { Homeworks.plan(context, open) }.getOrNull()
    }

    fun solve(hw: Homework, t: HwTask) {
        scope.launch {
            busy = "Готовлю страницу…"
            Homeworks.pageFor(context, hw.id, t.id)
                .onSuccess { (nb, page) -> NotebookStart.set(nb, page); openNotebook(nb) }
                .onFailure { error = it.message }
            busy = null
        }
    }

    // Условия задач из учебника ищутся в фоне, по одной; на экране — «ищу: 2 из 6».
    fun locate(hwId: String) {
        app.appScope.launch {
            busy = "Ищу задачи в учебнике…"
            Homeworks.locate(context, hwId) { i, n -> busy = "Ищу задачи в учебнике: $i из $n" }.onFailure { error = it.message }
            busy = null
        }
    }

    if (adding) {
        AddHomework(
            sharedFile = sharedFile,
            onBack = { adding = false; onSharedUsed() },
            onAdded = { hw -> adding = false; onSharedUsed(); if (hw.bookId.isNotEmpty()) locate(hw.id) },
        )
        return
    }

    ScreenScaffold(title = "", onBack = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Домашка", style = MaterialTheme.typography.headlineMedium)
                val left = open.sumOf { hw -> hw.tasks.size - HomeworkRules.done(hw.statuses) }
                Text(
                    if (open.isEmpty()) "Всё сдано" else "Осталось $left ${ruPlural(left, "задача", "задачи", "задач")}",
                    style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                )
            }
            RoundIconButton(R.drawable.ic_plus, "Добавить ДЗ") { adding = true }
        }
        busy?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Brand.Accent, strokeWidth = 2.dp)
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        } }
        error?.let { StatusLine(it, Tone.ERROR) }
        plan?.let { p ->
            Column(Modifier.fillMaxWidth().enter().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("План на сегодня", style = MaterialTheme.typography.titleSmall, color = Brand.Accent)
                Text(plain(p), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().enter().glass().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Домашки пока нет", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Добавь ДЗ: номера задач из учебника (учебник в PDF загружается один раз) или файл с заданиями — " +
                        "его можно прислать сюда через «Поделиться» из Telegram или почты. Решаешь в тетради, наставник " +
                        "проверяет каждую задачу; ДЗ засчитано, когда засчитаны все.",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
                )
                InkButton("Добавить ДЗ", icon = R.drawable.ic_plus) { adding = true }
            }
        }
        list.forEach { hw ->
            HomeworkCard(
                hw,
                onSolve = { t -> solve(hw, t) },
                onDispute = { t -> scope.launch { withContext(Dispatchers.IO) { Homeworks.dispute(context, hw.id, t.id) } } },
                onLocate = { locate(hw.id) },
                onDelete = { scope.launch { withContext(Dispatchers.IO) { Homeworks.delete(context, hw) } } },
            )
        }
    }
}

@Composable
private fun HomeworkCard(hw: Homework, onSolve: (HwTask) -> Unit, onDispute: (HwTask) -> Unit, onLocate: () -> Unit, onDelete: () -> Unit) {
    val done = HomeworkRules.done(hw.statuses)
    var open by remember(hw.id) { mutableStateOf(!hw.complete) }
    var confirmDelete by remember(hw.id) { mutableStateOf(false) }
    val overdue = hw.dueDate.isBefore(LocalDate.now()) && !hw.complete
    Column(
        Modifier.fillMaxWidth().enter().glass().animateContentSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hw.subject, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (hw.complete) "сделано на 100%" else "к ${Homeworks.dayName(hw.dueDate)}") + " · $done из ${hw.tasks.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when { hw.complete -> Brand.Ok; overdue -> Brand.Error; else -> Brand.InkSoft },
                )
            }
            Text("${if (hw.tasks.isEmpty()) 0 else done * 100 / hw.tasks.size}%", style = MaterialTheme.typography.headlineMedium, color = if (hw.complete) Brand.Ok else Brand.Accent)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brand.Accent.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (hw.tasks.isEmpty()) 0f else done.toFloat() / hw.tasks.size).clip(RoundedCornerShape(2.dp)).background(if (hw.complete) Brand.Ok else Brand.Accent))
        }
        if (open) {
            if (hw.tasks.any { it.parts.isEmpty() && !it.missing && hw.bookId.isNotEmpty() }) LinkText("Найти условия в учебнике", onClick = onLocate)
            hw.tasks.forEach { t -> TaskRow(hw, t, onSolve = { onSolve(t) }, onDispute = { onDispute(t) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!confirmDelete) LinkText("Удалить ДЗ") { confirmDelete = true }
                else {
                    LinkText("Да, удалить", onClick = onDelete)
                    LinkText("Не надо") { confirmDelete = false }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(hw: Homework, t: HwTask, onSolve: () -> Unit, onDispute: () -> Unit) {
    val (label, color) = statusView(t.status)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (t.missing) "не нашёл в учебнике — условие перепиши на страницу сам" else label,
                    style = MaterialTheme.typography.bodySmall, color = if (t.missing) Brand.InkSoft else color,
                )
            }
            if (t.status != Status.DONE && t.status != Status.DISPUTED) {
                SoftButton(if (t.pageId == 0L) "Решать" else "Дальше", icon = R.drawable.ic_pen, onClick = onSolve)
            } else {
                LinkText("Открыть", onClick = onSolve)
            }
        }
        if (t.comment.isNotBlank() && t.status != Status.TODO) Text(plain(t.comment), style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        if (HomeworkRules.canDispute(t.status, t.failed, hw.statuses)) {
            LinkText("Уверен, что верно — засчитать как спорную (одна на ДЗ)", onClick = onDispute)
        }
    }
}

/** Добавить ДЗ: предмет, откуда (учебник или файл), номера, срок. */
@Composable
private fun AddHomework(sharedFile: Uri?, onBack: () -> Unit, onAdded: (Homework) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val version by Homeworks.version.collectAsStateWithLifecycle()
    val books by produceState(emptyList<Textbook>(), version) { value = withContext(Dispatchers.IO) { Homeworks.books(context) } }
    val today = LocalDate.now()
    // Предметы и пары на две недели вперёд — для выбора предмета и срока «к следующей паре».
    val lessons by produceState(emptyList<Pair<LocalDate, String>>()) {
        value = (1L..14L).flatMap { d -> val day = today.plusDays(d); StudyLockRules.disciplines(app.schedule.day(day)).map { day to it } }
    }
    val subjects = (lessons.map { it.second } + books.map { it.subject }).distinct()
    var subject by remember { mutableStateOf("") }
    // Учебник или задания на один раз — выбираешь каждый раз сам (по одному предмету бывает и то и другое).
    var fromBook by remember { mutableStateOf<Boolean?>(null) }
    var bookId by remember { mutableStateOf<String?>(null) }
    var numbers by remember { mutableStateOf("") }
    var file by remember { mutableStateOf(sharedFile) }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val subjectBooks = books.filter { subject.isNotBlank() && (StudyLockRules.sameSubject(it.subject, subject) || it.subject.equals(subject.trim(), ignoreCase = true)) }
    val book = subjectBooks.firstOrNull { it.id == bookId } ?: subjectBooks.singleOrNull()
    val defaultDue = if (subject.isBlank()) null else HomeworkRules.defaultDue(subject, today, lessons) { a, b -> StudyLockRules.sameSubject(a, b) || a == b }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) file = uri }
    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && subject.isNotBlank()) scope.launch {
            busy = "Загружаю учебник…"
            Homeworks.importBook(context, uri, subject.trim()).onSuccess { bookId = it.id }.onFailure { error = it.message }
            busy = null
        }
    }

    ScreenScaffold(title = "Новое ДЗ", onBack = onBack) {
        SectionCard("Предмет") {
            if (subjects.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEach { s -> ChipButton(s, selected = subject == s) { subject = s } }
            }
            AppTextField(subject, { subject = it }, label = "Предмет", placeholder = "Математический анализ")
        }
        SectionCard("Что это за ДЗ") {
            Segmented(listOf<Pair<Boolean?, String>>(true to "Из учебника — номера", false to "Задания на один раз"), fromBook) { fromBook = it }
            when (fromBook) {
                null -> Text(
                    "Из учебника — учебник (задачник) в PDF загружается один раз, дальше хватит номеров задач. " +
                        "На один раз — PDF с заданиями (например, прислали в Telegram): наставник сам разделит его на задачи.",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
                )
                true -> {
                    if (subject.isBlank()) Text("Сначала выбери предмет.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                    else {
                        if (subjectBooks.isEmpty()) Text("Учебника по этому предмету ещё нет — загрузи PDF один раз.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                        if (subjectBooks.size > 1 || (subjectBooks.isNotEmpty() && book == null)) {
                            Text("Какой учебник:", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                        }
                        if (subjectBooks.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            subjectBooks.forEach { b -> ChipButton("${b.title} · ${b.pages} стр.", selected = book?.id == b.id) { bookId = b.id } }
                        }
                        // Прислали PDF через «Поделиться» — его можно сразу сделать учебником.
                        if (file != null) SoftButton("Присланный PDF — это учебник", icon = R.drawable.ic_pdf) {
                            val f = file!!
                            scope.launch {
                                busy = "Загружаю учебник…"
                                Homeworks.importBook(context, f, subject.trim()).onSuccess { bookId = it.id; file = null }.onFailure { error = it.message }
                                busy = null
                            }
                        }
                        LinkText(if (subjectBooks.isEmpty()) "Загрузить учебник (PDF)" else "Загрузить другой учебник") { pickBook.launch(arrayOf("application/pdf")) }
                        if (book != null) {
                            AppTextField(numbers, { numbers = it }, label = "Номера задач", placeholder = "245, 247–251, 260а")
                            val parsed = HomeworkRules.parseNumbers(numbers)
                            if (parsed.isNotEmpty()) Text("${parsed.size} ${ruPlural(parsed.size, "задача", "задачи", "задач")}: ${parsed.joinToString()}", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                        }
                    }
                }
                false -> {
                    Text(
                        if (file == null) "PDF с заданиями — выбери здесь или пришли через «Поделиться → Наставник» из Telegram, почты." else "Файл: ${app.notebooks.displayName(file!!) ?: "выбран"}",
                        style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
                    )
                    SoftButton(if (file == null) "Выбрать PDF" else "Другой файл", icon = R.drawable.ic_pdf) { pickPdf.launch(arrayOf("application/pdf")) }
                }
            }
        }
        SectionCard("Срок") {
            val chosen = due ?: defaultDue
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                defaultDue?.let { d -> ChipButton("к паре · ${Homeworks.dayName(d)}", selected = chosen == d) { due = d } }
                (1L..7L).map { today.plusDays(it) }.filter { it != defaultDue }.forEach { d ->
                    ChipButton(Homeworks.dayName(d).replaceFirstChar { it.uppercase() }, selected = chosen == d) { due = d }
                }
            }
        }
        busy?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Brand.Accent, strokeWidth = 2.dp)
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
        } }
        error?.let { StatusLine(it, Tone.ERROR) }
        val parsed = HomeworkRules.parseNumbers(numbers)
        val ready = subject.isNotBlank() && busy == null && when (fromBook) {
            true -> book != null && parsed.isNotEmpty()
            false -> file != null
            null -> false
        }
        InkButton("Добавить", enabled = ready, modifier = Modifier.fillMaxWidth()) {
            val d = due ?: defaultDue ?: today.plusDays(1)
            error = null
            if (fromBook == true) {
                val hw = Homeworks.fromBook(context, book!!, parsed, d)
                onAdded(hw)
            } else scope.launch {
                busy = "Читаю задания…"
                Homeworks.fromFile(context, file!!, subject.trim(), d) { i, n -> busy = "Читаю задания: страница ${i + 1} из $n" }
                    .onSuccess { onAdded(it) }
                    .onFailure { error = it.message }
                busy = null
            }
        }
        if (books.isNotEmpty()) SectionCard("Учебники") {
            books.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${b.subject} · ${b.pages} стр. · просмотрено ${b.index.size}", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                    }
                    LinkText("Убрать") { scope.launch { withContext(Dispatchers.IO) { Homeworks.deleteBook(context, b) } } }
                }
            }
        }
    }
}

/**
 * Плашка на странице задачи ДЗ в тетради: «№ 245 · не решена» и «Готово — проверь». Ошибка — наставник ещё и
 * отмечает место на листе; засчитано — карточки-задачи на завтра и «Следующая задача».
 */
@Composable
fun HomeworkBar(ctl: NotebookController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val page = ctl.page
    val version by Homeworks.version.collectAsStateWithLifecycle()
    val found by produceState<Pair<Homework, HwTask>?>(null, page?.id, version) {
        value = page?.let { p -> withContext(Dispatchers.IO) { Homeworks.taskForPage(context, ctl.notebookId, p.id) } }
    }
    var checking by remember { mutableStateOf(false) }
    var note by remember(page?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(page?.id) { checking = false }
    val (hw, t) = found ?: return
    val (label, color) = statusView(t.status)
    AnimatedVisibility(true, modifier = modifier) {
        Column(
            Modifier.widthIn(max = 560.dp).glass(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("ДЗ · ${hw.subject} · ${t.label}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$label · в ДЗ ${HomeworkRules.done(hw.statuses)} из ${hw.tasks.size}", style = MaterialTheme.typography.bodySmall, color = color)
                }
                if (checking) CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Accent, strokeWidth = 2.dp)
                else if (t.status != Status.DONE && t.status != Status.DISPUTED) InkButton("Готово — проверь", icon = R.drawable.ic_check) {
                    val p = ctl.page ?: return@InkButton
                    checking = true
                    note = null
                    scope.launch {
                        val shot = runCatching { app.notebooks.renderJpeg(ctl.notebookId, p, ImageUtils.MAX_PIXELS) }.getOrNull()
                        if (shot == null) { note = "Не получилось сфотографировать страницу"; checking = false; return@launch }
                        Homeworks.check(context, hw.id, t.id, shot)
                            .onSuccess { v ->
                                note = when (v.status) {
                                    Status.DONE -> "Засчитано. ${plain(v.comment)}"
                                    else -> plain(v.comment)
                                }
                                // Ошибка — пусть наставник покажет место на листе (не исправляя).
                                if (v.status == Status.WRONG) ctl.ask(QuickAction.FIND_ERROR.prompt, AskKind.QUICK)
                                // Засчитано — три карточки-задачи на завтра (в фоне).
                                if (v.status == Status.DONE) app.appScope.launch { Homeworks.makeCards(context, hw.id, t.id, shot) }
                            }
                            .onFailure { note = it.message }
                        checking = false
                    }
                } else {
                    val next = hw.tasks.firstOrNull { it.status != Status.DONE && it.status != Status.DISPUTED }
                    if (next != null) SoftButton("Следующая: ${next.label}") {
                        scope.launch {
                            Homeworks.pageFor(context, hw.id, next.id).onSuccess { (nb, pg) ->
                                NotebookStart.set(nb, pg)
                                if (nb == ctl.notebookId) ctl.load()
                            }.onFailure { note = it.message }
                        }
                    }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (t.status == Status.DONE) Brand.Ok else Brand.InkSoft) }
            if (note == null && t.comment.isNotBlank() && t.status != Status.TODO && t.status != Status.DONE) {
                Text(plain(t.comment), style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            }
            if (HomeworkRules.canDispute(t.status, t.failed, hw.statuses)) {
                LinkText("Уверен, что верно — засчитать как спорную (одна на ДЗ)") {
                    scope.launch { withContext(Dispatchers.IO) { Homeworks.dispute(context, hw.id, t.id) } }
                }
            }
        }
    }
}

package app.nastavnik.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ink.Paper
import app.nastavnik.notes.NoteStorage
import app.nastavnik.study.Exam
import app.nastavnik.study.ExamPlan
import app.nastavnik.study.Material
import app.nastavnik.study.PracticeTask
import app.nastavnik.study.Progress
import app.nastavnik.study.QuizQuestion
import app.nastavnik.study.TrainResult
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class TrainTab { VARIANT, QUIZ, EXAMS }

/**
 * Тренировка: вариант контрольной (решать в тетради — наставник знает ответы), мини-тест
 * с разбором и экзамены, к которым подстраивается повторение карточек.
 */
@Composable
fun TrainingScreen(
    initialTab: TrainTab,
    onBack: () -> Unit,
    onOpenNotebook: (Long) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Тема общая для всех вкладок: из экзамена можно сразу перейти к варианту по предмету.
    var topic by rememberSaveable { mutableStateOf("") }
    var noteId by rememberSaveable { mutableStateOf<Long?>(null) }

    ScreenScaffold(title = "Тренировка", onBack = onBack) {
        Segmented(
            listOf(TrainTab.VARIANT to "Вариант", TrainTab.QUIZ to "Мини-тест", TrainTab.EXAMS to "Экзамены"),
            tab,
        ) { tab = it }
        when (tab) {
            TrainTab.VARIANT -> {
                SourcePicker(topic, { topic = it }, noteId, { noteId = it })
                VariantTab(topic, noteId, onOpenNotebook, onSettings)
            }
            TrainTab.QUIZ -> {
                SourcePicker(topic, { topic = it }, noteId, { noteId = it })
                QuizTab(topic, noteId, onReview, onSettings)
            }
            TrainTab.EXAMS -> ExamsTab(onPrepare = { subject ->
                topic = subject
                noteId = null
                tab = TrainTab.VARIANT
            })
        }
    }
}

/** О чём тренироваться: своя тема или один из сохранённых чистовиков. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcePicker(topic: String, onTopic: (String) -> Unit, noteId: Long?, onNote: (Long?) -> Unit) {
    val context = LocalContext.current
    val notes by remember { context.app.db.cleanNotes().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    SectionCard("О чём") {
        AppTextField(topic, onTopic, "Тема", placeholder = "Например: пределы, производная, матрицы")
        if (notes.isNotEmpty()) {
            Text("Или по конспекту — задачи будут по тому, что в нём есть:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notes.take(8).forEach { n ->
                    val title = NoteStorage.plainTitle(n.title).ifBlank { "Конспект" }
                    ChipButton(title.take(32), selected = noteId == n.id) {
                        if (noteId == n.id) onNote(null) else {
                            onNote(n.id)
                            if (topic.isBlank()) onTopic(title)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun material(context: Context, topic: String, noteId: Long?): Material {
    val fromNote = noteId?.let { context.app.trainer.noteMaterial(it) }
    return if (fromNote != null) fromNote.copy(topic = topic.ifBlank { fromNote.topic }) else Material(topic)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VariantTab(topic: String, noteId: Long?, onOpenNotebook: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by rememberSaveable { mutableIntStateOf(5) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val tasks = remember { mutableStateListOf<PracticeTask>() }
    var showAnswers by remember { mutableStateOf(false) }

    fun generate() {
        loading = true
        problem = null
        scope.launch {
            val m = material(context, topic, noteId)
            when (val r = context.app.trainer.variant(m, count)) {
                is TrainResult.Ok -> {
                    tasks.clear()
                    tasks.addAll(r.value)
                    showAnswers = false
                }
                is TrainResult.Failed -> problem = r.message to r.needsSettings
            }
            loading = false
        }
    }

    SectionCard("Вариант контрольной") {
        FieldLabel("Сколько задач", "От разминки к задаче с подвохом. Решаешь в тетради — наставник знает ответы и проверит.")
        Segmented(listOf(3 to "3", 5 to "5", 8 to "8"), count) { count = it }
        InkButton(
            if (tasks.isEmpty()) "Составить вариант" else "Новый вариант",
            Modifier.fillMaxWidth(),
            enabled = !loading,
            icon = R.drawable.ic_sparkle,
        ) { generate() }
        if (loading) Working("Составляю задачи…")
        problem?.let { (text, settings) ->
            StatusLine(text, Tone.ERROR)
            if (settings) LinkText("Открыть настройки", onClick = onSettings)
        }
    }

    if (tasks.isNotEmpty()) {
        val bring = remember { BringIntoViewRequester() }
        LaunchedEffect(tasks.firstOrNull()) {
            delay(200)
            bring.bringIntoView()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bring)
                .glass(RoundedCornerShape(28.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            tasks.forEachIndexed { i, t ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberBadge(i + 1)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LevelDots(t.level)
                        MathText(t.task, 16)
                        if (showAnswers) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Brand.OkSoft)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) { MathText("Ответ: ${t.answer}", 15) }
                        }
                    }
                }
            }
            FlowRow2 {
                InkButton("Решать в тетради", icon = R.drawable.ic_notebook) {
                    scope.launch {
                        val title = "Вариант: " + topic.ifBlank { "тренировка" }.take(40)
                        val id = withContext(Dispatchers.IO) {
                            val store = context.app.notebooks.store
                            val (m, _) = store.create(title, Paper.GRID)
                            store.saveMeta(m.copy(tasks = tasks.toList()))
                            m.id
                        }
                        context.app.study.record(Progress.KIND_VARIANT, topic)
                        onOpenNotebook(id)
                    }
                }
                SoftButton(if (showAnswers) "Скрыть ответы" else "Показать ответы", icon = if (showAnswers) R.drawable.ic_eye_off else R.drawable.ic_eye) {
                    showAnswers = !showAnswers
                }
            }
        }
    } else if (!loading) {
        Hint(
            "Как это работает",
            "Наставник составит задачи как на контрольной. «Решать в тетради» откроет новую тетрадь: условия видны в панели, " +
                "а «Проверь ответ» сверит твоё решение с верным ответом — сам ответ наставник не выдаст раньше времени.",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuizTab(topic: String, noteId: Long?, onReview: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var questions by remember { mutableStateOf<List<QuizQuestion>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val wrong = remember { mutableStateListOf<QuizQuestion>() }
    var cardsAdded by remember { mutableStateOf(false) }
    var quizTopic by remember { mutableStateOf("") }

    fun start() {
        loading = true
        problem = null
        scope.launch {
            val m = material(context, topic, noteId)
            when (val r = context.app.trainer.quiz(m)) {
                is TrainResult.Ok -> {
                    questions = r.value
                    index = 0
                    picked = null
                    wrong.clear()
                    cardsAdded = false
                    quizTopic = m.topic
                }
                is TrainResult.Failed -> problem = r.message to r.needsSettings
            }
            loading = false
        }
    }

    if (questions.isEmpty() || loading) {
        SectionCard("Мини-тест") {
            FieldLabel("8 вопросов с вариантами", "Сразу видно, где верно, и почему. Ошибки можно одним нажатием отправить в карточки.")
            InkButton("Начать тест", Modifier.fillMaxWidth(), enabled = !loading, icon = R.drawable.ic_quiz) { start() }
            if (loading) Working("Готовлю вопросы…")
            problem?.let { (text, settings) ->
                StatusLine(text, Tone.ERROR)
                if (settings) LinkText("Открыть настройки", onClick = onSettings)
            }
        }
        return
    }

    if (index >= questions.size) {
        val right = questions.size - wrong.size
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$right из ${questions.size}", style = MaterialTheme.typography.displaySmall)
            Text(
                when {
                    wrong.isEmpty() -> "Без ошибок. Тему можно считать закрытой — карточки напомнят перед экзаменом."
                    right * 2 >= questions.size -> "Неплохо. Ошибки лучше закрепить карточками — они вернутся завтра."
                    else -> "Тему стоит повторить: открой конспект или реши вариант, а ошибки отправь в карточки."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow2 {
                if (wrong.isNotEmpty()) {
                    if (!cardsAdded) {
                        InkButton("Ошибки в карточки (${wrong.size})", icon = R.drawable.ic_cards) {
                            scope.launch {
                                context.app.trainer.cardsFromMistakes(wrong.toList(), quizTopic)
                                cardsAdded = true
                            }
                        }
                    } else {
                        InkButton("Повторить карточки", icon = R.drawable.ic_cards, onClick = onReview)
                    }
                }
                SoftButton("Ещё тест", icon = R.drawable.ic_refresh) { start() }
            }
        }
        return
    }

    val q = questions[index]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(Brand.Ink.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((index + if (picked != null) 1 else 0).toFloat() / questions.size)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Brand.Accent),
            )
        }
        Text("${index + 1} из ${questions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp))
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MathText(q.q, 18)
        q.options.forEachIndexed { i, option ->
            QuizOption(option, i, picked, q.correct) {
                if (picked == null) {
                    picked = i
                    if (i != q.correct) wrong.add(q)
                }
            }
        }
        if (picked != null) {
            if (q.why.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brand.Ink.copy(alpha = 0.05f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { MathText(q.why, 15) }
            }
            // Ответил — разбор и кнопка «Дальше» сами въезжают на экран.
            val bring = remember { BringIntoViewRequester() }
            LaunchedEffect(index) {
                delay(150)
                bring.bringIntoView()
            }
            InkButton(if (index == questions.lastIndex) "Итог" else "Дальше", Modifier.fillMaxWidth().bringIntoViewRequester(bring)) {
                if (index == questions.lastIndex) {
                    scope.launch { context.app.study.record(Progress.KIND_QUIZ, quizTopic, wrong.size) }
                }
                index++
                picked = null
            }
        }
    }
}

@Composable
private fun QuizOption(text: String, i: Int, picked: Int?, correct: Int, onClick: () -> Unit) {
    val state = when {
        picked == null -> 0
        i == correct -> 1
        i == picked -> 2
        else -> 3
    }
    val border by animateColorAsState(
        when (state) {
            1 -> Brand.Ok
            2 -> Brand.Error
            else -> Brand.Line
        },
        label = "opt",
    )
    val bg = when (state) {
        1 -> Brand.OkSoft
        2 -> Brand.ErrorSoft
        else -> Brand.Glass.copy(alpha = if (state == 3) 0.35f else 0.62f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(if (state == 1 || state == 2) 1.5.dp else 0.6.dp, border, RoundedCornerShape(16.dp))
            .clickable(enabled = picked == null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (state == 1) Brand.Ok else if (state == 2) Brand.Error else Brand.Ink.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ('А' + i).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (state == 1 || state == 2) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(Modifier.weight(1f)) { MathText(text, 16) }
    }
}

/** Экзамены: ближайшие сверху, сколько дней осталось, «Готовиться» — вариант по предмету. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamsTab(onPrepare: (String) -> Unit) {
    val context = LocalContext.current
    val study = context.app.study
    var exams by remember { mutableStateOf(study.exams()) }
    var subject by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val fmt = remember { SimpleDateFormat("d MMMM, EEEE", Locale.forLanguageTag("ru")) }

    if (picking) {
        val todayUtc = remember { utcMidnight(now) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.let { utcMidnight(it) } ?: (todayUtc + 14 * DAY),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = localMorning(it) }
                    picking = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }

    SectionCard("Новый экзамен") {
        AppTextField(subject, { subject = it }, "Предмет", placeholder = "Например: Математический анализ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(date?.let { fmt.format(Date(it)) } ?: "Выбрать дату", Modifier.weight(1f), icon = R.drawable.ic_calendar) { picking = true }
            InkButton("Добавить", enabled = subject.isNotBlank() && date != null) {
                val list = exams + Exam(subject.trim(), date!!)
                study.saveExams(list)
                exams = study.exams()
                subject = ""
                date = null
            }
        }
        Text(
            "Карточки по этому предмету будут повторяться не позже, чем накануне экзамена, — к нему всё будет в голове.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val upcoming = exams.filter { it.at + DAY > now }
    if (upcoming.isEmpty()) {
        Hint("Пока нет экзаменов", "Добавь предмет и дату — на главном экране появится обратный отсчёт, а повторение подстроится под дату.")
    }
    upcoming.forEach { e ->
        val days = ExamPlan.daysLeft(e.at, now)
        Row(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (days <= 3) Brand.ErrorSoft else Brand.Ink.copy(alpha = 0.06f))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(days.toString(), style = MaterialTheme.typography.titleLarge, color = if (days <= 3) Brand.Error else MaterialTheme.colorScheme.onSurface)
                Text(ruPlural(days, "день", "дня", "дней"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.subject, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(if (days == 0) "Сегодня. Удачи!" else fmt.format(Date(e.at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChipButton("Готовиться") { onPrepare(e.subject) }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable {
                        study.saveExams(exams - e)
                        exams = study.exams()
                    }
                    .semantics { contentDescription = "Удалить экзамен" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private const val DAY = 24 * 60 * 60 * 1000L

/** Полночь по UTC того же календарного дня — так DatePicker хранит даты. */
private fun utcMidnight(local: Long): Long {
    val l = Calendar.getInstance().apply { timeInMillis = local }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Выбранный день в местном времени, 9 утра — обычное время экзамена. */
private fun localMorning(utc: Long): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear()
        set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 9, 0)
    }.timeInMillis
}

/** Сложность задачи: одна–три точки. */
@Composable
private fun LevelDots(level: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i < level) Brand.Ink else Brand.Ink.copy(alpha = 0.15f)),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            when (level) {
                1 -> "разминка"
                2 -> "типовая"
                else -> "с подвохом"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = Brand.Ink, trackColor = Brand.Ink.copy(alpha = 0.1f))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(title: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Формулы — через KaTeX (WebView), обычный текст — обычным Text: так экран не тормозит от десятка WebView. */
@Composable
fun MathText(text: String, sizePx: Int = 16) {
    if (text.contains('$') || text.contains('\\')) {
        RichText(text, sizePx = sizePx)
    } else {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = sizePx.sp, lineHeight = (sizePx * 1.4f).sp),
        )
    }
}

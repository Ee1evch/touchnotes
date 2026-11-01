package app.nastavnik.ui

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nastavnik.app
import app.nastavnik.data.CardEntity
import app.nastavnik.study.Learning
import app.nastavnik.study.Material
import app.nastavnik.study.Progress
import app.nastavnik.study.Siren
import app.nastavnik.study.SirenPrefs
import app.nastavnik.study.SirenRules
import app.nastavnik.study.SirenService
import app.nastavnik.study.SleepPlan
import app.nastavnik.study.StudyLockRules
import app.nastavnik.study.TrainResult
import app.nastavnik.study.WakePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Основная задача сирены: условие и ответ, который нужно ввести. */
private data class MainTask(val text: String, val answer: String, val cardId: Long? = null)

/** Карточка по основному предмету (матан, линал, матлог, физика). */
private fun CardEntity.core() = StudyLockRules.isCore(subject) || StudyLockRules.isCore(topic)

/**
 * Основная задача — только по основным предметам: сначала нерешённые задачи («Задача. …»), потом задача от
 * наставника по слабой теме основного предмета, без нейросети — карточка «Заполни пропуск».
 * [skip] — те, что уже пропущены сегодня.
 */
private suspend fun nextMainTask(context: Context, skip: Set<String>): MainTask? {
    val app = context.app
    val cards = app.db.cards().all().filter { it.core() }
    cards.filter { Learning.isProblem(it.front) && it.front.removePrefix(Learning.PROBLEM_PREFIX) !in skip }.sortedWith(compareBy({ it.box }, { it.dueAt })).firstOrNull()
        ?.let { return MainTask(it.front.removePrefix(Learning.PROBLEM_PREFIX), it.back, it.id) }
    val now = System.currentTimeMillis()
    val events = app.study.events()
    // Тема: опасная или слабая — из основных предметов; иначе сам основной предмет (сегодняшний или из карточек).
    val today = StudyLockRules.disciplines(app.schedule.day(StudyLockRules.sessionDate(java.time.LocalDateTime.now())))
    val topic = (Learning.dangerous(events, now).map { it.first } + Progress.summarize(events, now).weakTopics.map { it.first })
        .firstOrNull { StudyLockRules.isCore(it) }
        ?: today.firstOrNull()
        ?: cards.firstOrNull()?.let { it.subject.ifBlank { it.topic } }
    if (topic != null && app.settings.notReadyReason() == null) {
        val r = app.trainer.variant(Material(topic, "Нужны задачи с коротким ответом: одно число или одно слово."), 2)
        if (r is TrainResult.Ok) r.value.firstOrNull { it.task !in skip }?.let { return MainTask(it.task, it.answer) }
    }
    cards.filter { it.front.startsWith("Заполни пропуск") && it.front !in skip }.shuffled().firstOrNull()
        ?.let { return MainTask(it.front, it.back, it.id) }
    return null
}

/**
 * Экран сирены поверх блокировки. Звучит — сначала мини-задача (лёгкий пример): решил — 5 минут тишины.
 * Основная задача — ответ вводится и проверяется; «Решил» без ответа нет. Решил основную — свободен до завтра.
 */
class SirenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val appearance = if (WakePrefs.unlocked(this)) appearanceOf(app.settings.load()) else Appearance()
        setContent { NastavnikTheme(appearance) { SirenScreen(onDone = { finish() }) } }
    }
}

@Composable
private fun SirenScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val s = remember { SirenPrefs.of(context) }
    var ringing by remember { mutableStateOf(SirenService.ringing) }
    LaunchedEffect(Unit) { while (true) { ringing = SirenService.ringing; delay(500) } }
    // Пока звучит — «Назад» не уводит.
    BackHandler(enabled = ringing) { }
    Column(
        Modifier.fillMaxSize().paperBackdrop().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (ringing) "Задача дня не решена" else "Задача дня", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (ringing) MiniTask(s) else MainPanel(s, onDone)
    }
}

/** Мини-задача: лёгкий пример — снимает звук на 5 минут. */
@Composable
private fun MiniTask(s: SirenPrefs) {
    val context = LocalContext.current
    val seed = remember { s.miniSeed.takeIf { it != 0L } ?: System.nanoTime().also { s.miniSeed = it } }
    val (task, answer) = remember(seed) { SleepPlan.task(seed) }
    var input by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 10.dp, strong = true).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Мини-задача — 5 минут тишины на основную", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft, textAlign = TextAlign.Center)
        Text("$task = ?", style = MaterialTheme.typography.displaySmall)
        AppTextField(
            value = input,
            onValueChange = { v ->
                input = v.filter { it.isDigit() || it == '-' }.take(6)
                if (input.toIntOrNull() == answer) Siren.miniSolved(context)
            },
            label = "Ответ",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** Основная задача: ввести ответ. Пока тишина — отсчёт 5 минут. */
@Composable
private fun MainPanel(s: SirenPrefs, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skip by remember { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val task by produceState<MainTask?>(null, reload) {
        loaded = false
        value = if (WakePrefs.unlocked(context)) nextMainTask(context, skip) else null
        loaded = true
    }
    var input by remember(task) { mutableStateOf("") }
    var wrong by remember(task) { mutableStateOf(false) }
    var shown by remember(task) { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) }
    val left by produceState(0L) {
        while (true) { value = ((s.quietUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0); delay(1000) }
    }
    if (solved) {
        Text("Решено. Свободен до завтра", style = MaterialTheme.typography.titleLarge, color = Brand.Ok, textAlign = TextAlign.Center)
        LaunchedEffect(Unit) { delay(1500); onDone() }
        return
    }
    if (left > 0) Text("Тишина ещё %d:%02d — реши основную задачу".format(left / 60, left % 60), style = MaterialTheme.typography.bodyMedium, color = Brand.Error)
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 10.dp, strong = true).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Основная задача", style = MaterialTheme.typography.titleMedium)
        val t = task
        when {
            !WakePrefs.unlocked(context) -> Text("Разблокируй телефон — задача по твоим темам откроется.", style = MaterialTheme.typography.bodyMedium)
            t == null && !loaded -> Text("Подбираю задачу по слабым темам…", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            // Нет ни задач, ни карточек «заполни пропуск», нейросеть не настроена — повторить 5 карточек
            // (а если карточек нет совсем — три примера подряд).
            t == null -> CardsFallback { solved = true; Siren.mainSolved(context) }
            else -> {
                MathText(t.text, 17)
                if (shown) MathText("Ответ: ${t.answer}", 15)
                AppTextField(
                    value = input,
                    onValueChange = { input = it.take(60); wrong = false },
                    label = "Твой ответ",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (wrong) Text("Не то — проверь решение", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InkButton("Проверить", enabled = input.isNotBlank() && !shown) {
                        if (SirenRules.sameAnswer(input, t.answer)) {
                            solved = true
                            Siren.mainSolved(context)
                            scope.launch { context.app.study.record(Learning.KIND_TASK, t.text.take(40), 0) }
                        } else {
                            wrong = true
                        }
                    }
                    // Сдаться можно — но это не решение: будет другая задача, а эта вернётся позже.
                    SoftButton(if (shown) "Другая задача" else "Сдаться") {
                        if (!shown) shown = true else { skip = skip + t.text; reload++ }
                    }
                }
            }
        }
    }
}

/** Запасной вариант без нейросети и без задач: 5 карточек (честно «помню / не помню»), без карточек — 3 примера. */
@Composable
private fun CardsFallback(onDone: () -> Unit) {
    val context = LocalContext.current
    val cards by produceState<List<CardEntity>?>(null) {
        val core = context.app.db.cards().all().filter { it.core() }
        val now = System.currentTimeMillis()
        value = core.filter { it.dueAt <= now }.take(5).ifEmpty { core.shuffled().take(5) }
    }
    val list = cards ?: return
    var i by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    if (list.isEmpty()) {
        // Карточек нет — три примера подряд.
        var n by remember { mutableIntStateOf(0) }
        val seed = remember(n) { System.nanoTime() }
        val (task, answer) = remember(seed) { SleepPlan.task(seed) }
        var input by remember(n) { mutableStateOf("") }
        Text("Карточек пока нет — три примера (${n + 1} из 3): $task = ?", style = MaterialTheme.typography.bodyLarge)
        AppTextField(
            value = input,
            onValueChange = { v ->
                input = v.filter { it.isDigit() || it == '-' }.take(6)
                if (input.toIntOrNull() == answer) { if (n >= 2) onDone() else n++ }
            },
            label = "Ответ",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        return
    }
    val c = list[i.coerceAtMost(list.lastIndex)]
    Text("Повтори ${list.size} карточек (${i + 1} из ${list.size})", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    MathText(c.front, 17)
    if (open) MathText(c.back, 15)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!open) InkButton("Показать ответ") { open = true }
        else InkButton(if (i >= list.lastIndex) "Готово" else "Дальше") {
            open = false
            if (i >= list.lastIndex) onDone() else i++
        }
    }
}


package app.nastavnik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.ink.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.nastavnik.app
import app.nastavnik.study.Streaks
import app.nastavnik.study.ruPlural
import app.nastavnik.study.Exam
import app.nastavnik.study.ExamPlan
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.notes.NoteApps
import app.nastavnik.overlay.BubbleService

@Composable
fun HomeScreen(resumeTick: Int, go: (String) -> Unit, openNotebook: (Long) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val overlay = remember(resumeTick, tick) { Permissions.overlay(context) }
    val notif = remember(resumeTick, tick) { Permissions.notifications(context) }
    val mic = remember(resumeTick, tick) { Permissions.mic(context) }
    // Наставника включают и выключают и отсюда, и из шторки, ярлыка, уведомления — следим за ним живьём.
    val running by BubbleService.runningFlow.collectAsStateWithLifecycle()
    val notesApp = remember(resumeTick) { NoteApps.chosen(context) }

    val notReady = remember(resumeTick, tick) { context.app.settings.notReadyReason() }
    val provider = remember(resumeTick, tick) { context.app.settings.provider().shortTitle }

    ScreenScaffold(title = "", onBack = null) {
        // Шапка: значок, название и кнопка настроек.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .glass(RoundedCornerShape(14.dp), 6.dp, strong = true),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(20.dp))
            }
            Text("Наставник", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
            RoundIconButton(R.drawable.ic_tune, "Настройки") { go(MainActivity.SCREEN_SETTINGS) }
        }

        Column(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Решай сам.\nЯ подскажу, где нужно.", style = MaterialTheme.typography.displaySmall)
            Text(
                "Пиши где удобно — в своём приложении для записей или прямо здесь, в тетради. Застрянешь — наставник подскажет прямо на записях.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Два способа работать: кнопка поверх своего приложения для записей и тетради прямо здесь.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val hero = @Composable { m: Modifier ->
                Hero(
                    modifier = m,
                    running = running,
                    canStart = overlay,
                    notesLabel = notesApp?.label,
                    onStart = { BubbleService.start(context); tick++ },
                    onStop = { BubbleService.stop(context); tick++ },
                    onOpenNotes = { notesApp?.let { NoteApps.open(context, it) } },
                ) {
                    StatusPill(provider, notReady == null)
                    StatusPill("Уведомления", notif)
                    StatusPill("Микрофон", mic)
                    StatusPill(notesApp?.label ?: "Приложение для записей не выбрано", notesApp != null)
                }
            }
            val notebooks = @Composable { m: Modifier -> NotebooksCard(m, resumeTick, go, openNotebook) }
            if (maxWidth > 700.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    hero(Modifier.weight(1.1f))
                    notebooks(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    hero(Modifier.fillMaxWidth())
                    notebooks(Modifier.fillMaxWidth())
                }
            }
        }

        if (!overlay) {
            Attention("Нужно разрешение «Поверх других окон»", "Без него кнопка и подсказки не появятся поверх приложения для записей. Тетради работают и без него.", "Разрешить") {
                context.startActivity(Permissions.overlaySettingsIntent(context))
            }
        }
        if (notReady != null) {
            Attention(notReady, "Без этого наставник не сможет ответить.", "Настроить") { go(MainActivity.SCREEN_SETTINGS) }
        }

        // Учебный день: сколько карточек ждёт и сколько дней подряд занимаешься.
        val db = context.app.db
        val now = remember(resumeTick) { System.currentTimeMillis() }
        val due by remember(now) { db.cards().observeDueCount(now) }.collectAsStateWithLifecycle(initialValue = 0)
        val events by remember { db.activity().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
        val streak = remember(events, now) {
            Streaks.current(events.map { Streaks.day(it.at) }.toSet(), Streaks.day(now))
        }
        if (due > 0) {
            Today(due, streak) { go(MainActivity.SCREEN_REVIEW) }
        }

        // Ближайший экзамен: обратный отсчёт и сразу к подготовке.
        val exam = remember(resumeTick) { context.app.study.nextExam() }
        if (exam != null) ExamCountdown(exam) { go(MainActivity.SCREEN_EXAMS) }

        // Плитки: на планшете в один ряд, на телефоне — по три.
        val tiles: List<TileSpec> = listOf(
            TileSpec(
                R.drawable.ic_cards, "Повторение",
                if (due > 0) "${due} ${ruPlural(due, "карточка ждёт", "карточки ждут", "карточек ждут")}" else "Карточки из конспектов",
            ) { go(MainActivity.SCREEN_REVIEW) },
            TileSpec(R.drawable.ic_target, "Тренировка", "Вариант, мини-тест, экзамены") { go(MainActivity.SCREEN_TRAINING) },
            TileSpec(
                R.drawable.ic_flame, "Прогресс",
                if (streak > 0) "$streak ${ruPlural(streak, "день", "дня", "дней")} подряд" else "Серия и темы с ошибками",
            ) { go(MainActivity.SCREEN_PROGRESS) },
            TileSpec(R.drawable.ic_history, "История", "Задачи и чистовики") { go(MainActivity.SCREEN_HISTORY) },
            TileSpec(R.drawable.ic_note, "Чистовик", "Конспект в PDF") { go(MainActivity.SCREEN_CLEAN) },
        )
        val perRow = if (LocalConfiguration.current.screenWidthDp >= 840) tiles.size else 3
        tiles.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { t -> Tile(t.icon, t.title, t.caption, Modifier.weight(1f), t.onClick) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        SectionLabel("Как это работает")
        Column(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            val steps = listOf(
                "Решай сам" to "Пиши в своём приложении для записей (стилус проходит сквозь кнопку наставника) или прямо в тетради «Наставника».",
                "Застрял — нажми кнопку пальцем" to "Подсказка, поиск ошибки, теория или свой вопрос. Ответ появится прямо на нужной строке.",
                "Решил — закрепи" to "«Похожая задача» даст такую же на другие числа, а «Чистовик» превратит записи в аккуратный PDF.",
                "Повторяй по паре минут" to "Из каждого чистовика получаются карточки. Они возвращаются завтра, через 3 дня, через неделю — к экзамену всё в голове.",
            )
            steps.forEachIndexed { i, (title, text) ->
                Step(i + 1, title, text)
                if (i < steps.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Text(
            "Пройти первую настройку заново",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .clickable { go(MainActivity.SCREEN_ONBOARDING) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Кнопка поверх своего приложения для записей: состояние и главная кнопка. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    modifier: Modifier,
    running: Boolean,
    canStart: Boolean,
    notesLabel: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenNotes: () -> Unit,
    pills: @Composable () -> Unit,
) {
    Column(
        modifier
            .glass(RoundedCornerShape(28.dp), 14.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (running) Brand.Ok else Color(0xFFA3A2AB)),
            )
            Text(
                if (running) "Работает поверх ${notesLabel ?: "приложений"}" else "Сейчас выключен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (running) "Наставник включён" else "Поверх твоего приложения", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (running) "Кнопка ждёт у края экрана. Нажми её пальцем — подсказка, проверка или вопрос."
                else "Небольшая кнопка поверх ${notesLabel ?: "любого приложения для записей"}. Стилусу она не мешает.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                if (notesLabel != null) InkButton("Открыть $notesLabel", icon = R.drawable.ic_open, onClick = onOpenNotes)
                SoftButton("Выключить", icon = R.drawable.ic_power, onClick = onStop)
            } else {
                InkButton("Запустить наставника", enabled = canStart, icon = R.drawable.ic_power, onClick = onStart)
                if (notesLabel != null) SoftButton("Открыть $notesLabel", onClick = onOpenNotes)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills() }
    }
}

/** Тетради прямо в приложении: новая, фото, PDF и последние тетради. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotebooksCard(modifier: Modifier, resumeTick: Int, go: (String) -> Unit, openNotebook: (Long) -> Unit) {
    val context = LocalContext.current
    val nbs = context.app.notebooks
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val recent by produceState(emptyList<NotebookMeta>(), resumeTick) {
        value = withContext(Dispatchers.IO) { nbs.store.list().take(3) }
    }
    val importers = rememberImporters(
        onImages = { uris -> scope.launch { busy = true; nbs.importImages(uris)?.let { openNotebook(it.id) }; busy = false } },
        onPdf = { uri -> scope.launch { busy = true; nbs.importPdf(uri)?.let { openNotebook(it.id) }; busy = false } },
    )
    Column(
        modifier
            .glass(RoundedCornerShape(28.dp), 14.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(R.drawable.ic_notebook, 36)
            Text("Тетради", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.Ink, trackColor = Brand.Ink.copy(alpha = 0.1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Пиши прямо здесь", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Стилусом на листе в клетку, поверх фото тетради или PDF. Наставник рядом и видит всю тетрадь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Новая тетрадь", icon = R.drawable.ic_plus) {
                scope.launch {
                    val (m, _) = withContext(Dispatchers.IO) { nbs.store.create(nbs.defaultTitle(), Paper.GRID) }
                    openNotebook(m.id)
                }
            }
            SoftButton("Фото", icon = R.drawable.ic_camera) { importers.camera() }
            SoftButton("PDF", icon = R.drawable.ic_pdf) { importers.pdf() }
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { m -> ChipButton(m.title) { openNotebook(m.id) } }
                ChipButton("Все тетради →") { go(MainActivity.SCREEN_NOTEBOOKS) }
            }
        }
    }
}

/** «Сегодня»: карточки ждут повторения — одна кнопка, чтобы начать. */
@Composable
private fun Today(due: Int, streak: Int, onReview: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .clickable(onClick = onReview)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brand.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(due.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Сегодня: ${ruPlural(due, "карточка", "карточки", "карточек").let { "$due $it" }} на повторение",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (streak > 0) "Пара минут — и серия не прервётся: уже $streak ${ruPlural(streak, "день", "дня", "дней")} подряд." else "Пара минут — и материал не забудется к экзамену.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InkButton("Повторить", icon = R.drawable.ic_cards, onClick = onReview)
    }
}

/** Карточка «нужно внимание»: что не так и кнопка, чтобы исправить. */
@Composable
private fun Attention(title: String, text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Brand.Error),
            contentAlignment = Alignment.Center,
        ) { Text("!", color = Color.White, style = MaterialTheme.typography.titleMedium) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
        }
        InkButton(action, onClick = onAction)
    }
}

@Composable
private class TileSpec(val icon: Int, val title: String, val caption: String, val onClick: () -> Unit)

/** Экзамен скоро: сколько дней осталось. */
@Composable
private fun ExamCountdown(e: Exam, onClick: () -> Unit) {
    val days = ExamPlan.daysLeft(e.at, System.currentTimeMillis())
    Row(
        Modifier
            .fillMaxWidth()
            .glass()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(R.drawable.ic_calendar)
        Column(Modifier.weight(1f)) {
            Text(
                if (days == 0) "Сегодня экзамен: ${e.subject}" else "${e.subject}: экзамен через $days ${ruPlural(days, "день", "дня", "дней")}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            Text("Карточки предмета успеют повториться. Реши вариант — проверь себя.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Tile(icon: Int, title: String, caption: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .glass()
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            // Две строки у всех плиток — ряд ровный, даже если подпись короткая.
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2)
        }
    }
}

@Composable
private fun Step(n: Int, title: String, text: String) {
    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NumberBadge(n)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

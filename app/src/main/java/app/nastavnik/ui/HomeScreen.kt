package app.nastavnik.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.study.Streaks
import app.nastavnik.study.ruPlural
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.notes.TouchnotesSender
import app.nastavnik.overlay.BubbleService
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(resumeTick: Int, go: (String) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val overlay = remember(resumeTick, tick) { Permissions.overlay(context) }
    val notif = remember(resumeTick, tick) { Permissions.notifications(context) }
    val mic = remember(resumeTick, tick) { Permissions.mic(context) }
    val running = remember(resumeTick, tick) { BubbleService.running }
    val touchnotes = remember(resumeTick) { TouchnotesSender.findTouchnotes(context) }

    // Состояние сервиса меняется асинхронно — перепроверяем чуть позже.
    LaunchedEffect(tick) {
        delay(800)
        if (BubbleService.running != running) tick++
    }

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
                "Пиши в Touchnotes, а когда застрянешь — нажми кнопку. Ответ появится прямо на твоих записях.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Hero(
            running = running,
            canStart = overlay,
            touchnotesLabel = touchnotes?.label,
            onStart = { BubbleService.start(context); tick++ },
            onStop = { BubbleService.stop(context); tick++ },
            onOpenTouchnotes = { touchnotes?.let { openApp(context, it.packageName) } },
        ) {
            StatusPill(provider, notReady == null)
            StatusPill("Уведомления", notif)
            StatusPill("Микрофон", mic)
            StatusPill(if (touchnotes != null) "Touchnotes" else "Touchnotes не найден", touchnotes != null)
        }

        if (!overlay) {
            Attention("Нужно разрешение «Поверх других окон»", "Без него кнопка и подсказки не появятся поверх Touchnotes.", "Разрешить") {
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                R.drawable.ic_cards, Brand.Accent, "Повторение",
                if (due > 0) "${due} ${ruPlural(due, "карточка ждёт", "карточки ждут", "карточек ждут")}" else "Карточки из конспектов",
                Modifier.weight(1f),
            ) { go(MainActivity.SCREEN_REVIEW) }
            Tile(
                R.drawable.ic_flame, Brand.Theory, "Прогресс",
                if (streak > 0) "$streak ${ruPlural(streak, "день", "дня", "дней")} подряд" else "Серия и темы с ошибками",
                Modifier.weight(1f),
            ) { go(MainActivity.SCREEN_PROGRESS) }
            Tile(R.drawable.ic_history, Brand.InkSoft, "История", "Задачи и чистовики", Modifier.weight(1f)) { go(MainActivity.SCREEN_HISTORY) }
            Tile(R.drawable.ic_note, Brand.Ok, "Чистовик", "Конспект в PDF", Modifier.weight(1f)) { go(MainActivity.SCREEN_CLEAN) }
        }

        SectionLabel("Как это работает")
        Column(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            val steps = listOf(
                "Решай сам" to "Пиши в Touchnotes как обычно. Стилус проходит сквозь кнопку наставника — она не мешает.",
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

/** Стеклянная карточка состояния с главной кнопкой. */
@Composable
private fun Hero(
    running: Boolean,
    canStart: Boolean,
    touchnotesLabel: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTouchnotes: () -> Unit,
    pills: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), 14.dp)
            .padding(26.dp),
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
                if (running) "Работает поверх Touchnotes" else "Сейчас выключен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (running) "Наставник включён" else "Включи наставника",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (running) "Кнопка ждёт у края экрана. Нажми её пальцем — выбери подсказку, проверку или вопрос."
                else "Поверх записей появится небольшая кнопка. Стилусу она не мешает.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                if (touchnotesLabel != null) InkButton("Открыть $touchnotesLabel", icon = R.drawable.ic_open, onClick = onOpenTouchnotes)
                SoftButton("Выключить", icon = R.drawable.ic_power, onClick = onStop)
            } else {
                InkButton("Запустить наставника", enabled = canStart, icon = R.drawable.ic_power, onClick = onStart)
                if (touchnotesLabel != null) SoftButton("Открыть $touchnotesLabel", onClick = onOpenTouchnotes)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills() }
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
                if (streak > 0) "Пара минут — и серия $streak ${ruPlural(streak, "день", "дня", "дней")} продолжится." else "Пара минут — и материал не забудется к экзамену.",
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
private fun Tile(icon: Int, tint: Color, title: String, caption: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .glass()
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, tint)
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

private fun openApp(context: Context, pkg: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

package app.nastavnik.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
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
        // Шапка: искра и радужное название.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.ic_sparkle), contentDescription = null, modifier = Modifier.size(44.dp))
            Column {
                Text(
                    "Наставник",
                    style = MaterialTheme.typography.headlineMedium.copy(brush = GoogleColors.rainbow),
                )
                Text(
                    "Подсказки прямо на записях в Touchnotes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        StatusHero(
            running = running,
            canStart = overlay,
            touchnotesLabel = touchnotes?.label,
            onStart = { BubbleService.start(context); tick++ },
            onStop = { BubbleService.stop(context); tick++ },
            onOpenTouchnotes = { touchnotes?.let { openApp(context, it.packageName) } },
        ) {
            StatusPill("Нейросеть: $provider", notReady == null)
            StatusPill("Уведомления", notif)
            StatusPill("Микрофон", mic)
            StatusPill(if (touchnotes != null) "Touchnotes" else "Touchnotes не найден", touchnotes != null)
        }

        if (!overlay) {
            AttentionCard(
                "Нужно разрешение «Поверх других окон»",
                "Без него кнопка и подсказки не появятся поверх Touchnotes.",
                "Разрешить",
            ) { context.startActivity(Permissions.overlaySettingsIntent(context)) }
        }
        if (notReady != null) {
            AttentionCard("$notReady", "Без этого наставник не сможет ответить.", "Открыть настройки") {
                go(MainActivity.SCREEN_SETTINGS)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(R.drawable.ic_tune, GoogleColors.Blue, "Настройки", "Нейросеть и ключ", Modifier.weight(1f)) { go(MainActivity.SCREEN_SETTINGS) }
            Tile(R.drawable.ic_history, GoogleColors.Violet, "История", "Задачи и чистовики", Modifier.weight(1f)) { go(MainActivity.SCREEN_HISTORY) }
            Tile(R.drawable.ic_note, GoogleColors.Green, "Тест чистовика", "PDF без нейросети", Modifier.weight(1f)) { go(MainActivity.SCREEN_CLEAN_TEST) }
        }

        SectionCard("Как это работает") {
            Step(1, GoogleColors.Blue, "Пиши в Touchnotes как обычно", "Кнопка наставника не мешает: стилус проходит сквозь неё.")
            Step(2, GoogleColors.Violet, "Нажми кнопку пальцем", "«Подсказка», «Где ошибка?», «Теория», «Спросить…» или «Чистовик».")
            Step(3, GoogleColors.Red, "Ответ появится на записи", "Нужная строка подсвечена, рядом — заметка с формулами. Её можно перетащить за заголовок, а при прокрутке она едет вместе со страницей.")
            Step(4, GoogleColors.Green, "Решение готово — сделай чистовик", "Аккуратный PDF уйдёт в Touchnotes и сохранится в «Документы/Конспекты».")
            Text(
                "Долгое нажатие на кнопку: «Следить за решением» — наставник сам проверит, когда ты перестанешь писать.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = { go(MainActivity.SCREEN_ONBOARDING) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Пройти первую настройку заново", fontWeight = FontWeight.Normal)
        }
    }
}

/** Большая карточка состояния с главной кнопкой. */
@Composable
private fun StatusHero(
    running: Boolean,
    canStart: Boolean,
    touchnotesLabel: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTouchnotes: () -> Unit,
    pills: @Composable () -> Unit,
) {
    val bg = Brush.linearGradient(listOf(Color(0xFFE8F0FE), Color(0xFFF3E8FD), Color(0xFFFCE8E6)))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (running) GoogleColors.rainbow else Brush.linearGradient(listOf(Color.White, Color.White))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_power),
                        contentDescription = null,
                        tint = if (running) Color.White else Color(0xFF5F6368),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Наставник работает" else "Наставник выключен",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF1F1F1F),
                    )
                    Text(
                        if (running) "Кнопка висит поверх Touchnotes — нажми её, когда понадобится помощь."
                        else "Включи — поверх записей появится кнопка наставника.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF444746),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    OutlinedButton(onClick = onStop) { Text("Выключить наставника") }
                } else {
                    RainbowButton("Запустить наставника", enabled = canStart, onClick = onStart)
                }
                if (touchnotesLabel != null) {
                    FilledTonalButton(onClick = onOpenTouchnotes) {
                        Icon(painterResource(R.drawable.ic_open), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Открыть $touchnotesLabel")
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills() }
        }
    }
}

/** Карточка «нужно внимание»: что не так и кнопка, чтобы исправить. */
@Composable
private fun AttentionCard(title: String, text: String, action: String, onAction: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun Tile(icon: Int, tint: Color, title: String, caption: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(icon, tint)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Step(n: Int, color: Color, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), color = color, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openApp(context: Context, pkg: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

package app.nastavnik.ui

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.notes.CleanView
import app.nastavnik.notes.NoteStorage
import app.nastavnik.session.CleanState

/**
 * Предпросмотр и сохранение чистовика. Снимки собираются кнопкой поверх приложения для записей или из тетради,
 * а сюда приложение переходит само, когда нейросеть оформила конспект.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanScreen(onClose: () -> Unit, onSettings: () -> Unit, onExample: () -> Unit) {
    val context = LocalContext.current
    val clean = context.app.clean
    val state by clean.state.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf<FrameLayout?>(null) }
    var view by remember { mutableStateOf<CleanView?>(null) }
    var shownJson by remember { mutableStateOf<String?>(null) }
    val conversationId = remember { context.app.settings.load().activeConversationId }

    val s = state
    val json = when (s) {
        is CleanState.Preview -> s.json
        is CleanState.Saving -> s.json
        is CleanState.Saved -> s.json
        is CleanState.Failed -> s.json
        else -> null
    }
    val title = when (s) {
        is CleanState.Preview -> NoteStorage.plainTitle(s.note.title).ifBlank { "Чистовик" }
        is CleanState.Saving -> NoteStorage.plainTitle(s.note.title).ifBlank { "Чистовик" }
        is CleanState.Saved -> NoteStorage.plainTitle(s.note.title).ifBlank { "Чистовик" }
        is CleanState.Failed -> "Не получилось"
        is CleanState.Generating -> "Собираю чистовик…"
        else -> "Чистовик"
    }

    ScreenScaffold(title = title, onBack = onClose, scrollable = json == null) {
        if (s == CleanState.Idle) {
            IdleClean(onExample)
            return@ScreenScaffold
        }
        val icon = when (s) {
            is CleanState.Saved -> R.drawable.ic_check
            is CleanState.Failed -> R.drawable.ic_close
            else -> R.drawable.ic_sparkle
        }
        val message = when (s) {
            CleanState.Idle -> ""
            is CleanState.Collecting -> "Снимков: ${s.shots.size} из ${CleanState.MAX_SHOTS}. Добавлять экраны можно кнопкой наставника поверх приложения для записей."
            is CleanState.Generating -> "Нейросеть оформляет конспект по снимкам (${s.shots.size}). Обычно это до минуты." +
                if (s.retry) "\nПервый ответ не разобрался — пробую ещё раз." else ""
            is CleanState.Preview -> "Проверь, всё ли на месте. «Сохранить» сделает PDF и картинки в «Документы/Конспекты» и отправит конспект в твоё приложение для записей."
            is CleanState.Saving -> "Сохраняю PDF и картинки…"
            is CleanState.Saved -> s.sendMessage
            is CleanState.Failed -> s.message
        }
        Column(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (s is CleanState.Generating || s is CleanState.Saving) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Brand.Ink, trackColor = Brand.Ink.copy(alpha = 0.1f))
                    }
                } else {
                    IconBadge(icon)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    is CleanState.Collecting -> {
                        InkButton("Собрать чистовик", enabled = s.shots.isNotEmpty() && !s.capturing) { clean.build() }
                        SoftButton("Отмена") { clean.cancel(); onClose() }
                    }
                    is CleanState.Generating -> SoftButton("Отмена") { clean.cancel(); onClose() }
                    is CleanState.Preview -> {
                        InkButton("Сохранить", enabled = host != null, icon = R.drawable.ic_share) { host?.let { clean.save(it, conversationId) } }
                        // Шпаргалка собрана без снимков — пересобирать её нечем.
                        if (s.shots.isNotEmpty()) SoftButton("Пересобрать", icon = R.drawable.ic_refresh) { clean.build() }
                        SoftButton("Отмена") { clean.cancel(); onClose() }
                    }
                    is CleanState.Saving -> InkButton("Сохранить", enabled = false) {}
                    is CleanState.Saved -> {
                        InkButton("Готово") { clean.cancel(); onClose() }
                        SoftButton("Отправить ещё раз", icon = R.drawable.ic_share) { clean.resend() }
                        SoftButton("Картинками", icon = R.drawable.ic_image) { clean.sendPngs() }
                    }
                    is CleanState.Failed -> {
                        if (s.shots.isNotEmpty()) InkButton("Пересобрать", icon = R.drawable.ic_refresh) { clean.build() }
                        if (s.note != null) SoftButton("К предпросмотру") { clean.backToPreview() }
                        if (s.needsSettings) SoftButton("Настройки", icon = R.drawable.ic_tune, onClick = onSettings)
                        SoftButton("Отмена") { clean.cancel(); onClose() }
                    }
                    CleanState.Idle -> Unit
                }
            }
        }
        if (json != null) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .glass(RoundedCornerShape(24.dp)),
                factory = { ctx ->
                    FrameLayout(ctx).also { frame ->
                        val web = WebView(ctx)
                        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        view = CleanView(web, "preview")
                        host = frame
                    }
                },
                update = {
                    if (shownJson != json) {
                        shownJson = json
                        view?.show(json)
                    }
                },
            )
        }
    }
}

/** Чистовика сейчас нет: как его сделать и пример без запроса к нейросети. */
@Composable
private fun IdleClean(onExample: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBadge(R.drawable.ic_note, 52)
        Text("Аккуратный конспект из твоих записей", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Нейросеть перепишет рукописные записи начисто: заголовки, определения, формулы, шаги решения и графики. " +
                "Ничего не добавит от себя без пометки и не исправит ошибки молча.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val steps = listOf(
            "Открой конспект — в своём приложении для записей (кнопка наставника → «Чистовик») или в тетради «Наставника» (кнопка «Сделать чистовик»).",
            "Конспект длиннее экрана? Прокрути и нажми «+ Экран» — до ${CleanState.MAX_SHOTS} снимков.",
            "Нажми «Собрать», проверь предпросмотр и сохрани: PDF уйдёт в твоё приложение для записей.",
        )
        steps.forEachIndexed { i, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberBadge(i + 1)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        InkButton("Посмотреть пример", icon = R.drawable.ic_open, onClick = onExample)
    }
}

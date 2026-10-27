package app.nastavnik.ui

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.nastavnik.app
import app.nastavnik.notes.CleanView
import app.nastavnik.notes.NoteStorage
import app.nastavnik.session.CleanState

/**
 * Предпросмотр и сохранение чистовика. Снимки собираются кнопкой поверх Touchnotes,
 * а сюда приложение переходит само, когда нейросеть оформила конспект.
 */
@Composable
fun CleanScreen(onClose: () -> Unit, onSettings: () -> Unit) {
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
        is CleanState.Saved -> "Готово: " + NoteStorage.plainTitle(s.note.title)
        is CleanState.Failed -> "Не получилось"
        is CleanState.Generating -> "Собираю чистовик…"
        else -> "Чистовик"
    }

    ScreenScaffold(title = title, onBack = onClose, scrollable = false) {
        val message = when (s) {
            CleanState.Idle -> "Чистовика сейчас нет. Нажми на кнопку наставника поверх Touchnotes → «Чистовик»."
            is CleanState.Collecting -> "Снимков: ${s.shots.size} из ${CleanState.MAX_SHOTS}. Добавлять экраны можно кнопкой наставника поверх Touchnotes."
            is CleanState.Generating -> "Нейросеть оформляет конспект по снимкам (${s.shots.size}). Обычно это до минуты." +
                if (s.retry) "\nПервый ответ не разобрался — пробую ещё раз." else ""
            is CleanState.Preview -> "Проверь предпросмотр. «Сохранить» — PDF и PNG в «Документы/Конспекты» и отправка в Touchnotes."
            is CleanState.Saving -> "Сохраняю PDF и картинки…"
            is CleanState.Saved -> s.sendMessage
            is CleanState.Failed -> s.message
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s is CleanState.Generating || s is CleanState.Saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (s) {
                is CleanState.Collecting -> {
                    Button(enabled = s.shots.isNotEmpty() && !s.capturing, onClick = { clean.build() }) { Text("Собрать чистовик") }
                    TextButton(onClick = { clean.cancel(); onClose() }) { Text("Отмена") }
                }
                is CleanState.Generating -> TextButton(onClick = { clean.cancel(); onClose() }) { Text("Отмена") }
                is CleanState.Preview -> {
                    Button(enabled = host != null, onClick = { host?.let { clean.save(it, conversationId) } }) { Text("Сохранить") }
                    OutlinedButton(onClick = { clean.build() }) { Text("Пересобрать") }
                    TextButton(onClick = { clean.cancel(); onClose() }) { Text("Отмена") }
                }
                is CleanState.Saving -> Button(enabled = false, onClick = {}) { Text("Сохранить") }
                is CleanState.Saved -> {
                    Button(onClick = { clean.resend() }) { Text("Отправить ещё раз") }
                    OutlinedButton(onClick = { clean.sendPngs() }) { Text("Отправить картинками") }
                    TextButton(onClick = { clean.cancel(); onClose() }) { Text("Закрыть") }
                }
                is CleanState.Failed -> {
                    if (s.shots.isNotEmpty()) Button(onClick = { clean.build() }) { Text("Пересобрать") }
                    if (s.note != null) OutlinedButton(onClick = { clean.backToPreview() }) { Text("К предпросмотру") }
                    if (s.needsSettings) OutlinedButton(onClick = onSettings) { Text("Настройки") }
                    TextButton(onClick = { clean.cancel(); onClose() }) { Text("Отмена") }
                }
                CleanState.Idle -> TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
        if (json != null) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
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

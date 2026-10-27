package app.nastavnik.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import app.nastavnik.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    ScreenScaffold(title = "", onBack = null) {
        // Приветствие в стиле Gemini: искра и радужный заголовок.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_sparkle), contentDescription = null, modifier = Modifier.size(40.dp))
            Text(
                "Привет! Я — Наставник",
                style = MaterialTheme.typography.headlineMedium.copy(brush = GoogleColors.rainbow, fontWeight = FontWeight.Medium),
            )
        }
        Text(
            "Я работаю в фоне поверх Touchnotes. Спроси — и ответ появится прямо на записи, там, где задача: " +
                "рамка вокруг ошибки или места для следующего шага и короткая заметка рядом. Решать будешь ты.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(if (running) "Наставник включён" else "Наставник выключен") {
            if (!overlay) {
                Text("Нужно разрешение «Поверх других окон», иначе кнопку не показать.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { context.startActivity(Permissions.overlaySettingsIntent(context)) }) { Text("Разрешить показ поверх окон") }
            }
            if (notReady != null) {
                Text("$notReady — без этого наставник не ответит.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { go(MainActivity.SCREEN_SETTINGS) }) { Text("Открыть настройки нейросети") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    OutlinedButton(onClick = { BubbleService.stop(context); tick++ }) { Text("Выключить наставника") }
                } else {
                    RainbowButton("Запустить наставника", enabled = overlay) {
                        BubbleService.start(context)
                        tick++
                    }
                }
                if (touchnotes != null) {
                    FilledTonalButton(onClick = { openApp(context, touchnotes.packageName) }) { Text("Открыть ${touchnotes.label}") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Нейросеть: ${context.app.settings.provider().shortTitle}", notReady == null)
                StatusPill("Уведомления", notif)
                StatusPill("Микрофон", mic)
                StatusPill(if (touchnotes != null) "Touchnotes найден" else "Touchnotes не найден", touchnotes != null)
            }
        }

        SectionCard("Как пользоваться") {
            Text(
                "1. Открой Touchnotes и пиши как обычно — кнопка не мешает: касания стилусом проходят сквозь неё.\n" +
                    "2. Нажми кнопку пальцем — рядом раскроется короткое меню: «Подсказка», «Где ошибка?», «Теория», «Спросить…» (голосом или текстом), «Чистовик».\n" +
                    "3. Я смотрю на свежий снимок экрана и отвечаю прямо на записи: красная рамка — ошибка, синяя пунктирная — где писать дальше, рядом заметка с формулами.\n" +
                    "4. Поднеси стилус к заметке — она станет прозрачной и пропустит штрих. Прокрутишь страницу — рамки поедут вместе с записями. Убрать — крестик или «Убрать заметки».\n" +
                    "5. Долгое нажатие на кнопку: «Следить за решением» (сам проверю, когда перестанешь писать), выделение области, новая задача, настройки.\n" +
                    "6. Когда решение готово — «Сделать чистовик»: аккуратный PDF уйдёт в Touchnotes и сохранится в «Документы/Конспекты».",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { go(MainActivity.SCREEN_SETTINGS) }) { Text("Настройки") }
            FilledTonalButton(onClick = { go(MainActivity.SCREEN_HISTORY) }) { Text("История") }
            FilledTonalButton(onClick = { go(MainActivity.SCREEN_CLEAN_TEST) }) { Text("Тест чистовика") }
        }
        TextButton(onClick = { go(MainActivity.SCREEN_ONBOARDING) }) {
            Text("Пройти первую настройку заново", fontWeight = FontWeight.Normal)
        }
    }
}

private fun openApp(context: Context, pkg: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

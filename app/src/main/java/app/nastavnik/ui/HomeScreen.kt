package app.nastavnik.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
    val hasKey = remember(resumeTick, tick) { context.app.settings.hasApiKey() }
    val running = remember(resumeTick, tick) { BubbleService.running }
    val touchnotes = remember(resumeTick) { TouchnotesSender.findTouchnotes(context) }

    // Состояние сервиса меняется асинхронно — перепроверяем чуть позже.
    LaunchedEffect(tick) {
        delay(800)
        if (BubbleService.running != running) tick++
    }

    ScreenScaffold(title = "Наставник", onBack = null) {
        Text(
            "Плавающая кнопка поверх Touchnotes. Нажми её — наставник посмотрит на экран и подскажет следующий шаг, но решать будешь ты.",
            style = MaterialTheme.typography.bodyLarge,
        )

        SectionCard(if (running) "Наставник включён" else "Наставник выключен") {
            if (!overlay) {
                Text("Нужно разрешение «Поверх других окон», иначе кнопку не показать.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { context.startActivity(Permissions.overlaySettingsIntent(context)) }) { Text("Разрешить показ поверх окон") }
            }
            if (!hasKey) {
                Text("Не задан API-ключ (${context.app.settings.provider().title}) — без него наставник не ответит.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { go(MainActivity.SCREEN_SETTINGS) }) { Text("Вставить ключ") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    Button(onClick = { BubbleService.stop(context); tick++ }) { Text("Выключить наставника") }
                } else {
                    Button(enabled = overlay, onClick = {
                        BubbleService.start(context)
                        tick++
                    }) { Text("Запустить наставника") }
                }
                if (touchnotes != null) {
                    FilledTonalButton(onClick = { openApp(context, touchnotes.packageName) }) { Text("Открыть ${touchnotes.label}") }
                }
            }
            Text(
                listOfNotNull(
                    "Уведомления: " + if (notif) "есть" else "нет (наставника может выключить система)",
                    "Микрофон: " + if (mic) "есть" else "нет (голосовой ввод выключен)",
                    "Touchnotes: " + if (touchnotes != null) "найден — чистовики уйдут прямо в него" else "не найден — будет обычное меню «Поделиться»",
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        SectionCard("Как пользоваться") {
            Text(
                "1. Открой Touchnotes и пиши как обычно — кнопка не мешает: касания стилусом проходят сквозь неё.\n" +
                    "2. Нажми кнопку пальцем — справа (или снизу) выедет панель. Писать в видимой части можно и с открытой панелью.\n" +
                    "3. «Подскажи следующий шаг», «Проверь, где ошибка», «Объясни теорию» — каждый раз делается свежий снимок экрана.\n" +
                    "4. Значок рамки в шапке панели — отправлять только выделенную область.\n" +
                    "5. Когда решение готово — «Сделать чистовик»: аккуратный PDF уйдёт в Touchnotes и сохранится в «Документы/Конспекты».\n" +
                    "6. Долгое нажатие на кнопку — спрятать на 10 минут, настройки, выключить.",
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { go(MainActivity.SCREEN_SETTINGS) }) { Text("Настройки") }
            OutlinedButton(onClick = { go(MainActivity.SCREEN_HISTORY) }) { Text("История") }
            OutlinedButton(onClick = { go(MainActivity.SCREEN_CLEAN_TEST) }) { Text("Тест чистовика") }
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

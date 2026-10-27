package app.nastavnik.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nastavnik.app
import app.nastavnik.overlay.BubbleService

private data class Step(
    val title: String,
    val why: String,
    val done: Boolean,
    val actionLabel: String?,
    val action: (() -> Unit)?,
    val optional: Boolean = false,
)

/** Первый запуск: по шагам выдаём разрешения и объясняем зачем — одной строкой каждое. */
@Composable
fun OnboardingScreen(resumeTick: Int, onDone: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val settings = context.app.settings
    var index by rememberSaveable { mutableIntStateOf(0) }
    var permTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    var captureUnderstood by rememberSaveable { mutableStateOf(false) }

    val overlay = remember(resumeTick, permTick) { Permissions.overlay(context) }
    val notif = remember(resumeTick, permTick) { Permissions.notifications(context) }
    val mic = remember(resumeTick, permTick) { Permissions.mic(context) }
    val hasKey = remember(resumeTick, permTick) { settings.notReadyReason() == null }

    val steps = listOf(
        Step(
            "Привет! Я — Наставник",
            "Я вишу кнопкой поверх Touchnotes, по твоему нажатию смотрю на экран и подсказываю, как решать, — но не решаю за тебя. Дай пару разрешений.",
            done = true, actionLabel = null, action = null,
        ),
        Step(
            "Поверх других окон",
            "Чтобы кнопка и ответы наставника появлялись поверх Touchnotes.",
            done = overlay, actionLabel = "Разрешить",
            action = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
        ),
        Step(
            "Уведомления",
            "Чтобы Android не выключал наставника в фоне (в шторке будет «Наставник рядом»).",
            done = notif, actionLabel = "Разрешить",
            action = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        ),
        Step(
            "Снимок экрана",
            "Чтобы наставник видел твои записи. Система спросит при запуске сессии; на Android 14+ — каждый раз заново, выбирай «Весь экран».",
            done = captureUnderstood, actionLabel = "Понятно",
            action = { captureUnderstood = true },
        ),
        Step(
            "Микрофон",
            "Чтобы задавать вопросы голосом. Можно пропустить.",
            done = mic, actionLabel = "Разрешить",
            action = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            optional = true,
        ),
        Step(
            "Нейросеть и API-ключ",
            "Выбери, кто будет думать: Claude, Gemini или любой OpenAI-совместимый сервис. Ключ хранится только на планшете, в зашифрованном виде.",
            done = hasKey, actionLabel = null, action = null,
        ),
        Step(
            "Всё готово",
            "Запусти наставника и открой Touchnotes. Нажми кнопку пальцем, когда понадобится подсказка.",
            done = true, actionLabel = null, action = null,
        ),
    )
    val step = steps[index.coerceIn(0, steps.lastIndex)]

    ScreenScaffold(title = "Первая настройка", onBack = null) {
        Text("Шаг ${index + 1} из ${steps.size}", color = MaterialTheme.colorScheme.secondary)
        LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())

        SectionCard(step.title) {
            Text(step.why, style = MaterialTheme.typography.bodyLarge)
            if (index in 1 until steps.lastIndex && step.done && step.actionLabel != "Понятно") {
                Text("✓ Готово", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            if (step.title.startsWith("Нейросеть")) {
                ProviderEditor(onChanged = { permTick++ })
            }
            if (step.actionLabel != null && !step.done) {
                Button(onClick = { step.action?.invoke() }) { Text(step.actionLabel) }
            }
            if (index == steps.lastIndex) {
                Button(enabled = overlay, onClick = {
                    settings.setOnboardingDone(true)
                    BubbleService.start(context)
                    onDone()
                }) { Text("Запустить наставника") }
                if (!overlay) Text("Сначала разреши показ поверх окон (шаг 2).", color = MaterialTheme.colorScheme.error)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (index > 0) OutlinedButton(onClick = { index-- }) { Text("Назад") }
            Spacer(Modifier.weight(1f))
            if (index < steps.lastIndex) {
                val canSkip = step.done || step.optional || index == 0 || step.title.startsWith("Нейросеть") || step.title == "Уведомления"
                Button(enabled = canSkip, onClick = { index++ }) {
                    Text(if (step.done || index == 0) "Далее" else "Пропустить")
                }
            }
        }
        TextButton(onClick = {
            settings.setOnboardingDone(true)
            onSkip()
        }) { Text("Пропустить настройку") }
    }
}

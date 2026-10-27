package app.nastavnik.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.nastavnik.R
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
    val icons = listOf(R.drawable.ic_sparkle, R.drawable.ic_open, R.drawable.ic_bulb, R.drawable.ic_search, R.drawable.ic_mic, R.drawable.ic_tune, R.drawable.ic_power)

    // На планшете карточка — по центру экрана и не шире 620 dp.
    val topPad = (LocalConfiguration.current.screenHeightDp * 0.08f).dp
    ScreenScaffold(title = "", onBack = null) {
      Column(
        Modifier.widthIn(max = 620.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(top = topPad),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Прогресс: полоски по числу шагов.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.indices.forEach { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        Text("Шаг ${index + 1} из ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Column(
            Modifier
                .fillMaxWidth()
                .softCard(RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface)
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brand.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icons[index.coerceIn(0, icons.lastIndex)]), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Text(step.title, style = MaterialTheme.typography.headlineMedium)
            Text(step.why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (index in 1 until steps.lastIndex && step.done && step.actionLabel != "Понятно") {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Brand.OkSoft)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✓", color = Brand.Ok, style = MaterialTheme.typography.titleSmall)
                    Text("Готово", color = Brand.Ok, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (step.title.startsWith("Нейросеть")) {
                ProviderEditor(onChanged = { permTick++ })
            }
            if (step.actionLabel != null && !step.done) {
                InkButton(step.actionLabel) { step.action?.invoke() }
            }
            if (index == steps.lastIndex) {
                InkButton("Запустить наставника", enabled = overlay, icon = R.drawable.ic_power) {
                    settings.setOnboardingDone(true)
                    BubbleService.start(context)
                    onDone()
                }
                if (!overlay) Text("Сначала разреши показ поверх окон (шаг 2).", color = MaterialTheme.colorScheme.error)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) SoftButton("Назад") { index-- }
            Spacer(Modifier.weight(1f))
            if (index < steps.lastIndex) {
                val canSkip = step.done || step.optional || index == 0 || step.title.startsWith("Нейросеть") || step.title == "Уведомления"
                InkButton(if (step.done || index == 0) "Далее" else "Пропустить", enabled = canSkip) { index++ }
            }
        }
        Text(
            "Пропустить настройку",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .clickable {
                    settings.setOnboardingDone(true)
                    onSkip()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }
    }
}

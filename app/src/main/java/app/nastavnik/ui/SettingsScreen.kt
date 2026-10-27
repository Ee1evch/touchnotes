package app.nastavnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.app
import app.nastavnik.claude.ClaudeModel
import app.nastavnik.claude.KeyCheck
import app.nastavnik.claude.Strictness
import app.nastavnik.data.BubbleSide
import app.nastavnik.data.SettingsRepo
import app.nastavnik.notes.TouchnotesSender
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.settings
    val s by remember { repo.flow() }.collectAsStateWithLifecycle(initialValue = repo.load())

    ScreenScaffold(title = "Настройки", onBack = onBack) {
        SectionCard("API-ключ Anthropic") {
            Text(
                "Ключ создаётся на console.anthropic.com → API keys. Хранится только на этом планшете, в зашифрованном виде.",
                style = MaterialTheme.typography.bodySmall,
            )
            ApiKeyEditor(onSaved = {})
            if (!repo.secureStorageOk) {
                Text(
                    "Зашифрованное хранилище недоступно: ключ сохранится только до перезапуска приложения.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard("Модель наставника") {
            ClaudeModel.entries.forEach { m ->
                RadioRow(s.model == m, "${m.label} — ${m.hint}", m.id) { repo.setModel(m) }
            }
        }

        SectionCard("Модель для чистовика") {
            ClaudeModel.entries.forEach { m ->
                RadioRow(s.cleanModel == m, "${m.label} — ${m.hint}", m.id) { repo.setCleanModel(m) }
            }
        }

        SectionCard("Строгость наставника") {
            Strictness.entries.forEach { st ->
                RadioRow(s.strictness == st, st.label) { repo.setStrictness(st) }
            }
        }

        SectionCard("Предмет по умолчанию") {
            Text("Подставляется в подсказки и в папку сохранения чистовиков.", style = MaterialTheme.typography.bodySmall)
            repo.subjects.forEach { subj ->
                RadioRow(s.subject == subj, subj) { repo.setSubject(subj) }
            }
            val custom = s.subject !in repo.subjects
            var customText by remember { mutableStateOf(if (custom) s.subject else "") }
            RadioRow(custom, "Другой") { if (customText.isNotBlank()) repo.setSubject(customText) }
            OutlinedTextField(
                value = customText,
                onValueChange = {
                    customText = it
                    if (it.isNotBlank()) repo.setSubject(it)
                },
                label = { Text("Свой предмет") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Плавающая кнопка") {
            var alpha by remember(s.bubbleAlpha) { mutableFloatStateOf(s.bubbleAlpha) }
            Text("Непрозрачность: ${(alpha * 100).roundToInt()}%")
            Slider(
                value = alpha,
                onValueChange = { alpha = it },
                onValueChangeFinished = { repo.setBubbleAlpha(alpha) },
                valueRange = SettingsRepo.MIN_ALPHA..SettingsRepo.MAX_ALPHA,
            )
            Text(
                "Больше 80% нельзя: иначе Android 12+ перестаёт пропускать касания стилуса сквозь кнопку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            var size by remember(s.bubbleSizeDp) { mutableFloatStateOf(s.bubbleSizeDp.toFloat()) }
            Text("Размер: ${size.roundToInt()} dp")
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { repo.setBubbleSize(size.roundToInt()) },
                valueRange = 40f..72f,
                steps = 7,
            )
            Text("Сторона экрана")
            RadioRow(s.bubbleSide == BubbleSide.LEFT, "Слева") { repo.setBubbleSide(BubbleSide.LEFT) }
            RadioRow(s.bubbleSide == BubbleSide.RIGHT, "Справа") { repo.setBubbleSide(BubbleSide.RIGHT) }
        }

        SectionCard("Вопросы своими словами") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Прикладывать снимок экрана", modifier = Modifier.weight(1f))
                Switch(checked = s.attachShotToTyped, onCheckedChange = { repo.setAttachShot(it) })
            }
            Text(
                "Быстрые кнопки всегда делают свежий снимок. Этот переключатель — для вопросов, набранных или сказанных голосом.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Куда отправлять чистовик") {
            val targets = remember { TouchnotesSender.pdfTargets(context) }
            val touch = remember(targets) { targets.firstOrNull { it.isTouchnotes } }
            RadioRow(
                s.sendTarget == SettingsRepo.TARGET_AUTO,
                "Автоматически в Touchnotes",
                if (touch != null) "Найдено: ${touch.label}" else "Touchnotes не найден — будет системный выбор",
            ) { repo.setSendTarget(SettingsRepo.TARGET_AUTO) }
            RadioRow(s.sendTarget == SettingsRepo.TARGET_CHOOSER, "Каждый раз спрашивать") {
                repo.setSendTarget(SettingsRepo.TARGET_CHOOSER)
            }
            targets.forEach { t ->
                RadioRow(s.sendTarget == t.encode(), t.label, if (t.action.endsWith("VIEW")) "открыть PDF" else "поделиться PDF") {
                    repo.setSendTarget(t.encode())
                }
            }
        }
    }
}

/** Поле ключа со скрытием и кнопкой «Проверить ключ». */
@Composable
fun ApiKeyEditor(onSaved: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.settings
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(repo.apiKey()) }
    var visible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim() },
        label = { Text("sk-ant-…") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать") }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            repo.setApiKey(key)
            status = if (key.isBlank()) "Ключ удалён" else "Ключ сохранён"
            onSaved()
        }) { Text("Сохранить") }
        OutlinedButton(enabled = !checking && key.isNotBlank(), onClick = {
            repo.setApiKey(key)
            onSaved()
            checking = true
            status = "Проверяю…"
            scope.launch {
                val model = repo.load().model.id
                status = when (val r = context.app.claude.checkKey(key, model)) {
                    KeyCheck.Ok -> "✓ Ключ работает, модель $model доступна"
                    is KeyCheck.ModelMissing -> "Ключ работает, но модель ${r.modelId} недоступна — выбери другую"
                    is KeyCheck.Failed -> r.message
                }
                checking = false
            }
        }) { Text("Проверить ключ") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

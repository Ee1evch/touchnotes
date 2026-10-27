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
import app.nastavnik.ai.ModelPresets
import app.nastavnik.ai.Strictness
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
        SectionCard("Нейросеть") {
            ProviderEditor(onChanged = {})
        }

        SectionCard("Модель наставника (${s.provider.shortTitle})") {
            ModelPicker(s.provider, ModelPresets.mentor(s.provider), s.model) { repo.setModel(it, s.provider) }
        }

        SectionCard("Модель для чистовика (${s.provider.shortTitle})") {
            Text(
                "Для чистовика нужна модель, которая хорошо читает рукописный текст и формулы.",
                style = MaterialTheme.typography.bodySmall,
            )
            ModelPicker(s.provider, ModelPresets.mentor(s.provider), s.cleanModel) { repo.setCleanModel(it, s.provider) }
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

        SectionCard("Ответы на экране") {
            Text(
                "Отдельного окна нет: ответ появляется прямо на записи. Рамка показывает место — красная: ошибка, " +
                    "синяя пунктирная: где писать дальше, зелёная: верно, фиолетовая: теория, — а рядом заметка. " +
                    "Прокрутишь страницу — рамки поедут вместе с записями. Заметку можно перетащить пальцем за заголовок или закрыть крестиком.",
                style = MaterialTheme.typography.bodySmall,
            )
            var fa by remember(s.noteFadeAlpha) { mutableFloatStateOf(s.noteFadeAlpha) }
            Text("Когда подносишь стилус, заметка становится прозрачной: ${(fa * 100).roundToInt()}%")
            Slider(
                value = fa,
                onValueChange = { fa = it },
                onValueChangeFinished = { repo.setNoteFadeAlpha(fa) },
                valueRange = 0.15f..0.8f,
            )
        }

        SectionCard("Следить за решением в фоне") {
            Text(
                "Наставник раз в пару секунд смотрит на экран — уменьшенный кадр остаётся на планшете. Когда ты перестанешь писать, " +
                    "он сам проверит решение и отметит ошибку, если найдёт; если всё верно — кнопка мигнёт зелёным. " +
                    "Каждая проверка — запрос к нейросети (не чаще раза в 45 с). Работает после первого разрешения на снимок экрана.",
                style = MaterialTheme.typography.bodySmall,
            )
            listOf(
                0 to "Выключено — отвечаю, только когда спросишь",
                5 to "Проверять через 5 с после того, как перестал писать",
                10 to "Через 10 с",
                20 to "Через 20 с",
            ).forEach { (sec, label) ->
                RadioRow(s.watchPauseSec == sec, label) { repo.setWatchPauseSec(sec) }
            }
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

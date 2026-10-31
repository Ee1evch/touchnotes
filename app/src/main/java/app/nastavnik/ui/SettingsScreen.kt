package app.nastavnik.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import app.nastavnik.R
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.app
import app.nastavnik.ai.ModelPresets
import app.nastavnik.ai.Strictness
import app.nastavnik.data.BubbleSide
import app.nastavnik.data.SettingsRepo
import app.nastavnik.notes.NoteApps
import app.nastavnik.study.Reminders
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

        SectionCard("Модели · ${s.provider.shortTitle}") {
            FieldLabel("Модель наставника", "Отвечает на вопросы и отмечает места на записях.")
            ModelPicker(s.provider, ModelPresets.mentor(s.provider), s.model) { repo.setModel(it, s.provider) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            FieldLabel("Модель для чистовика", "Нужна модель, которая хорошо читает рукописный текст и формулы.")
            ModelPicker(s.provider, ModelPresets.mentor(s.provider), s.cleanModel) { repo.setCleanModel(it, s.provider) }
        }

        SectionCard("Весь конспект") {
            FieldLabel(
                "Что видит наставник",
                "«Весь конспект» — страница, где ты спрашиваешь, и всё, что ты спокойно пролистал в этой тетради, " +
                    "уходят вместе с вопросом: наставник видит условие, определения и прошлые шаги. " +
                    "Пролистать всё сразу — долгое нажатие на кнопку → «Пролистать весь конспект». Страницы хранятся только на планшете, 12 часов.",
            )
            Segmented(listOf(true to "Весь конспект", false to "Только экран"), s.seeNotebook) { repo.setSeeNotebook(it) }
        }

        SectionCard("Наставник") {
            FieldLabel("Строгость", s.strictness.label)
            Segmented(
                listOf(Strictness.QUESTIONS_ONLY to "Только вопросы", Strictness.HINTS to "Подсказки", Strictness.DETAILED to "Подробно"),
                s.strictness,
            ) { repo.setStrictness(it) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            FieldLabel(
                "Следить за решением",
                "Когда перестанешь писать, наставник сам проверит решение и отметит ошибку; всё верно — кнопка мигнёт зелёным. " +
                    "Каждая проверка — запрос к нейросети (не чаще раза в 45 с).",
            )
            Segmented(listOf(0 to "Выкл.", 5 to "через 5 с", 10 to "через 10 с", 20 to "через 20 с"), s.watchPauseSec) {
                repo.setWatchPauseSec(it)
            }
        }

        SectionCard("Голос") {
            var speak by remember { mutableStateOf(repo.speakHints()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    FieldLabel(
                        "Читать подсказки вслух",
                        "Наставник сам прочитает короткую подсказку, когда ответит: удобно, когда в руке стилус. " +
                            "У каждой заметки есть и кнопка «прочитать».",
                    )
                }
                AppSwitch(speak) {
                    speak = it
                    repo.setSpeakHints(it)
                }
            }
        }

        SectionCard("Повторение") {
            FieldLabel(
                "Напоминать о карточках",
                "Раз в день, только если есть что повторить и сегодня ты ещё не занимался.",
            )
            Segmented(listOf(-1 to "Не напоминать", 9 to "в 9:00", 18 to "в 18:00", 21 to "в 21:00"), s.reminderHour) {
                repo.setReminderHour(it)
                Reminders.schedule(context)
            }
        }

        SectionCard("Заметки на экране") {
            var fa by remember(s.noteFadeAlpha) { mutableFloatStateOf(s.noteFadeAlpha) }
            FieldLabel(
                "Прозрачность у стилуса: ${(fa * 100).roundToInt()}%",
                "Поднесёшь стилус к заметке — она станет прозрачной и пропустит штрих. Перетащить заметку можно за заголовок, пальцем или стилусом.",
            )
            AppSlider(
                value = fa,
                onValueChange = { fa = it },
                onValueChangeFinished = { repo.setNoteFadeAlpha(fa) },
                valueRange = 0.15f..0.8f,
            )
        }

        SectionCard("Плавающая кнопка") {
            var alpha by remember(s.bubbleAlpha) { mutableFloatStateOf(s.bubbleAlpha) }
            FieldLabel(
                "Непрозрачность: ${(alpha * 100).roundToInt()}%",
                "Больше 80% нельзя: иначе Android 12+ перестаёт пропускать касания стилуса сквозь кнопку.",
            )
            AppSlider(
                value = alpha,
                onValueChange = { alpha = it },
                onValueChangeFinished = { repo.setBubbleAlpha(alpha) },
                valueRange = SettingsRepo.MIN_ALPHA..SettingsRepo.MAX_ALPHA,
            )
            var size by remember(s.bubbleSizeDp) { mutableFloatStateOf(s.bubbleSizeDp.toFloat()) }
            FieldLabel("Размер: ${size.roundToInt()} dp")
            AppSlider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { repo.setBubbleSize(size.roundToInt()) },
                valueRange = 40f..72f,
                steps = 7,
            )
            FieldLabel("Сторона экрана")
            Segmented(listOf(BubbleSide.LEFT to "Слева", BubbleSide.RIGHT to "Справа"), s.bubbleSide) { repo.setBubbleSide(it) }
        }

        SectionCard("Приложение для записей") {
            val known = remember { NoteApps.installedKnown(context) }
            val all = remember { NoteApps.launchable(context) }
            var pick by remember { mutableStateOf(repo.notesApp()) }
            var picker by remember { mutableStateOf(false) }
            fun choose(pkg: String) {
                pick = pkg
                repo.setNotesApp(pkg)
            }
            FieldLabel(
                "Где ты пишешь конспекты",
                "Поверх него работает кнопка наставника, туда уходит чистовик, его открывает кнопка на главном экране. " +
                    "Подойдёт любое: Touchnotes, Samsung Notes, OneNote, Squid, Nebo, Xodo, Flexcil и другие.",
            )
            RadioRow(
                pick.isEmpty(),
                "Определять самому",
                known.firstOrNull()?.let { "Сейчас: ${it.label}" } ?: "Известных приложений для записей не нашлось",
            ) { choose("") }
            known.forEach { a -> RadioRow(pick == a.packageName, a.label) { choose(a.packageName) } }
            all.firstOrNull { it.packageName == pick && known.none { k -> k.packageName == pick } }?.let { a ->
                RadioRow(true, a.label, "выбрано вручную") {}
            }
            SoftButton("Другое приложение…", icon = R.drawable.ic_search) { picker = true }
            if (picker) AppPickerDialog(all, onDismiss = { picker = false }) { a ->
                choose(a.packageName)
                picker = false
            }
        }

        SectionCard("Куда отправлять чистовик") {
            val targets = remember { NoteApps.pdfTargets(context) }
            val notes = remember(s) { NoteApps.chosen(context) }
            RadioRow(
                s.sendTarget == SettingsRepo.TARGET_AUTO,
                "В приложение для записей",
                notes?.let { "Сейчас: ${it.label}" } ?: "Приложение для записей не выбрано — будет системный выбор",
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

/** Выбор любого установленного приложения — с поиском по названию. */
@Composable
private fun AppPickerDialog(apps: List<NoteApps.App>, onDismiss: () -> Unit, onPick: (NoteApps.App) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = apps.filter { filter.isBlank() || it.label.contains(filter.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { LinkText("Закрыть", onClick = onDismiss) },
        title = { Text("Где ты пишешь конспекты") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(filter, { filter = it }, "Поиск: notes, onenote, squid…")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.packageName }) { a ->
                        Text(
                            a.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(a) }
                                .padding(vertical = 12.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(28.dp),
    )
}

package app.nastavnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.study.Mistake
import app.nastavnik.study.StudyRepo.PracticeMistake
import app.nastavnik.study.StudyJson
import app.nastavnik.study.TrainResult
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun MistakePracticeSection(date: LocalDate, onSettings: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val mistakes by remember(app, date) { app.study.observeMistakes(date) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable(date.toString()) { mutableStateOf<String?>(null) }
    var adding by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }

    SectionCard("Работа над ошибками") {
        Text(
            if (mistakes.isEmpty()) "Ошибки с занятий появятся здесь после проверки наставником. Можно записать свою."
            else "Разбери ошибку и реши похожую задачу с новыми данными.",
            style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft,
        )
        for (item in mistakes.sortedBy { it.reviewed }) {
            key(item.id) {
                HorizontalDivider()
                MistakeRow(item, selected == item.id, { selected = if (selected == item.id) null else item.id }, onSettings)
            }
        }
        if (saveError) StatusLine("Не удалось сохранить ошибку. Попробуй ещё раз.", Tone.ERROR)
        SoftButton("Записать ошибку", icon = R.drawable.ic_pen) { adding = true }
    }

    if (adding) {
        var topic by rememberSaveable { mutableStateOf("") }
        var description by rememberSaveable { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) adding = false },
            title = { Text("Ошибка с занятия") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(topic, { topic = it.take(120) }, "Предмет или тема")
                    AppTextField(description, { description = it.take(1000) }, "Где ошибся и что было в задаче")
                }
            },
            confirmButton = {
                TextButton(enabled = description.isNotBlank() && !saving, onClick = {
                    saving = true
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val at = if (date == LocalDate.now()) now else date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        saveError = runCatching { app.study.saveMistakes(listOf(Mistake(description, topic, at))) }.isFailure
                        saving = false
                        if (!saveError) adding = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun MistakeRow(item: PracticeMistake, expanded: Boolean, onExpand: () -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var taskJson by rememberSaveable { mutableStateOf("") }
    val task = remember(taskJson) { StudyJson.tasks(taskJson).firstOrNull() }
    var hints by rememberSaveable { mutableStateOf(0) }
    var showAnswer by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<TrainResult.Failed?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.mistake.topic.ifBlank { "Ошибка с занятия" }, style = MaterialTheme.typography.titleSmall)
        MathText(item.mistake.text, 15)
        if (item.reviewed) {
            Text("Разобрано", color = Brand.Ok, style = MaterialTheme.typography.labelLarge)
        } else {
            LinkText(if (expanded) "Свернуть" else "Прорешать") { onExpand() }
        }
        if (expanded && !item.reviewed) {
            problem?.let {
                StatusLine(it.message, Tone.ERROR)
                if (it.needsSettings) LinkText("Открыть настройки", onClick = onSettings)
            }
            if (task == null) {
                InkButton(if (busy) "Составляю задачу…" else "Составить похожую задачу", enabled = !busy) {
                    busy = true
                    problem = null
                    scope.launch {
                        when (val result = app.trainer.mistakePractice(item.mistake)) {
                            is TrainResult.Ok -> taskJson = Json.encodeToString(listOf(result.value))
                            is TrainResult.Failed -> problem = result
                        }
                        busy = false
                    }
                }
            } else {
                MathText(task.task)
                task.hints.take(hints).forEach { MathText(it, 14) }
                if (hints < task.hints.size && !showAnswer) LinkText("Следующая подсказка") { hints++ }
                if (!showAnswer) {
                    SoftButton("Сверить решение") { showAnswer = true }
                } else {
                    MathText(task.answer)
                    fun finish(retry: Boolean) {
                        busy = true
                        scope.launch {
                            problem = runCatching { app.study.reviewMistake(item, task.takeIf { retry }) }
                                .exceptionOrNull()?.let { TrainResult.Failed("Не удалось сохранить результат. Попробуй ещё раз.") }
                            busy = false
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton("Разобрал", enabled = !busy) { finish(false) }
                        TextButton(onClick = { finish(true) }, enabled = !busy) { Text("В повторение") }
                    }
                }
            }
        }
    }
}

package app.nastavnik.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CleanNoteEntity
import app.nastavnik.notes.CheatSheet
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteApps
import app.nastavnik.notes.NoteExport
import app.nastavnik.notes.NoteStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Форматы, в которые можно выгрузить чистовик. */
enum class ExportFormat(val label: String, val hint: String, val ext: String, val mime: String) {
    MARKDOWN("Markdown", "Obsidian, Notion, любые заметки — формулы сохранятся", "md", "text/markdown"),
    LATEX("LaTeX", "Overleaf и курсовые: определения и теоремы — окружениями", "tex", "application/x-tex"),
    WORD("Word", "Документ .docx для Word и Google Документов", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
}

/** Записать чистовик в файл нужного формата в папке экспорта (её отдаёт FileProvider). */
suspend fun exportNote(context: Context, e: CleanNoteEntity, format: ExportFormat): File = withContext(Dispatchers.IO) {
    val note = CleanNoteParser.fromStoredJson(e.json)
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val name = NoteStorage.sanitize(NoteStorage.plainTitle(note.title).ifBlank { "Конспект" }).take(60)
    val file = File(dir, "$name.${format.ext}")
    when (format) {
        ExportFormat.MARKDOWN -> file.writeText(NoteExport.markdown(note))
        ExportFormat.LATEX -> file.writeText(NoteExport.latex(note))
        ExportFormat.WORD -> file.writeBytes(NoteExport.docx(note))
    }
    file
}

/** Выбор формата: файл сразу уходит в «Поделиться». */
@Composable
fun ExportDialog(note: CleanNoteEntity, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Экспорт конспекта") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExportFormat.entries.forEach { f ->
                    RadioRow(false, f.label, f.hint) {
                        onDismiss()
                        scope.launch {
                            val outcome = runCatching { exportNote(context, note, f) }
                                .fold({ NoteApps.shareFile(context, it, f.mime, note.title) }, { NoteApps.Outcome.Failed("Не получилось: ${it.message}") })
                            (outcome as? NoteApps.Outcome.Failed)?.let { onError(it.message) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { SoftButton("Отмена", onClick = onDismiss) },
    )
}

/**
 * Шпаргалка: все определения, теоремы и формулы из чистовиков на нескольких листах —
 * по одному предмету или по всем. Собирается без нейросети и открывается в предпросмотре чистовика.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheatSheetCard(notes: List<CleanNoteEntity>, onReady: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subjects = remember(notes) { notes.map { it.subject.trim() }.filter { it.isNotEmpty() }.distinct() }
    var subject by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(R.drawable.ic_layers)
            Column(Modifier.weight(1f)) {
                Text("Шпаргалка к экзамену", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Все определения, теоремы и формулы из чистовиков — без повторов, по темам.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (subjects.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Все предметы", selected = subject == null) { subject = null }
                subjects.forEach { s -> ChipButton(s.take(28), selected = subject == s) { subject = s } }
            }
        }
        problem?.let { StatusLine(it, Tone.INFO) }
        InkButton("Собрать шпаргалку", icon = R.drawable.ic_layers) {
            scope.launch {
                val sheet = withContext(Dispatchers.Default) {
                    val parsed = notes.mapNotNull { runCatching { CleanNoteParser.fromStoredJson(it.json) }.getOrNull() }
                    val date = SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(Date())
                    CheatSheet.build(parsed, date, subject)
                }
                if (sheet == null) {
                    problem = "В чистовиках пока нет определений, теорем и формул — шпаргалку не из чего собрать."
                } else {
                    context.app.clean.preview(sheet)
                    onReady()
                }
            }
        }
    }
}

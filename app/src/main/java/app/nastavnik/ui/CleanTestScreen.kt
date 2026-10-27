package app.nastavnik.ui

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import app.nastavnik.app
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.CleanView
import app.nastavnik.notes.NoteStorage
import app.nastavnik.notes.PdfExporter
import app.nastavnik.notes.TouchnotesSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Отладочный экран: рисует встроенный пример чистовика (формулы, таблица, график)
 * без обращения к API и позволяет проверить всю цепочку PDF → «Документы» → Touchnotes.
 */
@Composable
fun CleanTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val json = remember {
        val raw = context.assets.open("sample_note.json").bufferedReader().use { it.readText() }
        CleanNoteParser.toJson(CleanNoteParser.parse(raw))
    }
    var status by remember { mutableStateOf("Предпросмотр встроенного примера. API не используется.") }
    var busy by remember { mutableStateOf(false) }
    var lastPdf by remember { mutableStateOf<File?>(null) }
    var host by remember { mutableStateOf<FrameLayout?>(null) }

    ScreenScaffold(title = "Тест чистовика", onBack = onBack, scrollable = false) {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy && host != null, onClick = {
                val h = host ?: return@Button
                busy = true
                status = "Делаю PDF…"
                scope.launch {
                    status = try {
                        val storage = NoteStorage(context)
                        val result = PdfExporter(context).export(h, json, storage.newWorkDir())
                        lastPdf = result.pdf
                        val note = CleanNoteParser.fromStoredJson(json)
                        val published = withContext(Dispatchers.IO) {
                            storage.publish(result.pdf, result.pngs, "Тест", NoteStorage.plainTitle(note.title))
                        }
                        "Готово: страниц ${result.pageCount}, PDF ${result.pdf.length() / 1024} КБ.\n" +
                            (published.publicPath ?: published.error ?: "")
                    } catch (e: Exception) {
                        "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                    }
                    busy = false
                }
            }) { Text("Сделать PDF") }
            OutlinedButton(enabled = lastPdf != null, onClick = {
                lastPdf?.let { TouchnotesSender.viewPdf(context, it) }
            }) { Text("Открыть PDF") }
            OutlinedButton(enabled = lastPdf != null, onClick = {
                lastPdf?.let {
                    val r = TouchnotesSender.sendPdf(context, it, "Тест чистовика", context.app.settings.load().sendTarget)
                    if (r is TouchnotesSender.Outcome.Failed) status = r.message
                }
            }) { Text("В Touchnotes") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                FrameLayout(ctx).also { frame ->
                    val web = WebView(ctx)
                    frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    val view = CleanView(web, "preview")
                    view.onError = { status = "Ошибка вёрстки: $it" }
                    view.show(json)
                    host = frame
                }
            },
        )
    }
}

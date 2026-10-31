package app.nastavnik.ui

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import app.nastavnik.R
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.nastavnik.notes.NoteApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Пример чистовика: рисует встроенный конспект (формулы, таблица, график) без обращения к нейросети
 * и позволяет проверить всю цепочку PDF → «Документы» → приложение для записей.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val json = remember {
        val raw = context.assets.open("sample_note.json").bufferedReader().use { it.readText() }
        CleanNoteParser.toJson(CleanNoteParser.parse(raw))
    }
    var status by remember { mutableStateOf("Так выглядит чистовик. Это встроенный пример — нейросеть не используется.") }
    var tone by remember { mutableStateOf(Tone.INFO) }
    var busy by remember { mutableStateOf(false) }
    var lastPdf by remember { mutableStateOf<File?>(null) }
    var host by remember { mutableStateOf<FrameLayout?>(null) }

    ScreenScaffold(title = "Пример чистовика", onBack = onBack, scrollable = false) {
        StatusLine(status, tone)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton(if (busy) "Делаю PDF…" else "Сделать PDF", enabled = !busy && host != null) {
                val h = host ?: return@InkButton
                busy = true
                tone = Tone.INFO
                status = "Делаю PDF…"
                scope.launch {
                    try {
                        val storage = NoteStorage(context)
                        val result = PdfExporter(context).export(h, json, storage.newWorkDir())
                        lastPdf = result.pdf
                        val note = CleanNoteParser.fromStoredJson(json)
                        val published = withContext(Dispatchers.IO) {
                            storage.publish(result.pdf, result.pngs, "Пример", NoteStorage.plainTitle(note.title))
                        }
                        status = "Готово: страниц ${result.pageCount}, PDF ${result.pdf.length() / 1024} КБ.\n" +
                            (published.publicPath ?: published.error ?: "")
                        tone = Tone.OK
                    } catch (e: Exception) {
                        status = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                        tone = Tone.ERROR
                    }
                    busy = false
                }
            }
            if (lastPdf != null) {
                SoftButton("Открыть PDF", icon = R.drawable.ic_open) { lastPdf?.let { NoteApps.viewPdf(context, it) } }
                SoftButton("Отправить", icon = R.drawable.ic_share) {
                    lastPdf?.let {
                        val r = NoteApps.sendPdf(context, it, "Пример чистовика", context.app.settings.load().sendTarget)
                        if (r is NoteApps.Outcome.Failed) {
                            status = r.message
                            tone = Tone.ERROR
                        }
                    }
                }
            }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .glass(RoundedCornerShape(24.dp)),
            factory = { ctx ->
                FrameLayout(ctx).also { frame ->
                    val web = WebView(ctx)
                    frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    val view = CleanView(web, "preview")
                    view.onError = {
                        status = "Ошибка вёрстки: $it"
                        tone = Tone.ERROR
                    }
                    view.show(json)
                    host = frame
                }
            },
        )
    }
}

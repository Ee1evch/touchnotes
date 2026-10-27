package app.nastavnik.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CleanNoteEntity
import app.nastavnik.data.ConversationSummary
import app.nastavnik.data.Message
import app.nastavnik.notes.TouchnotesSender
import app.nastavnik.notes.WebViews
import app.nastavnik.overlay.BubbleService
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openConversation by rememberSaveable { mutableStateOf<Long?>(null) }
    val conv = openConversation
    if (conv != null) {
        ConversationDetail(conv, onBack = { openConversation = null })
        return
    }
    ScreenScaffold(title = "История", onBack = onBack) {
        Segmented(listOf(0 to "Задачи", 1 to "Чистовики"), tab) { tab = it }
        if (tab == 0) ConversationList(onOpen = { openConversation = it }) else CleanNoteList()
    }
}

@Composable
private fun ConversationList(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val items by remember { context.app.db.conversations().observeSummaries() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val active = remember { context.app.settings.load().activeConversationId }
    if (items.isEmpty()) {
        Empty("Пока пусто", "Задачи появятся после первого вопроса наставнику.")
    }
    items.forEach { c: ConversationSummary ->
        Row(
            Modifier
                .fillMaxWidth()
                .softCard(color = MaterialTheme.colorScheme.surface)
                .clickable { onOpen(c.id) }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(R.drawable.ic_bulb, if (c.id == active) Brand.Accent else Brand.InkSoft)
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    listOf(c.subject, dateFmt.format(Date(c.updatedAt)), "сообщений: ${c.messageCount}").filter { it.isNotBlank() }.joinToString(" · ") +
                        if (c.id == active) " · текущая" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Empty(title: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .softCard(color = MaterialTheme.colorScheme.surface)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(R.drawable.ic_history, Brand.Accent, 52)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ConversationDetail(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = context.app.db
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Message>?>(null) }
    var title by remember { mutableStateOf("Задача") }
    LaunchedEffect(id) {
        title = db.conversations().get(id)?.title ?: "Задача"
        messages = db.messages().forConversation(id).filter { it.text.isNotBlank() }
    }
    ScreenScaffold(title = title, onBack = onBack, scrollable = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Продолжить эту задачу", onClick = {
                context.app.settings.setActiveConversation(id)
                if (BubbleService.running) {
                    context.startService(Intent(context, BubbleService::class.java).setAction(BubbleService.ACTION_RELOAD))
                }
                onBack()
            })
            SoftButton("Удалить", onClick = {
                scope.launch {
                    db.conversations().delete(id)
                    if (context.app.settings.load().activeConversationId == id) context.app.settings.setActiveConversation(0)
                    onBack()
                }
            })
        }
        val list = messages
        if (list != null) {
            val json = remember(list) {
                JsonArray(list.map { m ->
                    buildJsonObject {
                        put("id", m.id)
                        put("role", m.role)
                        put("text", m.text)
                        put("hadImage", m.hadImage)
                        put("error", m.isError)
                    }
                }).toString()
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        WebViews.setupLocal(this)
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onReady() {
                                post { evaluateJavascript("Chat.setMessages($json)", null) }
                            }
                        }, "NativeChat")
                        loadUrl("file:///android_asset/chat.html")
                    }
                },
            )
        }
    }
}

@Composable
private fun CleanNoteList() {
    val context = LocalContext.current
    val db = context.app.db
    val scope = rememberCoroutineScope()
    val items by remember { db.cleanNotes().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    if (items.isEmpty()) {
        Empty("Чистовиков пока нет", "Их делает пункт «Чистовик» в меню кнопки наставника.")
    }
    items.forEach { n: CleanNoteEntity ->
        val pdf = n.pdfPath?.let(::File)?.takeIf { it.exists() }
        val pngs = n.pngPaths.orEmpty().split("\n").filter { it.isNotBlank() }.map(::File).filter { it.exists() }
        Column(
            Modifier
                .fillMaxWidth()
                .softCard(color = MaterialTheme.colorScheme.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(R.drawable.ic_note, Brand.Ok)
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        listOf(n.subject, dateFmt.format(Date(n.createdAt))).filter { it.isNotBlank() }.joinToString(" · ") + (n.publicPath?.let { "\n$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pdf != null) {
                    TextButton(onClick = { report(TouchnotesSender.viewPdf(context, pdf)) { message = it } }) { Text("Открыть") }
                    TextButton(onClick = {
                        report(TouchnotesSender.sendPdf(context, pdf, n.title, context.app.settings.load().sendTarget)) { message = it }
                    }) { Text("В Touchnotes") }
                }
                if (pngs.isNotEmpty()) {
                    TextButton(onClick = { report(TouchnotesSender.sendImages(context, pngs, n.title)) { message = it } }) { Text("Картинками") }
                }
                TextButton(onClick = {
                    scope.launch {
                        db.cleanNotes().delete(n.id)
                        pdf?.parentFile?.deleteRecursively()
                    }
                }) { Text("Удалить") }
            }
            if (pdf == null) Text("Файл удалён — осталась копия в «Документы/Конспекты».", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun report(outcome: TouchnotesSender.Outcome, show: (String?) -> Unit) {
    show((outcome as? TouchnotesSender.Outcome.Failed)?.message)
}

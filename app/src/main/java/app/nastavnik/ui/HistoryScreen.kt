package app.nastavnik.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.Calendar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

private val timeFmt = SimpleDateFormat("HH:mm", Locale("ru"))
private val dayFmt = SimpleDateFormat("d MMMM", Locale("ru"))
private val dayYearFmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

/** «Сегодня», «Вчера», «12 октября» — заголовок группы в истории. */
internal fun dayLabel(time: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(day, today)) return "Сегодня"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(day, today)) return "Вчера"
    return if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) dayFmt.format(Date(time)) else dayYearFmt.format(Date(time))
}

/** 1 сообщение, 2 сообщения, 5 сообщений. */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    val word = when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openConversation by rememberSaveable { mutableStateOf<Long?>(null) }
    val conv = openConversation
    if (conv != null) {
        BackHandler { openConversation = null }
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
    val items by remember { context.app.db.conversations().observeSummaries() }.collectAsStateWithLifecycle(initialValue = null)
    val active = remember { context.app.settings.load().activeConversationId }
    val list = items ?: return
    if (list.isEmpty()) {
        Empty(R.drawable.ic_bulb, "Здесь будут твои задачи", "Нажми кнопку наставника поверх Touchnotes и попроси подсказку — задача появится в истории, и к ней можно будет вернуться.")
        return
    }
    list.groupBy { dayLabel(it.updatedAt) }.forEach { (day, group) ->
        SectionLabel(day)
        Column(
            Modifier
                .fillMaxWidth()
                .softCard(color = MaterialTheme.colorScheme.surface),
        ) {
            group.forEachIndexed { i, c: ConversationSummary ->
                val current = c.id == active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(c.id) }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(R.drawable.ic_bulb, if (current) Brand.Accent else Brand.InkSoft)
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(timeFmt.format(Date(c.updatedAt)), plural(c.messageCount, "сообщение", "сообщения", "сообщений"))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (current) Tag("сейчас")
                    Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                }
                if (i < group.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Маленькая метка фирменного цвета. */
@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Empty(icon: Int, title: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .softCard(color = MaterialTheme.colorScheme.surface)
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, Brand.Accent, 56)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
    }
}

/** Подтверждение удаления: история не восстанавливается. */
@Composable
private fun ConfirmDelete(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { InkButton("Удалить", danger = true, onClick = onConfirm) },
        dismissButton = { SoftButton("Отмена", onClick = onDismiss) },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    )
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
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        ConfirmDelete("Удалить задачу?", "Разговор с наставником по этой задаче удалится без возможности вернуть.", onConfirm = {
            confirm = false
            scope.launch {
                db.conversations().delete(id)
                if (context.app.settings.load().activeConversationId == id) context.app.settings.setActiveConversation(0)
                onBack()
            }
        }, onDismiss = { confirm = false })
    }
    ScreenScaffold(title = title, onBack = onBack, scrollable = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Продолжить эту задачу", icon = R.drawable.ic_sparkle, onClick = {
                context.app.settings.setActiveConversation(id)
                if (BubbleService.running) {
                    context.startService(Intent(context, BubbleService::class.java).setAction(BubbleService.ACTION_RELOAD))
                }
                onBack()
            })
            SoftButton("Удалить", icon = R.drawable.ic_trash, onClick = { confirm = true })
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .softCard(color = Brand.Paper),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CleanNoteList() {
    val context = LocalContext.current
    val db = context.app.db
    val scope = rememberCoroutineScope()
    val items by remember { db.cleanNotes().observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    var message by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<CleanNoteEntity?>(null) }
    toDelete?.let { n ->
        ConfirmDelete(
            "Удалить чистовик?",
            "«${n.title}» пропадёт из истории приложения. Копия в «Документы/Конспекты» и в Touchnotes останется.",
            onConfirm = {
                toDelete = null
                scope.launch {
                    db.cleanNotes().delete(n.id)
                    n.pdfPath?.let(::File)?.parentFile?.deleteRecursively()
                }
            },
            onDismiss = { toDelete = null },
        )
    }
    message?.let { StatusLine(it, Tone.ERROR) }
    val list = items ?: return
    if (list.isEmpty()) {
        Empty(R.drawable.ic_note, "Чистовиков пока нет", "Нажми кнопку наставника поверх Touchnotes → «Чистовик»: записи превратятся в аккуратный PDF-конспект.")
        return
    }
    list.forEach { n: CleanNoteEntity ->
        val pdf = n.pdfPath?.let(::File)?.takeIf { it.exists() }
        val pngs = n.pngPaths.orEmpty().split("\n").filter { it.isNotBlank() }.map(::File).filter { it.exists() }
        Column(
            Modifier
                .fillMaxWidth()
                .softCard(color = MaterialTheme.colorScheme.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(R.drawable.ic_note, Brand.Ok)
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(n.subject, dayLabel(n.createdAt) + ", " + timeFmt.format(Date(n.createdAt))).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pdf != null) {
                    ChipButton("Открыть") { report(TouchnotesSender.viewPdf(context, pdf)) { message = it } }
                    ChipButton("В Touchnotes") {
                        report(TouchnotesSender.sendPdf(context, pdf, n.title, context.app.settings.load().sendTarget)) { message = it }
                    }
                }
                if (pngs.isNotEmpty()) {
                    ChipButton("Картинками") { report(TouchnotesSender.sendImages(context, pngs, n.title)) { message = it } }
                }
                ChipButton("Удалить") { toDelete = n }
            }
            if (pdf == null) {
                Text(
                    "Файл в приложении удалён — копия осталась в «Документы/Конспекты».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun report(outcome: TouchnotesSender.Outcome, show: (String?) -> Unit) {
    show((outcome as? TouchnotesSender.Outcome.Failed)?.message)
}

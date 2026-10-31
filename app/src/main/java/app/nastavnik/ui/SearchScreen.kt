package app.nastavnik.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CleanNoteEntity
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteApps
import app.nastavnik.notes.NoteStorage
import app.nastavnik.notes.Search
import app.nastavnik.study.NoteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Найденное: откуда, заголовок, кусочек текста вокруг совпадения и что по нему сделать. */
private sealed interface Hit {
    val title: String
    val snippet: String
    val score: Int

    data class Note(val e: CleanNoteEntity, override val title: String, override val snippet: String, override val score: Int) : Hit
    data class Task(val conversationId: Long, override val title: String, override val snippet: String, override val score: Int, val full: String) : Hit
    data class Notebook(val id: Long, override val title: String, override val snippet: String, override val score: Int) : Hit
    data class Card(override val title: String, override val snippet: String, override val score: Int) : Hit
}

/** Поиск по конспектам, разговорам с наставником, тетрадям и карточкам. */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenNotebook: (Long) -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val words = Search.words(query)
        if (words.isEmpty()) {
            hits = null
            return@LaunchedEffect
        }
        delay(250) // печатают — не ищем на каждую букву
        hits = withContext(Dispatchers.IO) { find(context, words) }
    }

    ScreenScaffold(title = "Поиск", onBack = onBack) {
        AppTextField(
            query, { query = it }, "Что найти",
            placeholder = "Например: производная частного",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        problem?.let { StatusLine(it, Tone.ERROR) }
        val list = hits
        when {
            list == null -> Text(
                "Ищу по чистовикам, ответам наставника, названиям тетрадей и карточкам повторения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.isEmpty() -> Text("Ничего не нашлось. Попробуй другое слово или часть слова.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                val groups = listOf(
                    "Чистовики" to list.filterIsInstance<Hit.Note>(),
                    "Задачи с наставником" to list.filterIsInstance<Hit.Task>(),
                    "Тетради" to list.filterIsInstance<Hit.Notebook>(),
                    "Карточки" to list.filterIsInstance<Hit.Card>(),
                )
                groups.filter { it.second.isNotEmpty() }.forEach { (label, items) ->
                    SectionLabel("$label · ${items.size}")
                    items.forEach { h ->
                        HitRow(h, expanded = opened == key(h)) {
                            when (h) {
                                is Hit.Note -> {
                                    val pdf = h.e.pdfPath?.let(::File)?.takeIf { it.exists() }
                                    if (pdf != null) {
                                        (NoteApps.viewPdf(context, pdf) as? NoteApps.Outcome.Failed)?.let { problem = it.message }
                                    } else {
                                        onHistory()
                                    }
                                }
                                is Hit.Notebook -> onOpenNotebook(h.id)
                                is Hit.Task, is Hit.Card -> opened = if (opened == key(h)) null else key(h)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun key(h: Hit) = h.javaClass.simpleName + h.title + h.snippet

@Composable
private fun HitRow(h: Hit, expanded: Boolean, onClick: () -> Unit) {
    val icon = when (h) {
        is Hit.Note -> R.drawable.ic_note
        is Hit.Task -> R.drawable.ic_history
        is Hit.Notebook -> R.drawable.ic_notebook
        is Hit.Card -> R.drawable.ic_cards
    }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, 38)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (expanded && h is Hit.Task) {
                MathText(h.full, 15)
            } else if (expanded && h is Hit.Card) {
                MathText(h.snippet, 15)
            } else if (h.snippet.isNotBlank()) {
                Text(h.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Служебная разметка ответа наставника (@@ …, [[ГОТОВО]]) в поиске не нужна. */
private fun readable(text: String): String = text.lines()
    .filterNot { it.trimStart().startsWith("@@") }
    .joinToString("\n")
    .replace(Regex("\\[\\[[^\\]]*]]"), "")
    .trim()

private suspend fun find(context: Context, words: List<String>): List<Hit> {
    val app = context.app
    val out = ArrayList<Hit>()

    app.db.cleanNotes().observeAll().first().forEach { e ->
        val note = runCatching { CleanNoteParser.fromStoredJson(e.json) }.getOrNull() ?: return@forEach
        val title = NoteStorage.plainTitle(note.title).ifBlank { e.title }
        val body = NoteText.plain(note) + "\n" + note.subject
        if (Search.matches("$title\n$body", words)) {
            out += Hit.Note(e, title, Search.snippet(body, words), Search.score(title, body, words) + 3)
        }
    }

    val conversations = app.db.conversations().all().associateBy { it.id }
    app.db.messages().recent(MESSAGE_LIMIT)
        .groupBy { it.conversationId }
        .forEach { (cid, msgs) ->
            val c = conversations[cid] ?: return@forEach
            val text = msgs.sortedBy { it.id }.joinToString("\n\n") { readable(it.text) }
            if (Search.matches(c.title + "\n" + text, words)) {
                val hit = msgs.firstOrNull { Search.matches(readable(it.text), words) }?.let { readable(it.text) } ?: text
                out += Hit.Task(cid, c.title, Search.snippet(hit, words), Search.score(c.title, text, words), hit)
            }
        }

    app.notebooks.store.list().forEach { m ->
        if (Search.matches(m.title + "\n" + m.tasks.joinToString("\n") { it.task }, words)) {
            val tasks = m.tasks.joinToString(" · ") { it.task }
            out += Hit.Notebook(m.id, m.title, if (tasks.isBlank()) "${m.pages.size} стр." else Search.snippet(tasks, words), Search.score(m.title, tasks, words))
        }
    }

    app.db.cards().all().forEach { c ->
        if (Search.matches(c.front + "\n" + c.back + "\n" + c.topic, words)) {
            out += Hit.Card(c.front, c.back, Search.score(c.front, c.back, words))
        }
    }
    return out.sortedByDescending { it.score }.take(MAX_HITS)
}

private const val MESSAGE_LIMIT = 4000
private const val MAX_HITS = 60

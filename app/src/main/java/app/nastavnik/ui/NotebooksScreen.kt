package app.nastavnik.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.ink.PageDrawer
import app.nastavnik.ink.Paper
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Все тетради: новая (с выбором бумаги), из фото, из PDF, и сетка тетрадей с превью первой страницы. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebooksScreen(resumeTick: Int, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val nbs = context.app.notebooks
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<NotebookMeta>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload, resumeTick) { list = withContext(Dispatchers.IO) { nbs.store.list() } }

    val importers = rememberImporters(
        onImages = { uris ->
            scope.launch {
                busy = "Готовлю страницы из фото…"
                val m = nbs.importImages(uris)
                busy = null
                if (m != null) onOpen(m.id) else problem = "Не получилось открыть фото."
            }
        },
        onPdf = { uri ->
            scope.launch {
                busy = "Открываю PDF…"
                val m = nbs.importPdf(uri)
                busy = null
                if (m != null) onOpen(m.id) else problem = "Не получилось открыть PDF. Может, он защищён паролем?"
            }
        },
    )

    ScreenScaffold(title = "Тетради", onBack = onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Новая тетрадь", style = MaterialTheme.typography.titleLarge)
            Text(
                "Пиши стилусом прямо здесь — наставник рядом и видит всю тетрадь. Или сфотографируй тетрадь с бумаги, " +
                    "или открой PDF: писать можно и поверх них.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Paper.entries.forEach { p ->
                    PaperChoice(p) {
                        scope.launch {
                            val (m, _) = withContext(Dispatchers.IO) { nbs.store.create(nbs.defaultTitle(), p) }
                            onOpen(m.id)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Сфотографировать", icon = R.drawable.ic_camera) { importers.camera() }
                SoftButton("Фото из галереи", icon = R.drawable.ic_image) { importers.gallery() }
                SoftButton("Открыть PDF", icon = R.drawable.ic_pdf) { importers.pdf() }
            }
            busy?.let { StatusLine(it, Tone.INFO) }
            problem?.let { StatusLine(it, Tone.ERROR) }
        }

        val items = list ?: return@ScreenScaffold
        if (items.isEmpty()) return@ScreenScaffold
        SectionLabel("Мои тетради")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.forEach { m ->
                NotebookCard(m, onOpen = { onOpen(m.id) }, onChanged = { reload++ })
            }
        }
    }
}

/** Выбор бумаги: маленький листок с настоящей разлиновкой. */
@Composable
private fun PaperChoice(p: Paper, onClick: () -> Unit) {
    Column(
        Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.414f)
                .glass(RoundedCornerShape(12.dp), 4.dp, strong = true),
        ) { PaperPreview(p) }
        Text(p.label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PaperPreview(p: Paper) {
    val line = Color(PageDrawer.RULE)
    val margin = Color(PageDrawer.MARGIN)
    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val cell = size.width / 10f
        when (p) {
            Paper.GRID -> {
                var y = cell
                while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += cell }
                var x = cell
                while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += cell }
                drawLine(margin, Offset(size.width - cell * 1.5f, 0f), Offset(size.width - cell * 1.5f, size.height), 1.5f)
            }
            Paper.LINED -> {
                var y = cell * 2
                while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += cell * 1.4f }
                drawLine(margin, Offset(cell * 1.6f, 0f), Offset(cell * 1.6f, size.height), 1.5f)
            }
            Paper.DOTS -> {
                var y = cell
                while (y < size.height) {
                    var x = cell
                    while (x < size.width) { drawCircle(Color(PageDrawer.DOT), 1.4f, Offset(x, y)); x += cell }
                    y += cell
                }
            }
            Paper.BLANK -> Unit
        }
    }
}

private val dateFmt = SimpleDateFormat("d MMMM, HH:mm", Locale("ru"))

@Composable
private fun NotebookCard(m: NotebookMeta, onOpen: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val nbs = context.app.notebooks
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, m.id, m.updatedAt) {
        value = withContext(Dispatchers.IO) {
            nbs.store.thumb(m.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    Column(
        Modifier
            .width(210.dp)
            .glass(RoundedCornerShape(22.dp), 8.dp)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.2f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(PageDrawer.PAPER))
                .border(0.8.dp, Brand.Ink.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.ic_notebook), contentDescription = null, tint = Brand.Ink.copy(alpha = 0.3f), modifier = Modifier.size(36.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${m.pages.size} ${ruPlural(m.pages.size, "страница", "страницы", "страниц")} · ${dateFmt.format(Date(m.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "Действия с тетрадью", tint = Brand.Ink, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White, shape = RoundedCornerShape(18.dp)) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; confirm = true })
                }
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Название тетради") },
            text = { AppTextField(text, { text = it }, "Название") },
            confirmButton = {
                InkButton("Сохранить") {
                    scope.launch { withContext(Dispatchers.IO) { nbs.store.rename(m, text) }; onChanged() }
                    renaming = false
                }
            },
            dismissButton = { SoftButton("Отмена") { renaming = false } },
            containerColor = Color.White,
            shape = RoundedCornerShape(28.dp),
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить тетрадь?") },
            text = { Text("«${m.title}» и все её страницы удалятся без возможности вернуть.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                InkButton("Удалить", danger = true) {
                    scope.launch { withContext(Dispatchers.IO) { nbs.store.delete(m.id) }; onChanged() }
                    confirm = false
                }
            },
            dismissButton = { SoftButton("Отмена") { confirm = false } },
            containerColor = Color.White,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

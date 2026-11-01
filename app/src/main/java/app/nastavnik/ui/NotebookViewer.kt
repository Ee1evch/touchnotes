package app.nastavnik.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ink.InkPage
import app.nastavnik.ink.NotebookMeta
import app.nastavnik.ink.PAGE_W
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Просмотр тетради на телефоне: страницы одна под другой, двумя пальцами — приблизить,
 * двойное касание — вернуть. Писать нельзя — для этого планшет со стилусом.
 */
@Composable
fun NotebookViewer(notebookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val nbs = context.app.notebooks
    val data by produceState<Pair<NotebookMeta, List<InkPage>>?>(null, notebookId) {
        value = withContext(Dispatchers.IO) { nbs.store.meta(notebookId)?.let { it to nbs.store.loadPages(it) } }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    val shownScale by animateFloatAsState(scale, spring(dampingRatio = 0.85f, stiffness = 600f), label = "zoom")

    Column(Modifier.fillMaxSize().paperBackdrop()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
            Column(Modifier.weight(1f)) {
                Text(data?.first?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("только просмотр · пишут на планшете", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            }
        }
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
            return@Column
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .transformable(state, lockRotationOnZoomPan = true)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1.2f) 1f else 2.2f; if (scale == 1f) offset = Offset.Zero }) }
                .graphicsLayer { scaleX = shownScale; scaleY = shownScale; translationX = offset.x; translationY = offset.y },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(d.second, key = { _, p -> p.id }) { i, page ->
                PageImage(notebookId, page, i + 1)
            }
        }
    }
}

@Composable
private fun PageImage(notebookId: Long, page: InkPage, number: Int) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, page.id) {
        value = runCatching { context.app.notebooks.render(notebookId, page, 1_800_000).asImageBitmap() }.getOrNull()
    }
    val ratio = PAGE_W / page.height.coerceAtLeast(1f)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, contentDescription = "Страница $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(color = Brand.Accent, strokeWidth = 2.dp)
        Text("$number", style = MaterialTheme.typography.labelSmall, color = Brand.InkSoft, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

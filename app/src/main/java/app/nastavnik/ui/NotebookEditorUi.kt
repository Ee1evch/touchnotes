package app.nastavnik.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nastavnik.R
import app.nastavnik.ink.EditTool
import app.nastavnik.ink.InkPage
import app.nastavnik.ink.PageDrawer
import app.nastavnik.ink.ShapeKind
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---------------- панель инструментов ----------------

private class ToolSpec(val tool: EditTool, val icon: Int, val label: String)

private val TOOLS = listOf(
    ToolSpec(EditTool.PEN, R.drawable.ic_pen, "Ручка"),
    ToolSpec(EditTool.HIGHLIGHTER, R.drawable.ic_highlighter, "Маркер"),
    ToolSpec(EditTool.ERASER, R.drawable.ic_eraser, "Ластик"),
    ToolSpec(EditTool.LASSO, R.drawable.ic_lasso, "Лассо"),
    ToolSpec(EditTool.SHAPE, R.drawable.ic_shapes, "Фигуры"),
    ToolSpec(EditTool.TEXT, R.drawable.ic_text, "Текст"),
)

private val TOOL_SIZE = 44.dp
private val TOOL_GAP = 4.dp

/**
 * Инструменты: ряд значков с «пилюлей», которая перетекает к выбранному; над ним — настройки
 * выбранного инструмента (цвет, толщина, вид фигуры…). Пока пишешь, панель становится полупрозрачной.
 */
@Composable
fun EditorToolbar(ctl: NotebookController, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (ctl.writing) 0.32f else 1f, tween(if (ctl.writing) 160 else 420), label = "toolbarAlpha")
    // Перо задержали — подсвечиваем ластик, пока его не отпустят.
    val shown = if (ctl.tempEraser) EditTool.ERASER else ctl.editTool
    val index = TOOLS.indexOfFirst { it.tool == shown }.coerceAtLeast(0)
    val indicator by animateDpAsState(
        (TOOL_SIZE + TOOL_GAP) * index,
        spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "toolIndicator",
    )
    Column(
        modifier.graphicsLayer { this.alpha = alpha },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolOptions(ctl)
        Row(
            Modifier
                .glass(CircleShape, 14.dp, strong = true)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                // Скользящая «пилюля» под выбранным инструментом.
                Box(
                    Modifier
                        .offset(x = indicator)
                        .size(TOOL_SIZE)
                        .clip(CircleShape)
                        .background(Brand.Ink),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TOOL_GAP)) {
                    TOOLS.forEach { t ->
                        val active = t.tool == shown
                        val tint by animateColorAsState(if (active) Brand.OnInk else Brand.Ink, tween(220), label = "tint")
                        val bounce by animateFloatAsState(if (active) 1f else 0.9f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "bounce")
                        Box(
                            Modifier
                                .size(TOOL_SIZE)
                                .clip(CircleShape)
                                .clickable { ctl.pickTool(t.tool) }
                                .semantics { contentDescription = t.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(t.icon), contentDescription = null, tint = tint,
                                modifier = Modifier.size(21.dp).graphicsLayer { scaleX = bounce; scaleY = bounce },
                            )
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(24.dp).background(Brand.Line))
            ToggleIcon(R.drawable.ic_hand, if (ctl.fingerDraws) "Пальцем пишу" else "Пальцем двигаю лист", ctl.fingerDraws) { ctl.toggleFinger() }
        }
    }
}

@Composable
private fun ToggleIcon(icon: Int, description: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Brand.Ink else Color.Transparent, tween(220), label = "toggleBg")
    val tint by animateColorAsState(if (active) Brand.OnInk else Brand.Ink, tween(220), label = "toggleTint")
    Box(
        Modifier
            .size(TOOL_SIZE)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Настройки выбранного инструмента — сменяются с лёгким «перетеканием». */
@Composable
private fun ToolOptions(ctl: NotebookController) {
    AnimatedContent(
        targetState = ctl.editTool,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 3 } + scaleIn(initialScale = 0.96f))
                .togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.96f))
        },
        label = "toolOptions",
    ) { tool ->
        Row(
            Modifier
                .glass(CircleShape, 10.dp, strong = true)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tool) {
                EditTool.PEN -> {
                    NotebookController.PENS.forEach { c -> Swatch(Color(c), ctl.penColor == c, colorName(c)) { ctl.pickPen(c) } }
                    OptionDivider()
                    WidthSlider(ctl.penWidth, 1.2f..8f, Color(ctl.penColor)) { ctl.changePenWidth(it) }
                }
                EditTool.HIGHLIGHTER -> {
                    NotebookController.MARKERS.forEach { c -> Swatch(Color(c), ctl.markerColor == c, "Маркер", marker = true) { ctl.pickMarker(c) } }
                    OptionDivider()
                    WidthSlider(ctl.markerWidth, 10f..36f, Color(ctl.markerColor).copy(alpha = 0.6f)) { ctl.changeMarkerWidth(it) }
                }
                EditTool.ERASER -> {
                    Pill("Линию целиком", !ctl.partialEraser) { ctl.choosePartialEraser(false) }
                    Pill("Часть линии", ctl.partialEraser) { ctl.choosePartialEraser(true) }
                    OptionDivider()
                    Pill("Очистить страницу", false) { ctl.clearPage() }
                }
                EditTool.LASSO -> {
                    Text(
                        "Обведи записи пером — потом их можно двигать",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    if (ctl.canPaste) Pill("Вставить", false, R.drawable.ic_paste) { ctl.paste() }
                }
                EditTool.SHAPE -> {
                    SHAPES.forEach { (k, icon) ->
                        val on = ctl.shapeKind == k
                        val bg by animateColorAsState(if (on) Brand.Ink else Color.Transparent, tween(200), label = "shapeBg")
                        val tint by animateColorAsState(if (on) Brand.OnInk else Brand.Ink, tween(200), label = "shapeTint")
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .clickable { ctl.pickShape(k) }
                                .semantics { contentDescription = k.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        }
                    }
                    OptionDivider()
                    NotebookController.PENS.take(4).forEach { c -> Swatch(Color(c), ctl.penColor == c, colorName(c), small = true) { ctl.pickPen(c) } }
                }
                EditTool.TEXT -> {
                    NotebookController.TEXT_SIZES.forEachIndexed { i, size ->
                        Pill(listOf("Мелкий", "Обычный", "Заголовок")[i], ctl.textSize == size) { ctl.pickTextSize(size) }
                    }
                    OptionDivider()
                    NotebookController.PENS.take(4).forEach { c -> Swatch(Color(c), ctl.penColor == c, colorName(c), small = true) { ctl.pickPen(c) } }
                }
            }
        }
    }
}

private val SHAPES = listOf(
    ShapeKind.LINE to R.drawable.ic_line,
    ShapeKind.ARROW to R.drawable.ic_arrow,
    ShapeKind.RECT to R.drawable.ic_rect,
    ShapeKind.ELLIPSE to R.drawable.ic_ellipse,
    ShapeKind.TRIANGLE to R.drawable.ic_triangle,
    ShapeKind.AXES to R.drawable.ic_axes,
)

fun colorName(c: Int) = when (c) {
    NotebookController.PENS[0] -> "Синий"
    NotebookController.PENS[1] -> "Чёрный"
    NotebookController.PENS[2] -> "Красный"
    NotebookController.PENS[3] -> "Зелёный"
    NotebookController.PENS[4] -> "Оранжевый"
    else -> "Фиолетовый"
}

@Composable
private fun OptionDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp).background(Brand.Line))
}

@Composable
private fun Pill(text: String, selected: Boolean, icon: Int? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Brand.Ink else Color.Transparent, tween(200), label = "pillBg")
    val fg by animateColorAsState(if (selected) Brand.OnInk else Brand.Ink, tween(200), label = "pillFg")
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Цвет: выбранный «вырастает» с пружинкой и получает кольцо. */
@Composable
fun Swatch(color: Color, selected: Boolean, description: String, marker: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val base = if (small) 18.dp else 22.dp
    val size by animateDpAsState(if (selected) base + 6.dp else base, spring(dampingRatio = 0.5f, stiffness = 600f), label = "swatch")
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "ring")
    val shape = if (marker) RoundedCornerShape(7.dp) else CircleShape
    Box(
        Modifier
            .size(if (small) 32.dp else 38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(shape).background(color).border(1.dp, Brand.Line, shape))
        if (ring > 0f) {
            Box(
                Modifier
                    .size(if (small) 30.dp else 35.dp)
                    .graphicsLayer { alpha = ring; scaleX = 0.8f + 0.2f * ring; scaleY = 0.8f + 0.2f * ring }
                    .border(2.dp, Brand.Ink, if (marker) RoundedCornerShape(10.dp) else CircleShape),
            )
        }
    }
}

/** Толщина: ползунок и живая точка — видно, какой будет линия. */
@Composable
private fun WidthSlider(value: Float, range: ClosedFloatingPointRange<Float>, color: Color, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(150.dp)) {
            AppSlider(v, { v = it }, { onChange(v) }, range)
        }
        val dot = (4 + (v - range.start) / (range.endInclusive - range.start) * 18).dp
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot).clip(CircleShape).background(color))
        }
    }
}

// ---------------- выделение ----------------

/** Действия с выделенным — пузырь над выделением, появляется с пружинкой. */
@Composable
fun SelectionMenu(ctl: NotebookController) {
    val rect = ctl.selection
    var colors by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = rect != null,
        enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.85f),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
    ) {
        val r = rect ?: return@AnimatedVisibility
        val density = LocalDensity.current
        val y = with(density) { (r.top - 64.dp.toPx()).coerceAtLeast(8.dp.toPx()) }
        val x = with(density) { (r.centerX() - 150.dp.toPx()).coerceAtLeast(8.dp.toPx()) }
        Column(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .glass(CircleShape, 14.dp, strong = true)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuAction(R.drawable.ic_copy, "Копировать") { ctl.copySelection() }
                MenuAction(R.drawable.ic_duplicate, "Дублировать") { ctl.duplicateSelection() }
                MenuAction(R.drawable.ic_palette_dot, "Цвет") { colors = !colors }
                MenuAction(R.drawable.ic_trash, "Удалить", danger = true) { ctl.deleteSelection() }
            }
            AnimatedVisibility(visible = colors, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut()) {
                Row(
                    Modifier
                        .glass(CircleShape, 10.dp, strong = true)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    NotebookController.PENS.forEach { c ->
                        Swatch(Color(c), false, colorName(c), small = true) { ctl.recolorSelection(c); colors = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuAction(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (danger) Brand.Error else Brand.Ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (danger) Brand.Error else MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------- подсказка ----------------

/** Короткая подсказка сверху листа: въезжает, держится пару секунд и тает. */
@Composable
fun EditorHint(ctl: NotebookController, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(ctl.hintSeq) {
        val h = ctl.hint ?: return@LaunchedEffect
        text = h
        visible = true
        delay(1900)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) { -it },
        exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { -it / 2 },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .glass(CircleShape, 10.dp, strong = true)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

// ---------------- текст ----------------

/** Ввод печатного текста: новый или правка (тогда можно и удалить). */
@Composable
fun TextDialog(ctl: NotebookController) {
    val req = ctl.textRequest ?: return
    val existing = req.third
    var text by remember(req) { mutableStateOf(existing?.text.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(req) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = { ctl.cancelText() },
        title = { Text(if (existing == null) "Текст на листе" else "Изменить текст") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Например: Дано, Найти, Ответ") },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Ink),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { InkButton("Готово", enabled = text.isNotBlank() || existing != null) { ctl.submitText(text) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) SoftButton("Удалить") { ctl.submitText("") }
                SoftButton("Отмена") { ctl.cancelText() }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    )
}

// ---------------- обзор страниц ----------------

/** Все страницы тетради сеткой: открыть, переставить, скопировать, удалить. */
@Composable
fun PagesOverview(ctl: NotebookController, onAdd: () -> Unit, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить страницу ${ctl.index + 1}?") },
            text = { Text("Всё, что на ней написано, пропадёт.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { InkButton("Удалить", danger = true) { ctl.deletePage(); confirmDelete = false } },
            dismissButton = { SoftButton("Отмена") { confirmDelete = false } },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 980.dp)
                .fillMaxWidth()
                .softCard(RoundedCornerShape(32.dp), 24.dp, MaterialTheme.colorScheme.surface)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Страницы", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                SoftButton("Новая", icon = R.drawable.ic_plus) { onAdd(); onDismiss() }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(R.drawable.ic_close, "Закрыть", onDismiss)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.heightIn(max = 560.dp),
            ) {
                itemsIndexed(ctl.pages, key = { _, p -> p.id }) { i, p ->
                    PageThumb(ctl, p, i, selected = i == ctl.index) { ctl.showPage(i); onDismiss() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Страница ${ctl.index + 1}:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                SoftButton("Раньше", icon = R.drawable.ic_chevron_left) { ctl.movePage(ctl.index, ctl.index - 1) }
                SoftButton("Позже", icon = R.drawable.ic_chevron) { ctl.movePage(ctl.index, ctl.index + 1) }
                SoftButton("Копия", icon = R.drawable.ic_duplicate) { ctl.duplicatePage() }
                if (ctl.pages.size > 1) SoftButton("Удалить", icon = R.drawable.ic_trash) { confirmDelete = true }
            }
        }
    }
}

@Composable
private fun PageThumb(ctl: NotebookController, p: InkPage, i: Int, selected: Boolean, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, p.id, p.version) {
        value = runCatching { ctl.thumbnail(p, 300).asImageBitmap() }.getOrNull()
    }
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "pageRing")
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.35f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(PageDrawer.PAPER))
                .border((1 + ring * 1.5f).dp, if (selected) Brand.Ink else Brand.Line, RoundedCornerShape(14.dp)),
        ) {
            val t = thumb
            if (t != null) Image(t, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }
        Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------- подсказки при первом открытии ----------------

/** Один раз: как пользоваться листом. Коротко, с картинками-значками. */
@Composable
fun EditorTips(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .softCard(RoundedCornerShape(32.dp), 24.dp, MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Как устроен лист", style = MaterialTheme.typography.headlineSmall)
            Tip(R.drawable.ic_pen, "Пиши пером", "Пальцем листаешь и увеличиваешь. Ладонь на экране не мешает.")
            Tip(R.drawable.ic_eraser, "Задержи перо на секунду", "Включится ластик — сотри, что нужно. Отпустишь — снова ручка.")
            Tip(R.drawable.ic_shapes, "Нарисуй и задержи", "Линия, треугольник, прямоугольник или овал станут ровными.")
            Tip(R.drawable.ic_undo, "Коснись двумя пальцами", "Отменить последнее. Двойное касание одним — увеличить.")
            Tip(R.drawable.ic_lasso, "Лассо", "Обведи записи — двигай, копируй, перекрашивай, удаляй.")
            InkButton("Понятно", Modifier.fillMaxWidth(), onClick = onDone)
        }
    }
}

@Composable
private fun Tip(icon: Int, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, 40)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

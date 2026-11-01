package app.nastavnik.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
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

// ---------------- плашка инструментов ----------------

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
private val RAIL_W = 56.dp

/**
 * Инструменты — узкая плашка у края листа: сами инструменты, под ними кружок с цветом (или настройкой)
 * выбранного инструмента и «писать пальцем». Цвета и толщина открываются по касанию кружка
 * (или выбранного инструмента ещё раз) и закрываются касанием мимо. Плашку можно перетащить за ручку
 * вверх-вниз и к другому краю; пока пишешь, она становится почти прозрачной.
 */
@Composable
fun EditorRail(ctl: NotebookController, maxHeight: Dp) {
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ctl.writing && !open) 0.28f else 1f, tween(if (ctl.writing) 160 else 420), label = "railAlpha")
    val shown = if (ctl.tempEraser) EditTool.ERASER else ctl.editTool
    val index = TOOLS.indexOfFirst { it.tool == shown }.coerceAtLeast(0)
    val indicator by animateDpAsState((TOOL_SIZE + TOOL_GAP) * index, spring(dampingRatio = 0.72f, stiffness = 520f), label = "toolIndicator")
    val railHeight = (TOOL_SIZE + TOOL_GAP) * TOOLS.size + 150.dp
    val freeY = (maxHeight - railHeight - 24.dp).coerceAtLeast(0.dp)
    // Перетаскивание: пока тянут — за пальцем, отпустили — прилипает к ближайшему краю.
    var dragY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val y by animateDpAsState(
        if (dragging) with(density) { dragY.toDp() } else freeY * ctl.railY,
        if (dragging) snap() else spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "railY",
    )
    val right = ctl.railRight

    Box(Modifier.fillMaxSize()) {
        // Касание мимо закрывает окно настроек.
        if (open) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = false })
        Row(
            Modifier
                .align(if (right) Alignment.TopEnd else Alignment.TopStart)
                .padding(horizontal = 12.dp)
                .offset(y = 12.dp + y),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (right) ToolPopover(ctl, open, fromRight = true) { open = false }
            Column(
                Modifier
                    .graphicsLayer { this.alpha = alpha }
                    .width(RAIL_W)
                    .glass(RoundedCornerShape(28.dp), 14.dp, strong = true)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Ручка: потянуть — переставить плашку.
                Box(
                    Modifier
                        .size(width = 40.dp, height = 18.dp)
                        .pointerInput(freeY) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragY = with(density) { (freeY * ctl.railY).toPx() }
                                    open = false
                                },
                                onDragEnd = {
                                    dragging = false
                                    val free = with(density) { freeY.toPx() }.coerceAtLeast(1f)
                                    ctl.moveRail(ctl.railRight, (dragY / free).coerceIn(0f, 1f))
                                },
                                onDragCancel = { dragging = false },
                            ) { change, d ->
                                change.consume()
                                dragY = (dragY + d.y).coerceIn(0f, with(density) { freeY.toPx() })
                                // Утащили к другому краю — плашка переезжает туда.
                                if (abs(d.x) > 0f) {
                                    val px = change.position.x
                                    if (!ctl.railRight && px > with(density) { 260.dp.toPx() }) ctl.moveRail(true, ctl.railY)
                                    if (ctl.railRight && px < -with(density) { 220.dp.toPx() }) ctl.moveRail(false, ctl.railY)
                                }
                            }
                        }
                        .semantics { contentDescription = "Переставить панель" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 22.dp, height = 4.dp).clip(CircleShape).background(Brand.Ink.copy(alpha = 0.25f)))
                }
                Box {
                    Box(
                        Modifier
                            .offset(y = indicator)
                            .size(TOOL_SIZE)
                            .clip(CircleShape)
                            .background(Brand.Accent),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(TOOL_GAP)) {
                        TOOLS.forEach { t ->
                            val active = t.tool == shown
                            val tint by animateColorAsState(if (active) Brand.OnAccent else Brand.Ink, tween(220), label = "tint")
                            val bounce by animateFloatAsState(if (active) 1f else 0.88f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "bounce")
                            Box(
                                Modifier
                                    .size(TOOL_SIZE)
                                    .clip(CircleShape)
                                    .clickable {
                                        // Второе касание выбранного инструмента — его настройки.
                                        if (ctl.editTool == t.tool) open = !open else { ctl.pickTool(t.tool); open = false }
                                    }
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
                Box(Modifier.padding(vertical = 8.dp).width(26.dp).height(1.dp).background(Brand.Line))
                OptionsButton(ctl, open) { open = !open }
                Spacer(Modifier.height(4.dp))
                ToggleIcon(R.drawable.ic_hand, if (ctl.fingerDraws) "Пальцем пишу" else "Пальцем двигаю лист", ctl.fingerDraws) { ctl.toggleFinger() }
            }
            if (!right) ToolPopover(ctl, open, fromRight = false) { open = false }
        }
    }
}

/** Кружок настроек: цвет и толщина ручки или маркера, вид фигуры, размер текста, режим ластика. */
@Composable
private fun OptionsButton(ctl: NotebookController, open: Boolean, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (open) 1f else 0f, tween(200), label = "optRing")
    Box(
        Modifier
            .size(TOOL_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Настройки инструмента" },
        contentAlignment = Alignment.Center,
    ) {
        if (ring > 0f) Box(Modifier.size(TOOL_SIZE).graphicsLayer { alpha = ring }.border(2.dp, Brand.Accent, CircleShape))
        when (ctl.editTool) {
            EditTool.PEN, EditTool.TEXT -> {
                val d = if (ctl.editTool == EditTool.TEXT) 22.dp else (10 + (ctl.penWidth - 1.2f) / 6.8f * 16).dp
                AnimatedSwatch(Color(ctl.penColor), d, CircleShape)
            }
            EditTool.HIGHLIGHTER -> AnimatedSwatch(Color(ctl.markerColor), 22.dp, RoundedCornerShape(6.dp))
            EditTool.SHAPE -> Icon(painterResource(SHAPES.first { it.first == ctl.shapeKind }.second), contentDescription = null, tint = Color(ctl.penColor), modifier = Modifier.size(22.dp))
            EditTool.ERASER -> Text(if (ctl.partialEraser) "½" else "1", style = MaterialTheme.typography.titleMedium)
            EditTool.LASSO -> Icon(painterResource(R.drawable.ic_paste), contentDescription = null, tint = if (ctl.canPaste) Brand.Ink else Brand.Ink.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AnimatedSwatch(color: Color, size: Dp, shape: androidx.compose.ui.graphics.Shape) {
    val c by animateColorAsState(color, tween(220), label = "swatchColor")
    val d by animateDpAsState(size, spring(dampingRatio = 0.55f, stiffness = 600f), label = "swatchSize")
    Box(Modifier.size(d).clip(shape).background(c).border(1.dp, Brand.Line, shape))
}

/** Окно настроек выбранного инструмента — раскрывается от плашки с пружинкой. */
@Composable
private fun ToolPopover(ctl: NotebookController, open: Boolean, fromRight: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 520f), initialScale = 0.85f, transformOrigin = TransformOrigin(if (fromRight) 1f else 0f, 0.6f)),
        exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.9f, transformOrigin = TransformOrigin(if (fromRight) 1f else 0f, 0.6f)),
    ) {
        Column(
            Modifier
                .padding(top = 120.dp)
                .width(244.dp)
                .softCard(RoundedCornerShape(24.dp), 18.dp, MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (ctl.editTool) {
                EditTool.PEN -> {
                    PopTitle("Ручка")
                    ColorGrid(NotebookController.PENS, ctl.penColor) { ctl.pickPen(it); onClose() }
                    PopTitle("Толщина")
                    WidthSlider(ctl.penWidth, 1.2f..8f, Color(ctl.penColor)) { ctl.changePenWidth(it) }
                }
                EditTool.HIGHLIGHTER -> {
                    PopTitle("Маркер")
                    ColorGrid(NotebookController.MARKERS, ctl.markerColor, marker = true) { ctl.pickMarker(it); onClose() }
                    PopTitle("Толщина")
                    WidthSlider(ctl.markerWidth, 10f..36f, Color(ctl.markerColor).copy(alpha = 0.6f)) { ctl.changeMarkerWidth(it) }
                }
                EditTool.ERASER -> {
                    PopTitle("Ластик стирает")
                    RadioRow(!ctl.partialEraser, "Линию целиком") { ctl.choosePartialEraser(false); onClose() }
                    RadioRow(ctl.partialEraser, "Часть линии") { ctl.choosePartialEraser(true); onClose() }
                    SoftButton("Очистить страницу", Modifier.fillMaxWidth(), icon = R.drawable.ic_trash) { ctl.clearPage(); onClose() }
                }
                EditTool.LASSO -> {
                    PopTitle("Лассо")
                    Text("Обведи записи пером — их можно двигать, копировать, перекрашивать.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ctl.canPaste) InkButton("Вставить", Modifier.fillMaxWidth(), icon = R.drawable.ic_paste) { ctl.paste(); onClose() }
                }
                EditTool.SHAPE -> {
                    PopTitle("Фигура")
                    SHAPES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (k, icon) ->
                                val on = ctl.shapeKind == k
                                val bg by animateColorAsState(if (on) Brand.Accent else Brand.Line, tween(200), label = "shapeBg")
                                val tint by animateColorAsState(if (on) Brand.OnAccent else Brand.Ink, tween(200), label = "shapeTint")
                                Box(
                                    Modifier
                                        .size(width = 66.dp, height = 44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(bg)
                                        .clickable { ctl.pickShape(k); onClose() }
                                        .semantics { contentDescription = k.label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    PopTitle("Цвет")
                    ColorGrid(NotebookController.PENS, ctl.penColor) { ctl.pickPen(it) }
                }
                EditTool.TEXT -> {
                    PopTitle("Размер")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NotebookController.TEXT_SIZES.forEachIndexed { i, size ->
                            Pill(listOf("Мелкий", "Обычный", "Крупный")[i], ctl.textSize == size) { ctl.pickTextSize(size) }
                        }
                    }
                    PopTitle("Цвет")
                    ColorGrid(NotebookController.PENS, ctl.penColor) { ctl.pickPen(it); onClose() }
                }
            }
        }
    }
}

@Composable
private fun PopTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColorGrid(colors: List<Int>, selected: Int, marker: Boolean = false, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> Swatch(Color(c), c == selected, if (marker) "Маркер" else colorName(c), marker = marker) { onPick(c) } }
            }
        }
    }
}

@Composable
private fun ToggleIcon(icon: Int, description: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Brand.Accent else Color.Transparent, tween(220), label = "toggleBg")
    val tint by animateColorAsState(if (active) Brand.OnAccent else Brand.Ink, tween(220), label = "toggleTint")
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
    val bg by animateColorAsState(if (selected) Brand.Accent else Color.Transparent, tween(200), label = "pillBg")
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
        // На тёмной панели тёмные цвета обведены светлее — чтобы чёрная ручка не терялась.
        Box(Modifier.size(size).clip(shape).background(color).border(1.dp, if (Brand.dark) Brand.Ink.copy(alpha = 0.35f) else Brand.Line, shape))
        if (ring > 0f) {
            Box(
                Modifier
                    .size(if (small) 30.dp else 35.dp)
                    .graphicsLayer { alpha = ring; scaleX = 0.8f + 0.2f * ring; scaleY = 0.8f + 0.2f * ring }
                    .border(2.dp, Brand.Accent, if (marker) RoundedCornerShape(10.dp) else CircleShape),
            )
        }
    }
}

/** Толщина: ползунок и живая точка — видно, какой будет линия. */
@Composable
private fun WidthSlider(value: Float, range: ClosedFloatingPointRange<Float>, color: Color, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(170.dp)) {
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
                .border((1 + ring * 1.5f).dp, if (selected) Brand.Accent else Brand.Line, RoundedCornerShape(14.dp)),
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

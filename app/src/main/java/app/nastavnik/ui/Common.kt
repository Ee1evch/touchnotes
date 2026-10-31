package app.nastavnik.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nastavnik.R

/** Мягкая «бумажная» тень под карточкой: почти незаметная, но отделяет карточку от фона. */
fun Modifier.softCard(shape: Shape = RoundedCornerShape(24.dp), elevation: Dp = 14.dp, color: Color? = null): Modifier =
    this
        .shadow(elevation, shape, ambientColor = Color(0x1A16161A), spotColor = Color(0x2216161A))
        .clip(shape)
        .then(if (color != null) Modifier.background(color) else Modifier)

/**
 * Стеклянная пластина: полупрозрачная белая, светлее сверху (блик), со светлой кромкой и тонкой линией по краю.
 * Сквозь неё видны мягкие пятна фона. [strong] — плотнее, для мелких элементов поверх стекла.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), elevation: Dp = 10.dp, strong: Boolean = false): Modifier =
    this
        .shadow(elevation, shape, ambientColor = Color(0x1216161A), spotColor = Color(0x1A16161A))
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(Brand.Glass.copy(alpha = if (strong) 0.92f else 0.76f), Brand.Glass.copy(alpha = if (strong) 0.8f else 0.56f)),
            ),
        )
        .border(0.6.dp, Brand.Line, shape)
        .border(1.2.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (Brand.dark) 0.16f else 0.95f), Color.White.copy(alpha = if (Brand.dark) 0.03f else 0.25f))), shape)

/** Бумага с мягкими цветными пятнами — фон всех экранов, под стеклом. */
fun Modifier.paperBackdrop(): Modifier = drawBehind {
    drawRect(Brand.Paper)
    val w = size.width
    val h = size.height
    fun blob(c: Color, x: Float, y: Float, r: Float) =
        drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset(x, y), radius = r), r, Offset(x, y))
    blob(Brand.BlobCool, w * 0.88f, h * 0.06f, maxOf(w, h) * 0.5f)
    blob(Brand.BlobWarm, w * 0.04f, h * 0.42f, maxOf(w, h) * 0.42f)
    blob(Brand.BlobMint, w * 0.92f, h * 0.9f, maxOf(w, h) * 0.45f)
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(modifier = Modifier.paperBackdrop(), containerColor = Color.Transparent) { inner ->
        val base = Modifier
            .fillMaxSize()
            .padding(inner)
        Column(
            modifier = (if (scrollable) base.verticalScroll(rememberScrollState()) else base)
                .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (onBack != null || title.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, "Назад", onBack)
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                    }
                }
                content()
            }
        }
    }
}

/** Круглая белая кнопка-значок (назад, настройки). */
@Composable
fun RoundIconButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .glass(CircleShape, 6.dp, strong = true)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** Раздел: маленькая подпись над белой карточкой. */
@Composable
fun SectionCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) SectionLabel(title)
        Column(
            Modifier
                .fillMaxWidth()
                .glass()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
}

/** Подзаголовок внутри карточки с пояснением. */
@Composable
fun FieldLabel(text: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Главная кнопка-пилюля: графитовая; [danger] — красная, для удаления. */
@Composable
fun InkButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0xFFB9B7BF)
        danger -> Brand.Error
        else -> Brand.Ink
    }
    val fg = Brand.OnInk
    Row(
        modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** Второстепенная кнопка-пилюля из стекла. */
@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    val fg = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .heightIn(min = 50.dp)
            .glass(CircleShape, 4.dp, strong = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** Пилюля состояния с точкой: зелёная — всё хорошо, красная — нужно внимание. */
@Composable
fun StatusPill(text: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Brand.Glass.copy(alpha = 0.6f))
            .border(0.6.dp, Brand.Line, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) Brand.Ok else Brand.Error),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Значок в скруглённом стеклянном квадрате. Все значки одного цвета — графитовые: цвет в приложении только у смысла (ошибка, верно, теория). */
@Composable
fun IconBadge(icon: Int, size: Int = 42) {
    val shape = RoundedCornerShape((size * 0.33f).dp)
    Box(
        Modifier
            .size(size.dp)
            .clip(shape)
            .background(Brand.Ink.copy(alpha = 0.06f))
            .border(0.8.dp, Brand.Glass.copy(alpha = 0.9f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size((size * 0.48f).dp))
    }
}

/** Переключатель из нескольких коротких вариантов: выбранный — белая «таблетка» на сером. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.Line)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .then(
                        if (on) Modifier.glass(RoundedCornerShape(12.dp), 3.dp, strong = true)
                        else Modifier.clip(RoundedCornerShape(12.dp)),
                    )
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Выбор одного варианта из списка: своё круглое «радио» в фирменном цвете. */
@Composable
fun RadioRow(selected: Boolean, label: String, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(if (selected) 7.dp else 2.dp, ring, CircleShape),
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Поле ввода в общем стиле: скруглённое, с фирменной рамкой в фокусе. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Небольшая кнопка-«таблетка»: варианты адреса, второстепенные действия в строке. */
@Composable
fun ChipButton(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (selected) Brand.Ink else Brand.Glass.copy(alpha = 0.62f)
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> Brand.OnInk
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(0.6.dp, Brand.Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Текстовая ссылка фирменного цвета. */
@Composable
fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/** Короткий итог действия: зелёный — получилось, красный — нет, серый — просто сообщение. */
enum class Tone { OK, ERROR, INFO }

@Composable
fun StatusLine(text: String, tone: Tone) {
    val (bg, fg) = when (tone) {
        Tone.OK -> Brand.OkSoft to Brand.Ok
        Tone.ERROR -> Brand.ErrorSoft to Color(0xFFB4232A)
        Tone.INFO -> Brand.Ink.copy(alpha = 0.05f) to MaterialTheme.colorScheme.onSurface
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Номер шага в скруглённом квадрате фирменного цвета. */
@Composable
fun NumberBadge(n: Int) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(n.toString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    }
}

/** Ползунок в общем стиле: графитовая дорожка и ручка, пустая часть дорожки видна и на стекле. */
@Composable
fun AppSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, valueRange: ClosedFloatingPointRange<Float>, steps: Int = 0) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Brand.Ink,
            activeTrackColor = Brand.Ink,
            inactiveTrackColor = Brand.Ink.copy(alpha = 0.12f),
            activeTickColor = Color.White.copy(alpha = 0.7f),
            inactiveTickColor = Brand.Ink.copy(alpha = 0.3f),
        ),
    )
}

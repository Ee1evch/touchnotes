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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
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
            .softCard(CircleShape, 6.dp, MaterialTheme.colorScheme.surface)
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
                .softCard(color = MaterialTheme.colorScheme.surface)
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

/** Главная кнопка-пилюля. [light] — белая, для тёмной карточки. */
@Composable
fun InkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, light: Boolean = false, icon: Int? = null, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Color(0xFF9A9AA2)
        light -> Color.White
        else -> Brand.Ink
    }
    val fg = if (light && enabled) Brand.Ink else Color.White
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

/** Второстепенная кнопка-пилюля. [onDark] — полупрозрачная, для тёмной карточки. */
@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, onDark: Boolean = false, icon: Int? = null, onClick: () -> Unit) {
    val bg = if (onDark) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(bg)
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
fun StatusPill(text: String, ok: Boolean, onDark: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (onDark) Color.White.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF4ADE80) else Color(0xFFF87171)),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (onDark) Color(0xFFD9D8E2) else MaterialTheme.colorScheme.onSurface)
    }
}

/** Значок в скруглённом квадрате мягкого цвета. */
@Composable
fun IconBadge(icon: Int, tint: Color, size: Int = 42) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.33f).dp))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** Строка-пункт: значок, заголовок, пояснение и что-нибудь справа. */
@Composable
fun SettingRow(
    icon: Int,
    tint: Color,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Переключатель из нескольких коротких вариантов: выбранный — белая «таблетка» на сером. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                        if (on) Modifier.softCard(RoundedCornerShape(12.dp), 3.dp, MaterialTheme.colorScheme.surface)
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

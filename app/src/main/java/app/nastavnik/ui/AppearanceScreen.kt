package app.nastavnik.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.app

/** Вход в «Оформление» из настроек: мини-палитра текущей темы и стрелка. */
@Composable
fun AppearanceEntry(onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            listOf(Brand.BlobCool, Brand.BlobWarm, Brand.Accent).forEach {
                Box(Modifier.size(30.dp).clip(CircleShape).background(it).border(2.dp, Brand.Glass, CircleShape))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            Text("${Brand.current.name} · темы, акцент, фон, размер текста", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Brand.InkSoft, modifier = Modifier.size(18.dp))
    }
}

/**
 * Оформление: светлая и тёмная тема из галереи, режим, свой акцент, фон и размер текста.
 * Всё применяется сразу и плавно; лист тетради всегда белый.
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val repo = LocalContext.current.app.settings
    // Локальные копии — чтобы выбор отзывался мгновенно, а настройки сохранялись следом.
    var s by remember { mutableStateOf(repo.load()) }
    fun refresh() { s = repo.load() }
    val mode = runCatching { ThemeMode.valueOf(s.themeMode) }.getOrDefault(ThemeMode.LIGHT)

    ScreenScaffold(title = "Оформление", onBack = onBack) {
        SheetPreview()

        SectionCard("Режим") {
            Segmented(ThemeMode.entries.map { it to it.label }, mode) { repo.setThemeMode(it.name); refresh() }
            Text(
                when (mode) {
                    ThemeMode.SYSTEM -> "Днём — светлая тема, ночью (когда в системе тёмная) — тёмная. Обе выбираются ниже."
                    ThemeMode.LIGHT -> "Всегда светлая тема."
                    ThemeMode.DARK -> "Всегда тёмная тема — бережнее к глазам вечером. Лист тетради при этом белый."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("Светлые темы")
        ThemeRow(Palettes.light, selected = s.lightTheme) { p ->
            repo.setLightTheme(p.id)
            if (mode == ThemeMode.DARK) repo.setThemeMode(ThemeMode.LIGHT.name)
            refresh()
        }
        SectionLabel("Тёмные темы")
        ThemeRow(Palettes.dark, selected = s.darkTheme) { p ->
            repo.setDarkTheme(p.id)
            if (mode == ThemeMode.LIGHT) repo.setThemeMode(ThemeMode.DARK.name)
            refresh()
        }

        SectionCard("Акцент") {
            FieldLabel("Цвет кнопок и выбранного", "Свой цвет поверх любой темы — или как задумано в теме.")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipButton("Как в теме", selected = s.accent == 0) { repo.setAccent(0); refresh() }
                Palettes.accents.forEach { c ->
                    AccentDot(Color(c), s.accent == c) { repo.setAccent(c); refresh() }
                }
            }
        }

        SectionCard("Фон") {
            Segmented(Backdrop.entries.map { it to it.label }, runCatching { Backdrop.valueOf(s.backdrop) }.getOrDefault(Backdrop.BLOBS)) {
                repo.setBackdrop(it.name); refresh()
            }
            Text(
                "«Живые» — пятна под стеклом медленно плывут. «Ровный» — спокойный цвет темы без пятен.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Текст") {
            Segmented(listOf(0.92f to "Мельче", 1f to "Обычный", 1.12f to "Крупнее"), nearest(s.textScale)) {
                repo.setTextScale(it); refresh()
            }
        }
    }
}

private fun nearest(k: Float) = listOf(0.92f, 1f, 1.12f).minBy { kotlin.math.abs(it - k) }

/** Превью: белый лист с записями на «столе» текущей темы и панель инструментов в акценте. */
@Composable
private fun SheetPreview() {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), 12.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val desk = Brand.Desk
        val accent = Brand.Accent
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            drawRect(desk)
            val sheet = Size(size.width * 0.62f, size.height * 0.86f)
            val o = Offset((size.width - sheet.width) / 2, size.height * 0.07f)
            drawRoundRect(Color.Black.copy(alpha = 0.10f), o + Offset(0f, 4f), sheet, CornerRadius(10f))
            drawRoundRect(Color.White, o, sheet, CornerRadius(10f))
            // Клетка.
            val cell = sheet.width / 18
            var y = o.y + cell
            while (y < o.y + sheet.height) { drawLine(Color(0xFFD9E2EC), Offset(o.x, y), Offset(o.x + sheet.width, y), 1f); y += cell }
            var x = o.x + cell
            while (x < o.x + sheet.width) { drawLine(Color(0xFFD9E2EC), Offset(x, o.y), Offset(x, o.y + sheet.height), 1f); x += cell }
            // Рукописная строчка и подсветка маркером.
            drawRoundRect(Color(0x66FFE15A), o + Offset(cell * 1.5f, cell * 2.3f), Size(sheet.width * 0.55f, cell * 1.2f), CornerRadius(6f))
            squiggle(o + Offset(cell * 1.8f, cell * 3f), sheet.width * 0.5f, Color(0xFF1F4FD1))
            squiggle(o + Offset(cell * 1.8f, cell * 5f), sheet.width * 0.36f, Color(0xFF16161A))
            squiggle(o + Offset(cell * 1.8f, cell * 7f), sheet.width * 0.6f, Color(0xFF1F4FD1))
            // Панель инструментов в акценте.
            val bar = Size(sheet.width * 0.5f, 26f)
            val bo = Offset((size.width - bar.width) / 2, o.y + sheet.height - 40f)
            drawRoundRect(Color.White.copy(alpha = 0.92f), bo, bar, CornerRadius(13f))
            drawCircle(accent, 10f, bo + Offset(18f, 13f))
            for (i in 1..4) drawCircle(Color(0xFF9A99A3), 4f, bo + Offset(18f + i * 30f, 13f))
        }
        Text(
            "Лист тетради всегда белый, как бумага, — в любой теме. Меняется всё вокруг.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.squiggle(start: Offset, width: Float, color: Color) {
    val p = Path().apply {
        moveTo(start.x, start.y)
        var x = start.x
        var up = true
        while (x < start.x + width) {
            val nx = x + 14f
            quadraticBezierTo(x + 7f, start.y + if (up) -9f else 9f, nx, start.y)
            x = nx
            up = !up
        }
    }
    drawPath(p, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
}

@Composable
private fun ThemeRow(list: List<Palette>, selected: String, onPick: (Palette) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        list.forEach { p -> ThemeCard(p, p.id == selected) { onPick(p) } }
    }
}

/** Карточка темы: мини-экран — фон с пятнами, стеклянная карточка, белый лист и акцентная кнопка. */
@Composable
private fun ThemeCard(p: Palette, selected: Boolean, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "themeRing")
    val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "themeLift")
    Column(
        Modifier
            .width(148.dp)
            .graphicsLayer { translationY = -lift * 6f; scaleX = 1f + lift * 0.03f; scaleY = 1f + lift * 0.03f }
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Тема ${p.name}" }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(18.dp))
                .border((1 + ring * 2).dp, if (selected) p.accent else Brand.Line, RoundedCornerShape(18.dp)),
        ) {
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                drawRect(p.paper)
                fun blob(c: Color, x: Float, y: Float, r: Float) =
                    drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), Offset(x, y), r), r, Offset(x, y))
                blob(p.blobA, size.width * 0.9f, size.height * 0.1f, size.width * 0.6f)
                blob(p.blobB, size.width * 0.05f, size.height * 0.6f, size.width * 0.5f)
                blob(p.blobC, size.width * 0.85f, size.height, size.width * 0.5f)
                // Стеклянная карточка с «текстом».
                drawRoundRect(p.glass.copy(alpha = 0.78f), Offset(12f, 14f), Size(size.width * 0.5f, size.height * 0.62f), CornerRadius(14f))
                drawRoundRect(p.ink.copy(alpha = 0.85f), Offset(22f, 26f), Size(size.width * 0.28f, 7f), CornerRadius(4f))
                drawRoundRect(p.inkSoft.copy(alpha = 0.6f), Offset(22f, 40f), Size(size.width * 0.36f, 5f), CornerRadius(3f))
                drawRoundRect(p.accent, Offset(22f, size.height * 0.5f), Size(size.width * 0.3f, 16f), CornerRadius(8f))
                // Белый лист.
                drawRoundRect(Color.Black.copy(alpha = 0.08f), Offset(size.width * 0.6f, 18f), Size(size.width * 0.32f, size.height * 0.7f), CornerRadius(8f))
                drawRoundRect(Color.White, Offset(size.width * 0.6f, 15f), Size(size.width * 0.32f, size.height * 0.7f), CornerRadius(8f))
                for (i in 0..3) drawLine(Color(0xFF1F4FD1), Offset(size.width * 0.64f, 28f + i * 12f), Offset(size.width * (0.8f + (i % 2) * 0.06f), 28f + i * 12f), 2.2f, StrokeCap.Round)
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .graphicsLayer { scaleX = ring; scaleY = ring }
                        .clip(CircleShape)
                        .background(p.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = p.onAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(p.name, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AccentDot(c: Color, selected: Boolean, onClick: () -> Unit) {
    val k by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "accentDot")
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Акцент" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((26 + 6 * k).dp).clip(CircleShape).background(c))
        if (k > 0.01f) Box(Modifier.size(40.dp).graphicsLayer { alpha = k }.border(2.dp, c, CircleShape))
    }
}

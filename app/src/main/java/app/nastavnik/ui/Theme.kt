package app.nastavnik.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nastavnik.R

/**
 * Палитра темы: фон-«бумага», три цветных пятна под стеклом, текст, акцент (кнопки, переключатели,
 * выбранное), стекло и тонкие линии. Лист тетради от темы не зависит — он всегда белый.
 */
@Immutable
data class Palette(
    val id: String,
    val name: String,
    val dark: Boolean,
    val paper: Color,
    val ink: Color,
    val inkSoft: Color,
    val accent: Color,
    val onAccent: Color,
    val glass: Color,
    val line: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
)

/** Готовые темы: шесть светлых и четыре тёмные. */
object Palettes {
    // «Светлая» и «Тёмная» — как в приложении для родителя: ровный серо-голубой фон, белые карточки, синий акцент.
    val PAPER = Palette(
        "paper", "Светлая", false, Color(0xFFF4F5F8), Color(0xFF16161A), Color(0xFF6B6F7A),
        Color(0xFF2563EB), Color.White, Color.White, Color(0xFFE2E4EA),
        Color(0xFFD5E2F3), Color(0xFFF5DECD), Color(0xFFD5ECE1),
    )
    val OCEAN = Palette(
        "ocean", "Океан", false, Color(0xFFEEF3F9), Color(0xFF0F1B2D), Color(0xFF52617A),
        Color(0xFF2563EB), Color.White, Color.White, Color(0x1A0F1B2D),
        Color(0xFFC6DAF8), Color(0xFFCDEBF3), Color(0xFFDCD7F7),
    )
    val MINT = Palette(
        "mint", "Мята", false, Color(0xFFEEF6F1), Color(0xFF10201A), Color(0xFF4F6B60),
        Color(0xFF0E9A73), Color.White, Color.White, Color(0x1A10201A),
        Color(0xFFC9EAD9), Color(0xFFE4F2C6), Color(0xFFCBE6EE),
    )
    val SUNSET = Palette(
        "sunset", "Закат", false, Color(0xFFFBF1EA), Color(0xFF2A1712), Color(0xFF7A5A50),
        Color(0xFFE0533A), Color.White, Color.White, Color(0x1A2A1712),
        Color(0xFFFFD6BE), Color(0xFFFBCFD8), Color(0xFFFFE7B3),
    )
    val LAVENDER = Palette(
        "lavender", "Лаванда", false, Color(0xFFF3F0FA), Color(0xFF1C1630), Color(0xFF625A7A),
        Color(0xFF6A47DE), Color.White, Color.White, Color(0x1A1C1630),
        Color(0xFFDAD0F7), Color(0xFFF2D3EB), Color(0xFFD2E2F8),
    )
    val SAKURA = Palette(
        "sakura", "Сакура", false, Color(0xFFFBF0F3), Color(0xFF2B1520), Color(0xFF7A5566),
        Color(0xFFD6457A), Color.White, Color.White, Color(0x1A2B1520),
        Color(0xFFF8D0DF), Color(0xFFFBE2D0), Color(0xFFE6D9F6),
    )
    val GRAPHITE = Palette(
        "graphite", "Тёмная", true, Color(0xFF111216), Color(0xFFF2F3F5), Color(0xFFA3A7B1),
        Color(0xFF8AB4FF), Color(0xFF0B1B3A), Color(0xFF1B1C21), Color(0xFF33353C),
        Color(0xFF1C2A3D), Color(0xFF3A2A20), Color(0xFF183028),
    )
    val MIDNIGHT = Palette(
        "midnight", "Полночь", true, Color(0xFF0B1020), Color(0xFFE8ECF8), Color(0xFF9AA4C0),
        Color(0xFF5EB3FF), Color(0xFF07101F), Color(0xFF1A2342), Color(0x26FFFFFF),
        Color(0xFF1C2D63), Color(0xFF2C1C52), Color(0xFF0F3C4E),
    )
    val FOREST = Palette(
        "forest", "Лес", true, Color(0xFF0D1512), Color(0xFFE6F2EC), Color(0xFF98B0A5),
        Color(0xFF46DB98), Color(0xFF07130E), Color(0xFF1A2723), Color(0x26FFFFFF),
        Color(0xFF15392B), Color(0xFF2C3C14), Color(0xFF11343A),
    )
    val ESPRESSO = Palette(
        "espresso", "Эспрессо", true, Color(0xFF17110E), Color(0xFFF3E9E2), Color(0xFFB7A49A),
        Color(0xFFF2A65A), Color(0xFF1A110A), Color(0xFF2B211C), Color(0x26FFFFFF),
        Color(0xFF3D2618), Color(0xFF311B27), Color(0xFF3B3218),
    )

    /** «Обои» — цвета из обоев телефона (Material You, Android 12+); строятся при запуске. */
    var wallpaperLight: Palette? = null
        private set
    var wallpaperDark: Palette? = null
        private set

    val light get() = listOfNotNull(wallpaperLight) + listOf(PAPER, OCEAN, MINT, SUNSET, LAVENDER, SAKURA)
    val dark get() = listOfNotNull(wallpaperDark) + listOf(GRAPHITE, MIDNIGHT, FOREST, ESPRESSO)
    val all get() = light + dark

    /** Палитры «Обои» из системных динамических цветов — как у приложений Android 12–17. */
    fun installWallpaper(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        fun from(id: String, dark: Boolean, c: androidx.compose.material3.ColorScheme) = Palette(
            id, "Обои", dark,
            paper = c.surfaceContainerLow, ink = c.onSurface, inkSoft = c.onSurfaceVariant,
            accent = c.primary, onAccent = c.onPrimary,
            glass = if (dark) c.surfaceContainerHigh else c.surfaceContainerLowest,
            line = c.outlineVariant.copy(alpha = 0.6f),
            blobA = c.primaryContainer, blobB = c.tertiaryContainer, blobC = c.secondaryContainer,
        )
        runCatching {
            wallpaperLight = from("wallpaper", false, androidx.compose.material3.dynamicLightColorScheme(context))
            wallpaperDark = from("wallpaper_dark", true, androidx.compose.material3.dynamicDarkColorScheme(context))
        }
    }

    fun byId(id: String?, fallback: Palette) = all.firstOrNull { it.id == id } ?: fallback

    /** Акценты на выбор (поверх любой темы). */
    val accents = listOf(
        0xFF2C2C33, 0xFF2563EB, 0xFF0EA5E9, 0xFF0E9A73, 0xFF65A30D,
        0xFFE0533A, 0xFFF59E0B, 0xFFD6457A, 0xFF6A47DE, 0xFF9333EA,
    ).map { it.toInt() }
}

/** Фон экранов. */
enum class Backdrop(val label: String) { BLOBS("Пятна"), LIVE("Живые"), GRADIENT("Градиент"), PLAIN("Ровный"), IMAGE("Картинка") }

/** Как выбрана тема в настройках. */
enum class ThemeMode(val label: String) { SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная") }

/** Всё оформление: режим, светлая и тёмная тема, свой акцент (0 — как в теме), фон и размер текста. */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.LIGHT,
    val light: String = Palettes.PAPER.id,
    val dark: String = Palettes.GRAPHITE.id,
    val accent: Int = 0,
    val backdrop: Backdrop = Backdrop.PLAIN,
    val textScale: Float = 1f,
) {
    fun resolve(systemDark: Boolean): Palette {
        val useDark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val base = if (useDark) Palettes.byId(dark, Palettes.GRAPHITE) else Palettes.byId(light, Palettes.PAPER)
        if (accent == 0) return base
        val a = Color(accent)
        // Текст на акценте: белый на тёмном акценте, тёмный на светлом.
        val lum = 0.299f * a.red + 0.587f * a.green + 0.114f * a.blue
        return base.copy(accent = a, onAccent = if (lum > 0.62f) Color(0xFF16161A) else Color.White)
    }
}

/**
 * Цвета приложения. Читаются из текущей палитры (она меняется плавно при смене темы), поэтому любые
 * экраны и модификаторы перекрашиваются сами.
 */
object Brand {
    var current by mutableStateOf(Palettes.PAPER)
    var backdrop by mutableStateOf(Backdrop.PLAIN)

    /** Своя картинка фона (для [Backdrop.IMAGE]). */
    var backdropImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** Фаза «живого» фона, 0…1 по кругу. */
    var phase by mutableFloatStateOf(0f)

    val dark: Boolean get() = current.dark
    val Ink get() = current.ink
    val InkSoft get() = current.inkSoft
    val Paper get() = current.paper
    val Glass get() = current.glass
    val Line get() = current.line
    val Accent get() = current.accent
    val OnAccent get() = current.onAccent

    /** Текст и значки на акцентной кнопке. */
    val OnInk get() = current.onAccent
    val AccentSoft get() = current.accent.copy(alpha = 0.12f)
    val BlobCool get() = current.blobA
    val BlobWarm get() = current.blobB
    val BlobMint get() = current.blobC

    // Смысловые цвета — одинаковые во всех темах.
    val Error = Color(0xFFE5484D)
    val ErrorSoft get() = if (dark) Color(0xFF3A1F22) else Color(0xFFFDECEC)
    val Ok = Color(0xFF1F9D60)
    val OkSoft get() = if (dark) Color(0xFF16301F) else Color(0xFFE5F6ED)
    val Theory = Color(0xFFD9822B)
    val TheorySoft get() = if (dark) Color(0xFF3A2A18) else Color(0xFFFCF0E3)

    /** Стол вокруг белого листа тетради. */
    val Desk get() = if (dark) lerp(current.paper, Color.Black, 0.25f) else lerp(current.paper, current.ink, 0.06f)
}

val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

private fun typography(k: Float): Typography {
    fun t(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
        TextStyle(fontFamily = Onest, fontSize = (size * k).sp, lineHeight = (line * k).sp, fontWeight = weight, letterSpacing = tracking.sp)
    return Typography(
        displaySmall = t(34, 40, FontWeight.Bold, -0.6),
        headlineLarge = t(30, 36, FontWeight.Bold, -0.5),
        headlineMedium = t(26, 32, FontWeight.Bold, -0.4),
        headlineSmall = t(22, 28, FontWeight.SemiBold, -0.3),
        titleLarge = t(20, 26, FontWeight.SemiBold, -0.2),
        titleMedium = t(17, 22, FontWeight.SemiBold),
        titleSmall = t(15, 20, FontWeight.SemiBold),
        bodyLarge = t(16, 24, FontWeight.Normal),
        bodyMedium = t(15, 22, FontWeight.Normal),
        bodySmall = t(13, 18, FontWeight.Normal),
        labelLarge = t(15, 20, FontWeight.SemiBold),
        labelMedium = t(13, 18, FontWeight.Medium),
        labelSmall = t(12, 16, FontWeight.SemiBold, 0.4),
    )
}

private fun scheme(p: Palette) = if (p.dark) {
    darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = lerp(p.glass, p.accent, 0.25f), onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = p.paper,
        secondaryContainer = p.glass, onSecondaryContainer = p.ink,
        tertiary = Brand.Ok,
        background = p.paper, onBackground = p.ink,
        surface = lerp(p.paper, p.glass, 0.6f), onSurface = p.ink,
        surfaceVariant = p.glass, onSurfaceVariant = p.inkSoft,
        surfaceContainerLowest = p.paper,
        surfaceContainerLow = lerp(p.paper, p.glass, 0.4f),
        surfaceContainer = lerp(p.paper, p.glass, 0.7f),
        surfaceContainerHigh = p.glass,
        surfaceContainerHighest = lerp(p.glass, p.ink, 0.06f),
        outline = lerp(p.glass, p.ink, 0.25f), outlineVariant = lerp(p.glass, p.ink, 0.1f),
        error = Brand.Error, onError = Color.White,
        errorContainer = Color(0xFF3A1F22), onErrorContainer = Color(0xFFFFB4B6),
    )
} else {
    lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = lerp(Color.White, p.accent, 0.1f), onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = Color.White,
        secondaryContainer = lerp(p.paper, p.ink, 0.05f), onSecondaryContainer = p.ink,
        tertiary = Brand.Ok,
        background = p.paper, onBackground = p.ink,
        surface = Color.White, onSurface = p.ink,
        surfaceVariant = lerp(p.paper, p.ink, 0.05f), onSurfaceVariant = p.inkSoft,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = lerp(Color.White, p.paper, 0.5f),
        surfaceContainer = p.paper,
        surfaceContainerHigh = lerp(p.paper, p.ink, 0.04f),
        surfaceContainerHighest = lerp(p.paper, p.ink, 0.07f),
        outline = lerp(p.paper, p.ink, 0.2f), outlineVariant = lerp(p.paper, p.ink, 0.08f),
        error = Brand.Error, onError = Color.White,
        errorContainer = Color(0xFFFDECEC), onErrorContainer = Color(0xFF8E1F24),
    )
}

/**
 * Тема приложения. При смене темы цвета перетекают за полсекунды; «живой» фон медленно дышит.
 * Лист тетради, чистовик и заметки поверх записей остаются белыми, как бумага.
 */
@Composable
fun NastavnikTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val target = appearance.resolve(isSystemInDarkTheme())
    val spec = tween<Color>(520)
    val paper by animateColorAsState(target.paper, spec, label = "paper")
    val ink by animateColorAsState(target.ink, spec, label = "ink")
    val inkSoft by animateColorAsState(target.inkSoft, spec, label = "inkSoft")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "onAccent")
    val glass by animateColorAsState(target.glass, spec, label = "glass")
    val line by animateColorAsState(target.line, spec, label = "line")
    val a by animateColorAsState(target.blobA, spec, label = "blobA")
    val b by animateColorAsState(target.blobB, spec, label = "blobB")
    val c by animateColorAsState(target.blobC, spec, label = "blobC")
    val palette = target.copy(paper = paper, ink = ink, inkSoft = inkSoft, accent = accent, onAccent = onAccent, glass = glass, line = line, blobA = a, blobB = b, blobC = c)
    Brand.current = palette
    Brand.backdrop = appearance.backdrop
    // Своя картинка фона — загружаем, когда выбрана (и заново, если её сменили).
    val context = androidx.compose.ui.platform.LocalContext.current
    val image by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, appearance.backdrop, CustomImages.version) {
        value = if (appearance.backdrop == Backdrop.IMAGE) CustomImages.load(CustomImages.backdropFile(context)) else null
    }
    Brand.backdropImage = image

    // «Живой» фон: пятна медленно плывут (цикл 28 с). Меняется только рисование, без перекомпоновки.
    if (appearance.backdrop == Backdrop.LIVE) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> Brand.phase = ((now - start) / 28e9f) % 1f }
            }
        }
    }

    val typography = remember(appearance.textScale) { typography(appearance.textScale) }
    MaterialTheme(
        colorScheme = scheme(palette),
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        // Текст без явного цвета — цвета темы (на прозрачном фоне Scaffold иначе остаётся чёрным).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}

package app.nastavnik.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nastavnik.R

/**
 * Фирменные цвета: «чернила, бумага и стекло». Тёплый фон с мягкими цветными пятнами, графитовый текст
 * и полупрозрачные стеклянные пластины вместо яркого акцента; у видов заметок — свои спокойные цвета.
 */
object Brand {
    /** Тёмная тема включена: цвета ниже читаются в композиции, поэтому экраны перерисуются сами. */
    var dark by mutableStateOf(false)

    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    /** Чернила: основной текст и графитовые кнопки (в тёмной теме — светлые). */
    val Ink get() = pick(0xFF16161A, 0xFFEDECF1)
    val InkSoft get() = pick(0xFF5E5D66, 0xFFA3A2AD)

    /** Текст и значки на графитовой кнопке. */
    val OnInk get() = pick(0xFFFFFFFF, 0xFF16161A)
    val Paper get() = pick(0xFFF4F2EE, 0xFF121215)

    /** Цвет стекла и поверхностей. */
    val Glass get() = pick(0xFFFFFFFF, 0xFF2A2A31)

    /** Тонкие линии и подложки «на просвет». */
    val Line get() = pick(0x1A16161A, 0x24FFFFFF)

    /** «Акцент» — графит: выделение делают стекло и контраст, а не цвет. */
    val Accent get() = pick(0xFF2C2C33, 0xFFE4E3EA)
    val AccentSoft get() = pick(0xFFE9E9EE, 0xFF2E2E36)
    val Error = Color(0xFFE5484D)
    val ErrorSoft get() = pick(0xFFFDECEC, 0xFF3A1F22)
    val Ok = Color(0xFF1F9D60)
    val OkSoft get() = pick(0xFFE5F6ED, 0xFF16301F)
    val Theory = Color(0xFFD9822B)
    val TheorySoft get() = pick(0xFFFCF0E3, 0xFF3A2A18)

    /** Мягкие пятна фона под стеклом: без них прозрачная пластина на ровной бумаге не читается как стекло. */
    val BlobCool get() = pick(0xFFD5E2F3, 0xFF1C2A3D)
    val BlobWarm get() = pick(0xFFF5DECD, 0xFF3A2A20)
    val BlobMint get() = pick(0xFFD5ECE1, 0xFF183028)
}

val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

private fun scheme(dark: Boolean) = if (dark) Dark else Light

private val Dark = darkColorScheme(
    primary = Color(0xFFE4E3EA),
    onPrimary = Color(0xFF16161A),
    primaryContainer = Color(0xFF2E2E36),
    onPrimaryContainer = Color(0xFFEDECF1),
    secondary = Color(0xFFEDECF1),
    onSecondary = Color(0xFF16161A),
    secondaryContainer = Color(0xFF2A2A31),
    onSecondaryContainer = Color(0xFFEDECF1),
    tertiary = Brand.Ok,
    background = Color(0xFF121215),
    onBackground = Color(0xFFEDECF1),
    surface = Color(0xFF1E1E23),
    onSurface = Color(0xFFEDECF1),
    surfaceVariant = Color(0xFF2A2A31),
    onSurfaceVariant = Color(0xFFA3A2AD),
    surfaceContainerLowest = Color(0xFF16161A),
    surfaceContainerLow = Color(0xFF1C1C21),
    surfaceContainer = Color(0xFF222228),
    surfaceContainerHigh = Color(0xFF2A2A31),
    surfaceContainerHighest = Color(0xFF33333B),
    outline = Color(0xFF4A4A53),
    outlineVariant = Color(0xFF34343C),
    error = Brand.Error,
    onError = Color.White,
    errorContainer = Color(0xFF3A1F22),
    onErrorContainer = Color(0xFFFFB4B6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2C2C33),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E9EE),
    onPrimaryContainer = Color(0xFF16161A),
    secondary = Color(0xFF16161A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE9E3),
    onSecondaryContainer = Color(0xFF16161A),
    tertiary = Brand.Ok,
    background = Color(0xFFF4F2EE),
    onBackground = Color(0xFF16161A),
    surface = Color.White,
    onSurface = Color(0xFF16161A),
    surfaceVariant = Color(0xFFECE9E3),
    onSurfaceVariant = Color(0xFF5E5D66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F6),
    surfaceContainer = Color(0xFFF1EFEA),
    surfaceContainerHigh = Color(0xFFECE9E3),
    surfaceContainerHighest = Color(0xFFE6E2DB),
    outline = Color(0xFFCFCBC3),
    outlineVariant = Color(0xFFE6E2DB),
    error = Brand.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8E1F24),
)

private fun t(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontFamily = Onest, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val AppTypography = Typography(
    displaySmall = t(34, 40, FontWeight.Bold, -0.6),
    headlineLarge = t(30, 36, FontWeight.Bold, -0.5),
    headlineMedium = t(26, 32, FontWeight.Bold, -0.4),
    headlineSmall = t(22, 28, FontWeight.SemiBold, -0.3),
    titleLarge = t(20, 26, FontWeight.SemiBold, -0.2),
    titleMedium = t(16, 22, FontWeight.SemiBold),
    titleSmall = t(14, 20, FontWeight.SemiBold),
    bodyLarge = t(16, 24, FontWeight.Normal),
    bodyMedium = t(15, 22, FontWeight.Normal),
    bodySmall = t(13, 18, FontWeight.Normal),
    labelLarge = t(15, 20, FontWeight.SemiBold),
    labelMedium = t(13, 18, FontWeight.Medium),
    labelSmall = t(12, 16, FontWeight.SemiBold, 0.4),
)

/** Как выбрана тема в настройках. */
enum class ThemeMode(val label: String) { SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная") }

/**
 * Тема приложения. Тёмная — «графитовое стекло»; лист тетради, чистовик и заметки поверх записей
 * остаются светлыми, как бумага.
 */
@Composable
fun NastavnikTheme(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    Brand.dark = dark
    MaterialTheme(
        colorScheme = scheme(dark),
        typography = AppTypography,
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

package app.nastavnik.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nastavnik.R

/**
 * Фирменные цвета: «чернила и бумага». Тёплый фон, графитовый текст и один яркий
 * фиолетово-синий акцент; у видов заметок — свои спокойные цвета.
 */
object Brand {
    val Ink = Color(0xFF16161A)
    val InkSoft = Color(0xFF5E5D66)
    val Paper = Color(0xFFF4F2EE)
    val Accent = Color(0xFF5B4CF0)
    val Accent2 = Color(0xFF8E5CF7)
    val AccentSoft = Color(0xFFECEAFE)
    val Error = Color(0xFFE5484D)
    val ErrorSoft = Color(0xFFFDECEC)
    val Ok = Color(0xFF1F9D60)
    val OkSoft = Color(0xFFE5F6ED)
    val Theory = Color(0xFFD9822B)
    val TheorySoft = Color(0xFFFCF0E3)

    /** Значок приложения и главные акценты. */
    val gradient = Brush.linearGradient(listOf(Color(0xFF4A3BE6), Accent, Color(0xFFB15CF2)))
}

val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = Brand.Accent,
    onPrimary = Color.White,
    primaryContainer = Brand.AccentSoft,
    onPrimaryContainer = Color(0xFF2A1F9E),
    secondary = Brand.Ink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE9E3),
    onSecondaryContainer = Brand.Ink,
    tertiary = Brand.Ok,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Color.White,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFECE9E3),
    onSurfaceVariant = Brand.InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F6),
    surfaceContainer = Color(0xFFF1EFEA),
    surfaceContainerHigh = Color(0xFFECE9E3),
    surfaceContainerHighest = Color(0xFFE6E2DB),
    outline = Color(0xFFCFCBC3),
    outlineVariant = Color(0xFFE6E2DB),
    error = Brand.Error,
    onError = Color.White,
    errorContainer = Brand.ErrorSoft,
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

/**
 * Приложение всегда светлое, как бумага: заметки поверх Touchnotes, чистовик и PDF тоже светлые,
 * и тёмные экраны между ними выглядели бы чужими.
 */
@Composable
fun NastavnikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Light,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

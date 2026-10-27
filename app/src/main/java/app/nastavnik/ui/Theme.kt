package app.nastavnik.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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

private val Dark = darkColorScheme(
    primary = Color(0xFF8B7FFF),
    onPrimary = Color(0xFF160F5C),
    primaryContainer = Color(0xFF2B2470),
    onPrimaryContainer = Color(0xFFE3E0FF),
    secondary = Color(0xFFF4F4F6),
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF26262D),
    onSecondaryContainer = Color(0xFFF4F4F6),
    tertiary = Color(0xFF5FD49A),
    background = Color(0xFF0F0F12),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF1A1A1F),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF24242B),
    onSurfaceVariant = Color(0xFFA3A2AB),
    surfaceContainerLowest = Color(0xFF1A1A1F),
    surfaceContainerLow = Color(0xFF1C1C21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF26262D),
    surfaceContainerHighest = Color(0xFF2E2E36),
    outline = Color(0xFF45454E),
    outlineVariant = Color(0xFF2C2C33),
    error = Color(0xFFFF6369),
    errorContainer = Color(0xFF3B1517),
    onErrorContainer = Color(0xFFFFDAD9),
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

@Composable
fun NastavnikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
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

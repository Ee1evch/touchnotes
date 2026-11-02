package app.nastavnik.parent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

/** Цвета по смыслу — одинаковые в светлой и тёмной теме. */
object Tone {
    val Ok = Color(0xFF1F9D60)
    val Warn = Color(0xFFE0A21E)
    val Bad = Color(0xFFE5484D)
    val Accent = Color(0xFF2563EB)

    /** Цвет пары по типу — как в «Наставнике». */
    fun lesson(type: String): Color = when (type) {
        "Лекция" -> Color(0xFF4C7DF0)
        "Практика" -> Color(0xFF1FA463)
        "Семинар" -> Color(0xFFE08A1E)
        "Лабораторная" -> Color(0xFF9B5DE5)
        else -> Color(0xFF7C8596)
    }

    /** Цвет оценки: 5 — зелёный, 4 — синий, 3 — жёлтый, 2 и ниже — красный; не число — нейтральный. */
    fun mark(value: String): Color {
        val v = value.replace(',', '.').toDoubleOrNull() ?: return Color(0xFF7C8596)
        return when {
            v >= 4.5 -> Ok
            v >= 3.5 -> Accent
            v >= 2.5 -> Warn
            else -> Bad
        }
    }
}

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) =
    TextStyle(fontFamily = Onest, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val type = Typography(
    headlineLarge = style(30, 36, FontWeight.Bold, -0.5f),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.3f),
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(17, 22, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 22, FontWeight.Normal),
    bodyMedium = style(15, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.Medium),
    labelMedium = style(13, 16, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)

@Composable
fun ParentTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF0B1B3A),
        background = Color(0xFF111216), surface = Color(0xFF1B1C21), surfaceVariant = Color(0xFF26272D),
        onBackground = Color(0xFFF2F3F5), onSurface = Color(0xFFF2F3F5), onSurfaceVariant = Color(0xFFA3A7B1),
        outlineVariant = Color(0xFF33353C), secondaryContainer = Color(0xFF26314A), onSecondaryContainer = Color(0xFFDDE6FF),
    ) else lightColorScheme(
        primary = Color(0xFF2563EB), onPrimary = Color.White,
        background = Color(0xFFF4F5F8), surface = Color.White, surfaceVariant = Color(0xFFEDEEF2),
        onBackground = Color(0xFF16161A), onSurface = Color(0xFF16161A), onSurfaceVariant = Color(0xFF6B6F7A),
        outlineVariant = Color(0xFFE2E4EA), secondaryContainer = Color(0xFFE3ECFF), onSecondaryContainer = Color(0xFF1A3C8C),
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}

package app.nastavnik.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Цвета Google и радужные градиенты в духе Gemini. */
object GoogleColors {
    val Blue = Color(0xFF4285F4)
    val Red = Color(0xFFEA4335)
    val Yellow = Color(0xFFFBBC04)
    val Green = Color(0xFF34A853)
    val Violet = Color(0xFF9B72CB)
    val Rose = Color(0xFFD96570)

    /** Радуга Google — для заголовков, рамок и главной кнопки. */
    val rainbow = Brush.linearGradient(listOf(Blue, Violet, Red, Yellow, Green))

    /** Мягкий градиент «искры» Gemini. */
    val sparkle = Brush.linearGradient(listOf(Blue, Violet, Rose))
}

private val Light = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF444746),
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    tertiary = Color(0xFF146C2E),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F4F9),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFFC4C7C5),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFCE8E6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF131314),
    surface = Color(0xFF131314),
    surfaceVariant = Color(0xFF1E1F20),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFC4C7C5),
)

@Composable
fun NastavnikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

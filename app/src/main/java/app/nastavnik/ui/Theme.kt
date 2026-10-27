package app.nastavnik.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8ECFF),
    onPrimaryContainer = Color(0xFF14246B),
    secondary = Color(0xFF5B6382),
    secondaryContainer = Color(0xFFE3E6F2),
    background = Color(0xFFFAFBFF),
    surface = Color(0xFFFAFBFF),
    surfaceVariant = Color(0xFFEEF0F7),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFDECEC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB8C3FF),
    onPrimary = Color(0xFF0E1E6B),
    primaryContainer = Color(0xFF2A3EB1),
    onPrimaryContainer = Color(0xFFE8ECFF),
)

@Composable
fun NastavnikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content,
    )
}

package `in`.llmlabs.docsmanager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// The llmlabs.in palette (src/styles.css), so the app and the site feel like one product.
private val Light = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8FD),
    onPrimaryContainer = Color(0xFF1C2F8A),
    secondary = Color(0xFF4F6DF5),
    secondaryContainer = Color(0xFFE3E8FD),
    onSecondaryContainer = Color(0xFF1C2F8A),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1B1F2A),
    surface = Color.White,
    onSurface = Color(0xFF1B1F2A),
    surfaceVariant = Color(0xFFEEF0F7),
    onSurfaceVariant = Color(0xFF5F6678),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFF0F2F9),
    outline = Color(0xFFC9CEDD),
    outlineVariant = Color(0xFFE3E6EF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6C85F5),
    onPrimary = Color(0xFF0B0F1A),
    primaryContainer = Color(0xFF26306A),
    onPrimaryContainer = Color(0xFFDFE5FF),
    secondary = Color(0xFF8DA0F8),
    secondaryContainer = Color(0xFF26306A),
    onSecondaryContainer = Color(0xFFDFE5FF),
    background = Color(0xFF12141B),
    onBackground = Color(0xFFECEEF5),
    surface = Color(0xFF1B1E28),
    onSurface = Color(0xFFECEEF5),
    surfaceVariant = Color(0xFF242836),
    onSurfaceVariant = Color(0xFF9AA1B5),
    surfaceContainer = Color(0xFF1B1E28),
    surfaceContainerLow = Color(0xFF1B1E28),
    surfaceContainerHigh = Color(0xFF242836),
    outline = Color(0xFF3A3F50),
    outlineVariant = Color(0xFF2A2E3C),
)

/** Serif headings over a clean sans-serif body, like a well-set document. */
private val Base = Typography()
private val Type = Base.copy(
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
)

@Composable
fun DocsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}

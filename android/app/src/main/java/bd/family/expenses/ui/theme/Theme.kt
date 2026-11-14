package bd.family.expenses.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimum height for anything tappable (larger than Material's 48dp). */
val TouchTarget = 60.dp

// Fixed high-contrast palette (no dynamic wallpaper colours, which can reduce contrast).
private val Light =
  lightColorScheme(
    primary = Color(0xFF0B4F9C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FB),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF8A3B00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2D0),
    onSecondaryContainer = Color(0xFF331200),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDE8),
    onSurfaceVariant = Color(0xFF3B3B38),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF5F5F5A),
  )

private val Dark =
  darkColorScheme(
    primary = Color(0xFF9CC5FF),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF0B3E78),
    onPrimaryContainer = Color(0xFFD6E6FB),
    secondary = Color(0xFFFFB68A),
    onSecondary = Color(0xFF4A1C00),
    background = Color(0xFF121211),
    onBackground = Color(0xFFF2F2EE),
    surface = Color(0xFF1C1C1A),
    onSurface = Color(0xFFF2F2EE),
    surfaceVariant = Color(0xFF2C2C29),
    onSurfaceVariant = Color(0xFFD5D5CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1712),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA5A59E),
  )

// Large type throughout; the system font size setting still scales it further.
private val Large =
  Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 21.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
    labelLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
  )

@Composable
fun FamilyExpensesTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Large, content = content)
}

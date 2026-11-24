package selo.Nachipa01.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens from PRD v6.0 section 11.2. */
object Selo {
    val Red = Color(0xFFCC0023)
    val RedDeep = Color(0xFF9E001B)
    val RedTint = Color(0xFFFFF0F2)
    val Ink = Color(0xFF1A1A2E)
    val Mute = Color(0xFF6B6B78)
    val Line = Color(0xFFECECF0)
    val Field = Color(0xFFDCDCE3)
    val Surface = Color(0xFFF5F5F5)
    val Card = Color(0xFFFFFFFF)
    val Green = Color(0xFF10B981)
    val GreenDark = Color(0xFF047857)
    val GreenTint = Color(0xFFECFDF5)
    val Purple = Color(0xFF7C3AED)
    val PurpleTint = Color(0xFFEDE5FF)
    val Amber = Color(0xFFF59E0B)
    val AmberTint = Color(0xFFFFF4DC)
    val AmberDark = Color(0xFF92400E)
    val Teal = Color(0xFF0D9488)
    val TealTint = Color(0xFFE6F7F5)
    val Coral = Color(0xFFFF6B6B)
    val Grey = Color(0xFF9CA3AF)
}

private val scheme = lightColorScheme(
    primary = Selo.Red, onPrimary = Color.White, secondary = Selo.Green, tertiary = Selo.Purple,
    background = Selo.Surface, surface = Selo.Card, onBackground = Selo.Ink, onSurface = Selo.Ink,
    error = Selo.Red, outline = Selo.Field,
)

private val type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun SeloTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)

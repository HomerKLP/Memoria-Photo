package app.memoria

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Honey palette from the mockup, light only.
private val fg = Color(0xFF2B2118)
private val bg = Color(0xFFFBF6EC)
private val surface = Color(0xFFF3EADA)
private val soft = Color(0xFFF3DCB2)
private val muted = Color(0xFF6F604F)

private val colors = lightColorScheme(
    primary = Color(0xFF9A5A0A),
    onPrimary = Color.White,
    primaryContainer = soft,
    onPrimaryContainer = fg,
    secondary = fg,
    onSecondary = Color.White,
    secondaryContainer = soft,
    onSecondaryContainer = fg,
    background = bg,
    onBackground = fg,
    surface = bg,
    onSurface = fg,
    surfaceVariant = surface,
    onSurfaceVariant = muted,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surface,
    surfaceContainerHighest = surface,
    outline = muted,
    outlineVariant = Color(0xFFE4D6BF),
    error = Color(0xFFB3261E),
)

// The mockup's two fonts, both bundled (some OEMs swap the system font entirely): titles and headings in
// Bricolage Grotesque (--display), all other text in Roboto (--body). Screens pick a style below, never a fontFamily.
private val display = FontFamily(Font(R.font.bricolage_medium, FontWeight.Medium), Font(R.font.bricolage_bold, FontWeight.Bold))
private val body = FontFamily(Font(R.font.roboto_regular), Font(R.font.roboto_medium, FontWeight.Medium), Font(R.font.roboto_bold, FontWeight.Bold))

// letterSpacing 0: the mockup has none, Material's 0.1-0.5 sp spread the text out
private val type = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = display, letterSpacing = 0.sp),
        displayMedium = displayMedium.copy(fontFamily = display, letterSpacing = 0.sp),
        displaySmall = displaySmall.copy(fontFamily = display, letterSpacing = 0.sp),
        headlineLarge = headlineLarge.copy(fontFamily = display, letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = display, letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = display, letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = display, letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = display, letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontFamily = body, letterSpacing = 0.sp), // the timeline's day headers, body text in the mockup
        bodyLarge = bodyLarge.copy(fontFamily = body, letterSpacing = 0.sp), // also the default for a Text with no style
        bodyMedium = bodyMedium.copy(fontFamily = body, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = body, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = body, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = body, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = body, letterSpacing = 0.sp),
    )
}

@Composable
fun MemoriaTheme(content: @Composable () -> Unit) = MaterialTheme(colors, typography = type, content = content)

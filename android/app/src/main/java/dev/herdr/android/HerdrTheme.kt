package dev.herdr.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Slate surfaces and a blue navigation accent; terminal cells retain server colors.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF1A5BD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E4FB),
    onPrimaryContainer = Color(0xFF0A2A66),
    inversePrimary = Color(0xFF8AB4F8),
    secondary = Color(0xFF0B6E73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEDEA),
    onSecondaryContainer = Color(0xFF06393C),
    tertiary = Color(0xFF6A3BBF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECE2FB),
    onTertiaryContainer = Color(0xFF2A0E5C),
    error = Color(0xFFBA1A3C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDCE2),
    onErrorContainer = Color(0xFF5C0A1D),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFF7F8FA),
    surfaceBright = Color(0xFFF7F8FA),
    surfaceDim = Color(0xFFD5DAE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F6),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE4E8ED),
    surfaceContainerHighest = Color(0xFFDDE2E8),
    surfaceVariant = Color(0xFFDDE2E8),
    onSurface = Color(0xFF1A202B),
    onBackground = Color(0xFF1A202B),
    onSurfaceVariant = Color(0xFF4A5466),
    outline = Color(0xFF727C8C),
    outlineVariant = Color(0xFFC3CAD4),
    inverseSurface = Color(0xFF283039),
    inverseOnSurface = Color(0xFFEEF1F5),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFF7F8FA),
    primaryFixed = Color(0xFFD8E4FB),
    primaryFixedDim = Color(0xFF8AB4F8),
    onPrimaryFixed = Color(0xFF0A2350),
    onPrimaryFixedVariant = Color(0xFF1F3557),
    secondaryFixed = Color(0xFFCDEDEA),
    secondaryFixedDim = Color(0xFF86D7CC),
    onSecondaryFixed = Color(0xFF00332E),
    onSecondaryFixedVariant = Color(0xFF1C3735),
    tertiaryFixed = Color(0xFFECE2FB),
    tertiaryFixedDim = Color(0xFFC8B0F0),
    onTertiaryFixed = Color(0xFF2E1A52),
    onTertiaryFixedVariant = Color(0xFF3A2E52),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A2350),
    primaryContainer = Color(0xFF1F3557),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF1A5BD6),
    secondary = Color(0xFF86D7CC),
    onSecondary = Color(0xFF00332E),
    secondaryContainer = Color(0xFF1C3735),
    onSecondaryContainer = Color(0xFFC4F1EA),
    tertiary = Color(0xFFC8B0F0),
    onTertiary = Color(0xFF2E1A52),
    tertiaryContainer = Color(0xFF3A2E52),
    onTertiaryContainer = Color(0xFFEADDFF),
    error = Color(0xFFF28B9E),
    onError = Color(0xFF4F0A1C),
    errorContainer = Color(0xFF5A1D2B),
    onErrorContainer = Color(0xFFFFD9DF),
    background = Color(0xFF11151B),
    surface = Color(0xFF11151B),
    surfaceBright = Color(0xFF2F3743),
    surfaceDim = Color(0xFF11151B),
    surfaceContainerLowest = Color(0xFF0D1015),
    surfaceContainerLow = Color(0xFF151A21),
    surfaceContainer = Color(0xFF1A2029),
    surfaceContainerHigh = Color(0xFF212833),
    surfaceContainerHighest = Color(0xFF29313D),
    surfaceVariant = Color(0xFF29313D),
    onSurface = Color(0xFFE0E5ED),
    onBackground = Color(0xFFE0E5ED),
    onSurfaceVariant = Color(0xFFA9B2C0),
    outline = Color(0xFF6E7889),
    outlineVariant = Color(0xFF353D4A),
    inverseSurface = Color(0xFFE0E5ED),
    inverseOnSurface = Color(0xFF1A2029),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF11151B),
    primaryFixed = Color(0xFFD8E4FB),
    primaryFixedDim = Color(0xFF8AB4F8),
    onPrimaryFixed = Color(0xFF0A2350),
    onPrimaryFixedVariant = Color(0xFF1F3557),
    secondaryFixed = Color(0xFFCDEDEA),
    secondaryFixedDim = Color(0xFF86D7CC),
    onSecondaryFixed = Color(0xFF00332E),
    onSecondaryFixedVariant = Color(0xFF1C3735),
    tertiaryFixed = Color(0xFFECE2FB),
    tertiaryFixedDim = Color(0xFFC8B0F0),
    onTertiaryFixed = Color(0xFF2E1A52),
    onTertiaryFixedVariant = Color(0xFF3A2E52),
)

@Immutable
internal data class HerdrStatusColors(
    val working: Color,
    val blocked: Color,
    val done: Color,
    val idle: Color,
    val unknown: Color,
) {
    fun forStatus(status: String): Color = when (status) {
        "working" -> working
        "blocked" -> blocked
        "done" -> done
        "idle" -> idle
        else -> unknown
    }
}

private val LightStatuses = HerdrStatusColors(
    working = Color(0xFF965A00), blocked = Color(0xFFBA1A3C), done = Color(0xFF0B6E73),
    idle = Color(0xFF2F7D32), unknown = Color(0xFF727C8C),
)
private val DarkStatuses = HerdrStatusColors(
    working = Color(0xFFF2CD6F), blocked = Color(0xFFF28B9E), done = Color(0xFF86D7CC),
    idle = Color(0xFF9BD58F), unknown = Color(0xFF7D8798),
)
internal val LocalHerdrStatusColors = staticCompositionLocalOf { LightStatuses }

private val HerdrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp),
)
private val HerdrTypography = Typography().let {
    it.copy(titleSmall = it.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
}

// Overlays sit over arbitrary server terminal colors, independently of the app theme.
internal val TerminalOverlayBackground = Color(0xEB29313D)
internal val TerminalOverlayForeground = Color(0xFFE0E5ED)

@Composable
internal fun HerdrTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHerdrStatusColors provides if (dark) DarkStatuses else LightStatuses) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
            shapes = HerdrShapes, typography = HerdrTypography, content = content)
    }
}

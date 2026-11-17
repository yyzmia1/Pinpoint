package com.pocketeverything.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Slate950 = Color(0xFF0D1117)
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Sky400 = Color(0xFF38BDF8)
val Sky700 = Color(0xFF0369A1)
val Amber400 = Color(0xFFFBBF24)
val Amber200 = Color(0xFFFDE68A)
val Emerald400 = Color(0xFF34D399)
val Rose400 = Color(0xFFFB7185)

// High-contrast calibrated dark palette so all buttons, tonal pills, borders, and icons stay vivid in night mode.
val DarkColors = darkColorScheme(
    primary = Sky400,
    onPrimary = Color(0xFF05192B),
    primaryContainer = Color(0xFF154970),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Amber400,
    onSecondary = Color(0xFF1E1400),
    secondaryContainer = Color(0xFF243B55),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Emerald400,
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF131923),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFB0C0D4),
    surfaceTint = Color(0xFF131923),
    surfaceContainerLowest = Color(0xFF090D13),
    surfaceContainerLow = Color(0xFF182232),
    surfaceContainer = Color(0xFF1F2B3E),
    surfaceContainerHigh = Color(0xFF28374F),
    surfaceContainerHighest = Color(0xFF344663),
    outline = Color(0xFF526685),
    outlineVariant = Color(0xFF29364B)
)

val LightColors = lightColorScheme(
    primary = Sky700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFF059669),
    background = Color.White,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF526277),
    surfaceTint = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F7FB),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE4EAF2),
    surfaceContainerHighest = Color(0xFFDCE4EE),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0)
)

val ColorScheme.isLightScheme: Boolean
    get() = background.luminance() >= 0.5f

val ColorScheme.groupedBackdrop: Color
    get() = if (isLightScheme) surfaceContainerLow else background

val ColorScheme.groupedCard: Color
    get() = if (isLightScheme) surface else surfaceContainer

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp)
)

@Composable
fun PocketEverythingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

package com.pocketeverything.app.ui.theme

import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketeverything.app.data.ThemeAccent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

val LocalHasCustomBg = compositionLocalOf { false }
val LocalCardOpacity = compositionLocalOf { 1f }
val LocalHapticsEnabled = compositionLocalOf { true }
val LocalHapticStrength = compositionLocalOf { 0.20f }

internal fun effectiveCardOpacity(hasCustomBg: Boolean, rawOpacity: Float): Float =
    if (hasCustomBg) rawOpacity.coerceIn(0f, 1f) else 1f

@Composable
fun lowOpacityCardBorder(
    hasCustomBg: Boolean = LocalHasCustomBg.current,
    cardAlpha: Float = LocalCardOpacity.current
): BorderStroke? = if (hasCustomBg && cardAlpha <= 0.20f) {
    BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f))
} else {
    null
}

/**
 * Lightweight wrapper around Android's native linear-motor Vibrator with continuous 0..1 amplitude scaling.
 */
class AppHaptics(
    private val view: View?,
    private val vibrator: Vibrator?,
    val strength: Float
) {
    val enabled: Boolean get() = strength > 0.01f

    fun tick(overrideStrength: Float = strength) {
        vibrateScaled(overrideStrength, isClick = false)
    }

    fun click(forceEvenIfDisabled: Boolean = false, overrideStrength: Float? = null) {
        val s = resolveClickStrength(forceEvenIfDisabled, overrideStrength)
        vibrateScaled(s, isClick = true)
    }

    internal fun resolveClickStrength(
        forceEvenIfDisabled: Boolean = false,
        overrideStrength: Float? = null
    ): Float {
        val raw = overrideStrength ?: if (forceEvenIfDisabled && strength <= 0.01f) 0.20f else strength
        return raw.coerceIn(0f, 1f)
    }

    private fun vibrateScaled(rawStrength: Float, isClick: Boolean) {
        val s = rawStrength.coerceIn(0f, 1f)
        if (s <= 0.01f) return

        val vib = vibrator
        if (vib != null && vib.hasVibrator()) {
            val handled = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val prim = if (s < 0.35f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                    } else {
                        VibrationEffect.Composition.PRIMITIVE_TICK
                    }
                    if (vib.areAllPrimitivesSupported(prim)) {
                        val scale = if (isClick) {
                            (s * 0.72f).coerceIn(0.05f, 1.0f)
                        } else {
                            (s * 0.50f).coerceIn(0.04f, 0.85f)
                        }
                        val effect = VibrationEffect.startComposition()
                            .addPrimitive(prim, scale)
                            .compose()
                        vib.vibrate(effect)
                        return@runCatching true
                    }
                }
                val durationMs = if (isClick) {
                    (3L + (s * 7f).roundToInt()).coerceIn(3L, 10L)
                } else {
                    (2L + (s * 5f).roundToInt()).coerceIn(2L, 7L)
                }
                val amplitude = if (isClick) {
                    (s * 165f).roundToInt().coerceIn(1, 255)
                } else {
                    (s * 115f).roundToInt().coerceIn(1, 200)
                }
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                true
            }.getOrDefault(false)
            if (handled) return
        }

        // Fallback if Vibrator service is unavailable
        if (view != null) {
            val code = if (s < 0.45f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            view.performHapticFeedback(code)
        }
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    val context = LocalContext.current
    val strength = LocalHapticStrength.current
    val vibrator = remember(context) { context.getSystemService(Vibrator::class.java) }
    return remember(view, vibrator, strength) { AppHaptics(view, vibrator, strength) }
}

fun ThemeAccent.previewColors(darkTheme: Boolean): Pair<Color, Color> {
    val scheme = accentColorScheme(this, darkTheme)
    return scheme.primary to scheme.primaryContainer
}

fun accentColorScheme(accent: ThemeAccent, darkTheme: Boolean): ColorScheme = when (accent) {
    ThemeAccent.SKY -> if (darkTheme) DarkColors else LightColors
    ThemeAccent.SAGE -> if (darkTheme) {
        DarkColors.copy(
            primary = Color(0xFF6EE7B7),
            onPrimary = Color(0xFF052E1C),
            primaryContainer = Color(0xFF145337),
            onPrimaryContainer = Color(0xFFD1FAE5),
            secondaryContainer = Color(0xFF1E3F32),
            onSecondaryContainer = Color(0xFFD1FAE5)
        )
    } else {
        LightColors.copy(
            primary = Color(0xFF1E6B47),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCEAE2),
            onPrimaryContainer = Color(0xFF0D3B26),
            secondaryContainer = Color(0xFFDCEAE2),
            onSecondaryContainer = Color(0xFF0D3B26),
            surfaceContainerLow = Color(0xFFF3F8F5),
            surfaceContainer = Color(0xFFEAF2ED)
        )
    }
    ThemeAccent.ROSE -> if (darkTheme) {
        DarkColors.copy(
            primary = Color(0xFFF9A8D4),
            onPrimary = Color(0xFF3B0722),
            primaryContainer = Color(0xFF6B163E),
            onPrimaryContainer = Color(0xFFFCE7F3),
            secondaryContainer = Color(0xFF4C1D36),
            onSecondaryContainer = Color(0xFFFCE7F3)
        )
    } else {
        LightColors.copy(
            primary = Color(0xFFAE1457),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFCE7F3),
            onPrimaryContainer = Color(0xFF500724),
            secondaryContainer = Color(0xFFFCE7F3),
            onSecondaryContainer = Color(0xFF500724),
            surfaceContainerLow = Color(0xFFFCF5F8),
            surfaceContainer = Color(0xFFF7EBF0)
        )
    }
    ThemeAccent.AMBER -> if (darkTheme) {
        DarkColors.copy(
            primary = Color(0xFFFCD34D),
            onPrimary = Color(0xFF2E1900),
            primaryContainer = Color(0xFF633B06),
            onPrimaryContainer = Color(0xFFFEF3C7),
            secondaryContainer = Color(0xFF4A3214),
            onSecondaryContainer = Color(0xFFFEF3C7)
        )
    } else {
        LightColors.copy(
            primary = Color(0xFF9A4406),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFEF3C7),
            onPrimaryContainer = Color(0xFF451A03),
            secondaryContainer = Color(0xFFFEF3C7),
            onSecondaryContainer = Color(0xFF451A03),
            surfaceContainerLow = Color(0xFFFCF9F2),
            surfaceContainer = Color(0xFFF6EFE2)
        )
    }
    ThemeAccent.VIOLET -> if (darkTheme) {
        DarkColors.copy(
            primary = Color(0xFFC4B5FD),
            onPrimary = Color(0xFF21104E),
            primaryContainer = Color(0xFF432382),
            onPrimaryContainer = Color(0xFFEDE9FE),
            secondaryContainer = Color(0xFF332557),
            onSecondaryContainer = Color(0xFFEDE9FE)
        )
    } else {
        LightColors.copy(
            primary = Color(0xFF5B21B6),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEDE9FE),
            onPrimaryContainer = Color(0xFF2E1065),
            secondaryContainer = Color(0xFFEDE9FE),
            onSecondaryContainer = Color(0xFF2E1065),
            surfaceContainerLow = Color(0xFFF7F5FC),
            surfaceContainer = Color(0xFFEEEAF8)
        )
    }
    ThemeAccent.MONO -> if (darkTheme) {
        DarkColors.copy(
            primary = Color(0xFFE2E8F0),
            onPrimary = Color(0xFF0F172A),
            primaryContainer = Color(0xFF334155),
            onPrimaryContainer = Color(0xFFF8FAFC),
            secondaryContainer = Color(0xFF273244),
            onSecondaryContainer = Color(0xFFF1F5F9)
        )
    } else {
        LightColors.copy(
            primary = Color(0xFF334155),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE2E8F0),
            onPrimaryContainer = Color(0xFF0F172A),
            secondaryContainer = Color(0xFFE2E8F0),
            onSecondaryContainer = Color(0xFF0F172A),
            surfaceContainerLow = Color(0xFFF6F7F9),
            surfaceContainer = Color(0xFFECEEF2)
        )
    }
}

/**
 * Pure Kotlin RGB <-> HSL seed color scheme generator (WCAG >= 4.5 contrast guaranteed).
 */
fun seedColorScheme(seedArgb: Int, darkTheme: Boolean): ColorScheme {
    if (seedArgb == 0) return if (darkTheme) DarkColors else LightColors
    val (h, sRaw, _) = argbToHsl(seedArgb)
    val s = if (sRaw < 0.08f) 0.05f else sRaw.coerceIn(0.38f, 0.78f)
    return if (darkTheme) {
        val primary = hslAtLuminance(h, s, 0.48f)
        val onPrimary = hslAtLuminance(h, s, 0.03f)
        val primaryContainer = hslAtLuminance(h, s * 0.85f, 0.08f)
        val onPrimaryContainer = hslAtLuminance(h, s, 0.86f)
        val secondaryContainer = hslAtLuminance(h, s * 0.65f, 0.07f)
        DarkColors.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onPrimaryContainer
        )
    } else {
        val primary = hslAtLuminance(h, s, 0.14f)
        val primaryContainer = hslAtLuminance(h, s * 0.80f, 0.84f)
        val onPrimaryContainer = hslAtLuminance(h, s, 0.05f)
        val surfaceLow = hslAtLuminance(h, s * 0.30f, 0.93f)
        val surfaceMid = hslAtLuminance(h, s * 0.35f, 0.88f)
        LightColors.copy(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onPrimaryContainer,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceMid
        )
    }
}

private fun hslAtLuminance(h: Float, s: Float, targetLum: Float): Color {
    var lo = 0f
    var hi = 1f
    var best = hslToColor(h, s, 0.5f)
    repeat(10) {
        val mid = (lo + hi) * 0.5f
        best = hslToColor(h, s, mid)
        if (best.luminance() < targetLum) lo = mid else hi = mid
    }
    return best
}

fun ColorScheme.withPureBlackIfEnabled(darkTheme: Boolean, pureBlack: Boolean): ColorScheme {
    if (!darkTheme || !pureBlack) return this
    return copy(
        background = Color.Black,
        surface = Color(0xFF070A0F),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0F17),
        surfaceContainer = Color(0xFF121824)
    )
}

/**
 * Extracts a representative dominant/vibrant seed color from a Bitmap without external dependencies.
 */
fun extractSeedColorArgb(bitmap: Bitmap): Int {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0xFF0369A1.toInt()
    val stepX = (w / 32).coerceAtLeast(1)
    val stepY = (h / 32).coerceAtLeast(1)

    val hueBuckets = FloatArray(36)
    val rSum = FloatArray(36)
    val gSum = FloatArray(36)
    val bSum = FloatArray(36)
    var fallbackR = 0L
    var fallbackG = 0L
    var fallbackB = 0L
    var count = 0

    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val px = bitmap.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            fallbackR += r
            fallbackG += g
            fallbackB += b
            count++

            val (hue, sat, lig) = rgbToHsl(r, g, b)
            if (sat >= 0.15f && lig in 0.15f..0.85f) {
                val bucket = ((hue / 10f).toInt()).coerceIn(0, 35)
                val weight = sat * (1f - abs(lig - 0.5f))
                hueBuckets[bucket] += weight
                rSum[bucket] += r * weight
                gSum[bucket] += g * weight
                bSum[bucket] += b * weight
            }
            x += stepX
        }
        y += stepY
    }

    var bestBucket = -1
    var bestScore = 0f
    for (i in 0 until 36) {
        if (hueBuckets[i] > bestScore) {
            bestScore = hueBuckets[i]
            bestBucket = i
        }
    }

    if (bestBucket >= 0 && bestScore > 0f) {
        val r = (rSum[bestBucket] / bestScore).roundToInt().coerceIn(0, 255)
        val g = (gSum[bestBucket] / bestScore).roundToInt().coerceIn(0, 255)
        val b = (bSum[bestBucket] / bestScore).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    if (count == 0) return 0xFF0369A1.toInt()
    val r = (fallbackR / count).toInt().coerceIn(0, 255)
    val g = (fallbackG / count).toInt().coerceIn(0, 255)
    val b = (fallbackB / count).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun argbToHsl(argb: Int): Triple<Float, Float, Float> =
    rgbToHsl((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

internal fun rgbToHsl(rInt: Int, gInt: Int, bInt: Int): Triple<Float, Float, Float> {
    val r = rInt / 255f
    val g = gInt / 255f
    val b = bInt / 255f
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val d = maxC - minC
    val l = (maxC + minC) / 2f
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(0.0001f)
    val hRaw = when (maxC) {
        r -> ((g - b) / d) % 6f
        g -> ((b - r) / d) + 2f
        else -> ((r - g) / d) + 4f
    } * 60f
    val h = if (hRaw < 0f) hRaw + 360f else hRaw
    return Triple(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

internal fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}

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
    dynamicColor: Boolean = false,
    themeAccent: ThemeAccent = ThemeAccent.SKY,
    pureBlackDark: Boolean = false,
    hasCustomBg: Boolean = false,
    cardOpacity: Float = 1f,
    extractColorFromBg: Boolean = false,
    customBgSeedArgb: Int = 0,
    hapticsEnabled: Boolean = true,
    hapticStrength: Float = 0.20f,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        hasCustomBg && extractColorFromBg && customBgSeedArgb != 0 -> {
            seedColorScheme(customBgSeedArgb, darkTheme)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            accentColorScheme(themeAccent, darkTheme)
        }
    }
    val colorScheme = baseScheme.withPureBlackIfEnabled(darkTheme, pureBlackDark)
    val effectiveStrength = if (hapticsEnabled) hapticStrength.coerceIn(0f, 1f) else 0f

    CompositionLocalProvider(
        LocalHasCustomBg provides hasCustomBg,
        LocalCardOpacity provides effectiveCardOpacity(hasCustomBg, cardOpacity),
        LocalHapticsEnabled provides (effectiveStrength > 0.01f),
        LocalHapticStrength provides effectiveStrength
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}

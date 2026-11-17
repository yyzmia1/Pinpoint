package com.pocketeverything.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.pocketeverything.app.data.ThemeAccent
import com.pocketeverything.app.ui.components.FileVisualFamily
import com.pocketeverything.app.ui.components.fileIconAccentColor
import com.pocketeverything.app.ui.components.fileIconPaperColor
import com.pocketeverything.app.ui.components.folderPalette
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.AppHaptics
import com.pocketeverything.app.ui.theme.DarkColors
import com.pocketeverything.app.ui.theme.LightColors
import com.pocketeverything.app.ui.theme.Slate950
import com.pocketeverything.app.ui.theme.accentColorScheme
import com.pocketeverything.app.ui.theme.effectiveCardOpacity
import com.pocketeverything.app.ui.theme.seedColorScheme
import com.pocketeverything.app.ui.theme.withPureBlackIfEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeContrastTest {

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun verifyScheme(name: String, scheme: ColorScheme) {
        fun assertReadable(label: String, fg: Color, bg: Color, minRatio: Double) {
            val ratio = contrastRatio(fg, bg)
            assertTrue(
                "[$name] $label contrast too low: %.2f (min %.2f)".format(ratio, minRatio),
                ratio >= minRatio
            )
        }

        // Primary CTA button (filled)
        assertReadable("onPrimary on primary", scheme.onPrimary, scheme.primary, 4.5)
        // Tonal button (FilledTonalButton uses secondaryContainer + onSecondaryContainer)
        assertReadable("onSecondaryContainer on secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer, 4.5)
        // Selected scope chips & pills
        assertReadable("onPrimaryContainer on primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, 4.5)
        // Outlined buttons & clickable text links
        assertReadable("primary text on background", scheme.primary, scheme.background, 4.5)
        assertReadable("primary text on surfaceContainer", scheme.primary, scheme.surfaceContainer, 4.5)
        assertReadable("outline border on background", scheme.outline, scheme.background, 2.0)
        // Text & secondary metadata
        assertReadable("onBackground on background", scheme.onBackground, scheme.background, 7.0)
        assertReadable("onSurface on surfaceContainer", scheme.onSurface, scheme.surfaceContainer, 7.0)
        assertReadable("onSurfaceVariant on background", scheme.onSurfaceVariant, scheme.background, 4.5)
        assertReadable("onSurfaceVariant on surfaceContainer", scheme.onSurfaceVariant, scheme.surfaceContainer, 4.5)
        // Search highlight legibility
        assertReadable("highlight text on Amber200", Slate950, Amber200, 10.0)
    }

    @Test
    fun `both light and dark color schemes meet WCAG contrast across all buttons and labels`() {
        verifyScheme("Light", LightColors)
        verifyScheme("Dark", DarkColors)
    }

    @Test
    fun `all preset theme accents and custom background seed schemes meet WCAG contrast`() {
        for (accent in ThemeAccent.entries) {
            verifyScheme("Light-${accent.name}", accentColorScheme(accent, darkTheme = false))
            val darkScheme = accentColorScheme(accent, darkTheme = true)
            verifyScheme("Dark-${accent.name}", darkScheme)
            verifyScheme("PureBlack-${accent.name}", darkScheme.withPureBlackIfEnabled(darkTheme = true, pureBlack = true))
        }

        val sampleSeeds = listOf(
            0xFFE11D48.toInt(),
            0xFF059669.toInt(),
            0xFFD97706.toInt(),
            0xFF4F46E5.toInt(),
            0xFF64748B.toInt()
        )
        for (seed in sampleSeeds) {
            verifyScheme("SeedLight-${Integer.toHexString(seed)}", seedColorScheme(seed, darkTheme = false))
            verifyScheme("SeedDark-${Integer.toHexString(seed)}", seedColorScheme(seed, darkTheme = true))
        }
    }

    @Test
    fun `all file and folder vector icons are crisp and legible in both light and dark modes`() {
        for (dark in listOf(false, true)) {
            val canvasBg = if (dark) DarkColors.background else LightColors.background
            val paper = fileIconPaperColor(dark)

            // Folder bright sunny gold must pop sharply against both day & night backgrounds
            val (folderBack, folderFrontTop, folderFrontBottom) = folderPalette(dark)
            assertTrue("folder back visible (dark=$dark)", contrastRatio(folderBack, canvasBg) >= if (dark) 6.0 else 1.5)
            assertTrue("folder light brightness sufficient", folderFrontTop.luminance() > 0.65f)
            assertTrue("folder bottom not dull dark amber", folderFrontBottom.luminance() > 0.55f)

            // Paper silhouette edge/body separates in dark mode against canvas
            if (dark) {
                assertTrue("dark paper card contrasts canvas", contrastRatio(paper, canvasBg) >= 1.2)
            }

            // Every single file family graphic glyph must contrast strongly against its paper card
            for (family in FileVisualFamily.entries) {
                if (family == FileVisualFamily.FOLDER) continue
                val accent = fileIconAccentColor(family, dark)
                val ratio = contrastRatio(accent, paper)
                assertTrue(
                    "File icon $family (dark=$dark) graphic contrast too low: %.2f".format(ratio),
                    ratio >= 3.5
                )
            }
        }
    }

    @Test
    fun `card opacity unlocks 0 to 100 range and haptics stays silent at explicit zero`() {
        assertEquals(0f, effectiveCardOpacity(hasCustomBg = true, rawOpacity = 0f), 0.0001f)
        assertEquals(0.15f, effectiveCardOpacity(hasCustomBg = true, rawOpacity = 0.15f), 0.0001f)
        assertEquals(0.80f, effectiveCardOpacity(hasCustomBg = true, rawOpacity = 0.80f), 0.0001f)
        assertEquals(1f, effectiveCardOpacity(hasCustomBg = false, rawOpacity = 0f), 0.0001f)

        val disabledHaptics = AppHaptics(view = null, vibrator = null, strength = 0f)
        // Explicit 0% slider drag must remain 0f (silent) even with forceEvenIfDisabled = true
        assertEquals(0f, disabledHaptics.resolveClickStrength(forceEvenIfDisabled = true, overrideStrength = 0f), 0.0001f)
        disabledHaptics.click(forceEvenIfDisabled = true, overrideStrength = 0f)
        // Explicit non-zero drag previews at requested strength
        assertEquals(0.4f, disabledHaptics.resolveClickStrength(forceEvenIfDisabled = true, overrideStrength = 0.4f), 0.0001f)
        // Toggle-on without override falls back to 20% feedback
        assertEquals(0.20f, disabledHaptics.resolveClickStrength(forceEvenIfDisabled = true), 0.0001f)
    }
}

package com.pocketeverything.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.pocketeverything.app.ui.components.FileVisualFamily
import com.pocketeverything.app.ui.components.fileIconAccentColor
import com.pocketeverything.app.ui.components.fileIconPaperColor
import com.pocketeverything.app.ui.components.folderPalette
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.DarkColors
import com.pocketeverything.app.ui.theme.LightColors
import com.pocketeverything.app.ui.theme.Slate950
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
}

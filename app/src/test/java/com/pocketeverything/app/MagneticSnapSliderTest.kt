package com.pocketeverything.app

import com.pocketeverything.app.ui.components.DocSizeSliderScale
import com.pocketeverything.app.ui.components.snapValueWithMagnetism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class MagneticSnapSliderTest {

    @Test
    fun `every single 1 MB from 1 to 150 is reachable while magnetic nodes snap within radius`() {
        val nodes150 = listOf(1f, 10f, 25f, 50f, 100f, 150f)
        val range = 1f..150f
        val radius = 0.85f

        // 1. Every integer MB from 1..150 is reachable
        for (mb in 1..150) {
            val target = mb.toFloat()
            val res = snapValueWithMagnetism(
                rawValue = target,
                valueRange = range,
                snapNodes = nodes150,
                snapRadius = radius,
                quantizeStep = 1f
            )
            assertEquals("Expected $mb MB to be reachable", mb, res.value.roundToInt())
            if (target in nodes150) {
                assertEquals(target, res.snappedNode)
            } else {
                assertNull("Expected $mb MB not to be swallowed by a node", res.snappedNode)
            }
        }

        // 2. Dragging near a node (within ±0.85 MB) magnetically snaps onto the node
        val near50Low = snapValueWithMagnetism(49.25f, range, nodes150, radius, 1f)
        assertEquals(50f, near50Low.value, 1e-4f)
        assertEquals(50f, near50Low.snappedNode)

        val near50High = snapValueWithMagnetism(50.78f, range, nodes150, radius, 1f)
        assertEquals(50f, near50High.value, 1e-4f)
        assertEquals(50f, near50High.snappedNode)

        // 3. Dragging outside radius stops on exact intermediate integer MB (e.g. 37 MB, 49 MB, 51 MB)
        val at37 = snapValueWithMagnetism(37.2f, range, nodes150, radius, 1f)
        assertEquals(37f, at37.value, 1e-4f)
        assertNull(at37.snappedNode)
    }

    @Test
    fun `every single 1 percent from 0 to 100 is reachable on percentage sliders while 10 percent nodes snap`() {
        val nodesPct = (0..10).map { it / 10f }
        val range = 0f..1f
        val radius = 0.008f

        for (pct in 0..100) {
            val raw = pct / 100f
            val res = snapValueWithMagnetism(
                rawValue = raw,
                valueRange = range,
                snapNodes = nodesPct,
                snapRadius = radius,
                quantizeStep = 0.01f
            )
            assertEquals("Expected $pct% to be reachable", pct, (res.value * 100).roundToInt())
            if (pct % 10 == 0) {
                assertNotNull("Expected $pct% to be a magnetic node", res.snappedNode)
            } else {
                assertNull("Expected intermediate $pct% not to be swallowed", res.snappedNode)
            }
        }

        // Magnetic capture near 30% (e.g. 29.3% -> 30%)
        val near30 = snapValueWithMagnetism(0.293f, range, nodesPct, radius, 0.01f)
        assertEquals(30, (near30.value * 100).roundToInt())
        assertEquals(0.3f, near30.snappedNode ?: -1f, 1e-4f)
    }

    @Test
    fun `document size slider scale maps every integer MB 1 to 1024 cleanly and supports unlimited endpoint`() {
        for (mb in 1..1024) {
            val f = DocSizeSliderScale.mbToFraction(mb)
            assertTrue("Expected $mb MB to be strictly inside finite zone, got $f", f in 0f..0.95f)
            assertEquals("Expected $mb MB round-trip", mb, DocSizeSliderScale.fractionToMb(f))
        }
        assertEquals(DocSizeSliderScale.UNLIMITED_FRACTION, DocSizeSliderScale.mbToFraction(DocSizeSliderScale.UNLIMITED_MB), 1e-5f)
        assertEquals(DocSizeSliderScale.UNLIMITED_MB, DocSizeSliderScale.fractionToMb(1.0f))
        assertEquals("不限", DocSizeSliderScale.formatMbLabel(0))
        assertEquals("500 MB", DocSizeSliderScale.formatMbLabel(500))
    }
}

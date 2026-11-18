package com.pocketeverything.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

data class MagneticSnapResult(
    val value: Float,
    val snappedNode: Float?
)

/**
 * Pure magnetic-snap calculation for continuous sliders:
 * - Within [snapRadius] of any node in [snapNodes], snaps directly to that node.
 * - Outside [snapRadius], quantizes by [quantizeStep] (e.g., `1f` for every 1 MB, `0.01f` for every 1%)
 *   so every intermediate unit remains reachable while nodes have a wider magnetic capture well.
 */
fun snapValueWithMagnetism(
    rawValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    snapNodes: List<Float>,
    snapRadius: Float,
    quantizeStep: Float = 0f
): MagneticSnapResult {
    val clamped = rawValue.coerceIn(valueRange.start, valueRange.endInclusive)
    var nearestNode: Float? = null
    var minDiff = Float.MAX_VALUE
    for (node in snapNodes) {
        if (node < valueRange.start || node > valueRange.endInclusive) continue
        val d = abs(clamped - node)
        if (d < minDiff) {
            minDiff = d
            nearestNode = node
        }
    }
    if (nearestNode != null && minDiff <= snapRadius) {
        return MagneticSnapResult(value = nearestNode, snappedNode = nearestNode)
    }

    val stepped = if (quantizeStep > 0f) {
        val steps = ((clamped - valueRange.start) / quantizeStep).roundToInt()
        (valueRange.start + steps * quantizeStep).coerceIn(valueRange.start, valueRange.endInclusive)
    } else {
        clamped
    }

    val exactHit = snapNodes.firstOrNull { abs(stepped - it) <= (if (quantizeStep > 0f) quantizeStep * 0.25f else 1e-4f) }
    return MagneticSnapResult(value = exactHit ?: stepped, snappedNode = exactHit)
}

data class SliderQuickNode(
    val value: Float,
    val label: String
)

/**
 * Piecewise continuous scale mapping track fraction `0f..1f` <-> integer MB limit
 * (`0` = unlimited at the far-right end stop, `1..1024` = finite MB).
 * Gives the common `1..100 MB` zone half the slider width so every 1 MB stays comfortable.
 */
object DocSizeSliderScale {
    const val UNLIMITED_MB: Int = 0
    const val MAX_FINITE_MB: Int = 1024
    const val UNLIMITED_FRACTION: Float = 1f

    private const val F_100 = 0.50f
    private const val F_500 = 0.82f
    private const val F_1024 = 0.94f

    fun mbToFraction(mb: Int): Float {
        if (mb <= 0) return UNLIMITED_FRACTION
        val c = mb.coerceIn(1, MAX_FINITE_MB)
        return when {
            c <= 100 -> ((c - 1) / 99f) * F_100
            c <= 500 -> F_100 + ((c - 100) / 400f) * (F_500 - F_100)
            else -> F_500 + ((c - 500) / 524f) * (F_1024 - F_500)
        }
    }

    fun fractionToMb(fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 0.965f) return UNLIMITED_MB
        return when {
            f <= F_100 -> (1 + (f / F_100) * 99f).roundToInt().coerceIn(1, 100)
            f <= F_500 -> (100 + ((f - F_100) / (F_500 - F_100)) * 400f).roundToInt().coerceIn(100, 500)
            else -> (500 + ((f - F_500) / (F_1024 - F_500)).coerceIn(0f, 1f) * 524f).roundToInt().coerceIn(500, MAX_FINITE_MB)
        }
    }

    fun formatMbLabel(mb: Int): String = if (mb <= 0) "不限" else "$mb MB"
}

/**
 * Continuous slider (`steps = 0`) with magnetic node snapping, visual track node markers,
 * and one-tap quick node pills so users can both snap to key milestones and freely pick any intermediate step.
 */
@Composable
fun MagneticSnapSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    snapNodes: List<Float>,
    snapRadius: Float,
    quantizeStep: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    quickNodes: List<SliderQuickNode> = emptyList(),
    onNodeSnapHaptic: (Float) -> Unit = {},
    onStepTickHaptic: ((Float) -> Unit)? = null,
    onValueChangeFinished: (Float) -> Unit = {},
    onValueBadgeClick: (() -> Unit)? = null
) {
    val tolerance = if (quantizeStep > 0f) quantizeStep * 0.35f else 0.008f
    var lastSnappedNode by remember {
        mutableStateOf(snapNodes.firstOrNull { abs(value - it) <= tolerance })
    }
    var lastQuantized by remember { mutableFloatStateOf(value) }
    var latestEmitted by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        lastSnappedNode = snapNodes.firstOrNull { abs(value - it) <= tolerance }
        lastQuantized = value
        latestEmitted = value
    }

    val isAtNode = snapNodes.any { abs(value - it) <= tolerance }
    val primaryColor = MaterialTheme.colorScheme.primary
    val nodeInactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isAtNode) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = if (onValueBadgeClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onValueBadgeClick() }
                } else {
                    Modifier
                }
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (isAtNode) MaterialTheme.colorScheme.onPrimaryContainer else primaryColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (icon != null) 28.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) {
            // Subtle magnetic node dots along the slider track
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(12.dp)
            ) {
                val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
                val y = size.height / 2f
                for (node in snapNodes) {
                    if (node < valueRange.start || node > valueRange.endInclusive) continue
                    val fraction = ((node - valueRange.start) / span).coerceIn(0f, 1f)
                    val x = fraction * size.width
                    val reached = value >= node - tolerance
                    val activeNode = abs(value - node) <= tolerance
                    drawCircle(
                        color = when {
                            activeNode -> primaryColor
                            reached -> primaryColor.copy(alpha = 0.55f)
                            else -> nodeInactiveColor
                        },
                        radius = if (activeNode) 4.2.dp.toPx() else 2.8.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = { raw ->
                    val res = snapValueWithMagnetism(
                        rawValue = raw,
                        valueRange = valueRange,
                        snapNodes = snapNodes,
                        snapRadius = snapRadius,
                        quantizeStep = quantizeStep
                    )
                    if (res.snappedNode != null && res.snappedNode != lastSnappedNode) {
                        lastSnappedNode = res.snappedNode
                        onNodeSnapHaptic(res.value)
                    } else if (res.snappedNode == null) {
                        lastSnappedNode = null
                        if (onStepTickHaptic != null && quantizeStep > 0f && abs(res.value - lastQuantized) >= quantizeStep * 3.9f) {
                            lastQuantized = res.value
                            onStepTickHaptic(res.value)
                        }
                    }
                    latestEmitted = res.value
                    if (abs(res.value - value) > 1e-4f) {
                        onValueChange(res.value)
                    }
                },
                valueRange = valueRange,
                steps = 0,
                onValueChangeFinished = { onValueChangeFinished(latestEmitted) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        val quickNode = quickNodes.minByOrNull { abs(it.value - value) }
        val activeQuick = quickNode != null && abs(quickNode.value - value) <= tolerance
        if (quickNodes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (icon != null) 28.dp else 0.dp, top = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (qn in quickNodes) {
                    val selected = activeQuick && qn == quickNode
                    Surface(
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        border = if (selected) {
                            BorderStroke(0.6.dp, primaryColor.copy(alpha = 0.5f))
                        } else null,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                lastSnappedNode = qn.value
                                latestEmitted = qn.value
                                onNodeSnapHaptic(qn.value)
                                onValueChange(qn.value)
                                onValueChangeFinished(qn.value)
                            }
                    ) {
                        Text(
                            text = qn.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontFeatureSettings = "tnum"
                            ),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

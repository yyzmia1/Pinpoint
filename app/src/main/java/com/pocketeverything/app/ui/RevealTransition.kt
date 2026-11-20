package com.pocketeverything.app.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import kotlin.math.hypot
import kotlin.math.max

@Stable
class RevealTransition(private val layer: GraphicsLayer) {
    var busy by mutableStateOf(false)
        private set
    private var oldFrame by mutableStateOf<ImageBitmap?>(null)
    private var revealOrigin = Offset.Zero
    private var reverse = false
    private val progress = Animatable(1f)

    suspend fun run(origin: Offset, returning: Boolean = false, duration: Int = 350, change: () -> Unit) {
        if (busy) return
        busy = true
        try {
            if (!ValueAnimator.areAnimatorsEnabled() || layer.size.width == 0) {
                change()
                return
            }
            val frame = runCatching { layer.toImageBitmap() }.getOrNull()
            if (frame == null) {
                change()
                return
            }
            revealOrigin = if (origin == Offset.Zero) Offset(layer.size.width / 2f, layer.size.height / 2f) else origin
            reverse = returning
            progress.snapTo(0f)
            oldFrame = frame
            change()
            withFrameNanos { }
            progress.animateTo(1f, tween(duration, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            oldFrame = null
            busy = false
        }
    }

    fun modifier(): Modifier = Modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        val frame = oldFrame
        if (frame == null) {
            drawLayer(layer)
        } else {
            val x = revealOrigin.x.coerceIn(0f, size.width)
            val y = revealOrigin.y.coerceIn(0f, size.height)
            val radius = hypot(max(x, size.width - x), max(y, size.height - y)) *
                if (reverse) 1f - progress.value else progress.value
            val mask = Path().apply { addOval(androidx.compose.ui.geometry.Rect(
                x - radius, y - radius, x + radius, y + radius)) }
            if (reverse) {
                drawLayer(layer)
                clipPath(mask) { drawImage(frame) }
            } else {
                drawImage(frame)
                clipPath(mask) { drawLayer(layer) }
            }

        }
    }
}

package com.pocketeverything.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class OnboardingTargets(
    val searchField: Rect? = null,
    val modePill: Rect? = null,
    val scopePills: Rect? = null,
    val helpButton: Rect? = null
)

private data class GuideStep(
    val title: String,
    val body: String,
    val target: Rect?
)

/**
 * Translucent spotlight onboarding overlay highlighting core search, mode, scope, and syntax controls.
 */
@Composable
fun WelcomeGuideOverlay(
    targets: OnboardingTargets,
    onFinish: () -> Unit
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val steps = listOf(
        GuideStep(
            title = "欢迎使用 Pinpoint",
            body = "离线秒搜设备上的文件名、目录以及 TXT / MD / DOCX / PDF 正文内容，支持任意中文片段毫秒命中。",
            target = targets.searchField
        ),
        GuideStep(
            title = "一次选定模式，日常全自动增量",
            body = "普通模式仅公开 /sdcard；Shizuku 可穿透 /Android/data 与 /obb；Root 支持全盘（默认跳过高噪 /data，按需勾选）。首次建库后，每次打开与开机都会自动增量更新全部用户目录。",
            target = targets.modePill
        ),
        GuideStep(
            title = "范围筛选与即时预览",
            body = "横向标签可筛选文件夹、文件或文档格式；点击任意文件或文件夹即可应用内快速预览，长按查看详细属性。",
            target = targets.scopePills
        ),
        GuideStep(
            title = "进阶语法与快捷按钮",
            body = "点击问号查看完整语法规范（逻辑运算、通配符、严格正则及属性限定）并可一键试搜。",
            target = targets.helpButton
        )
    )

    val current = steps[stepIndex]
    val density = LocalDensity.current
    val padPx = with(density) { 6.dp.toPx() }
    val radiusPx = with(density) { 14.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    val primaryColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { /* Intercept background taps during guide */ }
            }
    ) {
        val maxHeightPx = with(density) { maxHeight.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(Color.Black.copy(alpha = 0.68f))
            current.target?.let { rect ->
                val left = (rect.left - padPx).coerceAtLeast(0f)
                val top = (rect.top - padPx).coerceAtLeast(0f)
                val w = (rect.width + padPx * 2f)
                val h = (rect.height + padPx * 2f)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    blendMode = BlendMode.Clear
                )
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = strokePx)
                )
            }
        }

        val target = current.target
        val minTopPx = with(density) { 96.dp.toPx() }.roundToInt()
        val maxBottomTopPx = (maxHeightPx - with(density) { 260.dp.toPx() }).roundToInt().coerceAtLeast(minTopPx)
        val placeBelow = target == null || target.bottom < maxHeightPx * 0.55f
        val topOffsetPx = if (target == null) {
            (maxHeightPx * 0.34f).roundToInt().coerceIn(minTopPx, maxBottomTopPx)
        } else if (placeBelow) {
            (target.bottom + with(density) { 16.dp.toPx() }).roundToInt().coerceIn(minTopPx, maxBottomTopPx)
        } else {
            (target.top - with(density) { 220.dp.toPx() }).roundToInt().coerceIn(minTopPx, maxBottomTopPx)
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .offset { IntOffset(0, topOffsetPx) }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        steps.indices.forEach { idx ->
                            val activeDot = idx == stepIndex
                            Box(
                                modifier = Modifier
                                    .size(width = if (activeDot) 14.dp else 6.dp, height = 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (activeDot) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = current.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onFinish) {
                        Text("跳过")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (stepIndex > 0) {
                            TextButton(onClick = { stepIndex-- }) {
                                Text("上一步")
                            }
                        }
                        Button(
                            onClick = {
                                if (stepIndex < steps.lastIndex) {
                                    stepIndex++
                                } else {
                                    onFinish()
                                }
                            }
                        ) {
                            Text(if (stepIndex < steps.lastIndex) "下一步" else "开始使用")
                        }
                    }
                }
            }
        }
    }
}

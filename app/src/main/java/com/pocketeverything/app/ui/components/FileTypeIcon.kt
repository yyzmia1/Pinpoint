package com.pocketeverything.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.ui.theme.isLightScheme
import java.util.Locale

enum class FileVisualFamily {
    FOLDER,
    PDF,
    WORD,
    SHEET,
    SLIDES,
    TEXT,
    CODE,
    IMAGE,
    VIDEO,
    AUDIO,
    APK,
    ARCHIVE,
    GENERIC
}

/**
 * Cohesive app emblem mirroring the adaptive launcher icon optics (folded sheet + precision lens).
 */
@Composable
fun BrandMarkIcon(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val radius = w * 0.27f

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF0A1120), Color(0xFF172D5A)),
                start = Offset.Zero,
                end = Offset(w, h)
            ),
            cornerRadius = CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = Color(0xFF38BDF8).copy(alpha = 0.28f),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 1.dp.toPx())
        )

        val docLeft = w * 0.23f
        val docTop = h * 0.19f
        val docRight = w * 0.67f
        val docBottom = h * 0.76f
        val fold = w * 0.13f
        val docPath = Path().apply {
            moveTo(docLeft, docTop)
            lineTo(docRight - fold, docTop)
            lineTo(docRight, docTop + fold)
            lineTo(docRight, docBottom)
            lineTo(docLeft, docBottom)
            close()
        }
        drawPath(path = docPath, color = Color(0xFFF1F5F9))

        val cx = w * 0.60f
        val cy = h * 0.56f
        val lensR = w * 0.17f
        drawCircle(
            color = Color(0xFF082F49),
            radius = lensR,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color(0xFF38BDF8),
            radius = lensR,
            center = Offset(cx, cy),
            style = Stroke(width = w * 0.065f)
        )
        drawLine(
            color = Color(0xFF38BDF8),
            start = Offset(cx + lensR * 0.72f, cy + lensR * 0.72f),
            end = Offset(w * 0.82f, h * 0.79f),
            strokeWidth = w * 0.085f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Pure geometric, text-free file & folder icon set:
 * - Folders use a stepped-tab two-layer amber silhouette.
 * - Files share a unified folded-corner sheet silhouette with pure symbolic iconography.
 */
@Composable
fun FileTypeIcon(
    isDir: Boolean,
    ext: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    val dark = !MaterialTheme.colorScheme.isLightScheme
    val family = classifyVisualFamily(isDir, ext)

    if (family == FileVisualFamily.FOLDER) {
        FolderIcon(dark = dark, modifier = modifier.size(size))
        return
    }

    val paperBg = fileIconPaperColor(dark)
    val paperStroke = if (dark) Color(0xFF364863) else Color(0xFFDCE4EE)
    val foldFill = if (dark) Color(0xFF32435D) else Color(0xFFE2E8F0)
    val neutralLine = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accent = fileIconAccentColor(family, dark)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            val left = w * 0.15f
            val right = w * 0.85f
            val top = h * 0.07f
            val bottom = h * 0.93f
            val cornerR = w * 0.10f
            val foldSize = w * 0.21f

            val sheet = Path().apply {
                moveTo(left + cornerR, top)
                lineTo(right - foldSize, top)
                lineTo(right, top + foldSize)
                lineTo(right, bottom - cornerR)
                quadraticTo(right, bottom, right - cornerR, bottom)
                lineTo(left + cornerR, bottom)
                quadraticTo(left, bottom, left, bottom - cornerR)
                lineTo(left, top + cornerR)
                quadraticTo(left, top, left + cornerR, top)
                close()
            }

            drawPath(path = sheet, color = paperBg)
            drawPath(
                path = sheet,
                color = paperStroke,
                style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round)
            )

            val dogEar = Path().apply {
                moveTo(right - foldSize, top)
                lineTo(right, top + foldSize)
                lineTo(right - foldSize, top + foldSize)
                close()
            }
            drawPath(path = dogEar, color = foldFill)

            when (family) {
                FileVisualFamily.PDF -> {
                    // Iconic continuous PDF ribbon curve — pure symbol, zero letters
                    val ribbon = Path().apply {
                        moveTo(w * 0.50f, h * 0.34f)
                        cubicTo(w * 0.47f, h * 0.51f, w * 0.40f, h * 0.68f, w * 0.32f, h * 0.72f)
                        cubicTo(w * 0.28f, h * 0.75f, w * 0.34f, h * 0.79f, w * 0.41f, h * 0.71f)
                        cubicTo(w * 0.49f, h * 0.62f, w * 0.64f, h * 0.58f, w * 0.69f, h * 0.62f)
                        cubicTo(w * 0.73f, h * 0.66f, w * 0.68f, h * 0.72f, w * 0.60f, h * 0.65f)
                        cubicTo(w * 0.52f, h * 0.57f, w * 0.53f, h * 0.40f, w * 0.50f, h * 0.34f)
                    }
                    drawPath(
                        path = ribbon,
                        color = accent,
                        style = Stroke(
                            width = w * 0.062f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                FileVisualFamily.WORD -> {
                    // Cobalt document margin bar + crisp prose alignment bars
                    val sw = w * 0.06f
                    drawLine(
                        color = accent,
                        start = Offset(w * 0.27f, h * 0.34f),
                        end = Offset(w * 0.27f, h * 0.73f),
                        strokeWidth = w * 0.07f,
                        cap = StrokeCap.Round
                    )
                    drawLine(accent, Offset(w * 0.39f, h * 0.38f), Offset(w * 0.63f, h * 0.38f), sw, StrokeCap.Round)
                    drawLine(accent.copy(alpha = 0.78f), Offset(w * 0.39f, h * 0.50f), Offset(w * 0.71f, h * 0.50f), sw, StrokeCap.Round)
                    drawLine(accent.copy(alpha = 0.78f), Offset(w * 0.39f, h * 0.62f), Offset(w * 0.71f, h * 0.62f), sw, StrokeCap.Round)
                    drawLine(accent.copy(alpha = 0.55f), Offset(w * 0.39f, h * 0.73f), Offset(w * 0.58f, h * 0.73f), sw, StrokeCap.Round)
                }

                FileVisualFamily.SHEET -> {
                    // 2x3 miniature spreadsheet grid
                    val gLeft = w * 0.27f
                    val gRight = w * 0.73f
                    val gTop = h * 0.37f
                    val gBottom = h * 0.75f
                    val gw = gRight - gLeft
                    val gh = gBottom - gTop
                    val cr = CornerRadius(w * 0.04f, w * 0.04f)
                    // Header bar tint
                    drawRoundRect(
                        color = accent.copy(alpha = if (dark) 0.28f else 0.18f),
                        topLeft = Offset(gLeft, gTop),
                        size = Size(gw, gh * 0.34f),
                        cornerRadius = cr
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(gLeft, gTop),
                        size = Size(gw, gh),
                        cornerRadius = cr,
                        style = Stroke(width = w * 0.052f)
                    )
                    drawLine(accent, Offset(gLeft, gTop + gh * 0.34f), Offset(gRight, gTop + gh * 0.34f), w * 0.045f)
                    drawLine(accent, Offset(gLeft, gTop + gh * 0.67f), Offset(gRight, gTop + gh * 0.67f), w * 0.045f)
                    drawLine(accent, Offset(gLeft + gw * 0.46f, gTop), Offset(gLeft + gw * 0.46f, gBottom), w * 0.045f)
                }

                FileVisualFamily.SLIDES -> {
                    // Minimal presentation screen + stand
                    val sLeft = w * 0.26f
                    val sRight = w * 0.74f
                    val sTop = h * 0.36f
                    val sBottom = h * 0.67f
                    drawRoundRect(
                        color = accent.copy(alpha = if (dark) 0.22f else 0.13f),
                        topLeft = Offset(sLeft, sTop),
                        size = Size(sRight - sLeft, sBottom - sTop),
                        cornerRadius = CornerRadius(w * 0.05f, w * 0.05f)
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(sLeft, sTop),
                        size = Size(sRight - sLeft, sBottom - sTop),
                        cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
                        style = Stroke(width = w * 0.052f)
                    )
                    // Play / slide chart cue inside frame
                    val tri = Path().apply {
                        moveTo(w * 0.45f, h * 0.45f)
                        lineTo(w * 0.58f, h * 0.515f)
                        lineTo(w * 0.45f, h * 0.58f)
                        close()
                    }
                    drawPath(path = tri, color = accent)
                    // Stand legs
                    drawLine(accent, Offset(w * 0.50f, sBottom), Offset(w * 0.50f, h * 0.75f), w * 0.05f, StrokeCap.Round)
                    drawLine(accent, Offset(w * 0.37f, h * 0.77f), Offset(w * 0.63f, h * 0.77f), w * 0.05f, StrokeCap.Round)
                }

                FileVisualFamily.TEXT -> {
                    val sw = w * 0.058f
                    drawLine(accent, Offset(w * 0.27f, h * 0.36f), Offset(w * 0.53f, h * 0.36f), sw * 1.15f, StrokeCap.Round)
                    drawLine(neutralLine, Offset(w * 0.27f, h * 0.49f), Offset(w * 0.71f, h * 0.49f), sw, StrokeCap.Round)
                    drawLine(neutralLine, Offset(w * 0.27f, h * 0.61f), Offset(w * 0.71f, h * 0.61f), sw, StrokeCap.Round)
                    drawLine(neutralLine, Offset(w * 0.27f, h * 0.73f), Offset(w * 0.57f, h * 0.73f), sw, StrokeCap.Round)
                }

                FileVisualFamily.CODE -> {
                    val strokeW = w * 0.058f
                    val yMid = h * 0.53f
                    val dy = h * 0.11f
                    val dx = w * 0.085f
                    drawLine(accent, Offset(w * 0.38f, yMid - dy), Offset(w * 0.38f - dx, yMid), strokeW, StrokeCap.Round)
                    drawLine(accent, Offset(w * 0.38f - dx, yMid), Offset(w * 0.38f, yMid + dy), strokeW, StrokeCap.Round)
                    drawLine(accent, Offset(w * 0.62f, yMid - dy), Offset(w * 0.62f + dx, yMid), strokeW, StrokeCap.Round)
                    drawLine(accent, Offset(w * 0.62f + dx, yMid), Offset(w * 0.62f, yMid + dy), strokeW, StrokeCap.Round)
                    drawLine(accent.copy(alpha = 0.75f), Offset(w * 0.53f, yMid - dy * 1.15f), Offset(w * 0.47f, yMid + dy * 1.15f), strokeW * 0.85f, StrokeCap.Round)
                }

                FileVisualFamily.IMAGE -> {
                    val frameTop = h * 0.34f
                    val frameBottom = h * 0.74f
                    val frameLeft = w * 0.25f
                    val frameRight = w * 0.75f
                    drawRoundRect(
                        color = accent.copy(alpha = if (dark) 0.22f else 0.13f),
                        topLeft = Offset(frameLeft, frameTop),
                        size = Size(frameRight - frameLeft, frameBottom - frameTop),
                        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
                    )
                    drawCircle(
                        color = accent.copy(alpha = 0.88f),
                        radius = w * 0.055f,
                        center = Offset(w * 0.37f, h * 0.46f)
                    )
                    val mountain = Path().apply {
                        moveTo(frameLeft + w * 0.04f, frameBottom - h * 0.03f)
                        lineTo(w * 0.47f, h * 0.54f)
                        lineTo(w * 0.57f, h * 0.64f)
                        lineTo(w * 0.64f, h * 0.57f)
                        lineTo(frameRight - w * 0.04f, frameBottom - h * 0.03f)
                        close()
                    }
                    drawPath(path = mountain, color = accent.copy(alpha = 0.88f))
                }

                FileVisualFamily.VIDEO -> {
                    val cx = w * 0.50f
                    val cy = h * 0.55f
                    drawCircle(
                        color = accent.copy(alpha = if (dark) 0.22f else 0.14f),
                        radius = w * 0.21f,
                        center = Offset(cx, cy)
                    )
                    val play = Path().apply {
                        moveTo(cx - w * 0.06f, cy - h * 0.095f)
                        lineTo(cx + w * 0.105f, cy)
                        lineTo(cx - w * 0.06f, cy + h * 0.095f)
                        close()
                    }
                    drawPath(path = play, color = accent)
                }

                FileVisualFamily.AUDIO -> {
                    val bars = listOf(0.16f, 0.30f, 0.38f, 0.22f)
                    val startX = w * 0.33f
                    val step = w * 0.11f
                    bars.forEachIndexed { idx, frac ->
                        val bx = startX + idx * step
                        val half = h * frac * 0.5f
                        drawLine(
                            color = accent,
                            start = Offset(bx, h * 0.55f - half),
                            end = Offset(bx, h * 0.55f + half),
                            strokeWidth = w * 0.068f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                FileVisualFamily.APK -> {
                    // Pure Android silhouette dome + two antennae + eye cutouts (zero text)
                    val cx = w * 0.50f
                    val domeBaseY = h * 0.72f
                    val domeR = w * 0.22f
                    drawLine(
                        color = accent,
                        start = Offset(cx - domeR * 0.45f, domeBaseY - domeR * 0.86f),
                        end = Offset(cx - domeR * 0.78f, domeBaseY - domeR * 1.28f),
                        strokeWidth = w * 0.048f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = accent,
                        start = Offset(cx + domeR * 0.45f, domeBaseY - domeR * 0.86f),
                        end = Offset(cx + domeR * 0.78f, domeBaseY - domeR * 1.28f),
                        strokeWidth = w * 0.048f,
                        cap = StrokeCap.Round
                    )
                    drawArc(
                        color = accent,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = Offset(cx - domeR, domeBaseY - domeR),
                        size = Size(domeR * 2f, domeR * 2f)
                    )
                    val eyeY = domeBaseY - domeR * 0.38f
                    drawCircle(paperBg, w * 0.032f, Offset(cx - domeR * 0.42f, eyeY))
                    drawCircle(paperBg, w * 0.032f, Offset(cx + domeR * 0.42f, eyeY))
                }

                FileVisualFamily.ARCHIVE -> {
                    // Clean vertical zipper track + pull slider
                    val zx = w * 0.46f
                    val toothW = w * 0.11f
                    val toothStroke = w * 0.052f
                    // Slider tab near top
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(zx - w * 0.05f, h * 0.27f),
                        size = Size(w * 0.15f, h * 0.14f),
                        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
                    )
                    val ys = listOf(0.47f, 0.55f, 0.63f, 0.71f, 0.79f)
                    ys.forEachIndexed { idx, yf ->
                        val offset = if (idx % 2 == 0) -w * 0.02f else w * 0.05f
                        drawLine(
                            color = accent,
                            start = Offset(zx + offset, h * yf),
                            end = Offset(zx + offset + toothW, h * yf),
                            strokeWidth = toothStroke,
                            cap = StrokeCap.Round
                        )
                    }
                }

                FileVisualFamily.GENERIC, FileVisualFamily.FOLDER -> {
                    val sw = w * 0.055f
                    drawLine(neutralLine, Offset(w * 0.27f, h * 0.38f), Offset(w * 0.56f, h * 0.38f), sw, StrokeCap.Round)
                    drawLine(neutralLine, Offset(w * 0.27f, h * 0.52f), Offset(w * 0.70f, h * 0.52f), sw, StrokeCap.Round)
                    drawLine(neutralLine, Offset(w * 0.27f, h * 0.66f), Offset(w * 0.61f, h * 0.66f), sw, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun FolderIcon(dark: Boolean, modifier: Modifier = Modifier) {
    // Bright, soft, airy Windows 11 / HyperOS / OneDrive style honey gold (never heavy dark amber)
    val (backColor, frontTop, frontBottom) = folderPalette(dark)

    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height

        val left = w * 0.08f
        val right = w * 0.92f
        val top = h * 0.16f
        val bottom = h * 0.85f
        val r = w * 0.11f

        val backPath = Path().apply {
            moveTo(left + r, top)
            lineTo(w * 0.42f, top)
            lineTo(w * 0.50f, h * 0.27f)
            lineTo(right - r, h * 0.27f)
            quadraticTo(right, h * 0.27f, right, h * 0.27f + r)
            lineTo(right, bottom - r)
            quadraticTo(right, bottom, right - r, bottom)
            lineTo(left + r, bottom)
            quadraticTo(left, bottom, left, bottom - r)
            lineTo(left, top + r)
            quadraticTo(left, top, left + r, top)
            close()
        }
        drawPath(path = backPath, color = backColor)

        val pocketTop = h * 0.31f
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(frontTop, frontBottom),
                startY = pocketTop,
                endY = bottom
            ),
            topLeft = Offset(left, pocketTop),
            size = Size(right - left, bottom - pocketTop),
            cornerRadius = CornerRadius(r, r)
        )

        drawLine(
            color = Color(0xFFFFF8DC).copy(alpha = if (dark) 0.42f else 0.55f),
            start = Offset(left + r, pocketTop + 1.dp.toPx()),
            end = Offset(right - r, pocketTop + 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

fun fileIconPaperColor(dark: Boolean): Color = if (dark) Color(0xFF1E293B) else Color.White

fun folderPalette(dark: Boolean): Triple<Color, Color, Color> = if (dark) {
    Triple(Color(0xFFE5B13E), Color(0xFFFFE57F), Color(0xFFFFD14D))
} else {
    Triple(Color(0xFFF3BA44), Color(0xFFFFE682), Color(0xFFFFD04E))
}

fun classifyVisualFamily(isDir: Boolean, ext: String): FileVisualFamily {
    if (isDir) return FileVisualFamily.FOLDER
    return when (ext.lowercase(Locale.US)) {
        "pdf" -> FileVisualFamily.PDF
        "doc", "docx", "rtf" -> FileVisualFamily.WORD
        "xls", "xlsx", "csv", "tsv" -> FileVisualFamily.SHEET
        "ppt", "pptx" -> FileVisualFamily.SLIDES
        "txt", "md", "markdown", "log", "srt", "ass", "vtt", "lrc" -> FileVisualFamily.TEXT
        "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties",
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "htm", "css",
        "sh", "c", "cpp", "h", "rs", "go", "sql", "gradle" -> FileVisualFamily.CODE
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "ico" -> FileVisualFamily.IMAGE
        "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v" -> FileVisualFamily.VIDEO
        "mp3", "flac", "wav", "m4a", "ogg", "aac", "opus" -> FileVisualFamily.AUDIO
        "apk", "apks", "xapk", "aab" -> FileVisualFamily.APK
        "zip", "rar", "7z", "tar", "gz", "xz", "jar" -> FileVisualFamily.ARCHIVE
        else -> FileVisualFamily.GENERIC
    }
}

fun fileIconAccentColor(family: FileVisualFamily, dark: Boolean): Color {
    fun pick(lightHex: Long, darkHex: Long) = Color(if (dark) darkHex else lightHex)
    return when (family) {
        FileVisualFamily.FOLDER -> folderPalette(dark).second
        FileVisualFamily.PDF -> pick(0xFFD92D20, 0xFFFB7185)
        FileVisualFamily.WORD -> pick(0xFF2563EB, 0xFF60A5FA)
        FileVisualFamily.SHEET -> pick(0xFF047857, 0xFF34D399)
        FileVisualFamily.SLIDES -> pick(0xFFC2410C, 0xFFFB923C)
        FileVisualFamily.TEXT -> pick(0xFF0F766E, 0xFF2DD4BF)
        FileVisualFamily.CODE -> pick(0xFF4338CA, 0xFF818CF8)
        FileVisualFamily.IMAGE -> pick(0xFF4F46E5, 0xFFA5B4FC)
        FileVisualFamily.VIDEO -> pick(0xFF0369A1, 0xFF38BDF8)
        FileVisualFamily.AUDIO -> pick(0xFF0F766E, 0xFF2DD4BF)
        FileVisualFamily.APK -> pick(0xFF4D7C0F, 0xFFA3E635)
        FileVisualFamily.ARCHIVE -> pick(0xFFB45309, 0xFFFCD34D)
        FileVisualFamily.GENERIC -> pick(0xFF475569, 0xFF94A3B8)
    }
}

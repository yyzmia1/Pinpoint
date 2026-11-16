package com.pocketeverything.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private enum class FileVisualFamily {
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

        // Inner folded document card
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
        drawPath(
            path = docPath,
            color = Color(0xFFF1F5F9)
        )

        // Sky-blue precision lens + handle
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
 * Big-tech inspired filesystem icon set:
 * - Folders use a classic stepped-tab two-layer silhouette.
 * - Files share a unified folded-corner sheet silhouette with restrained category accents.
 */
@Composable
fun FileTypeIcon(
    isDir: Boolean,
    ext: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val family = classifyVisualFamily(isDir, ext)

    if (family == FileVisualFamily.FOLDER) {
        FolderIcon(dark = dark, modifier = modifier.size(size))
        return
    }

    val paperBg = if (dark) Color(0xFF172033) else Color(0xFFFFFFFF)
    val paperStroke = if (dark) Color(0xFF2D3A54) else Color(0xFFE2E8F0)
    val foldFill = if (dark) Color(0xFF27344D) else Color(0xFFE2E8F0)
    val neutralLine = if (dark) Color(0xFF475569) else Color(0xFFCBD5E1)
    val (accent, badgeLabel) = accentAndLabelFor(family, ext, dark)

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

            // Folded page polygon with rounded outer corners and crisp dog-ear top-right
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

            // Dog-ear triangle
            val dogEar = Path().apply {
                moveTo(right - foldSize, top)
                lineTo(right, top + foldSize)
                lineTo(right - foldSize, top + foldSize)
                close()
            }
            drawPath(path = dogEar, color = foldFill)

            when (family) {
                FileVisualFamily.IMAGE -> {
                    // Minimal gallery horizon inside sheet
                    val frameTop = h * 0.30f
                    val frameBottom = h * 0.66f
                    val frameLeft = w * 0.26f
                    val frameRight = w * 0.74f
                    drawRoundRect(
                        color = accent.copy(alpha = if (dark) 0.22f else 0.13f),
                        topLeft = Offset(frameLeft, frameTop),
                        size = Size(frameRight - frameLeft, frameBottom - frameTop),
                        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
                    )
                    drawCircle(
                        color = accent.copy(alpha = 0.85f),
                        radius = w * 0.055f,
                        center = Offset(w * 0.38f, h * 0.41f)
                    )
                    val mountain = Path().apply {
                        moveTo(frameLeft + w * 0.04f, frameBottom - h * 0.03f)
                        lineTo(w * 0.48f, h * 0.48f)
                        lineTo(w * 0.58f, h * 0.57f)
                        lineTo(w * 0.65f, h * 0.50f)
                        lineTo(frameRight - w * 0.04f, frameBottom - h * 0.03f)
                        close()
                    }
                    drawPath(path = mountain, color = accent.copy(alpha = 0.85f))
                }

                FileVisualFamily.VIDEO -> {
                    val cx = w * 0.50f
                    val cy = h * 0.50f
                    drawCircle(
                        color = accent.copy(alpha = if (dark) 0.22f else 0.14f),
                        radius = w * 0.20f,
                        center = Offset(cx, cy)
                    )
                    val play = Path().apply {
                        moveTo(cx - w * 0.06f, cy - h * 0.09f)
                        lineTo(cx + w * 0.10f, cy)
                        lineTo(cx - w * 0.06f, cy + h * 0.09f)
                        close()
                    }
                    drawPath(path = play, color = accent)
                }

                FileVisualFamily.AUDIO -> {
                    val bars = listOf(0.14f, 0.26f, 0.34f, 0.20f)
                    val startX = w * 0.33f
                    val step = w * 0.11f
                    bars.forEachIndexed { idx, frac ->
                        val bx = startX + idx * step
                        val half = h * frac * 0.5f
                        drawLine(
                            color = accent,
                            start = Offset(bx, h * 0.50f - half),
                            end = Offset(bx, h * 0.50f + half),
                            strokeWidth = w * 0.065f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                FileVisualFamily.CODE -> {
                    val strokeW = w * 0.058f
                    val yMid = h * 0.49f
                    val dy = h * 0.10f
                    val dx = w * 0.08f
                    // Left bracket <
                    drawLine(accent, Offset(w * 0.39f, yMid - dy), Offset(w * 0.39f - dx, yMid), strokeW, StrokeCap.Round)
                    drawLine(accent, Offset(w * 0.39f - dx, yMid), Offset(w * 0.39f, yMid + dy), strokeW, StrokeCap.Round)
                    // Right bracket >
                    drawLine(accent, Offset(w * 0.61f, yMid - dy), Offset(w * 0.61f + dx, yMid), strokeW, StrokeCap.Round)
                    drawLine(accent, Offset(w * 0.61f + dx, yMid), Offset(w * 0.61f, yMid + dy), strokeW, StrokeCap.Round)
                    // Slash /
                    drawLine(accent.copy(alpha = 0.72f), Offset(w * 0.53f, yMid - dy * 1.15f), Offset(w * 0.47f, yMid + dy * 1.15f), strokeW * 0.85f, StrokeCap.Round)
                }

                else -> {
                    // Two calm horizontal document text strokes above format pill (or subtle centered rules)
                    val lineStroke = w * 0.055f
                    drawLine(
                        color = neutralLine,
                        start = Offset(w * 0.27f, h * 0.30f),
                        end = Offset(w * 0.56f, h * 0.30f),
                        strokeWidth = lineStroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = neutralLine,
                        start = Offset(w * 0.27f, h * 0.42f),
                        end = Offset(w * 0.69f, h * 0.42f),
                        strokeWidth = lineStroke,
                        cap = StrokeCap.Round
                    )
                }
            }

            if (badgeLabel != null) {
                // Restrained integrated format ribbon bar at lower portion of the page
                val pillLeft = w * 0.21f
                val pillTop = h * 0.57f
                val pillW = w * 0.58f
                val pillH = h * 0.24f
                drawRoundRect(
                    color = accent.copy(alpha = if (dark) 0.24f else 0.14f),
                    topLeft = Offset(pillLeft, pillTop),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(w * 0.07f, w * 0.07f)
                )
            } else if (family == FileVisualFamily.GENERIC) {
                drawLine(
                    color = neutralLine,
                    start = Offset(w * 0.27f, h * 0.54f),
                    end = Offset(w * 0.60f, h * 0.54f),
                    strokeWidth = w * 0.055f,
                    cap = StrokeCap.Round
                )
            }
        }

        if (badgeLabel != null) {
            Text(
                text = badgeLabel,
                fontSize = if (badgeLabel.length >= 4) 6.5.sp else 7.5.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp,
                maxLines = 1,
                softWrap = false,
                color = accent,
                modifier = Modifier.padding(top = size * 0.38f)
            )
        }
    }
}

@Composable
private fun FolderIcon(dark: Boolean, modifier: Modifier = Modifier) {
    val backColor = if (dark) Color(0xFFD97706) else Color(0xFFE5951A)
    val frontTop = if (dark) Color(0xFFF59E0B) else Color(0xFFFBBF24)
    val frontBottom = if (dark) Color(0xFFD97706) else Color(0xFFF59E0B)

    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height

        val left = w * 0.08f
        val right = w * 0.92f
        val top = h * 0.16f
        val bottom = h * 0.85f
        val r = w * 0.11f

        // Folder back sheet with stepped top-left tab
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

        // Front folder pocket with rounded top
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

        // Subtle crisp top sheen
        drawLine(
            color = Color.White.copy(alpha = if (dark) 0.22f else 0.38f),
            start = Offset(left + r, pocketTop + 1.dp.toPx()),
            end = Offset(right - r, pocketTop + 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun classifyVisualFamily(isDir: Boolean, ext: String): FileVisualFamily {
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

private fun accentAndLabelFor(
    family: FileVisualFamily,
    ext: String,
    dark: Boolean
): Pair<Color, String?> {
    fun pick(lightHex: Long, darkHex: Long) = Color(if (dark) darkHex else lightHex)
    val upper = ext.uppercase(Locale.US)
    return when (family) {
        FileVisualFamily.PDF -> pick(0xFFD92D20, 0xFFF87171) to "PDF"
        FileVisualFamily.WORD -> pick(0xFF2563EB, 0xFF60A5FA) to if (upper == "DOC") "DOC" else "DOCX"
        FileVisualFamily.SHEET -> pick(0xFF059669, 0xFF34D399) to if (upper == "CSV" || upper == "TSV") upper else "XLS"
        FileVisualFamily.SLIDES -> pick(0xFFD97706, 0xFFFB923C) to "PPT"
        FileVisualFamily.TEXT -> pick(0xFF0F766E, 0xFF2DD4BF) to when (upper) {
            "MD", "MARKDOWN" -> "MD"
            "LOG" -> "LOG"
            else -> "TXT"
        }
        FileVisualFamily.CODE -> pick(0xFF4F46E5, 0xFF818CF8) to null
        FileVisualFamily.IMAGE -> pick(0xFF6366F1, 0xFFA5B4FC) to null
        FileVisualFamily.VIDEO -> pick(0xFF0284C7, 0xFF38BDF8) to null
        FileVisualFamily.AUDIO -> pick(0xFF0D9488, 0xFF2DD4BF) to null
        FileVisualFamily.APK -> pick(0xFF4D7C0F, 0xFFA3E635) to "APK"
        FileVisualFamily.ARCHIVE -> pick(0xFFB45309, 0xFFFBBF24) to if (upper.length <= 3) upper else "ZIP"
        else -> {
            val tag = upper.takeIf { it.length in 1..4 }
            pick(0xFF64748B, 0xFF94A3B8) to tag
        }
    }
}

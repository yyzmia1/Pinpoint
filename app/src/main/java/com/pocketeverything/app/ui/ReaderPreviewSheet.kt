package com.pocketeverything.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.ui.components.FileTypeIcon
import com.pocketeverything.app.ui.preview.FilePreviewLoader
import com.pocketeverything.app.ui.preview.PreviewContent
import com.pocketeverything.app.ui.preview.PreviewPayload
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Slate950
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPreviewSheet(
    payload: PreviewPayload?,
    loadingItem: SearchResultItem?,
    onDismiss: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenChild: (SearchResultItem) -> Unit,
    onRefreshFolder: (SearchResultItem) -> Unit,
    onOpenExternal: (SearchResultItem) -> Unit,
    onCopyPath: (SearchResultItem) -> Unit
) {
    val activeItem = loadingItem ?: payload?.item ?: return
    val isLoading = loadingItem != null && (payload == null || payload.item.path != loadingItem.path)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shortParent = remember(activeItem.parentPath, activeItem.path) {
        val raw = if (activeItem.isDir) activeItem.path else activeItem.parentPath
        FormatUtils.shortenPath(raw, emptyList()).first
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // Sheet header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (payload?.canNavigateBack == true && !isLoading) {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                FileTypeIcon(isDir = activeItem.isDir, ext = activeItem.ext, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeItem.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = if (activeItem.isDir) {
                        shortParent
                    } else {
                        "$shortParent · ${FormatUtils.formatBytes(activeItem.size)}"
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭预览")
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // Main content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isLoading || payload == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "正在准备预览…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    when (val content = payload.content) {
                        is PreviewContent.Text -> TextPreviewBody(content)
                        is PreviewContent.Pdf -> PdfPreviewBody(content)
                        is PreviewContent.Image -> ImagePreviewBody(content)
                        is PreviewContent.Archive -> ArchivePreviewBody(content)
                        is PreviewContent.Media -> MediaPreviewBody(content, activeItem)
                        is PreviewContent.Folder -> FolderPreviewBody(
                            content = content,
                            onChildClick = onOpenChild,
                            onRefresh = { onRefreshFolder(activeItem) }
                        )
                        is PreviewContent.BinaryHex -> BinaryHexPreviewBody(content)
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onCopyPath(activeItem) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制路径")
                }
                Button(
                    onClick = { onOpenExternal(activeItem) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (activeItem.isDir) "系统打开目录" else "用其他应用打开")
                }
            }
        }
    }
}

@Composable
private fun TextPreviewBody(content: PreviewContent.Text) {
    val scrollState = rememberScrollState()
    var activeMatchIdx by remember(content) { mutableIntStateOf(0) }
    var textLayout by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    var useMonospace by remember(content) { mutableStateOf(content.isMonospace) }

    val highlighted = remember(content, activeMatchIdx) {
        FormatUtils.buildHighlightedText(
            text = content.text,
            ranges = content.matchRanges,
            highlightBg = Amber200.copy(alpha = 0.55f),
            highlightFg = Slate950,
            activeIndex = activeMatchIdx,
            activeBg = Amber400,
            activeFg = Slate950
        )
    }

    LaunchedEffect(activeMatchIdx, textLayout, content) {
        val layout = textLayout ?: return@LaunchedEffect
        val target = content.matchRanges.getOrNull(activeMatchIdx) ?: return@LaunchedEffect
        if (content.text.isNotEmpty()) {
            val safeOffset = target.first.coerceIn(0, content.text.lastIndex)
            val rect = layout.getBoundingBox(safeOffset)
            scrollState.animateScrollTo((rect.top - 120f).toInt().coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (content.matchRanges.isNotEmpty() || content.statusNote != null || content.text.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (content.matchRanges.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "命中 ${activeMatchIdx + 1} / ${content.matchRanges.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row {
                                IconButton(
                                    onClick = {
                                        activeMatchIdx = if (activeMatchIdx <= 0) {
                                            content.matchRanges.lastIndex
                                        } else {
                                            activeMatchIdx - 1
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一处", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { activeMatchIdx = (activeMatchIdx + 1) % content.matchRanges.size },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一处", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = content.statusNote ?: "${content.text.length} 字符",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = { useMonospace = !useMonospace },
                    shape = RoundedCornerShape(10.dp),
                    color = if (useMonospace) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = "切换等宽字体",
                        tint = if (useMonospace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp).size(18.dp)
                    )
                }
            }
        }

        val bodyModifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .let { if (useMonospace) it.horizontalScroll(rememberScrollState()) else it }
            .padding(vertical = 12.dp)

        SelectionContainer(modifier = bodyModifier) {
            Text(
                text = if (highlighted.isEmpty()) AnnotatedString("（文件无字符内容）") else highlighted,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (useMonospace) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (useMonospace) 13.sp else 14.sp,
                    lineHeight = if (useMonospace) 19.sp else 21.sp
                ),
                onTextLayout = { textLayout = it }
            )
        }
    }
}

@Composable
private fun PdfPreviewBody(content: PreviewContent.Pdf) {
    val canRenderPages = content.pdfFile != null && content.pageCount > 0
    val hasText = !content.extractedText.isNullOrBlank()
    // Default to text mode if query matched inside body text; otherwise show visual pages first (including scanned PDFs).
    var showTextMode by remember(content) {
        mutableStateOf(
            when {
                content.matchRanges.isNotEmpty() && hasText -> true
                canRenderPages -> false
                else -> true
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (canRenderPages && hasText) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSwitchPill(
                    label = "页面视图 (${content.pageCount}页)",
                    selected = !showTextMode,
                    onClick = { showTextMode = false }
                )
                ModeSwitchPill(
                    label = if (content.matchRanges.isNotEmpty()) "提取文本 (${content.matchRanges.size}处命中)" else "提取文本",
                    selected = showTextMode,
                    onClick = { showTextMode = true }
                )
            }
        }

        if (showTextMode && hasText) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TextPreviewBody(
                    PreviewContent.Text(
                        text = content.extractedText.orEmpty(),
                        matchRanges = content.matchRanges,
                        statusNote = content.statusNote
                    )
                )
            }
        } else if (canRenderPages) {
            var pageIndex by remember(content) { mutableIntStateOf(0) }
            var pageBitmap by remember(content, pageIndex) { mutableStateOf<Bitmap?>(null) }
            var rendering by remember(content, pageIndex) { mutableStateOf(true) }
            var scale by remember(content, pageIndex) { mutableFloatStateOf(1f) }
            var offsetX by remember(content, pageIndex) { mutableFloatStateOf(0f) }
            var offsetY by remember(content, pageIndex) { mutableFloatStateOf(0f) }

            LaunchedEffect(content.pdfFile, pageIndex) {
                rendering = true
                pageBitmap = withContext(Dispatchers.IO) {
                    FilePreviewLoader.renderPdfPage(content.pdfFile!!, pageIndex, 1440)
                }
                rendering = false
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clipToBounds()
                    .pointerInput(pageIndex) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.2f
                                }
                            }
                        )
                    }
                    .pointerInput(pageIndex) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = nextScale
                            if (nextScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (rendering || pageBitmap == null) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                } else {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = "第 ${pageIndex + 1} 页",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                }
            }

            // Page navigator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (pageIndex > 0) pageIndex-- },
                            enabled = pageIndex > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一页")
                        }
                        Text(
                            text = "${pageIndex + 1} / ${content.pageCount}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = { if (pageIndex < content.pageCount - 1) pageIndex++ },
                            enabled = pageIndex < content.pageCount - 1,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一页")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitchPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ImagePreviewBody(content: PreviewContent.Image) {
    var scale by remember(content) { mutableFloatStateOf(1f) }
    var offsetX by remember(content) { mutableFloatStateOf(0f) }
    var offsetY by remember(content) { mutableFloatStateOf(0f) }
    var darkCanvas by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = content.statusNote ?: "${content.originalWidth} × ${content.originalHeight}",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                onClick = { darkCanvas = !darkCanvas },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (darkCanvas) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (darkCanvas) "浅色底" else "深色底",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (darkCanvas) Color(0xFF0D1117) else Color(0xFFF3F5F8))
                .clipToBounds()
                .pointerInput(content) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(content) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 5f)
                        scale = next
                        if (next > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = content.bitmap.asImageBitmap(),
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        }
    }
}

@Composable
private fun ArchivePreviewBody(content: PreviewContent.Archive) {
    var filter by remember(content) { mutableStateOf("") }
    val filtered = remember(content, filter) {
        if (filter.isBlank()) content.entries
        else content.entries.filter { it.path.contains(filter.trim(), ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.weight(1f).height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (filter.isEmpty()) {
                            Text(
                                "筛选包内条目（共 ${content.totalCount} 项）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (content.statusNote != null) {
            Text(
                text = content.statusNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(filtered) { entry ->
                val cleanName = entry.path.trimEnd('/')
                val ext = cleanName.substringAfterLast('.', "")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileTypeIcon(isDir = entry.isDir, ext = if (entry.isDir) "" else ext, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.path,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.isDir) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = FormatUtils.formatBytes(entry.size),
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun MediaPreviewBody(content: PreviewContent.Media, item: SearchResultItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            if (content.thumbnail != null) {
                Image(
                    bitmap = content.thumbnail.asImageBitmap(),
                    contentDescription = "媒体预览封面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (content.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediaInfoLine("时长", FormatUtils.formatDuration(content.durationMs))
                if (content.width > 0 && content.height > 0) {
                    MediaInfoLine("分辨率", "${content.width} × ${content.height}")
                }
                if (!content.title.isNullOrBlank()) MediaInfoLine("标题", content.title)
                if (!content.artist.isNullOrBlank()) MediaInfoLine("艺术家", content.artist)
                if (!content.mimeType.isNullOrBlank()) MediaInfoLine("编码格式", content.mimeType)
                if (content.bitrate > 0L) MediaInfoLine("码率", "${content.bitrate / 1000} kbps")
                MediaInfoLine("大小", FormatUtils.formatBytes(item.size))
            }
        }

        if (content.statusNote != null) {
            Text(
                text = content.statusNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MediaInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FolderPreviewBody(
    content: PreviewContent.Folder,
    onChildClick: (SearchResultItem) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = content.statusNote ?: "共 ${content.children.size} 个直属子项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                onClick = onRefresh,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新目录", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (content.children.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("库内暂无此目录子项记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(content.children, key = { it.id }) { child ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChildClick(child) }
                            .padding(vertical = 9.dp)
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileTypeIcon(isDir = child.isDir, ext = child.ext, size = 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = child.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = FormatUtils.formatSmartDate(child.mtime),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!child.isDir) {
                                    Text(
                                        text = FormatUtils.formatBytes(child.size),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 46.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BinaryHexPreviewBody(content: PreviewContent.BinaryHex) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = content.magicSignature,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!content.statusNote.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = content.statusNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "已检视文件头前 ${content.inspectedBytes} 字节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (content.hexDump.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = content.hexDump,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

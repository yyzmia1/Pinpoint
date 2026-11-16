package com.pocketeverything.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Slate950

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPreviewSheet(
    payload: PreviewPayload,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onCopyPath: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var activeMatchIdx by remember(payload) { mutableIntStateOf(0) }
    var textLayout by remember(payload) { mutableStateOf<TextLayoutResult?>(null) }

    val highlighted = remember(payload, activeMatchIdx) {
        FormatUtils.buildHighlightedText(
            text = payload.fullText,
            ranges = payload.matchRanges,
            highlightBg = Amber200.copy(alpha = 0.55f),
            highlightFg = Slate950,
            activeIndex = activeMatchIdx,
            activeBg = Amber400,
            activeFg = Slate950
        )
    }

    val shortPath = remember(payload.item.path) {
        FormatUtils.shortenPath(payload.item.path, emptyList()).first
    }

    LaunchedEffect(activeMatchIdx, textLayout, payload) {
        val layout = textLayout ?: return@LaunchedEffect
        val target = payload.matchRanges.getOrNull(activeMatchIdx) ?: return@LaunchedEffect
        if (payload.fullText.isNotEmpty()) {
            val safeOffset = target.first.coerceIn(0, payload.fullText.lastIndex)
            val rect = layout.getBoundingBox(safeOffset)
            scrollState.animateScrollTo((rect.top - 140f).toInt().coerceAtLeast(0))
        }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = payload.item.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = shortPath,
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

            if (payload.matchRanges.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "命中 ${activeMatchIdx + 1} / ${payload.matchRanges.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    activeMatchIdx = if (activeMatchIdx <= 0) {
                                        payload.matchRanges.lastIndex
                                    } else {
                                        activeMatchIdx - 1
                                    }
                                }
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一处")
                            }
                            IconButton(
                                onClick = {
                                    activeMatchIdx = (activeMatchIdx + 1) % payload.matchRanges.size
                                }
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一处")
                            }
                        }
                    }
                }
            } else if (payload.statusNote != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = payload.statusNote,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodyMedium,
                    onTextLayout = { textLayout = it }
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyPath,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制路径")
                }
                Button(
                    onClick = onOpenExternal,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("用其他应用打开")
                }
            }
        }
    }
}

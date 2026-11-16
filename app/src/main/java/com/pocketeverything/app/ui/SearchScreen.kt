package com.pocketeverything.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.Tier1Phase
import com.pocketeverything.app.indexer.Tier2Phase
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.QuickScope
import com.pocketeverything.app.search.SearchResponse
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.search.SortOrder
import com.pocketeverything.app.search.Tier2Status
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Emerald400
import com.pocketeverything.app.ui.theme.Rose400
import com.pocketeverything.app.ui.theme.Slate950

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    quickScope: QuickScope,
    sortOrder: SortOrder,
    searchResponse: SearchResponse,
    settings: AppSettings,
    modeStatuses: Map<AccessMode, ModeAvailability>,
    indexProgress: IndexProgressState,
    onQueryChange: (String) -> Unit,
    onScopeChange: (QuickScope) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onItemClick: (SearchResultItem) -> Unit,
    onItemLongPress: (SearchResultItem) -> Unit,
    onSelectMode: (AccessMode) -> Unit,
    onQuickSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShizukuGuide: () -> Unit,
    onRequestPermissionForCurrentMode: () -> Unit,
    onTargetsMeasured: (OnboardingTargets) -> Unit = {}
) {
    var showSyntaxSheet by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf(OnboardingTargets()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(settings.hasSeenOnboarding) {
        if (settings.hasSeenOnboarding) {
            runCatching { focusRequester.requestFocus() }
        } else {
            keyboard?.hide()
        }
    }
    LaunchedEffect(query, quickScope, sortOrder) { listState.scrollToItem(0) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) keyboard?.hide()
    }
    LaunchedEffect(targets) {
        onTargetsMeasured(targets)
    }

    val availability = modeStatuses[settings.accessMode]
    val emptyHiddenBySetting = query.isBlank() && quickScope == QuickScope.ALL && !settings.showRecentOnEmptyQuery

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            HeaderBar(
                mode = settings.accessMode,
                availability = availability,
                onModeClick = { showModeSheet = true },
                onQuickSync = onQuickSync,
                onOpenSettings = onOpenSettings,
                onModePillBounds = { rect -> targets = targets.copy(modePill = rect) }
            )

            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                focusRequester = focusRequester,
                onSubmit = { keyboard?.hide() },
                onHelp = { showSyntaxSheet = true },
                onSearchBounds = { rect -> targets = targets.copy(searchField = rect) },
                onHelpBounds = { rect -> targets = targets.copy(helpButton = rect) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (availability != null && availability !is ModeAvailability.Ready) {
                PermissionBanner(
                    message = when (availability) {
                        is ModeAvailability.NeedsPermission -> availability.reason
                        is ModeAvailability.Unavailable -> availability.reason
                        else -> ""
                    },
                    actionLabel = if (settings.accessMode == AccessMode.SHIZUKU) "向导" else "授权",
                    onAction = {
                        if (settings.accessMode == AccessMode.SHIZUKU) onOpenShizukuGuide()
                        else onRequestPermissionForCurrentMode()
                    }
                )
            }

            ScopePills(
                selected = quickScope,
                onSelect = onScopeChange,
                modifier = Modifier.onGloballyPositioned { coords ->
                    targets = targets.copy(scopePills = coords.boundsInRoot())
                }
            )

            StatsRow(
                query = query,
                quickScope = quickScope,
                showRecentOnEmpty = settings.showRecentOnEmptyQuery,
                response = searchResponse,
                progress = indexProgress,
                sortOrder = sortOrder,
                onSortChange = onSortChange
            )

            IndexProgressStrip(indexProgress)

            if (searchResponse.items.isEmpty()) {
                EmptyState(
                    indexEmpty = indexProgress.stats.totalEntries == 0L,
                    hiddenRecent = emptyHiddenBySetting
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(searchResponse.items, key = { _, item -> item.id }) { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        ResultRow(
                            item = item,
                            onClick = { onItemClick(item) },
                            onLongPress = { onItemLongPress(item) }
                        )
                    }
                }
            }
        }
    }

    if (showSyntaxSheet) {
        SyntaxGuideSheet(
            onInsertSnippet = { token ->
                val prefix = if (query.isNotEmpty() && !query.last().isWhitespace() && !token.startsWith("-")) "$query " else query
                onQueryChange(prefix + token)
            },
            onApplyExample = { onQueryChange(it) },
            onDismiss = { showSyntaxSheet = false }
        )
    }

    if (showModeSheet) {
        ModeSheet(
            current = settings.accessMode,
            statuses = modeStatuses,
            onSelect = {
                onSelectMode(it)
                showModeSheet = false
            },
            onOpenShizukuGuide = {
                showModeSheet = false
                onOpenShizukuGuide()
            },
            onDismiss = { showModeSheet = false }
        )
    }
}

@Composable
private fun HeaderBar(
    mode: AccessMode,
    availability: ModeAvailability?,
    onModeClick: () -> Unit,
    onQuickSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onModePillBounds: (Rect) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "PocketEverything",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )

        Surface(
            onClick = onModeClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.onGloballyPositioned { coords ->
                onModePillBounds(coords.boundsInRoot())
            }
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(availabilityColor(availability))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = mode.shortLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "切换模式",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        IconButton(onClick = onQuickSync) {
            Icon(Icons.Default.Refresh, contentDescription = "增量同步", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onHelp: () -> Unit,
    onSearchBounds: (Rect) -> Unit,
    onHelpBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "searchBorder"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.5.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .onGloballyPositioned { coords -> onSearchBounds(coords.boundsInRoot()) }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = "文件名、路径或正文（如 ext:pdf 关键词）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = onHelp,
                modifier = Modifier
                    .size(34.dp)
                    .onGloballyPositioned { coords -> onHelpBounds(coords.boundsInRoot()) }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "搜索语法",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ScopePills(
    selected: QuickScope,
    onSelect: (QuickScope) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        QuickScope.entries.forEach { scope ->
            val isSelected = scope == selected
            Surface(
                onClick = { onSelect(scope) },
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    text = scope.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatsRow(
    query: String,
    quickScope: QuickScope,
    showRecentOnEmpty: Boolean,
    response: SearchResponse,
    progress: IndexProgressState,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val stats = progress.stats
        val text = when {
            response.queryError != null -> "语法提示：${response.queryError}"
            query.isBlank() && quickScope == QuickScope.ALL && !showRecentOnEmpty ->
                "${stats.totalEntries} 项 · ${stats.indexedDocs} 正文"
            query.isBlank() ->
                "${stats.totalEntries} 项 · ${stats.indexedDocs} 正文"
            else -> "${response.totalReturned} 项 · ${response.elapsedMs} ms"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (response.queryError != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showSortMenu = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "排序",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = sortOrder.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                SortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                order.label,
                                color = if (order == sortOrder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onSortChange(order)
                            showSortMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IndexProgressStrip(progress: IndexProgressState) {
    val active = progress.tier1Phase != Tier1Phase.IDLE || progress.tier2Phase != Tier2Phase.IDLE
    if (!active) {
        Spacer(Modifier.height(2.dp))
        return
    }
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceContainer
        )
        Text(
            text = if (progress.tier1Phase != Tier1Phase.IDLE) {
                progress.statusMessage
            } else {
                "正文解析 · ${progress.tier2ProcessedCount} / 剩余 ${progress.tier2PendingCount}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun EmptyState(indexEmpty: Boolean, hiddenRecent: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (hiddenRecent) Icons.Default.Search else Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                indexEmpty -> "索引库尚为空"
                hiddenRecent -> "输入关键词开始搜索"
                else -> "无匹配结果"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(72.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    item: SearchResultItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val hlBg = Amber200.copy(alpha = 0.75f)
    val hlFg = Slate950

    val nameText = remember(item.name, item.matchedNameRanges) {
        FormatUtils.buildHighlightedText(item.name, item.matchedNameRanges, hlBg, hlFg)
    }
    val pathText = remember(item.parentPath, item.matchedPathRanges) {
        val parentRanges = item.matchedPathRanges.filter { it.last < item.parentPath.length }
        val (shortPath, shortRanges) = FormatUtils.shortenPath(item.parentPath, parentRanges)
        FormatUtils.buildHighlightedText(shortPath, shortRanges, hlBg, hlFg)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        FileTypeIcon(isDir = item.isDir, ext = item.ext)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nameText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pathText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = FormatUtils.formatSmartDate(item.mtime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        statusTag(item.tier2Status)?.let { (label, color) ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(color.copy(alpha = 0.12f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        if (!item.isDir) {
                            Text(
                                text = FormatUtils.formatBytes(item.size),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            if (item.snippets.isNotEmpty()) {
                SnippetBlock(item, hlBg, hlFg)
            }
        }
    }
}

@Composable
private fun SnippetBlock(item: SearchResultItem, hlBg: Color, hlFg: Color) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(8.dp))
        Column {
            item.snippets.forEach { snippet ->
                val text = remember(snippet) {
                    FormatUtils.buildHighlightedText(snippet.text, snippet.highlightRanges, hlBg, hlFg)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.contentMatchCount > 1) {
                Text(
                    text = "${item.contentMatchCount} 处匹配",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun statusTag(status: Tier2Status): Pair<String, Color>? {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when (status) {
        Tier2Status.INDEXED -> "正文" to MaterialTheme.colorScheme.primary
        Tier2Status.SCANNED_OR_EMPTY -> "无文字" to muted
        Tier2Status.UNSUPPORTED_LEGACY_DOC -> ".doc" to muted
        Tier2Status.SKIPPED_SIZE -> "超限" to muted
        Tier2Status.ENCRYPTED -> "加密" to muted
        else -> null
    }
}

@Composable
private fun FileTypeIcon(isDir: Boolean, ext: String) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val lower = ext.lowercase()
    val (icon, color) = fileTypeVisual(isDir, lower, dark)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = if (dark) 0.16f else 0.11f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun fileTypeVisual(isDir: Boolean, ext: String, dark: Boolean): Pair<ImageVector, Color> {
    fun pick(light: Long, darkTint: Long) = Color(if (dark) darkTint else light)
    if (isDir) {
        return Icons.Rounded.Folder to if (dark) Amber400 else Color(0xFFD97706)
    }
    return when (ext) {
        "pdf" -> Icons.Rounded.PictureAsPdf to if (dark) Rose400 else Color(0xFFE11D48)
        "doc", "docx" -> Icons.Rounded.Description to pick(0xFF2563EB, 0xFF60A5FA)
        "txt", "md", "markdown" -> Icons.AutoMirrored.Rounded.Article to if (dark) Emerald400 else Color(0xFF059669)
        "xls", "xlsx", "csv" -> Icons.Rounded.TableChart to pick(0xFF16A34A, 0xFF4ADE80)
        "ppt", "pptx" -> Icons.Rounded.Slideshow to pick(0xFFC2410C, 0xFFFDBA74)
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Icons.Rounded.Image to pick(0xFF7C3AED, 0xFFA78BFA)
        "mp4", "mkv", "mov", "avi", "webm" -> Icons.Rounded.VideoFile to pick(0xFFDB2777, 0xFFF472B6)
        "mp3", "flac", "wav", "m4a", "ogg" -> Icons.Rounded.AudioFile to pick(0xFF0891B2, 0xFF22D3EE)
        "apk", "apks", "xapk" -> Icons.Rounded.Android to pick(0xFF65A30D, 0xFFA3E635)
        "zip", "rar", "7z", "tar", "gz", "xz" -> Icons.Rounded.FolderZip to pick(0xFFEA580C, 0xFFFB923C)
        "json", "xml", "yml", "yaml", "kt", "java", "py", "js", "ts", "html", "css", "sh", "c", "cpp", "rs" ->
            Icons.Rounded.Code to pick(0xFF4F46E5, 0xFF818CF8)
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to pick(0xFF64748B, 0xFF94A3B8)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSheet(
    current: AccessMode,
    statuses: Map<AccessMode, ModeAvailability>,
    onSelect: (AccessMode) -> Unit,
    onOpenShizukuGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("访问模式", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onOpenShizukuGuide) {
                    Text("Shizuku 向导")
                }
            }
            AccessMode.entries.forEach { mode ->
                val selected = mode == current
                val status = statuses[mode]
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when (mode) {
                                    AccessMode.NORMAL -> "内部与外置存储公开目录"
                                    AccessMode.SHIZUKU -> "免 Root 穿透 /Android/data 与 /obb"
                                    AccessMode.ROOT -> "根目录 / 全盘索引与挂载去重"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(availabilityColor(status))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when (status) {
                                is ModeAvailability.Ready -> "可用"
                                is ModeAvailability.NeedsPermission -> "待授权"
                                is ModeAvailability.Unavailable -> "未就绪"
                                null -> "检测中"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun availabilityColor(status: ModeAvailability?): Color = when (status) {
    is ModeAvailability.Ready -> Emerald400
    is ModeAvailability.NeedsPermission -> Amber400
    is ModeAvailability.Unavailable -> Rose400
    null -> MaterialTheme.colorScheme.outline
}

private fun AccessMode.shortLabel(): String = when (this) {
    AccessMode.NORMAL -> "普通"
    AccessMode.SHIZUKU -> "Shizuku"
    AccessMode.ROOT -> "Root"
}

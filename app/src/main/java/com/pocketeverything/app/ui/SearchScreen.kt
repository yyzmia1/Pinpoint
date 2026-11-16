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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
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
import com.pocketeverything.app.ui.components.BrandMarkIcon
import com.pocketeverything.app.ui.components.FileTypeIcon
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
    isDarkTheme: Boolean,
    modeStatuses: Map<AccessMode, ModeAvailability>,
    indexProgress: IndexProgressState,
    onQueryChange: (String) -> Unit,
    onScopeChange: (QuickScope) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onItemClick: (SearchResultItem) -> Unit,
    onItemLongPress: (SearchResultItem) -> Unit,
    onSelectMode: (AccessMode) -> Unit,
    onToggleTheme: () -> Unit,
    onQuickSync: () -> Unit,
    onFullScan: () -> Unit,
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
                isDarkTheme = isDarkTheme,
                availability = availability,
                onModeClick = { showModeSheet = true },
                onToggleTheme = onToggleTheme,
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
                    hiddenRecent = emptyHiddenBySetting,
                    needsPermission = availability != null && availability !is ModeAvailability.Ready,
                    onAction = {
                        if (availability != null && availability !is ModeAvailability.Ready) {
                            if (settings.accessMode == AccessMode.SHIZUKU) onOpenShizukuGuide()
                            else onRequestPermissionForCurrentMode()
                        } else {
                            onFullScan()
                        }
                    }
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
                                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
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
    isDarkTheme: Boolean,
    availability: ModeAvailability?,
    onModeClick: () -> Unit,
    onToggleTheme: () -> Unit,
    onQuickSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onModePillBounds: (Rect) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMarkIcon(size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Pinpoint",
            style = MaterialTheme.typography.titleLarge,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Surface(
            onClick = onModeClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            modifier = Modifier.onGloballyPositioned { coords ->
                onModePillBounds(coords.boundsInRoot())
            }
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(availabilityColor(availability))
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = mode.shortLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "切换模式",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        val actionTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = if (isDarkTheme) "切换白天模式" else "切换黑夜模式",
                tint = actionTint,
                modifier = Modifier.size(19.dp)
            )
        }
        IconButton(onClick = onQuickSync, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "增量同步",
                tint = actionTint,
                modifier = Modifier.size(19.dp)
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "设置",
                tint = actionTint,
                modifier = Modifier.size(19.dp)
            )
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
        targetValue = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Transparent,
        label = "searchBorder"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.2.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onGloballyPositioned { coords -> onSearchBounds(coords.boundsInRoot()) }
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索文件名、路径或正文…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
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
                        modifier = Modifier.size(17.dp)
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
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
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
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        QuickScope.entries.forEach { scope ->
            val isSelected = scope == selected
            Surface(
                onClick = { onSelect(scope) },
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f)
                },
                border = BorderStroke(
                    width = if (isSelected) 0.9.dp else 0.5.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
            ) {
                Text(
                    text = scope.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
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
                "库内 ${stats.totalEntries} 项 · ${stats.indexedDocs} 正文"
            query.isBlank() ->
                "最近 ${response.totalReturned} 项 · 库内 ${stats.totalEntries} 项"
            else -> "${response.totalReturned} 项 · ${response.elapsedMs} ms"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
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
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun EmptyState(
    indexEmpty: Boolean,
    hiddenRecent: Boolean,
    needsPermission: Boolean,
    onAction: () -> Unit
) {
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
        if (indexEmpty) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onAction) {
                Text(if (needsPermission) "前往授权并建库" else "立即扫描建库")
            }
        }
        Spacer(Modifier.height(68.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    item: SearchResultItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val hlBg = Amber200.copy(alpha = 0.72f)
    val hlFg = Slate950

    val nameText = remember(item.name, item.matchedNameRanges) {
        FormatUtils.buildHighlightedText(item.name, item.matchedNameRanges, hlBg, hlFg)
    }
    val pathText = remember(item.parentPath, item.matchedPathRanges) {
        val parentRanges = item.matchedPathRanges.filter { it.last < item.parentPath.length }
        val (shortPath, shortRanges) = FormatUtils.shortenPath(item.parentPath, parentRanges)
        FormatUtils.buildHighlightedText(shortPath, shortRanges, hlBg, hlFg)
    }
    val metaStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        FileTypeIcon(
            isDir = item.isDir,
            ext = item.ext,
            size = 38.dp,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Top line: Filename on left + Smart timestamp pinned to top-right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = nameText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = FormatUtils.formatSmartDate(item.mtime),
                    style = metaStyle,
                    color = metaColor,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (item.snippets.isNotEmpty()) {
                SnippetBlock(item, hlBg, hlFg)
            }

            Spacer(Modifier.height(3.dp))

            // Bottom line: Parent path on left + File size strictly anchored at bottom-right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pathText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!item.isDir) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = FormatUtils.formatBytes(item.size),
                        style = metaStyle,
                        color = metaColor,
                        maxLines = 1
                    )
                }
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.contentMatchCount > 1) {
                Text(
                    text = "${item.contentMatchCount} 处匹配",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
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
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
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

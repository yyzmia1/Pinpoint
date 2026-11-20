package com.pocketeverything.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketeverything.app.analyzer.AnalyzerTreeSort
import com.pocketeverything.app.analyzer.AnalyzerTreeSortField
import com.pocketeverything.app.analyzer.AppAttributionBadge
import com.pocketeverything.app.analyzer.AppLocationStat
import com.pocketeverything.app.analyzer.AppPathCategory
import com.pocketeverything.app.analyzer.AppStorageSummary
import com.pocketeverything.app.analyzer.DirNodeStat
import com.pocketeverything.app.analyzer.ExtensionStat
import com.pocketeverything.app.analyzer.LargeFileEntry
import com.pocketeverything.app.analyzer.SquarifiedTreemap
import com.pocketeverything.app.analyzer.TreeChildEntry
import com.pocketeverything.app.analyzer.TreemapInput
import com.pocketeverything.app.analyzer.TreemapRect
import com.pocketeverything.app.analyzer.WizTreeSnapshot
import com.pocketeverything.app.fs.DeletionRisk
import com.pocketeverything.app.fs.DeletionSafetyGuard
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.components.FileTypeIcon
import com.pocketeverything.app.ui.components.classifyVisualFamily
import com.pocketeverything.app.ui.components.fileIconAccentColor
import com.pocketeverything.app.ui.theme.PocketEverythingTheme
import com.pocketeverything.app.ui.theme.isLightScheme
import com.pocketeverything.app.ui.theme.rememberAppHaptics
import java.util.Locale

enum class AnalyzerTab(val label: String) {
    TREE("目录树"),
    APPS("应用归属"),
    FILES("大文件")
}

data class StorageAnalyzerUiState(
    val isLoading: Boolean = false,
    val isRefreshingInBackground: Boolean = false,
    val loadingMessage: String? = null,
    val snapshot: WizTreeSnapshot? = null,
    val selectedTab: AnalyzerTab = AnalyzerTab.TREE,
    val currentDirPath: String? = null,
    val activeDirNode: DirNodeStat? = null,
    val activeDirChildren: List<TreeChildEntry> = emptyList(),
    val treeSort: AnalyzerTreeSort = AnalyzerTreeSort.SIZE_DESC,
    val foldersFirst: Boolean = true,
    val selectedExtFilter: String? = null,
    val activeLargeFiles: List<LargeFileEntry> = emptyList(),
    val onlyUninstalledApps: Boolean = false,
    val showTreemap: Boolean = true,
    val deletingPath: String? = null,
    val errorMessage: String? = null,
    val appIcons: Map<String, ImageBitmap> = emptyMap()
) {
    val effectiveDirNode: DirNodeStat?
        get() {
            if (activeDirNode != null) return activeDirNode
            val snap = snapshot ?: return null
            val target = currentDirPath ?: snap.defaultRootPath ?: return null
            return snap.dirNodes[target] ?: snap.defaultRootPath?.let { snap.dirNodes[it] }
        }

    val effectiveDirChildren: List<TreeChildEntry>
        get() = if (activeDirChildren.isNotEmpty()) activeDirChildren else effectiveDirNode?.childrenSorted(treeSort, foldersFirst).orEmpty()

    val effectiveLargeFiles: List<LargeFileEntry>
        get() = if (activeLargeFiles.isNotEmpty() || selectedExtFilter != null) {
            activeLargeFiles
        } else {
            snapshot?.topLargeFiles.orEmpty()
        }

    val canNavigateUpInTree: Boolean
        get() {
            val snap = snapshot ?: return false
            val cur = effectiveDirNode ?: return false
            if (cur.path in snap.rootPaths || cur.path == "/") return false
            return cur.parentPath.isNotEmpty()
        }
}

@Stable
interface StorageAnalyzerActions {
    fun onExit()
    fun onNavigateUp()
    fun onRefresh()
    fun onSelectTab(tab: AnalyzerTab)
    fun onNavigateDir(path: String)
    fun onSelectTreeSort(sort: AnalyzerTreeSort)
    fun onToggleFoldersFirst()
    fun onSelectExtFilter(ext: String?)
    fun onToggleUninstalledOnly(enabled: Boolean)
    fun onToggleTreemap()
    fun onOpenFile(path: String)
    fun onDeletePath(path: String, accessMode: AccessMode, isDir: Boolean)
    fun onExcludeDir(dirPath: String)
    fun onCopyPath(path: String)
}

private data class AnalyzerActionTarget(
    val path: String,
    val displayName: String,
    val totalBytes: Long,
    val fileCount: Long,
    val dirCount: Long,
    val isDir: Boolean,
    val accessMode: AccessMode,
    val showLocateInTree: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    state: StorageAnalyzerUiState,
    useMonospaceFont: Boolean,
    actions: StorageAnalyzerActions
) {
    val haptics = rememberAppHaptics()
    var sheetTarget by remember { mutableStateOf<AnalyzerActionTarget?>(null) }
    var confirmDeleteTarget by remember { mutableStateOf<AnalyzerActionTarget?>(null) }
    var expandedAppPkg by remember { mutableStateOf<String?>(null) }

    val snapshot = state.snapshot
    val activeDir = state.effectiveDirNode
    val activeChildren = state.effectiveDirChildren
    val activeLargeFiles = state.effectiveLargeFiles
    val dark = !MaterialTheme.colorScheme.isLightScheme

    val treemapInputs = remember(snapshot, state.selectedTab, activeChildren, state.onlyUninstalledApps) {
        buildTreemapInputs(snapshot, state.selectedTab, activeChildren, state.onlyUninstalledApps)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AnalyzerTopBar(
                snapshot = snapshot,
                showTreemap = state.showTreemap,
                isLoading = state.isLoading || state.isRefreshingInBackground,
                loadingMessage = if (state.isRefreshingInBackground) state.loadingMessage ?: "后台增量更新缓存中…" else null,
                onExit = {
                    haptics.tick()
                    actions.onExit()
                },
                onToggleTreemap = {
                    haptics.tick()
                    actions.onToggleTreemap()
                },
                onRefresh = {
                    haptics.click()
                    actions.onRefresh()
                }
            )

            if (state.isLoading || state.isRefreshingInBackground || state.deletingPath != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            AnalyzerTabStrip(
                selected = state.selectedTab,
                uninstalledCount = snapshot?.uninstalledAppCount ?: 0,
                onSelect = {
                    haptics.tick()
                    actions.onSelectTab(it)
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )

            if (snapshot == null && state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.6.dp)
                        Text(
                            text = state.loadingMessage ?: "正在构建空间缓存（首次稍慢，后续秒开）…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (snapshot == null || (snapshot.totalFiles == 0L && snapshot.totalDirs == 0L)) {
                AnalyzerEmptyState(
                    errorMessage = state.errorMessage,
                    onRefresh = actions::onRefresh
                )
            } else {
                AnimatedVisibility(
                    visible = state.showTreemap && treemapInputs.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SquarifiedTreemapCard(
                        inputs = treemapInputs,
                        dark = dark,
                        onTileClick = { tile ->
                            if (tile.isOtherBucket) return@SquarifiedTreemapCard
                            haptics.tick()
                            when (state.selectedTab) {
                                AnalyzerTab.TREE -> {
                                    if (tile.isDir) actions.onNavigateDir(tile.id)
                                    else actions.onOpenFile(tile.id)
                                }
                                AnalyzerTab.APPS -> {
                                    expandedAppPkg = if (expandedAppPkg == tile.id) null else tile.id
                                }
                                AnalyzerTab.FILES -> {
                                    val next = if (state.selectedExtFilter == tile.id) null else tile.id
                                    actions.onSelectExtFilter(next)
                                }
                            }
                        },
                        onTileLongPress = { tile ->
                            if (tile.isOtherBucket) return@SquarifiedTreemapCard
                            haptics.click()
                            if (state.selectedTab == AnalyzerTab.TREE) {
                                val child = activeChildren.firstOrNull { it.path == tile.id }
                                if (child != null) {
                                    sheetTarget = AnalyzerActionTarget(
                                        path = child.path,
                                        displayName = child.name,
                                        totalBytes = child.totalBytes,
                                        fileCount = child.fileCount,
                                        dirCount = child.dirCount,
                                        isDir = child.isDir,
                                        accessMode = child.accessMode,
                                        showLocateInTree = false
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }

                when (state.selectedTab) {
                    AnalyzerTab.TREE -> {
                        TreeTabBody(
                            snapshot = snapshot,
                            activeDir = activeDir,
                            canNavigateUp = state.canNavigateUpInTree,
                            children = activeChildren,
                            sort = state.treeSort,
                            foldersFirst = state.foldersFirst,
                            appIcons = state.appIcons,
                            useMonospaceFont = useMonospaceFont,
                            onNavigateUp = {
                                haptics.tick()
                                actions.onNavigateUp()
                            },
                            onSelectRoot = {
                                haptics.tick()
                                actions.onNavigateDir(it)
                            },
                            onBreadcrumbClick = {
                                haptics.tick()
                                actions.onNavigateDir(it)
                            },
                            onSelectSort = { nextSort ->
                                haptics.tick()
                                actions.onSelectTreeSort(nextSort)
                            },
                            onToggleFoldersFirst = {
                                haptics.tick()
                                actions.onToggleFoldersFirst()
                            },
                            onRowClick = { item ->
                                haptics.tick()
                                if (item.isDir) actions.onNavigateDir(item.path)
                                else actions.onOpenFile(item.path)
                            },
                            onRowMore = { item ->
                                haptics.click()
                                sheetTarget = AnalyzerActionTarget(
                                    path = item.path,
                                    displayName = item.name,
                                    totalBytes = item.totalBytes,
                                    fileCount = item.fileCount,
                                    dirCount = item.dirCount,
                                    isDir = item.isDir,
                                    accessMode = item.accessMode,
                                    showLocateInTree = false
                                )
                            }
                        )
                    }
                    AnalyzerTab.APPS -> {
                        AppsTabBody(
                            snapshot = snapshot,
                            onlyUninstalled = state.onlyUninstalledApps,
                            expandedPkg = expandedAppPkg,
                            appIcons = state.appIcons,
                            useMonospaceFont = useMonospaceFont,
                            onToggleUninstalled = {
                                haptics.tick()
                                actions.onToggleUninstalledOnly(it)
                            },
                            onToggleExpand = { pkg ->
                                haptics.tick()
                                expandedAppPkg = if (expandedAppPkg == pkg) null else pkg
                            },
                            onLocateLocation = { loc ->
                                haptics.tick()
                                actions.onSelectTab(AnalyzerTab.TREE)
                                actions.onNavigateDir(loc.path)
                            },
                            onLocationActions = { app, loc ->
                                haptics.click()
                                sheetTarget = AnalyzerActionTarget(
                                    path = loc.path,
                                    displayName = "${app.displayLabel} · ${loc.category.label}",
                                    totalBytes = loc.totalBytes,
                                    fileCount = loc.fileCount,
                                    dirCount = 0L,
                                    isDir = true,
                                    accessMode = loc.accessMode,
                                    showLocateInTree = true
                                )
                            }
                        )
                    }
                    AnalyzerTab.FILES -> {
                        FilesTabBody(
                            snapshot = snapshot,
                            files = activeLargeFiles,
                            selectedExt = state.selectedExtFilter,
                            useMonospaceFont = useMonospaceFont,
                            dark = dark,
                            onSelectExt = { ext ->
                                haptics.tick()
                                actions.onSelectExtFilter(ext)
                            },
                            onFileClick = { f ->
                                haptics.tick()
                                actions.onOpenFile(f.path)
                            },
                            onFileMore = { f ->
                                haptics.click()
                                sheetTarget = AnalyzerActionTarget(
                                    path = f.path,
                                    displayName = f.name,
                                    totalBytes = f.size,
                                    fileCount = 1L,
                                    dirCount = 0L,
                                    isDir = false,
                                    accessMode = f.accessMode,
                                    showLocateInTree = true
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    sheetTarget?.let { target ->
        val safety = remember(target.path) { DeletionSafetyGuard.evaluate(target.path) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            AnalyzerActionSheetContent(
                target = target,
                safety = safety,
                onOpenOrEnter = {
                    sheetTarget = null
                    if (target.isDir) {
                        actions.onSelectTab(AnalyzerTab.TREE)
                        actions.onNavigateDir(target.path)
                    } else {
                        actions.onOpenFile(target.path)
                    }
                },
                onLocateInTree = {
                    sheetTarget = null
                    val dirTarget = if (target.isDir) target.path else target.path.substringBeforeLast('/').ifEmpty { "/" }
                    actions.onSelectTab(AnalyzerTab.TREE)
                    actions.onNavigateDir(dirTarget)
                },
                onExcludeDir = {
                    sheetTarget = null
                    val dirTarget = if (target.isDir) target.path else target.path.substringBeforeLast('/').ifEmpty { "/" }
                    actions.onExcludeDir(dirTarget)
                },
                onCopyPath = {
                    sheetTarget = null
                    actions.onCopyPath(target.path)
                },
                onRequestDelete = {
                    sheetTarget = null
                    confirmDeleteTarget = target
                }
            )
        }
    }

    confirmDeleteTarget?.let { target ->
        val safety = remember(target.path) { DeletionSafetyGuard.evaluate(target.path) }
        DeleteConfirmDialog(
            target = target,
            safety = safety,
            isDeleting = state.deletingPath == target.path,
            onDismiss = { confirmDeleteTarget = null },
            onConfirm = {
                confirmDeleteTarget = null
                actions.onDeletePath(target.path, target.accessMode, target.isDir)
            }
        )
    }
}

@Composable
private fun AnalyzerTopBar(
    snapshot: WizTreeSnapshot?,
    showTreemap: Boolean,
    isLoading: Boolean,
    loadingMessage: String? = null,
    onExit: () -> Unit,
    onToggleTreemap: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "退出空间透视",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "空间透视",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (loadingMessage != null) {
                Text(
                    text = loadingMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (snapshot != null && (snapshot.totalFiles > 0L || snapshot.totalDirs > 0L)) {
                val cacheTag = if (snapshot.isFromCache) "缓存" else "${snapshot.buildDurationMs}ms"
                Text(
                    text = "${FormatUtils.formatBytes(snapshot.totalBytes)} · ${formatCompactCount(snapshot.totalFiles)} 文件 · $cacheTag",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleTreemap, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (showTreemap) Icons.Filled.GridView else Icons.Outlined.GridView,
                contentDescription = if (showTreemap) "收起矩形树图" else "展开矩形树图",
                tint = if (showTreemap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onRefresh, enabled = !isLoading, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新分析",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AnalyzerTabStrip(
    selected: AnalyzerTab,
    uninstalledCount: Int,
    onSelect: (AnalyzerTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (tab in AnalyzerTab.entries) {
                val isSelected = tab == selected
                val icon: ImageVector = when (tab) {
                    AnalyzerTab.TREE -> Icons.Default.AccountTree
                    AnalyzerTab.APPS -> Icons.Default.Apps
                    AnalyzerTab.FILES -> Icons.AutoMirrored.Filled.InsertDriveFile
                }
                Surface(
                    onClick = { onSelect(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (isSelected) 1.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        if (tab == AnalyzerTab.APPS && uninstalledCount > 0) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$uninstalledCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SquarifiedTreemapCard(
    inputs: List<TreemapInput>,
    dark: Boolean,
    onTileClick: (TreemapInput) -> Unit,
    onTileLongPress: (TreemapInput) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val rects: List<TreemapRect> = remember(inputs, canvasSize) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) emptyList()
        else SquarifiedTreemap.layout(inputs, canvasSize.width.toFloat(), canvasSize.height.toFloat(), maxTiles = 16)
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gapPx = with(density) { 1.5.dp.toPx() }
    val radiusPx = with(density) { 5.dp.toPx() }
    val minLabelW = with(density) { 56.dp.toPx() }
    val minLabelH = with(density) { 28.dp.toPx() }
    val minSubH = with(density) { 42.dp.toPx() }
    val borderColor = MaterialTheme.colorScheme.background
    val folderPalette = remember(dark) {
        if (dark) {
            listOf(
                Color(0xFF1E3A5F),
                Color(0xFF1D4ED8),
                Color(0xFF0F766E),
                Color(0xFF4338CA),
                Color(0xFF9A3412),
                Color(0xFF6D28D9),
                Color(0xFF0369A1),
                Color(0xFF3F6212)
            )
        } else {
            listOf(
                Color(0xFF2563EB),
                Color(0xFF0284C7),
                Color(0xFF0D9488),
                Color(0xFF4F46E5),
                Color(0xFFD97706),
                Color(0xFF7C3AED),
                Color(0xFF059669),
                Color(0xFF475569)
            )
        }
    }
    val residueColor = if (dark) Color(0xFFB91C1C) else Color(0xFFDC2626)
    val otherColor = if (dark) Color(0xFF334155) else Color(0xFF64748B)
    val titleStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val sizeStyle = TextStyle(
        color = Color.White.copy(alpha = 0.86f),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(152.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(rects) {
                    detectTapGestures(
                        onTap = { pos ->
                            val hit = rects.firstOrNull { it.contains(pos.x, pos.y) }
                            if (hit != null) onTileClick(hit.item)
                        },
                        onLongPress = { pos ->
                            val hit = rects.firstOrNull { it.contains(pos.x, pos.y) }
                            if (hit != null) onTileLongPress(hit.item)
                        }
                    )
                }
        ) {
            rects.forEachIndexed { idx, rect ->
                val w = (rect.width - gapPx).coerceAtLeast(0f)
                val h = (rect.height - gapPx).coerceAtLeast(0f)
                if (w <= 0.5f || h <= 0.5f) return@forEachIndexed

                val fillColor = when {
                    rect.item.isOtherBucket -> otherColor
                    rect.item.isResidue -> residueColor
                    !rect.item.isDir && rect.item.ext.isNotEmpty() -> {
                        val base = fileIconAccentColor(classifyVisualFamily(false, rect.item.ext), dark)
                        if (dark) base.copy(alpha = 0.78f) else base
                    }
                    else -> folderPalette[idx % folderPalette.size]
                }

                val left = rect.left + gapPx / 2f
                val top = rect.top + gapPx / 2f
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )
                drawRoundRect(
                    color = borderColor.copy(alpha = 0.25f),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = 1f)
                )

                if (w >= minLabelW && h >= minLabelH) {
                    val pad = 5.dp.toPx()
                    val availW = (w - pad * 2f).toInt().coerceAtLeast(1)
                    val titleLayout = textMeasurer.measure(
                        text = rect.item.label,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = availW)
                    )
                    drawText(
                        textLayoutResult = titleLayout,
                        topLeft = Offset(left + pad, top + pad)
                    )
                    if (h >= minSubH) {
                        val sizeLayout = textMeasurer.measure(
                            text = FormatUtils.formatBytes(rect.item.weight),
                            style = sizeStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = availW)
                        )
                        drawText(
                            textLayoutResult = sizeLayout,
                            topLeft = Offset(left + pad, top + pad + titleLayout.size.height + 1.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeTabBody(
    snapshot: WizTreeSnapshot,
    activeDir: DirNodeStat?,
    canNavigateUp: Boolean,
    children: List<TreeChildEntry>,
    sort: AnalyzerTreeSort,
    foldersFirst: Boolean,
    appIcons: Map<String, ImageBitmap>,
    useMonospaceFont: Boolean,
    onNavigateUp: () -> Unit,
    onSelectRoot: (String) -> Unit,
    onBreadcrumbClick: (String) -> Unit,
    onSelectSort: (AnalyzerTreeSort) -> Unit,
    onToggleFoldersFirst: () -> Unit,
    onRowClick: (TreeChildEntry) -> Unit,
    onRowMore: (TreeChildEntry) -> Unit
) {
    if (activeDir == null) return
    val listState = rememberLazyListState()
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(activeDir.path, sort, foldersFirst) {
        listState.scrollToItem(0)
    }

    val breadcrumbs = remember(activeDir.path, snapshot.rootPaths) {
        buildBreadcrumbs(activeDir.path, snapshot.rootPaths)
    }
    val parentLabel = remember(breadcrumbs) {
        if (breadcrumbs.size >= 2) breadcrumbs[breadcrumbs.lastIndex - 1].second else ""
    }
    val crumbScroll = rememberScrollState()
    LaunchedEffect(activeDir.path) {
        crumbScroll.animateScrollTo(crumbScroll.maxValue)
    }

    // Mode-aware quick-jump shortcut pills (Root: "/" leftmost -> /data -> 模块目录 -> 内部存储; Shizuku / Normal curated)
    if (snapshot.shortcuts.size > 1) {
        val activeShortcutPath = remember(activeDir.path, snapshot.shortcuts) {
            snapshot.shortcuts
                .filter { sc ->
                    activeDir.path == sc.path ||
                        (sc.path == "/" && activeDir.path.startsWith("/")) ||
                        activeDir.path.startsWith("${sc.path}/")
                }
                .maxByOrNull { it.path.length }
                ?.path
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (sc in snapshot.shortcuts) {
                val active = activeShortcutPath == sc.path
                Surface(
                    onClick = { onSelectRoot(sc.path) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        0.6.dp,
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Text(
                        text = "${sc.label} · ${FormatUtils.formatBytes(sc.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        maxLines = 1
                    )
                }
            }
        }
    }

    // MT-Manager-style compact path bar + dedicated up-one-level button + 4-dimension sort menu
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canNavigateUp) {
            Surface(
                onClick = onNavigateUp,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = "返回上一级",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "上级",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(crumbScroll),
            verticalAlignment = Alignment.CenterVertically
        ) {
            breadcrumbs.forEachIndexed { idx, (crumbPath, label) ->
                val isLast = idx == breadcrumbs.lastIndex
                if (idx > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLast) { onBreadcrumbClick(crumbPath) }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box {
            Surface(
                onClick = { sortMenuExpanded = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = sort.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false }
            ) {
                AnalyzerTreeSortField.entries.forEach { field ->
                    val isSelected = sort.sortField == field
                    val nextSort = if (isSelected) sort.toggledDirection() else AnalyzerTreeSort.forField(field)
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "按${field.label}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = if (sort.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                        contentDescription = if (sort.descending) "降序" else "升序",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            sortMenuExpanded = false
                            onSelectSort(nextSort)
                        }
                    )
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "文件夹置顶",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (foldersFirst) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "已开启",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        sortMenuExpanded = false
                        onToggleFoldersFirst()
                    }
                )
            }
        }
    }

    // MT-Manager-style compact folder status bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${formatCompactCount(activeDir.dirCount)} 文件夹 · ${formatCompactCount(activeDir.fileCount)} 文件",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (useMonospaceFont) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = FormatUtils.formatBytes(activeDir.totalBytes),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

    if (children.isEmpty() && !canNavigateUp) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("空目录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val isMetricSort = sort.sortField == AnalyzerTreeSortField.SIZE || sort.sortField == AnalyzerTreeSortField.COUNT
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isMetricSort) 0.085f else 0.035f)
    val residueBarColor = MaterialTheme.colorScheme.error.copy(alpha = if (isMetricSort) 0.10f else 0.05f)
    val maxCount = remember(children, sort) {
        if (sort.sortField == AnalyzerTreeSortField.COUNT) {
            children.maxOfOrNull { it.fileCount + it.dirCount }?.coerceAtLeast(1L) ?: 1L
        } else {
            1L
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (canNavigateUp) {
            item(key = "__parent_up__", contentType = "parent_up") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateUp)
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "返回上一级",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "..",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = if (parentLabel.isNotEmpty()) "返回上一级 · $parentLabel" else "返回上一级目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 54.dp, end = 12.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            }
        }

        if (children.isEmpty()) {
            item(key = "__empty_dir__", contentType = "empty_hint") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("空目录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        items(
            items = children,
            key = { it.path },
            contentType = { if (it.isDir) "tree_dir" else "tree_file" }
        ) { item ->
            val ratio = if (sort.sortField == AnalyzerTreeSortField.COUNT) {
                ((item.fileCount + item.dirCount).toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
            } else {
                item.shareOfParent
            }
            val isResidue = item.appBadge?.isInstalled == false
            val rowFill = if (isResidue) residueBarColor else barColor
            val iconBmp = item.appBadge?.packageName?.let { appIcons[it] }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (ratio > 0.005f) {
                            drawRect(
                                color = rowFill,
                                topLeft = Offset.Zero,
                                size = Size(size.width * ratio.coerceIn(0.015f, 1f), size.height)
                            )
                        }
                    }
                    .combinedClickable(
                        onClick = { onRowClick(item) },
                        onLongClick = { onRowMore(item) }
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TreeItemLeadingIcon(
                    isDir = item.isDir,
                    ext = item.ext,
                    appBadge = item.appBadge,
                    appIcon = iconBmp
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (item.isDir) FontWeight.Medium else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.appBadge != null) {
                            Spacer(Modifier.width(6.dp))
                            AppBadgePill(badge = item.appBadge)
                        }
                    }
                    Spacer(Modifier.height(1.5.dp))
                    val pctText = String.format(Locale.US, "%.1f%%", item.shareOfParent * 100f)
                    val dateText = if (item.mtime > 0L) FormatUtils.formatSmartDate(item.mtime) else ""
                    val metaText = if (item.isDir) {
                        buildString {
                            if (dateText.isNotEmpty()) {
                                append(dateText)
                                append(" · ")
                            }
                            append("${formatCompactCount(item.fileCount)} 项 · $pctText")
                        }
                    } else {
                        buildString {
                            if (dateText.isNotEmpty()) {
                                append(dateText)
                                append(" · ")
                            }
                            append(pctText)
                        }
                    }
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (useMonospaceFont) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = FormatUtils.formatBytes(item.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isResidue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { onRowMore(item) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 54.dp, end = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun AppsTabBody(
    snapshot: WizTreeSnapshot,
    onlyUninstalled: Boolean,
    expandedPkg: String?,
    appIcons: Map<String, ImageBitmap>,
    useMonospaceFont: Boolean,
    onToggleUninstalled: (Boolean) -> Unit,
    onToggleExpand: (String) -> Unit,
    onLocateLocation: (AppLocationStat) -> Unit,
    onLocationActions: (AppStorageSummary, AppLocationStat) -> Unit
) {
    val displayed = remember(snapshot.appRankings, onlyUninstalled) {
        if (onlyUninstalled) snapshot.appRankings.filter { !it.isInstalled } else snapshot.appRankings
    }

    if (snapshot.uninstalledAppCount > 0) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterPill(
                label = "全部 (${snapshot.appRankings.size})",
                selected = !onlyUninstalled,
                isAlert = false,
                onClick = { onToggleUninstalled(false) }
            )
            FilterPill(
                label = "已卸载残留 (${snapshot.uninstalledAppCount} · ${FormatUtils.formatBytes(snapshot.uninstalledResidueBytes)})",
                selected = onlyUninstalled,
                isAlert = true,
                onClick = { onToggleUninstalled(true) }
            )
        }
    }

    if (displayed.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (onlyUninstalled) "未发现已卸载应用残留" else "未识别到应用数据目录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val maxAppBytes = remember(displayed) { displayed.firstOrNull()?.totalBytes?.coerceAtLeast(1L) ?: 1L }
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.085f)
    val residueBarColor = MaterialTheme.colorScheme.error.copy(alpha = 0.11f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(
            items = displayed,
            key = { it.packageName },
            contentType = { "app_summary" }
        ) { app ->
            val expanded = expandedPkg == app.packageName
            val ratio = (app.totalBytes.toFloat() / maxAppBytes.toFloat()).coerceIn(0.015f, 1f)
            val fill = if (!app.isInstalled) residueBarColor else barColor
            val iconBmp = appIcons[app.packageName]

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRect(
                                color = fill,
                                topLeft = Offset.Zero,
                                size = Size(size.width * ratio, size.height)
                            )
                        }
                        .clickable { onToggleExpand(app.packageName) }
                        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconBmp != null) {
                        Image(
                            bitmap = iconBmp,
                            contentDescription = app.displayLabel,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (!app.isInstalled) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.secondaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (!app.isInstalled) Icons.Default.DeleteOutline else Icons.Default.Apps,
                                contentDescription = null,
                                tint = if (!app.isInstalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = app.displayLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (!app.isInstalled) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "已卸载残留",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${app.packageName} · ${formatCompactCount(app.fileCount)} 文件 · ${app.locations.size} 处目录",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = if (useMonospaceFont) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = FormatUtils.formatBytes(app.totalBytes),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!app.isInstalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(start = 24.dp, end = 10.dp, top = 4.dp, bottom = 6.dp)
                    ) {
                        for (loc in app.locations) {
                            val shortLoc = FormatUtils.shortenPath(loc.path, emptyList()).first
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onLocateLocation(loc) }
                                    .padding(vertical = 7.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = loc.category.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = shortLoc,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = FormatUtils.formatBytes(loc.totalBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = { onLocationActions(app, loc) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "目录操作",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesTabBody(
    snapshot: WizTreeSnapshot,
    files: List<LargeFileEntry>,
    selectedExt: String?,
    useMonospaceFont: Boolean,
    dark: Boolean,
    onSelectExt: (String?) -> Unit,
    onFileClick: (LargeFileEntry) -> Unit,
    onFileMore: (LargeFileEntry) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedExt) {
        listState.scrollToItem(0)
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "__all__", contentType = "ext_chip") {
            FilterPill(
                label = "全部 · ${FormatUtils.formatBytes(snapshot.totalBytes)}",
                selected = selectedExt == null,
                isAlert = false,
                onClick = { onSelectExt(null) }
            )
        }
        items(
            items = snapshot.extensionStats,
            key = { "ext_${it.ext}" },
            contentType = { "ext_chip" }
        ) { stat ->
            val dotColor = fileIconAccentColor(classifyVisualFamily(false, stat.ext), dark)
            val isSelected = selectedExt == stat.ext
            Surface(
                onClick = { onSelectExt(if (isSelected) null else stat.ext) },
                shape = RoundedCornerShape(9.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    0.6.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${stat.displayLabel} ${FormatUtils.formatBytes(stat.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }

    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无大文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val maxFileSize = remember(files) { files.firstOrNull()?.size?.coerceAtLeast(1L) ?: 1L }
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(
            items = files,
            key = { it.path },
            contentType = { "large_file" }
        ) { file ->
            val ratio = (file.size.toFloat() / maxFileSize.toFloat()).coerceIn(0.015f, 1f)
            val shortParent = remember(file.parentPath) { FormatUtils.shortenPath(file.parentPath, emptyList()).first }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width * ratio, size.height)
                        )
                    }
                    .combinedClickable(
                        onClick = { onFileClick(file) },
                        onLongClick = { onFileMore(file) }
                    )
                    .padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileTypeIcon(isDir = false, ext = file.ext, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (file.appBadge != null) {
                            Spacer(Modifier.width(6.dp))
                            AppBadgePill(badge = file.appBadge)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$shortParent · ${FormatUtils.formatSmartDate(file.mtime)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (useMonospaceFont) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = FormatUtils.formatBytes(file.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { onFileMore(file) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "文件操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp, end = 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun TreeItemLeadingIcon(
    isDir: Boolean,
    ext: String,
    appBadge: AppAttributionBadge?,
    appIcon: ImageBitmap?
) {
    if (isDir && appIcon != null) {
        Image(
            bitmap = appIcon,
            contentDescription = appBadge?.displayLabel,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else if (isDir && appBadge?.isInstalled == false) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "已卸载残留",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    } else {
        FileTypeIcon(isDir = isDir, ext = ext, size = 32.dp)
    }
}

@Composable
private fun AppBadgePill(badge: AppAttributionBadge) {
    val isResidue = !badge.isInstalled
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = if (isResidue) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
    ) {
        Text(
            text = if (isResidue) "已卸载残留" else badge.displayLabel,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (isResidue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    isAlert: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        selected && isAlert -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val fg = when {
        selected && isAlert -> MaterialTheme.colorScheme.onErrorContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        isAlert -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = bg,
        border = BorderStroke(
            0.6.dp,
            when {
                selected && isAlert -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun AnalyzerActionSheetContent(
    target: AnalyzerActionTarget,
    safety: com.pocketeverything.app.fs.DeletionCheckResult,
    onOpenOrEnter: () -> Unit,
    onLocateInTree: () -> Unit,
    onExcludeDir: () -> Unit,
    onCopyPath: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val shortPath = remember(target.path) { FormatUtils.shortenPath(target.path, emptyList()).first }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = target.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$shortPath · ${FormatUtils.formatBytes(target.totalBytes)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        SheetActionRow(
            icon = Icons.Default.FolderOpen,
            label = if (target.isDir) "进入目录" else "打开 / 预览",
            onClick = onOpenOrEnter
        )
        if (target.showLocateInTree) {
            SheetActionRow(
                icon = Icons.Default.AccountTree,
                label = "在目录树中定位",
                onClick = onLocateInTree
            )
        }
        SheetActionRow(
            icon = Icons.Default.VisibilityOff,
            label = if (target.isDir) "仅排除出搜索库（保留磁盘文件）" else "将所在目录排除出搜索库",
            onClick = onExcludeDir
        )
        SheetActionRow(
            icon = Icons.Default.ContentCopy,
            label = "复制完整路径",
            onClick = onCopyPath
        )

        val blocked = safety.risk == DeletionRisk.BLOCKED
        SheetActionRow(
            icon = if (blocked) Icons.Default.Block else Icons.Default.DeleteForever,
            label = if (blocked) "受保护目录（禁止物理删除）" else "从磁盘永久删除",
            subtitle = if (blocked) safety.reason else null,
            tint = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            enabled = !blocked,
            onClick = onRequestDelete
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) tint else tint.copy(alpha = 0.55f),
                fontWeight = if (tint == MaterialTheme.colorScheme.error) FontWeight.SemiBold else FontWeight.Normal
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    target: AnalyzerActionTarget,
    safety: com.pocketeverything.app.fs.DeletionCheckResult,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val shortPath = remember(target.path) { FormatUtils.shortenPath(target.path, emptyList()).first }
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = {
            Icon(
                imageVector = if (safety.risk == DeletionRisk.HIGH_PERSONAL) Icons.Default.WarningAmber else Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("确认永久删除？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = shortPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (target.isDir) {
                        "体积 ${FormatUtils.formatBytes(target.totalBytes)} · ${formatCompactCount(target.fileCount)} 个文件"
                    } else {
                        "体积 ${FormatUtils.formatBytes(target.totalBytes)}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error
                )
                if (safety.risk == DeletionRisk.HIGH_PERSONAL && !safety.reason.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = safety.reason,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(if (isDeleting) "删除中…" else "永久删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AnalyzerEmptyState(
    errorMessage: String?,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = errorMessage ?: "暂无已索引文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onRefresh) {
                Text("刷新")
            }
        }
    }
}

private fun buildTreemapInputs(
    snapshot: WizTreeSnapshot?,
    tab: AnalyzerTab,
    activeChildren: List<TreeChildEntry>,
    onlyUninstalled: Boolean
): List<TreemapInput> {
    if (snapshot == null) return emptyList()
    return when (tab) {
        AnalyzerTab.TREE -> {
            activeChildren
                .filter { it.totalBytes > 0L }
                .map { child ->
                    TreemapInput(
                        id = child.path,
                        label = child.appBadge?.let { if (!it.isInstalled) "${child.name} (残留)" else it.displayLabel } ?: child.name,
                        weight = child.totalBytes,
                        ext = child.ext,
                        isDir = child.isDir,
                        isResidue = child.appBadge?.isInstalled == false
                    )
                }
        }
        AnalyzerTab.APPS -> {
            val source = if (onlyUninstalled) snapshot.appRankings.filter { !it.isInstalled } else snapshot.appRankings
            source.filter { it.totalBytes > 0L }.map { app ->
                TreemapInput(
                    id = app.packageName,
                    label = app.displayLabel,
                    weight = app.totalBytes,
                    ext = "",
                    isDir = true,
                    isResidue = !app.isInstalled
                )
            }
        }
        AnalyzerTab.FILES -> {
            snapshot.extensionStats.filter { it.totalBytes > 0L }.map { stat ->
                TreemapInput(
                    id = stat.ext,
                    label = stat.displayLabel,
                    weight = stat.totalBytes,
                    ext = stat.ext,
                    isDir = false
                )
            }
        }
    }
}

private fun buildBreadcrumbs(path: String, rootPaths: List<String>): List<Pair<String, String>> {
    val matchedRoot = rootPaths
        .filter { root -> path == root || (root == "/" && path.startsWith("/")) || path.startsWith("$root/") }
        .maxByOrNull { it.length }
        ?: "/"

    val rootLabel = when (matchedRoot) {
        "/storage/emulated/0", "/sdcard" -> "内部存储"
        else -> matchedRoot
    }
    val list = ArrayList<Pair<String, String>>()
    list.add(matchedRoot to rootLabel)
    if (path == matchedRoot) return list

    val rel = if (matchedRoot == "/") path.removePrefix("/") else path.removePrefix("$matchedRoot/")
    var acc = if (matchedRoot == "/") "" else matchedRoot
    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
        acc = "$acc/$seg"
        list.add(acc to seg)
    }
    return list
}

private fun formatCompactCount(count: Long): String = when {
    count >= 100_000L -> String.format(Locale.US, "%.1f万", count / 10_000.0)
    else -> String.format(Locale.US, "%,d", count)
}

private val NoOpAnalyzerActions = object : StorageAnalyzerActions {
    override fun onExit() {}
    override fun onNavigateUp() {}
    override fun onRefresh() {}
    override fun onSelectTab(tab: AnalyzerTab) {}
    override fun onNavigateDir(path: String) {}
    override fun onSelectTreeSort(sort: AnalyzerTreeSort) {}
    override fun onToggleFoldersFirst() {}
    override fun onSelectExtFilter(ext: String?) {}
    override fun onToggleUninstalledOnly(enabled: Boolean) {}
    override fun onToggleTreemap() {}
    override fun onOpenFile(path: String) {}
    override fun onDeletePath(path: String, accessMode: AccessMode, isDir: Boolean) {}
    override fun onExcludeDir(dirPath: String) {}
    override fun onCopyPath(path: String) {}
}

private fun previewWizTreeSnapshot(): WizTreeSnapshot {
    val wechatBadge = AppAttributionBadge("com.tencent.mm", "微信", true, AppPathCategory.APP_DATA)
    val deadGameBadge = AppAttributionBadge("com.legacy.rpg", "旧手游", false, AppPathCategory.APP_OBB)
    val quarkBadge = AppAttributionBadge("com.quark.browser", "夸克", true, AppPathCategory.SHARED_STORAGE)

    val rootChildren = listOf(
        TreeChildEntry("/storage/emulated/0/Android", "Android", "", true, 18_400_000_000L, 42_180, 910, 1794980000000L, AccessMode.SHIZUKU, 0.43f, null),
        TreeChildEntry("/storage/emulated/0/DCIM", "DCIM", "", true, 9_800_000_000L, 4_120, 18, 1794970000000L, AccessMode.NORMAL, 0.23f, null),
        TreeChildEntry("/storage/emulated/0/Download", "Download", "", true, 6_450_000_000L, 682, 24, 1794960000000L, AccessMode.NORMAL, 0.15f, null),
        TreeChildEntry("/storage/emulated/0/Android/obb/com.legacy.rpg", "com.legacy.rpg", "", true, 4_120_000_000L, 14, 2, 1794000000000L, AccessMode.SHIZUKU, 0.10f, deadGameBadge),
        TreeChildEntry("/storage/emulated/0/Quark", "Quark", "", true, 2_840_000_000L, 1_904, 52, 1794950000000L, AccessMode.NORMAL, 0.07f, quarkBadge),
        TreeChildEntry("/storage/emulated/0/backup_2026.tar.gz", "backup_2026.tar.gz", "gz", false, 1_190_000_000L, 1, 0, 1794940000000L, AccessMode.NORMAL, 0.02f, null)
    )
    val rootDir = DirNodeStat(
        path = "/storage/emulated/0",
        parentPath = "/storage/emulated",
        name = "0",
        totalBytes = 42_800_000_000L,
        fileCount = 48_901,
        dirCount = 1_006,
        mtime = 1794980000000L,
        accessMode = AccessMode.SHIZUKU,
        appBadge = null,
        childrenBySize = rootChildren
    )
    return WizTreeSnapshot(
        totalBytes = 42_800_000_000L,
        totalFiles = 48_901,
        totalDirs = 1_006,
        buildDurationMs = 46L,
        rootPaths = listOf("/"),
        shortcuts = listOf(
            com.pocketeverything.app.analyzer.AnalyzerShortcut("/", "/", 47_000_000_000L),
            com.pocketeverything.app.analyzer.AnalyzerShortcut("/data", "/data", 4_200_000_000L),
            com.pocketeverything.app.analyzer.AnalyzerShortcut("/storage/emulated/0", "内部存储", 42_800_000_000L)
        ),
        dirNodes = mapOf("/storage/emulated/0" to rootDir, "/data" to rootDir.copy(path = "/data", name = "data", totalBytes = 4_200_000_000L)),
        appRankings = listOf(
            AppStorageSummary(
                packageName = "com.tencent.mm",
                displayLabel = "微信",
                isInstalled = true,
                totalBytes = 14_280_000_000L,
                fileCount = 38_412,
                shareOfTotal = 0.33f,
                locations = listOf(
                    AppLocationStat("/storage/emulated/0/Android/data/com.tencent.mm", AppPathCategory.APP_DATA, 11_900_000_000L, 35_200, AccessMode.SHIZUKU),
                    AppLocationStat("/storage/emulated/0/Pictures/WeiXin", AppPathCategory.SHARED_STORAGE, 2_380_000_000L, 3_212, AccessMode.NORMAL)
                )
            ),
            AppStorageSummary(
                packageName = "com.legacy.rpg",
                displayLabel = "com.legacy.rpg",
                isInstalled = false,
                totalBytes = 4_120_000_000L,
                fileCount = 14,
                shareOfTotal = 0.10f,
                locations = listOf(
                    AppLocationStat("/storage/emulated/0/Android/obb/com.legacy.rpg", AppPathCategory.APP_OBB, 4_120_000_000L, 14, AccessMode.SHIZUKU)
                )
            )
        ),
        extensionStats = listOf(
            ExtensionStat("mp4", ".mp4", 12_400_000_000L, 184, 0.29f),
            ExtensionStat("obb", ".obb", 4_120_000_000L, 4, 0.10f),
            ExtensionStat("jpg", ".jpg", 5_600_000_000L, 6_410, 0.13f),
            ExtensionStat("apk", ".apk", 3_100_000_000L, 42, 0.07f),
            ExtensionStat("", "无后缀", 2_800_000_000L, 18_920, 0.06f)
        ),
        topLargeFiles = listOf(
            LargeFileEntry("/storage/emulated/0/Android/obb/com.legacy.rpg/main.204.obb", "/storage/emulated/0/Android/obb/com.legacy.rpg", "main.204.obb", "obb", 3_820_000_000L, 1794000000000L, AccessMode.SHIZUKU, deadGameBadge),
            LargeFileEntry("/storage/emulated/0/DCIM/Camera/VID_20261118_4K.mp4", "/storage/emulated/0/DCIM/Camera", "VID_20261118_4K.mp4", "mp4", 1_940_000_000L, 1794970000000L, AccessMode.NORMAL, null),
            LargeFileEntry("/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/msg_backup.db", "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg", "msg_backup.db", "db", 1_420_000_000L, 1794960000000L, AccessMode.SHIZUKU, wechatBadge)
        ),
        topFilesByExt = emptyMap()
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun StorageAnalyzerTreeLightPreview() {
    PocketEverythingTheme(darkTheme = false, dynamicColor = false) {
        StorageAnalyzerScreen(
            state = StorageAnalyzerUiState(
                snapshot = previewWizTreeSnapshot(),
                selectedTab = AnalyzerTab.TREE,
                currentDirPath = "/storage/emulated/0"
            ),
            useMonospaceFont = false,
            actions = NoOpAnalyzerActions
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun StorageAnalyzerTreeDarkPreview() {
    PocketEverythingTheme(darkTheme = true, dynamicColor = false, pureBlackDark = true) {
        StorageAnalyzerScreen(
            state = StorageAnalyzerUiState(
                snapshot = previewWizTreeSnapshot(),
                selectedTab = AnalyzerTab.TREE,
                currentDirPath = "/storage/emulated/0"
            ),
            useMonospaceFont = false,
            actions = NoOpAnalyzerActions
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun StorageAnalyzerAppsDarkPreview() {
    PocketEverythingTheme(darkTheme = true, dynamicColor = false) {
        StorageAnalyzerScreen(
            state = StorageAnalyzerUiState(
                snapshot = previewWizTreeSnapshot(),
                selectedTab = AnalyzerTab.APPS,
                currentDirPath = "/storage/emulated/0"
            ),
            useMonospaceFont = false,
            actions = NoOpAnalyzerActions
        )
    }
}

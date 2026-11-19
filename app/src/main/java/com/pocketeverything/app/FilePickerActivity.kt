package com.pocketeverything.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketeverything.app.data.ThemeMode
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.Tier1Phase
import com.pocketeverything.app.picker.MimeFilterHelper
import com.pocketeverything.app.search.QuickScope
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.search.SortOrder
import com.pocketeverything.app.ui.FormatUtils
import com.pocketeverything.app.ui.components.BrandMarkIcon
import com.pocketeverything.app.ui.components.FileTypeIcon
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.PocketEverythingTheme
import com.pocketeverything.app.ui.theme.Slate950
import com.pocketeverything.app.ui.theme.rememberAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilePickerActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        val app = application as PocketEverythingApp
        app.container.indexManager.triggerForegroundSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as PocketEverythingApp
        val container = app.container
        val allowMultiple = intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true
        val acceptedMimes = MimeFilterHelper.extractAcceptedMimes(
            primaryType = intent?.type,
            extraTypes = intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        )

        setContent {
            val settings by container.settingsRepository.settings.collectAsState()
            val indexProgress by container.indexManager.progress.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDarkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            SideEffect {
                val style = if (isDarkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            PocketEverythingTheme(
                darkTheme = isDarkTheme,
                dynamicColor = settings.useDynamicColor,
                themeAccent = settings.themeAccent,
                pureBlackDark = settings.pureBlackDark,
                hapticsEnabled = settings.hapticFeedbackEnabled,
                hapticStrength = settings.hapticStrength
            ) {
                FilePickerContent(
                    allowMultiple = allowMultiple,
                    acceptedMimes = acceptedMimes,
                    useMonospaceFont = settings.useMonospaceFont,
                    compactMode = settings.compactListMode,
                    indexProgress = indexProgress,
                    loadTotalEntries = {
                        withContext(Dispatchers.IO) {
                            runCatching { container.database.getStats().totalEntries }.getOrDefault(0L)
                        }
                    },
                    loadItems = { query, currentDir, enforceMime ->
                        withContext(Dispatchers.IO) {
                            val db = container.database
                            val rawItems = if (currentDir != null) {
                                db.listChildren(currentDir, limit = 500)
                            } else if (query.isBlank() && enforceMime && acceptedMimes.isNotEmpty()) {
                                val matchingExts = MimeFilterHelper.matchingExtensions(acceptedMimes)
                                if (matchingExts.isNotEmpty()) {
                                    db.search(
                                        rawQuery = "ext:${matchingExts.joinToString(";")}",
                                        quickScope = QuickScope.FILES,
                                        sortOrder = SortOrder.MODIFIED_DESC,
                                        limit = 150,
                                        showRecentOnEmpty = true
                                    ).items
                                } else {
                                    db.search(
                                        rawQuery = "",
                                        quickScope = QuickScope.FILES,
                                        sortOrder = SortOrder.MODIFIED_DESC,
                                        limit = 300,
                                        showRecentOnEmpty = true
                                    ).items
                                }
                            } else {
                                db.search(
                                    rawQuery = query,
                                    quickScope = QuickScope.ALL,
                                    sortOrder = if (query.isBlank()) SortOrder.MODIFIED_DESC else SortOrder.RELEVANCE,
                                    limit = if (enforceMime && acceptedMimes.isNotEmpty()) 400 else 150,
                                    showRecentOnEmpty = true
                                ).items
                            }
                            if (enforceMime && acceptedMimes.isNotEmpty()) {
                                rawItems.filter { it.isDir || MimeFilterHelper.matches(it.ext, acceptedMimes) }
                            } else {
                                rawItems
                            }
                        }
                    },
                    onResolveFiles = { items ->
                        withContext(Dispatchers.IO) {
                            items.map { item ->
                                item.name to container.fileOpener.resolveShareableUri(item)
                            }
                        }
                    },
                    onDeliverSelection = { resolved ->
                        val resultIntent = buildResultIntent(resolved)
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onOpenMainApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                        )
                        finish()
                    }
                )
            }
        }
    }

    private fun buildResultIntent(resolved: List<Pair<String, Uri>>): Intent {
        val intent = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (resolved.size == 1) {
            val (name, uri) = resolved[0]
            intent.data = uri
            intent.clipData = ClipData.newRawUri(name, uri)
        } else if (resolved.isNotEmpty()) {
            val clip = ClipData.newRawUri(resolved[0].first, resolved[0].second)
            for (i in 1 until resolved.size) {
                clip.addItem(ClipData.Item(resolved[i].second))
            }
            intent.clipData = clip
        }
        return intent
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerContent(
    allowMultiple: Boolean,
    acceptedMimes: Set<String>,
    useMonospaceFont: Boolean,
    compactMode: Boolean,
    indexProgress: IndexProgressState,
    loadTotalEntries: suspend () -> Long,
    loadItems: suspend (query: String, currentDir: String?, enforceMime: Boolean) -> List<SearchResultItem>,
    onResolveFiles: suspend (List<SearchResultItem>) -> List<Pair<String, Uri>>,
    onDeliverSelection: (List<Pair<String, Uri>>) -> Unit,
    onCancel: () -> Unit,
    onOpenMainApp: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var dirStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var enforceMimeFilter by remember(acceptedMimes) { mutableStateOf(acceptedMimes.isNotEmpty()) }
    var totalEntries by remember { mutableStateOf<Long?>(null) }
    var items by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isResolving by remember { mutableStateOf(false) }
    var lastLoadedNavKey by remember { mutableStateOf<Triple<String, String?, Boolean>?>(null) }

    val selectedMap = remember { mutableStateMapOf<String, SearchResultItem>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    val currentDir = dirStack.lastOrNull()
    val isIndexSyncing = indexProgress.tier1Phase != Tier1Phase.IDLE
    val indexSyncGeneration = indexProgress.syncGeneration
    val indexTotalEntries = indexProgress.stats.totalEntries
    val indexIndexedDocs = indexProgress.stats.indexedDocs

    BackHandler(enabled = dirStack.isNotEmpty()) {
        dirStack = dirStack.dropLast(1)
    }

    LaunchedEffect(indexSyncGeneration, indexTotalEntries) {
        totalEntries = loadTotalEntries()
    }

    LaunchedEffect(query, currentDir, enforceMimeFilter, indexSyncGeneration, indexTotalEntries, indexIndexedDocs) {
        val navKey = Triple(query, currentDir, enforceMimeFilter)
        val navChanged = navKey != lastLoadedNavKey
        if (navChanged && currentDir == null && query.isNotBlank()) {
            delay(65)
        }
        if (navChanged) {
            isLoading = true
        }
        runCatching {
            loadItems(query, currentDir, enforceMimeFilter)
        }.onSuccess { loaded ->
            items = loaded
            if (navChanged) {
                lastLoadedNavKey = navKey
                listState.scrollToItem(0)
            }
        }.onFailure { err ->
            snackbarHostState.showSnackbar("加载失败：${err.message ?: "请重试"}")
        }
        isLoading = false
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) keyboard?.hide()
    }

    fun confirmSelection(targets: List<SearchResultItem>) {
        if (targets.isEmpty() || isResolving) return
        isResolving = true
        scope.launch {
            runCatching {
                onResolveFiles(targets)
            }.onSuccess { resolved ->
                onDeliverSelection(resolved)
            }.onFailure { err ->
                isResolving = false
                snackbarHostState.showSnackbar(err.message ?: "无法读取所选文件")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        haptics.tick()
                        onCancel()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "取消")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMarkIcon(size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Pinpoint 选择",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (allowMultiple) {
                                Text(
                                    text = "支持多选文件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (allowMultiple) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选 ${selectedMap.size} 项",
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedMap.isNotEmpty() && !isResolving) {
                            TextButton(onClick = { selectedMap.clear() }) {
                                Text("清空")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Button(
                            enabled = selectedMap.isNotEmpty() && !isResolving,
                            onClick = {
                                haptics.click()
                                confirmSelection(selectedMap.values.toList())
                            }
                        ) {
                            Text(if (isResolving) "处理中…" else "确认选择")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            PickerSearchField(
                query = query,
                onQueryChange = { next ->
                    if (dirStack.isNotEmpty()) dirStack = emptyList()
                    query = next
                },
                onSubmit = { keyboard?.hide() },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (acceptedMimes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PickerFilterPill(
                        label = MimeFilterHelper.describeAcceptedMimes(acceptedMimes),
                        selected = enforceMimeFilter,
                        onClick = {
                            haptics.tick()
                            enforceMimeFilter = true
                        }
                    )
                    PickerFilterPill(
                        label = "全部格式",
                        selected = !enforceMimeFilter,
                        onClick = {
                            haptics.tick()
                            enforceMimeFilter = false
                        }
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }

            if (currentDir != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = {
                            haptics.tick()
                            dirStack = dirStack.dropLast(1)
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回搜索或上级目录",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "返回搜索 / 上级",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Text(
                        text = FormatUtils.shortenPath(currentDir, emptyList()).first,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = if (useMonospaceFont) FontFamily.Monospace else FontFamily.Default
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (isLoading || isResolving || isIndexSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            when {
                totalEntries == 0L && !isIndexSyncing -> {
                    PickerEmptyIndexState(onOpenMainApp = onOpenMainApp)
                }
                !isLoading && !isIndexSyncing && items.isEmpty() -> {
                    PickerNoResultsState(
                        enforceMimeFilter = enforceMimeFilter,
                        onShowAllFormats = { enforceMimeFilter = false }
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                )
                            }
                            val selected = !item.isDir && selectedMap.containsKey(item.path)
                            PickerResultRow(
                                item = item,
                                allowMultiple = allowMultiple,
                                isSelected = selected,
                                useMonospaceFont = useMonospaceFont,
                                compactMode = compactMode,
                                enabled = !isResolving,
                                onClick = {
                                    haptics.tick()
                                    if (item.isDir) {
                                        dirStack = dirStack + item.path
                                    } else if (allowMultiple) {
                                        if (selected) selectedMap.remove(item.path)
                                        else selectedMap[item.path] = item
                                    } else {
                                        confirmSelection(listOf(item))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth()
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
        }
    }
}

@Composable
private fun PickerFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
        border = BorderStroke(
            width = if (selected) 0.9.dp else 0.5.dp,
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PickerResultRow(
    item: SearchResultItem,
    allowMultiple: Boolean,
    isSelected: Boolean,
    useMonospaceFont: Boolean,
    compactMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val hlBg = Amber200.copy(alpha = 0.72f)
    val hlFg = Slate950
    val monoFamily = if (useMonospaceFont) FontFamily.Monospace else FontFamily.Default

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
        fontFamily = monoFamily,
        fontFeatureSettings = "tnum"
    )
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (compactMode) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileTypeIcon(
            isDir = item.isDir,
            ext = item.ext,
            size = if (compactMode) 34.dp else 38.dp
        )
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
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
            Spacer(Modifier.height(if (compactMode) 1.dp else 3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pathText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = monoFamily),
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

        if (item.isDir) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入文件夹",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        } else if (allowMultiple) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "已选中" else "未选中",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PickerEmptyIndexState(onOpenMainApp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "索引库尚为空",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "请先打开 Pinpoint 完成授权并建立文件索引，之后即可在系统选择器中极速查找文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(onClick = onOpenMainApp) {
            Text("打开 Pinpoint 建库")
        }
    }
}

@Composable
private fun PickerNoResultsState(
    enforceMimeFilter: Boolean,
    onShowAllFormats: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "无匹配结果",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (enforceMimeFilter) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onShowAllFormats) {
                Text("切换为全部格式")
            }
        }
    }
}

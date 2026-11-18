package com.pocketeverything.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.BuildConfig
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.data.ChangelogState
import com.pocketeverything.app.data.ReleaseUpdateInfo
import com.pocketeverything.app.data.UpdateCheckState
import com.pocketeverything.app.data.UpdateChecker
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.Tier1Phase
import com.pocketeverything.app.indexer.Tier2Phase
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.CrowdedDirStat
import com.pocketeverything.app.search.CrowdedSortBy
import com.pocketeverything.app.ui.components.BrandMarkIcon
import com.pocketeverything.app.ui.components.MagneticSnapSlider
import com.pocketeverything.app.ui.components.SliderQuickNode
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Emerald400
import com.pocketeverything.app.ui.theme.LocalCardOpacity
import com.pocketeverything.app.ui.theme.LocalHasCustomBg
import com.pocketeverything.app.ui.theme.Rose400
import com.pocketeverything.app.ui.theme.groupedBackdrop
import com.pocketeverything.app.ui.theme.groupedCard
import com.pocketeverything.app.ui.theme.lowOpacityCardBorder
import com.pocketeverything.app.ui.theme.rememberAppHaptics
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    modeStatuses: Map<AccessMode, ModeAvailability>,
    indexProgress: IndexProgressState,
    crowdedDirs: List<CrowdedDirStat>,
    crowdedSortBy: CrowdedSortBy = CrowdedSortBy.FILE_COUNT,
    updateState: UpdateCheckState,
    onBack: () -> Unit,
    onSelectMode: (AccessMode) -> Unit,
    onOpenThemeSettings: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenShizukuGuide: () -> Unit,
    onRefreshModes: () -> Unit,
    onUpdateFilterConfig: (FilterConfig) -> Unit,
    onExcludeCrowdedPath: (String) -> Unit,
    onRemoveExcludedPath: (String) -> Unit = {},
    onSetCrowdedSortBy: (CrowdedSortBy) -> Unit = {},
    onSetShowRecentOnEmpty: (Boolean) -> Unit,
    onSetPreviewPreference: (Boolean) -> Unit,
    onSetSystemFilePickerEnabled: (Boolean) -> Unit = {},
    onSetRealtimeMonitor: (Boolean) -> Unit,
    onQuickSync: () -> Unit,
    onFullRebuild: () -> Unit,
    onCancelScan: () -> Unit,
    onDismissScanError: () -> Unit,
    onSetReceiveStableUpdates: (Boolean) -> Unit,
    onSetReceiveBetaUpdates: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onReplayOnboarding: () -> Unit
) {
    val cfg = settings.filterConfig
    var lastAppliedCfg by remember { mutableStateOf(cfg) }
    var skipNoise by remember { mutableStateOf(cfg.skipNoiseCaches) }
    var rootIncludeData by remember { mutableStateOf(cfg.rootIncludeData) }
    var maxTxtMb by remember { mutableFloatStateOf((cfg.maxTxtMdBytes / (1024f * 1024f)).coerceIn(1f, 100f)) }
    var maxDocxMb by remember { mutableFloatStateOf((cfg.maxDocxBytes / (1024f * 1024f)).coerceIn(1f, 100f)) }
    var maxPdfMb by remember { mutableFloatStateOf((cfg.maxPdfBytes / (1024f * 1024f)).coerceIn(1f, 150f)) }
    var customExcludeText by remember { mutableStateOf(cfg.customExcludedPaths.joinToString("\n")) }
    var customExcludeDirNamesText by remember { mutableStateOf(cfg.customExcludedDirNames.joinToString("\n")) }
    var tier2AllowedText by remember { mutableStateOf(cfg.tier2AllowedPrefixes.joinToString("\n")) }
    var expandModes by remember { mutableStateOf(false) }
    var expandAdvancedFilters by remember { mutableStateOf(false) }

    // Sync only fields that changed in saved config without clobbering unsaved text/slider drafts.
    LaunchedEffect(cfg) {
        val prev = lastAppliedCfg
        if (cfg != prev) {
            if (cfg.skipNoiseCaches != prev.skipNoiseCaches) skipNoise = cfg.skipNoiseCaches
            if (cfg.rootIncludeData != prev.rootIncludeData) rootIncludeData = cfg.rootIncludeData
            if (cfg.maxTxtMdBytes != prev.maxTxtMdBytes) {
                maxTxtMb = (cfg.maxTxtMdBytes / (1024f * 1024f)).coerceIn(1f, 100f)
            }
            if (cfg.maxDocxBytes != prev.maxDocxBytes) {
                maxDocxMb = (cfg.maxDocxBytes / (1024f * 1024f)).coerceIn(1f, 100f)
            }
            if (cfg.maxPdfBytes != prev.maxPdfBytes) {
                maxPdfMb = (cfg.maxPdfBytes / (1024f * 1024f)).coerceIn(1f, 150f)
            }
            if (cfg.customExcludedPaths != prev.customExcludedPaths) {
                val added = cfg.customExcludedPaths - prev.customExcludedPaths.toSet()
                if (cfg.customExcludedPaths.isEmpty()) {
                    customExcludeText = ""
                } else if (added.isNotEmpty()) {
                    val existingLines = customExcludeText.lines()
                        .map { it.trim().trimEnd('/') }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    val toAppend = added.filter { it.trim().trimEnd('/') !in existingLines }
                    if (toAppend.isNotEmpty()) {
                        customExcludeText = if (customExcludeText.isBlank()) {
                            toAppend.joinToString("\n")
                        } else {
                            customExcludeText.trimEnd() + "\n" + toAppend.joinToString("\n")
                        }
                    }
                }
            }
            if (cfg.customExcludedDirNames != prev.customExcludedDirNames) {
                customExcludeDirNamesText = cfg.customExcludedDirNames.joinToString("\n")
            }
            if (cfg.tier2AllowedPrefixes != prev.tier2AllowedPrefixes) {
                tier2AllowedText = cfg.tier2AllowedPrefixes.joinToString("\n")
            }
            lastAppliedCfg = cfg
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onSetRealtimeMonitor(true)
    }

    val haptics = rememberAppHaptics()
    val hasCustomBg = LocalHasCustomBg.current
    val cardAlpha = LocalCardOpacity.current
    val pageBackdrop = if (hasCustomBg) Color.Transparent else MaterialTheme.colorScheme.groupedBackdrop
    val cardColor = MaterialTheme.colorScheme.groupedCard.copy(alpha = cardAlpha)

    Scaffold(
        containerColor = pageBackdrop,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageBackdrop,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = {
                        haptics.tick()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top Version & Update Hero Card (Big-Tech Updater Placement)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardColor,
                border = lowOpacityCardBorder(hasCustomBg = hasCustomBg, cardAlpha = cardAlpha),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BrandMarkIcon(size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pinpoint",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} · #${BuildConfig.VERSION_CODE}",
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val statusText: Pair<String, Color>? = when (updateState) {
                                is UpdateCheckState.UpToDate -> "已是最新版本" to Emerald400
                                is UpdateCheckState.Available -> "发现新版本 ${updateState.info.tagName}" to MaterialTheme.colorScheme.primary
                                is UpdateCheckState.Error -> "检查失败：${updateState.message}" to Rose400
                                else -> null
                            }
                            if (statusText != null) {
                                Text(
                                    text = statusText.first,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusText.second,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onCheckUpdates,
                            enabled = updateState !is UpdateCheckState.Checking,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            if (updateState is UpdateCheckState.Checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("检测中", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Text("检查更新", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenChangelog)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "查看版本更新日志 (Changelog)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "查看更新日志",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ToggleRow(
                            title = "接收正式版更新",
                            subtitle = "关闭后不再自动检查或推送正式版更新",
                            checked = settings.receiveStableUpdates,
                            onCheckedChange = onSetReceiveStableUpdates
                        )
                        SettingsDivider()
                        ToggleRow(
                            title = "接收测试版更新",
                            subtitle = "抢先体验 GitHub Pre-release 预览版本（默认关闭）",
                            checked = settings.receiveBetaUpdates,
                            onCheckedChange = onSetReceiveBetaUpdates
                        )
                    }
                }
            }

            // 2. Access Mode & Permissions (collapsed by default; shows current active mode only)
            SettingsSection(title = "访问模式与权限", cardColor = cardColor) {
                val activeMode = settings.accessMode
                val activeStatus = modeStatuses[activeMode]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.tick()
                            expandModes = !expandModes
                            if (expandModes) onRefreshModes()
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeMode.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = modeScopeDescription(activeMode, rootIncludeData),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ModeStatusChip(activeStatus)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = if (expandModes) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expandModes) "收起模式选择" else "切换访问模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(visible = expandModes) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        AccessMode.entries.forEach { mode ->
                            SettingsDivider()
                            val status = modeStatuses[mode]
                            val selected = settings.accessMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptics.tick()
                                        onSelectMode(mode)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        haptics.tick()
                                        onSelectMode(mode)
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = modeScopeDescription(mode, rootIncludeData),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                when {
                                    mode == AccessMode.NORMAL && status is ModeAvailability.NeedsPermission -> {
                                        TextButton(
                                            onClick = onRequestStoragePermission,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("去授权", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    mode == AccessMode.SHIZUKU -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                onClick = onOpenShizukuGuide,
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                contentColor = MaterialTheme.colorScheme.primary
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("指引", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                        contentDescription = "Shizuku 指引",
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                            ModeStatusChip(status)
                                        }
                                    }
                                    else -> ModeStatusChip(status)
                                }
                            }
                        }

                        SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRefreshModes() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("重新检测模式与权限状态", style = MaterialTheme.typography.bodyMedium)
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // 3. Index & Sync Management (with live progress, go-home shortcut & explicit errors)
            SettingsSection(title = "索引与同步", cardColor = cardColor) {
                val stats = indexProgress.stats
                val tier1Busy = indexProgress.tier1Phase != Tier1Phase.IDLE
                val tier2Busy = indexProgress.tier2Phase != Tier2Phase.IDLE
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricColumn(label = "总条目", value = "${stats.totalEntries}", modifier = Modifier.weight(1f))
                    VerticalDivider(modifier = Modifier.height(28.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    MetricColumn(label = "已提正文", value = "${stats.indexedDocs}", modifier = Modifier.weight(1f))
                    VerticalDivider(modifier = Modifier.height(28.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    MetricColumn(label = "待抽队列", value = "${stats.pendingDocs}", modifier = Modifier.weight(1f))
                    VerticalDivider(modifier = Modifier.height(28.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    MetricColumn(label = "库体积", value = FormatUtils.formatBytes(stats.dbSizeBytes), modifier = Modifier.weight(1f))
                }

                if (tier1Busy || tier2Busy) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (tier1Busy) {
                                indexProgress.statusMessage
                            } else {
                                "正在后台提取正文 · 已处理 ${indexProgress.tier2ProcessedCount} / 剩余 ${indexProgress.tier2PendingCount}"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("去首页看进度", style = MaterialTheme.typography.labelSmall) }
                        if (tier1Busy) {
                            TextButton(
                                onClick = onCancelScan,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("取消", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                } else if (!indexProgress.errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = indexProgress.errorMessage,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onDismissScanError,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("知道了", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                } else {
                    Text(
                        text = "${indexProgress.statusMessage} · 启动与开机将自动对全部用户目录增量同步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onQuickSync,
                        enabled = !tier1Busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("增量同步")
                    }
                    Button(
                        onClick = onFullRebuild,
                        enabled = !tier1Busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (tier1Busy) "重建中…" else "重建全库")
                    }
                }

                SettingsDivider()
                ToggleRow(
                    title = "后台实时监控（文件变动自动同步）",
                    checked = settings.realtimeMonitorEnabled,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onSetRealtimeMonitor(wantEnable)
                        }
                    }
                )
            }

            // 4. Appearance, Search & Preview Behavior
            SettingsSection(title = "外观与搜索", cardColor = cardColor) {
                val themeSummary = buildString {
                    append(settings.themeMode.label)
                    append(" · ")
                    when {
                        hasCustomBg && settings.extractColorFromBg && settings.customBgSeedArgb != 0 -> append("背景图取色")
                        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> append("系统动态色")
                        else -> append(settings.themeAccent.label)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.click()
                            onOpenThemeSettings()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("主题与外观", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text = "主题模式、莫奈色盘、自定义背景与微震动",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TwoToneSwatchDot(
                        primary = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        isSelected = true
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = themeSummary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "进入主题设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                SettingsDivider()
                ToggleRow(
                    title = "点击优先使用内置预览",
                    checked = settings.openContentHitsInPreview,
                    onCheckedChange = onSetPreviewPreference
                )
                SettingsDivider()
                ToggleRow(
                    title = "搜索栏为空时显示最近文件",
                    checked = settings.showRecentOnEmptyQuery,
                    onCheckedChange = onSetShowRecentOnEmpty
                )
                SettingsDivider()
                ToggleRow(
                    title = "系统文件选择器入口",
                    subtitle = "在微信/QQ 等系统文件选择面板提供「Pinpoint」(SAF) 与「Pinpoint 选择」入口",
                    checked = settings.systemFilePickerEnabled,
                    onCheckedChange = onSetSystemFilePickerEnabled
                )
                SettingsDivider()
                NavigationRow(
                    title = "重看新手操作引导",
                    onClick = onReplayOnboarding
                )
            }

            // 5. Filtering & Extraction Rules (Two-Stage Architecture: Tier-1 Scan Exclusion + Tier-2 Body Extraction)
            var pendingPersonalDirExclude by remember { mutableStateOf<String?>(null) }
            var showManualExcludeInput by remember { mutableStateOf(false) }

            SettingsSection(title = "过滤与提取规则", cardColor = cardColor) {
                ToggleRow(
                    title = "Root 扫描包含 /data 目录",
                    subtitle = if (settings.accessMode == AccessMode.ROOT) {
                        "包含 /data 会显著增加索引体积和扫描时间（开启后日常增量只跟进存储与 /data/adb，应用私有 /data/data 需重建）"
                    } else {
                        "仅 Root 全盘模式生效（Shizuku 与普通模式无法访问系统 /data 全盘）"
                    },
                    checked = rootIncludeData,
                    enabled = settings.accessMode == AccessMode.ROOT,
                    onCheckedChange = {
                        rootIncludeData = it
                        onUpdateFilterConfig(cfg.copy(rootIncludeData = it, deduplicateMounts = true))
                    }
                )
                SettingsDivider()
                ToggleRow(
                    title = "自动屏蔽缩略图、预览图与缓存目录",
                    subtitle = "扫描时直接跳过 .thumbnails、previews、temp、webview、回收站等高噪目录（保留 /data/local/tmp）",
                    checked = skipNoise,
                    onCheckedChange = {
                        skipNoise = it
                        onUpdateFilterConfig(cfg.copy(skipNoiseCaches = it, deduplicateMounts = true, rootIncludeData = rootIncludeData))
                    }
                )
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandAdvancedFilters = !expandAdvancedFilters }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "大目录排查、自定义排除与正文规则",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "已排除 ${cfg.customExcludedPaths.size} 个目录 · 跳过 ${cfg.customExcludedDirNames.size} 类文件夹 · 正文上限 ${cfg.maxTxtMdBytes / (1024L * 1024L)}/${cfg.maxDocxBytes / (1024L * 1024L)}/${cfg.maxPdfBytes / (1024L * 1024L)}M",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (expandAdvancedFilters) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = expandAdvancedFilters) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        // ==================== STAGE 1: TIER-1 SCAN & CROWDED DIR CLEANUP ====================
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    text = "一 · 全盘扫描与大目录清理（管哪些文件入库）",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "全盘扫描后，可在下方查看文件最多或占空间最大的目录；点击「排除」会直接从索引库物理删除并释放库体积。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (crowdedDirs.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "库内大目录排行 Top ${crowdedDirs.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CrowdedSortBy.entries.forEach { mode ->
                                    val selected = crowdedSortBy == mode
                                    Surface(
                                        onClick = {
                                            haptics.tick()
                                            onSetCrowdedSortBy(mode)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        border = if (selected) {
                                            BorderStroke(0.9.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                                        } else null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (selected) "● ${mode.label}" else mode.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = cardAlpha),
                                border = lowOpacityCardBorder(hasCustomBg = hasCustomBg, cardAlpha = cardAlpha),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    crowdedDirs.forEachIndexed { idx, stat ->
                                        if (idx > 0) SettingsDivider()
                                        val isExcluded = cfg.customExcludedPaths.any { ex ->
                                            val cleanEx = ex.trimEnd('/')
                                            stat.path == cleanEx || stat.path.startsWith("$cleanEx/")
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = FormatUtils.shortenPath(stat.path, emptyList()).first,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (stat.isPersonalDir) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = Amber400.copy(alpha = 0.18f)
                                                        ) {
                                                            Text(
                                                                text = "个人目录",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Amber400,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "${stat.childCount} 个直属条目 · 累计 ${FormatUtils.formatBytes(stat.totalBytes)}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            FilledTonalButton(
                                                enabled = !isExcluded,
                                                onClick = {
                                                    haptics.click()
                                                    val applyExclude = {
                                                        val clean = stat.path.trimEnd('/')
                                                        val existing = customExcludeText.lines()
                                                            .map { it.trim().trimEnd('/') }
                                                            .filter { it.isNotEmpty() }
                                                        if (clean.isNotEmpty() && clean !in existing) {
                                                            customExcludeText = if (customExcludeText.isBlank()) clean else customExcludeText.trimEnd() + "\n" + clean
                                                        }
                                                        onExcludeCrowdedPath(stat.path)
                                                    }
                                                    if (stat.isPersonalDir) {
                                                        pendingPersonalDirExclude = stat.path
                                                    } else {
                                                        applyExclude()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text(
                                                    text = if (isExcluded) "已排除" else "排除并清库",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (cfg.customExcludedPaths.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "当前已排除的特定目录（点 × 可移出排除）：",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                cfg.customExcludedPaths.forEach { exPath ->
                                    Surface(
                                        onClick = {
                                            haptics.tick()
                                            val clean = exPath.trim().trimEnd('/')
                                            customExcludeText = customExcludeText.lines()
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() && it.trimEnd('/') != clean }
                                                .joinToString("\n")
                                            onRemoveExcludedPath(exPath)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = FormatUtils.shortenPath(exPath, emptyList()).first,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "×",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { showManualExcludeInput = !showManualExcludeInput },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (showManualExcludeInput) "收起手动路径输入框 ▲" else "+ 手动输入或批量编辑排除目录路径 ▼",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        AnimatedVisibility(visible = showManualExcludeInput) {
                            OutlinedTextField(
                                value = customExcludeText,
                                onValueChange = { customExcludeText = it },
                                label = { Text("自定义排除目录前缀（每行一条）") },
                                placeholder = { Text("/storage/emulated/0/Android/media\n/storage/emulated/0/Movies") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "按文件夹名全局跳过（遇到同名文件夹直接不进）：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterConfig.SUGGESTED_CUSTOM_DIR_NAMES.forEach { preset ->
                                val currentList = customExcludeDirNamesText
                                    .split('\n', ',', ';')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val already = currentList.any { it.equals(preset, ignoreCase = true) }
                                Surface(
                                    onClick = {
                                        haptics.tick()
                                        customExcludeDirNamesText = if (already) {
                                            currentList.filterNot { it.equals(preset, ignoreCase = true) }.joinToString("\n")
                                        } else {
                                            (currentList + preset).joinToString("\n")
                                        }
                                    },
                                    shape = CircleShape,
                                    color = if (already) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = if (already) "✓ $preset" else "+ $preset",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (already) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customExcludeDirNamesText,
                            onValueChange = { customExcludeDirNamesText = it },
                            label = { Text("跳过的文件夹名称（每行或逗号分隔）") },
                            placeholder = { Text("preview, logs, backup") },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(14.dp))
                        SettingsDivider()
                        Spacer(Modifier.height(10.dp))

                        // ==================== STAGE 2: TIER-2 DOCUMENT BODY EXTRACTION ====================
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    text = "二 · 文档正文提取规则（管哪些 PDF/Word/TXT 读内容）",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "仅对下方白名单目录内、且未超过体积上限的文档抽取正文文字；范围外或超大的文档仅搜文件名，保存时会自动清理超范围正文并释放空间。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正文解析白名单范围快捷预设：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        val storageDefaultText = FilterConfig.DEFAULT_TIER2_ALLOWED_PREFIXES.joinToString("\n")
                        val commonDocsText = listOf(
                            "/storage/emulated/0/Download",
                            "/storage/emulated/0/Documents",
                            "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/Download"
                        ).joinToString("\n")
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "全盘存储 (/storage)" to storageDefaultText,
                                "仅常用文档目录" to commonDocsText,
                                "不限路径 (*)" to "*"
                            ).forEach { (label, presetVal) ->
                                val isCurrent = tier2AllowedText.trim() == presetVal.trim()
                                Surface(
                                    onClick = {
                                        haptics.tick()
                                        tier2AllowedText = presetVal
                                    },
                                    shape = CircleShape,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = if (isCurrent) BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = tier2AllowedText,
                            onValueChange = { tier2AllowedText = it },
                            label = { Text("正文解析白名单前缀（每行一条）") },
                            placeholder = { Text("留空或填 * / / 表示不限路径") },
                            supportingText = { Text("收紧白名单或调小下方体积上限后点保存，会立即从数据库清除范围外已抽取的正文") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(10.dp))
                        val mbNodes100 = remember { listOf(1f, 10f, 25f, 50f, 75f, 100f) }
                        val mbQuick100 = remember {
                            listOf(
                                SliderQuickNode(1f, "1M"),
                                SliderQuickNode(10f, "10M"),
                                SliderQuickNode(25f, "25M"),
                                SliderQuickNode(50f, "50M"),
                                SliderQuickNode(75f, "75M"),
                                SliderQuickNode(100f, "100M")
                            )
                        }
                        val mbNodes150 = remember { listOf(1f, 10f, 25f, 50f, 100f, 150f) }
                        val mbQuick150 = remember {
                            listOf(
                                SliderQuickNode(1f, "1M"),
                                SliderQuickNode(10f, "10M"),
                                SliderQuickNode(25f, "25M"),
                                SliderQuickNode(50f, "50M"),
                                SliderQuickNode(100f, "100M"),
                                SliderQuickNode(150f, "150M")
                            )
                        }

                        MagneticSnapSlider(
                            title = "TXT / MD 正文上限",
                            valueText = "${maxTxtMb.roundToInt()} MB",
                            value = maxTxtMb,
                            valueRange = 1f..100f,
                            snapNodes = mbNodes100,
                            snapRadius = 0.85f,
                            quantizeStep = 1f,
                            quickNodes = mbQuick100,
                            onNodeSnapHaptic = { haptics.tick() },
                            onValueChange = { maxTxtMb = it }
                        )

                        Spacer(Modifier.height(4.dp))
                        MagneticSnapSlider(
                            title = "Word (.docx) 正文上限",
                            valueText = "${maxDocxMb.roundToInt()} MB",
                            value = maxDocxMb,
                            valueRange = 1f..100f,
                            snapNodes = mbNodes100,
                            snapRadius = 0.85f,
                            quantizeStep = 1f,
                            quickNodes = mbQuick100,
                            onNodeSnapHaptic = { haptics.tick() },
                            onValueChange = { maxDocxMb = it }
                        )

                        Spacer(Modifier.height(4.dp))
                        MagneticSnapSlider(
                            title = "PDF 正文上限",
                            valueText = "${maxPdfMb.roundToInt()} MB",
                            value = maxPdfMb,
                            valueRange = 1f..150f,
                            snapNodes = mbNodes150,
                            snapRadius = 0.85f,
                            quantizeStep = 1f,
                            quickNodes = mbQuick150,
                            onNodeSnapHaptic = { haptics.tick() },
                            onValueChange = { maxPdfMb = it }
                        )

                        val draftExcludedPaths = customExcludeText.lines()
                            .map { it.trim().trimEnd('/') }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        val draftExcludedDirNames = customExcludeDirNamesText.split('\n', ',', ';')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        val draftTier2Allowed = tier2AllowedText.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val hasUnsavedDraftChanges =
                            (maxTxtMb.roundToInt() * 1024L * 1024L) != cfg.maxTxtMdBytes ||
                                (maxDocxMb.roundToInt() * 1024L * 1024L) != cfg.maxDocxBytes ||
                                (maxPdfMb.roundToInt() * 1024L * 1024L) != cfg.maxPdfBytes ||
                                draftExcludedPaths != cfg.customExcludedPaths ||
                                draftExcludedDirNames != cfg.customExcludedDirNames ||
                                FilterConfig.normalizeTier2AllowedPrefixes(draftTier2Allowed) !=
                                FilterConfig.normalizeTier2AllowedPrefixes(cfg.tier2AllowedPrefixes)

                        Spacer(Modifier.height(10.dp))
                        if (hasUnsavedDraftChanges) {
                            Text(
                                text = "● 当前有未保存的规则改动，请点击下方「保存并立即清理库」生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    haptics.click()
                                    val def = FilterConfig().recommendedForMode(settings.accessMode, includeData = false)
                                    skipNoise = def.skipNoiseCaches
                                    rootIncludeData = def.rootIncludeData
                                    maxTxtMb = 10f
                                    maxDocxMb = 30f
                                    maxPdfMb = 50f
                                    customExcludeText = ""
                                    customExcludeDirNamesText = ""
                                    tier2AllowedText = def.tier2AllowedPrefixes.joinToString("\n")
                                    onUpdateFilterConfig(def)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("恢复默认") }
                            Button(
                                onClick = {
                                    haptics.click()
                                    val newConfig = cfg.copy(
                                        skipNoiseCaches = skipNoise,
                                        deduplicateMounts = true,
                                        rootIncludeData = rootIncludeData,
                                        maxTxtMdBytes = maxTxtMb.roundToInt() * 1024L * 1024L,
                                        maxDocxBytes = maxDocxMb.roundToInt() * 1024L * 1024L,
                                        maxPdfBytes = maxPdfMb.roundToInt() * 1024L * 1024L,
                                        customExcludedPaths = draftExcludedPaths,
                                        customExcludedDirNames = draftExcludedDirNames,
                                        tier2AllowedPrefixes = draftTier2Allowed
                                    )
                                    onUpdateFilterConfig(newConfig)
                                },
                                modifier = Modifier.weight(1.35f)
                            ) { Text("保存并立即清理库") }
                        }
                    }
                }
            }

            if (pendingPersonalDirExclude != null) {
                val targetPath = pendingPersonalDirExclude!!
                AlertDialog(
                    onDismissRequest = { pendingPersonalDirExclude = null },
                    title = { Text("确认排除个人常用目录？") },
                    text = {
                        Text(
                            "「${FormatUtils.shortenPath(targetPath, emptyList()).first}」属于个人相册、下载或文档常用目录。\n\n排除后将从索引库中删除该目录下的全部文件记录，后续将无法搜到里面的照片或文档。确定要排除吗？",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val clean = targetPath.trimEnd('/')
                                val existing = customExcludeText.lines()
                                    .map { it.trim().trimEnd('/') }
                                    .filter { it.isNotEmpty() }
                                if (clean.isNotEmpty() && clean !in existing) {
                                    customExcludeText = if (customExcludeText.isBlank()) clean else customExcludeText.trimEnd() + "\n" + clean
                                }
                                pendingPersonalDirExclude = null
                                onExcludeCrowdedPath(targetPath)
                            }
                        ) {
                            Text("确认排除并清理")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingPersonalDirExclude = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            // 6. About & Open Source
            SettingsSection(title = "关于与开源", cardColor = cardColor) {
                InfoRow("开发者", BuildConfig.DEVELOPER_NAME)
                SettingsDivider()
                InfoRow("构建日期", "${BuildConfig.BUILD_DATE} (${BuildConfig.GIT_HASH})")
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl(BuildConfig.GITHUB_URL) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GitHub 仓库", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            BuildConfig.GITHUB_REPO,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { onCopyUrl(BuildConfig.GITHUB_URL) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制仓库地址",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "打开仓库",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                SettingsDivider()
                NavigationRow(
                    title = "更新日志 (Changelog)",
                    onClick = onOpenChangelog
                )
                SettingsDivider()
                NavigationRow(
                    title = "版本发布页 (Releases)",
                    onClick = { onOpenUrl("${BuildConfig.GITHUB_URL}/releases") }
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    info: ReleaseUpdateInfo,
    includeBetaUpdates: Boolean = true,
    onDownloadApk: () -> Unit,
    onOpenBrowser: () -> Unit,
    onIgnoreVersion: () -> Unit,
    onDismissLater: () -> Unit
) {
    var ignoreChecked by remember(info.tagName) { mutableStateOf(false) }
    val headingColor = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeFg = MaterialTheme.colorScheme.primary
    val renderedNotes = remember(info.releaseNotes, headingColor, codeBg, codeFg) {
        FormatUtils.renderMarkdown(
            markdown = info.releaseNotes.ifBlank { "包含性能改进与问题修复。" },
            headingColor = headingColor,
            codeBg = codeBg,
            codeFg = codeFg
        )
    }

    val canOfferApk = info.apkDownloadUrl != null && (!info.isPrerelease || includeBetaUpdates)

    AlertDialog(
        onDismissRequest = onDismissLater,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "发现新版本 ${info.tagName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (info.isPrerelease) {
                    ChangelogBadge(
                        text = "测试版",
                        bg = MaterialTheme.colorScheme.surfaceContainerHigh,
                        fg = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (info.apkSizeBytes > 0L) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = FormatUtils.formatBytes(info.apkSizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = renderedNotes,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { ignoreChecked = !ignoreChecked }
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = ignoreChecked,
                            onCheckedChange = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "忽略此版本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenBrowser)
                            .padding(vertical = 3.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "浏览器打开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "浏览器打开",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissLater) {
                Text("暂不更新")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        ignoreChecked -> onIgnoreVersion()
                        canOfferApk -> onDownloadApk()
                        else -> onOpenBrowser()
                    }
                }
            ) {
                Text(
                    when {
                        ignoreChecked -> "确认忽略"
                        canOfferApk -> "立即更新"
                        else -> "前往下载"
                    }
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogSheet(
    changelogState: ChangelogState,
    currentVersionName: String,
    currentVersionCode: Int,
    buildDate: String,
    gitHash: String,
    receiveStableUpdates: Boolean = true,
    receiveBetaUpdates: Boolean = false,
    onRefresh: () -> Unit,
    onDownloadApk: (ReleaseUpdateInfo) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val headingColor = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeFg = MaterialTheme.colorScheme.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("版本更新日志", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "当前安装：v$currentVersionName (#$currentVersionCode)",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新更新日志")
                }
                TextButton(onClick = { onOpenUrl("${BuildConfig.GITHUB_URL}/releases") }) {
                    Text("全部版本")
                }
            }

            Spacer(Modifier.height(10.dp))

            when (changelogState) {
                is ChangelogState.Idle, is ChangelogState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ChangelogState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Pinpoint v$currentVersionName",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "构建号 #$currentVersionCode · 日期 $buildDate ($gitHash)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "暂时无法从 GitHub 拉取完整历史（${changelogState.message}），可重试或直接打开网页查看。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onRefresh) { Text("重试") }
                                Button(onClick = { onOpenUrl("${BuildConfig.GITHUB_URL}/releases") }) {
                                    Text("浏览器查看")
                                }
                            }
                        }
                    }
                }
                is ChangelogState.Loaded -> {
                    val list = changelogState.releases
                    if (list.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无已发布版本记录", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(list, key = { it.tagName }) { rel ->
                                val isCurrent = UpdateChecker.isSameVersion(currentVersionName, rel.tagName)
                                val isNewer = UpdateChecker.isNewerVersion(currentVersionName, rel.tagName)
                                val channelAllowed = (!rel.isPrerelease && receiveStableUpdates) ||
                                    (rel.isPrerelease && receiveBetaUpdates)
                                val canUpgrade = isNewer && channelAllowed
                                val notes = remember(rel.releaseNotes, headingColor, codeBg, codeFg) {
                                    FormatUtils.renderMarkdown(
                                        markdown = rel.releaseNotes.ifBlank { "常规版本更新与问题修复。" },
                                        headingColor = headingColor,
                                        codeBg = codeBg,
                                        codeFg = codeFg
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = if (isCurrent) {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = rel.tagName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (rel.publishedDate.isNotBlank()) {
                                                Text(
                                                    text = rel.publishedDate,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (rel.isPrerelease) {
                                                ChangelogBadge("测试版", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            when {
                                                isCurrent -> ChangelogBadge("当前版本", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                                canUpgrade -> ChangelogBadge("可升级", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                                            }
                                        }
                                        Text(
                                            text = notes,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(
                                                onClick = { onOpenUrl(rel.releaseUrl.ifBlank { "${BuildConfig.GITHUB_URL}/releases" }) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text("Release 页面", style = MaterialTheme.typography.labelMedium)
                                            }
                                            if (canUpgrade && rel.apkDownloadUrl != null) {
                                                Spacer(Modifier.width(6.dp))
                                                FilledTonalButton(
                                                    onClick = { onDownloadApk(rel) },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                ) {
                                                    Text("立即更新", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogBadge(text: String, bg: Color, fg: Color) {
    Surface(
        shape = CircleShape,
        color = bg,
        contentColor = fg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NavigationRow(
    title: String,
    trailingValue: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (trailingValue != null) {
            Text(
                text = trailingValue,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    cardColor: Color,
    content: @Composable () -> Unit
) {
    val hasCustomBg = LocalHasCustomBg.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                shadow = if (hasCustomBg) {
                    Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 4f)
                } else null
            ),
            color = if (hasCustomBg) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardColor,
            border = lowOpacityCardBorder(hasCustomBg = hasCustomBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                haptics.click()
                onCheckedChange(!checked)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.55f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        ThumbSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = {
                haptics.click()
                onCheckedChange(it)
            }
        )
    }
}

private fun modeScopeDescription(mode: AccessMode, rootIncludeData: Boolean): String = when (mode) {
    AccessMode.NORMAL -> "仅扫描公开存储 /sdcard（不含 Android/data 与 /obb）"
    AccessMode.SHIZUKU -> "扫描存储目录并穿透 /Android/data 与 /obb（不扫系统 /data）"
    AccessMode.ROOT -> if (rootIncludeData) {
        "扫描根目录 /（已包含 /data；日常增量仅盯存储与 /data/adb）"
    } else {
        "扫描根目录 /（默认跳过 /data 防体积暴涨，可在下方按需开启）"
    }
}

@Composable
fun ModeSwitchConfirmDialog(
    pending: PendingModeSwitch,
    onToggleIncludeData: (Boolean) -> Unit,
    onToggleSyncRules: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "切换至 ${pending.targetMode.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (pending.targetMode) {
                        AccessMode.NORMAL -> "仅扫描公开存储 /sdcard（不含 Android/data 与 /obb）。跨模式切换后将自动全盘重建索引（未变更文件保留正文缓存），之后日常和开机自动增量同步。"
                        AccessMode.SHIZUKU -> "通过 Shizuku 扫描存储全区并穿透 /Android/data 与 /obb（无法访问系统 /data）。切换后将自动全盘重建索引，之后日常和开机自动增量同步。"
                        AccessMode.ROOT -> "通过 Root 从根目录 / 扫描系统与存储目录。切换后将自动全盘重建索引（未变更文件保留正文缓存），之后日常和开机自动增量同步。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (pending.targetMode == AccessMode.ROOT) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggleIncludeData(!pending.includeDataForRoot) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Checkbox(
                                checked = pending.includeDataForRoot,
                                onCheckedChange = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "扫描包含 /data 目录（默认不勾选）",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = "包含 /data 会显著增加索引体积和扫描时间（内含海量应用私有数据与缓存；开启后日常增量仅跟踪存储与 /data/adb，/data/data 需手动重建）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleSyncRules(!pending.syncRulesToPreset) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Checkbox(
                            checked = pending.syncRulesToPreset,
                            onCheckedChange = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (pending.hasCustomRules) {
                                    "同步重置下方过滤与正文规则为当前模式推荐值"
                                } else {
                                    "同步使用「${pending.targetMode.label}」推荐过滤与白名单规则"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = if (pending.syncRulesToPreset) {
                                    "将恢复默认目录排除项与正文白名单（保留文件体积上限）"
                                } else {
                                    "保留你当前在「过滤与提取规则」中已配置的规则"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("切换并重建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ModeStatusChip(status: ModeAvailability?) {
    val (bg, fg, label) = when (status) {
        is ModeAvailability.Ready -> Triple(
            Emerald400.copy(alpha = 0.14f),
            Emerald400,
            "可用"
        )
        is ModeAvailability.NeedsPermission -> Triple(
            Amber400.copy(alpha = 0.14f),
            Amber400,
            "需授权"
        )
        is ModeAvailability.Unavailable -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "未就绪"
        )
        null -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "检测中"
        )
    }
    Surface(shape = CircleShape, color = bg) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.BuildConfig
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.data.ChangelogState
import com.pocketeverything.app.data.ReleaseUpdateInfo
import com.pocketeverything.app.data.UpdateCheckState
import com.pocketeverything.app.data.UpdateChecker
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.filter.RootDataScope
import com.pocketeverything.app.filter.Tier2PriorityEntry
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.Tier1Phase
import com.pocketeverything.app.indexer.Tier2Phase
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.components.BrandMarkIcon
import com.pocketeverything.app.ui.components.DocSizeSliderScale
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

private enum class DirPickTarget {
    EXCLUDE,
    TIER2_ALLOW
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    modeStatuses: Map<AccessMode, ModeAvailability>,
    indexProgress: IndexProgressState,
    updateState: UpdateCheckState,
    onBack: () -> Unit,
    onSelectMode: (AccessMode) -> Unit,
    onOpenThemeSettings: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenShizukuGuide: () -> Unit,
    onRefreshModes: () -> Unit,
    onUpdateFilterConfig: (FilterConfig) -> Unit,
    onBrowseDirectories: suspend (String) -> List<String> = { emptyList() },
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
    var maxTxtMbDraft by remember(cfg.maxTxtMdBytes) {
        mutableIntStateOf((cfg.maxTxtMdBytes / (1024L * 1024L)).toInt())
    }
    var maxDocxMbDraft by remember(cfg.maxDocxBytes) {
        mutableIntStateOf((cfg.maxDocxBytes / (1024L * 1024L)).toInt())
    }
    var maxPdfMbDraft by remember(cfg.maxPdfBytes) {
        mutableIntStateOf((cfg.maxPdfBytes / (1024L * 1024L)).toInt())
    }
    var expandModes by remember { mutableStateOf(false) }
    var dirPickTarget by remember { mutableStateOf<DirPickTarget?>(null) }
    var exactLimitTarget by remember { mutableStateOf<Triple<String, Int, (Int) -> Unit>?>(null) }

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
                            checked = settings.receiveStableUpdates,
                            onCheckedChange = onSetReceiveStableUpdates
                        )
                        SettingsDivider()
                        ToggleRow(
                            title = "接收测试版更新 (Pre-release)",
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
                            text = modeScopeDescription(activeMode),
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
                                        text = modeScopeDescription(mode),
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
                        text = indexProgress.statusMessage,
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
                    title = "后台实时监控",
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
                    Text("主题与外观", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                    title = "点击优先内置预览",
                    checked = settings.openContentHitsInPreview,
                    onCheckedChange = onSetPreviewPreference
                )
                SettingsDivider()
                ToggleRow(
                    title = "空搜索时显示最近文件",
                    checked = settings.showRecentOnEmptyQuery,
                    onCheckedChange = onSetShowRecentOnEmpty
                )
                SettingsDivider()
                ToggleRow(
                    title = "系统文件选择器入口",
                    checked = settings.systemFilePickerEnabled,
                    onCheckedChange = onSetSystemFilePickerEnabled
                )
                SettingsDivider()
                NavigationRow(
                    title = "重看新手操作引导",
                    onClick = onReplayOnboarding
                )
            }

            // 5. Directory Scope & Exclusions
            val hasCustomFilterSettings = cfg.skipNoiseCaches != true ||
                FilterConfig.normalizePaths(cfg.customExcludedPaths) != FilterConfig.DEFAULT_EXCLUDED_PATHS
            SettingsSection(
                title = "目录范围与排除",
                cardColor = cardColor,
                trailingAction = if (hasCustomFilterSettings) {
                    {
                        Text(
                            text = "恢复默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptics.click()
                                    onUpdateFilterConfig(
                                        cfg.copy(
                                            skipNoiseCaches = true,
                                            deduplicateMounts = true,
                                            customExcludedPaths = FilterConfig.DEFAULT_EXCLUDED_PATHS
                                        )
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else null
            ) {
                if (settings.accessMode == AccessMode.ROOT) {
                    val currentRootScope = cfg.rootDataScope()
                    Text(
                        text = "Root /data 范围",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RootDataScope.entries.forEach { scope ->
                            FilterChipPill(
                                label = scope.label,
                                selected = currentRootScope == scope,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptics.tick()
                                    onUpdateFilterConfig(cfg.withRootDataScope(scope))
                                }
                            )
                        }
                    }
                    SettingsDivider()
                }

                ToggleRow(
                    title = "自动跳过缓存与缩略图目录",
                    checked = cfg.skipNoiseCaches,
                    onCheckedChange = {
                        onUpdateFilterConfig(cfg.copy(skipNoiseCaches = it, deduplicateMounts = true))
                    }
                )
                SettingsDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "排除目录",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            haptics.click()
                            dirPickTarget = DirPickTarget.EXCLUDE
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加目录", style = MaterialTheme.typography.labelMedium)
                    }
                }

                val visibleExcludedPaths = if (settings.accessMode == AccessMode.ROOT) {
                    cfg.customExcludedPaths
                } else {
                    cfg.customExcludedPaths.filterNot { it == "/data" || it.startsWith("/data/") }
                }
                if (visibleExcludedPaths.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    RemovablePathChipFlow(
                        paths = visibleExcludedPaths,
                        onRemove = { target ->
                            haptics.tick()
                            val cleanTarget = if (target.length > 1) target.trimEnd('/') else target
                            val updated = cfg.customExcludedPaths.filterNot {
                                (if (it.length > 1) it.trimEnd('/') else it) == cleanTarget
                            }
                            onUpdateFilterConfig(cfg.copy(customExcludedPaths = updated))
                        }
                    )
                }
            }

            // 6. Document Content Extraction Scope, Caps & Priority
            val defaultAllowedNorm = FilterConfig.normalizeTier2AllowedPrefixes(FilterConfig.DEFAULT_TIER2_ALLOWED_PREFIXES)
            val currentAllowedNorm = FilterConfig.normalizeTier2AllowedPrefixes(cfg.tier2AllowedPrefixes)
            val isStorageScope = currentAllowedNorm == defaultAllowedNorm
            val isAllScope = currentAllowedNorm.isEmpty() || currentAllowedNorm == listOf("/")
            val isCustomScope = !isStorageScope && !isAllScope
            val hasCustomTier2Settings = !isStorageScope ||
                cfg.maxTxtMdBytes != 10L * 1024L * 1024L ||
                cfg.maxDocxBytes != 30L * 1024L * 1024L ||
                cfg.maxPdfBytes != 50L * 1024L * 1024L ||
                cfg.tier2PriorityOrder != FilterConfig.DEFAULT_TIER2_PRIORITY_ORDER

            SettingsSection(
                title = "文档正文索引",
                cardColor = cardColor,
                trailingAction = if (hasCustomTier2Settings) {
                    {
                        Text(
                            text = "恢复默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptics.click()
                                    val def = FilterConfig()
                                    maxTxtMbDraft = 10
                                    maxDocxMbDraft = 30
                                    maxPdfMbDraft = 50
                                    onUpdateFilterConfig(
                                        cfg.copy(
                                            tier2AllowedPrefixes = def.tier2AllowedPrefixes,
                                            tier2PriorityOrder = def.tier2PriorityOrder,
                                            maxTxtMdBytes = def.maxTxtMdBytes,
                                            maxDocxBytes = def.maxDocxBytes,
                                            maxPdfBytes = def.maxPdfBytes
                                        )
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "解析目录",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (isCustomScope) {
                        FilledTonalButton(
                            onClick = {
                                haptics.click()
                                dirPickTarget = DirPickTarget.TIER2_ALLOW
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("添加", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChipPill(
                        label = "用户存储",
                        selected = isStorageScope,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.tick()
                            onUpdateFilterConfig(cfg.copy(tier2AllowedPrefixes = FilterConfig.DEFAULT_TIER2_ALLOWED_PREFIXES))
                        }
                    )
                    FilterChipPill(
                        label = "全部分区",
                        selected = isAllScope,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.tick()
                            onUpdateFilterConfig(cfg.copy(tier2AllowedPrefixes = emptyList()))
                        }
                    )
                    FilterChipPill(
                        label = "指定目录",
                        selected = isCustomScope,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.tick()
                            dirPickTarget = DirPickTarget.TIER2_ALLOW
                        }
                    )
                }

                if (isCustomScope) {
                    Spacer(Modifier.height(8.dp))
                    RemovablePathChipFlow(
                        paths = cfg.tier2AllowedPrefixes,
                        onRemove = { target ->
                            haptics.tick()
                            val cleanTarget = if (target.length > 1) target.trimEnd('/') else target
                            val remaining = cfg.tier2AllowedPrefixes.filterNot {
                                (if (it.length > 1) it.trimEnd('/') else it) == cleanTarget
                            }
                            onUpdateFilterConfig(
                                cfg.copy(
                                    tier2AllowedPrefixes = remaining.ifEmpty { FilterConfig.DEFAULT_TIER2_ALLOWED_PREFIXES }
                                )
                            )
                        }
                    )
                }

                SettingsDivider()

                DocSizeLimitSlider(
                    title = "TXT / MD 上限",
                    currentMb = maxTxtMbDraft,
                    onDraftChange = { maxTxtMbDraft = it },
                    onCommitMb = { mb ->
                        onUpdateFilterConfig(cfg.copy(maxTxtMdBytes = mb.toLong() * 1024L * 1024L))
                    },
                    onOpenExactInput = {
                        exactLimitTarget = Triple("TXT / MD 上限 (MB)", maxTxtMbDraft) { mb ->
                            maxTxtMbDraft = mb
                            onUpdateFilterConfig(cfg.copy(maxTxtMdBytes = mb.toLong() * 1024L * 1024L))
                        }
                    },
                    onSnapHaptic = { haptics.tick() }
                )

                DocSizeLimitSlider(
                    title = "Word (.docx) 上限",
                    currentMb = maxDocxMbDraft,
                    onDraftChange = { maxDocxMbDraft = it },
                    onCommitMb = { mb ->
                        onUpdateFilterConfig(cfg.copy(maxDocxBytes = mb.toLong() * 1024L * 1024L))
                    },
                    onOpenExactInput = {
                        exactLimitTarget = Triple("Word (.docx) 上限 (MB)", maxDocxMbDraft) { mb ->
                            maxDocxMbDraft = mb
                            onUpdateFilterConfig(cfg.copy(maxDocxBytes = mb.toLong() * 1024L * 1024L))
                        }
                    },
                    onSnapHaptic = { haptics.tick() }
                )

                DocSizeLimitSlider(
                    title = "PDF 上限",
                    currentMb = maxPdfMbDraft,
                    onDraftChange = { maxPdfMbDraft = it },
                    onCommitMb = { mb ->
                        onUpdateFilterConfig(cfg.copy(maxPdfBytes = mb.toLong() * 1024L * 1024L))
                    },
                    onOpenExactInput = {
                        exactLimitTarget = Triple("PDF 上限 (MB)", maxPdfMbDraft) { mb ->
                            maxPdfMbDraft = mb
                            onUpdateFilterConfig(cfg.copy(maxPdfBytes = mb.toLong() * 1024L * 1024L))
                        }
                    },
                    onSnapHaptic = { haptics.tick() }
                )

                SettingsDivider()

                Text(
                    text = "提取优先级",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Tier2PriorityList(
                    entries = cfg.tier2PriorityOrder,
                    onMove = { idx, delta ->
                        haptics.tick()
                        val list = cfg.tier2PriorityOrder.toMutableList()
                        val target = idx + delta
                        if (idx in list.indices && target in list.indices) {
                            val item = list.removeAt(idx)
                            list.add(target, item)
                            onUpdateFilterConfig(cfg.copy(tier2PriorityOrder = list))
                        }
                    },
                    onToggle = { idx, enabled ->
                        haptics.click()
                        val list = cfg.tier2PriorityOrder.toMutableList()
                        if (idx in list.indices) {
                            list[idx] = list[idx].copy(enabled = enabled)
                            onUpdateFilterConfig(cfg.copy(tier2PriorityOrder = list))
                        }
                    }
                )
            }

            if (dirPickTarget != null) {
                val targetType = dirPickTarget!!
                val initialRoot = if (settings.accessMode == AccessMode.ROOT) "/" else "/storage/emulated/0"
                DirectoryPickerDialog(
                    title = if (targetType == DirPickTarget.EXCLUDE) "选择排除目录" else "选择正文解析目录",
                    initialPath = initialRoot,
                    allowRootLevel = settings.accessMode == AccessMode.ROOT,
                    onBrowse = onBrowseDirectories,
                    onSelectPath = { chosenPath ->
                        val clean = if (chosenPath.length > 1) chosenPath.trimEnd('/') else chosenPath
                        dirPickTarget = null
                        if (clean.isEmpty()) return@DirectoryPickerDialog
                        when (targetType) {
                            DirPickTarget.EXCLUDE -> {
                                val updated = FilterConfig.normalizePaths(cfg.customExcludedPaths + clean)
                                onUpdateFilterConfig(cfg.copy(customExcludedPaths = updated))
                            }
                            DirPickTarget.TIER2_ALLOW -> {
                                val base = if (isCustomScope) cfg.tier2AllowedPrefixes else emptyList()
                                val updated = FilterConfig.normalizePaths(base + clean)
                                onUpdateFilterConfig(cfg.copy(tier2AllowedPrefixes = updated))
                            }
                        }
                    },
                    onDismiss = { dirPickTarget = null }
                )
            }

            if (exactLimitTarget != null) {
                val (label, currentMb, onConfirmMb) = exactLimitTarget!!
                ExactSizeInputDialog(
                    title = label,
                    initialMb = currentMb,
                    onConfirmMb = { mb ->
                        exactLimitTarget = null
                        onConfirmMb(mb)
                    },
                    onDismiss = { exactLimitTarget = null }
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
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val hasCustomBg = LocalHasCustomBg.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    shadow = if (hasCustomBg) {
                        Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 4f)
                    } else null
                ),
                color = if (hasCustomBg) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            trailingAction?.invoke()
        }
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
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) {
            BorderStroke(0.9.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else null,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemovablePathChipFlow(
    paths: List<String>,
    onRemove: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        paths.forEach { path ->
            Surface(
                onClick = { onRemove(path) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = FormatUtils.shortenPath(path, emptyList()).first,
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

@Composable
private fun DocSizeLimitSlider(
    title: String,
    currentMb: Int,
    onDraftChange: (Int) -> Unit,
    onCommitMb: (Int) -> Unit,
    onOpenExactInput: () -> Unit,
    onSnapHaptic: () -> Unit
) {
    val snapNodes = remember {
        listOf(10, 50, 100, 200, 500, 1024, DocSizeSliderScale.UNLIMITED_MB)
            .map { DocSizeSliderScale.mbToFraction(it) }
    }
    val quickNodes = remember {
        listOf(
            SliderQuickNode(DocSizeSliderScale.mbToFraction(10), "10M"),
            SliderQuickNode(DocSizeSliderScale.mbToFraction(50), "50M"),
            SliderQuickNode(DocSizeSliderScale.mbToFraction(100), "100M"),
            SliderQuickNode(DocSizeSliderScale.mbToFraction(200), "200M"),
            SliderQuickNode(DocSizeSliderScale.mbToFraction(500), "500M"),
            SliderQuickNode(DocSizeSliderScale.UNLIMITED_FRACTION, "不限")
        )
    }
    val fraction = DocSizeSliderScale.mbToFraction(currentMb)
    MagneticSnapSlider(
        title = title,
        valueText = "${DocSizeSliderScale.formatMbLabel(currentMb)} ▾",
        value = fraction,
        valueRange = 0f..1f,
        snapNodes = snapNodes,
        snapRadius = 0.016f,
        quantizeStep = 0f,
        quickNodes = quickNodes,
        onNodeSnapHaptic = { onSnapHaptic() },
        onValueBadgeClick = onOpenExactInput,
        onValueChange = { f -> onDraftChange(DocSizeSliderScale.fractionToMb(f)) },
        onValueChangeFinished = { committedFraction ->
            val committedMb = DocSizeSliderScale.fractionToMb(committedFraction)
            onDraftChange(committedMb)
            onCommitMb(committedMb)
        }
    )
}

@Composable
private fun Tier2PriorityList(
    entries: List<Tier2PriorityEntry>,
    onMove: (index: Int, delta: Int) -> Unit,
    onToggle: (index: Int, enabled: Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEachIndexed { index, entry ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                    alpha = if (entry.enabled) 0.9f else 0.45f
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(index, !entry.enabled) }
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (entry.enabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                                color = if (entry.enabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.rule.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = index > 0,
                        onClick = { onMove(index, -1) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "上移",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        enabled = index < entries.lastIndex,
                        onClick = { onMove(index, 1) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "下移",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Checkbox(
                        checked = entry.enabled,
                        onCheckedChange = { onToggle(index, it) },
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryPickerDialog(
    title: String,
    initialPath: String,
    allowRootLevel: Boolean,
    onBrowse: suspend (String) -> List<String>,
    onSelectPath: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember(initialPath) { mutableStateOf(initialPath) }
    var children by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var manualPath by remember { mutableStateOf("") }

    LaunchedEffect(currentDir) {
        loading = true
        children = runCatching { onBrowse(currentDir) }.getOrDefault(emptyList())
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = FormatUtils.shortenPath(currentDir, emptyList()).first,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    val canGoUp = if (allowRootLevel) {
                        currentDir != "/"
                    } else {
                        currentDir != "/" && currentDir != "/storage" && currentDir != "/storage/emulated"
                    }
                    if (canGoUp) {
                        TextButton(
                            onClick = {
                                val parent = currentDir.substringBeforeLast('/', "").ifEmpty { "/" }
                                currentDir = if (!allowRootLevel && parent == "/storage/emulated") "/" else parent
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("上级", style = MaterialTheme.typography.labelMedium) }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        children.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("无子目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(children, key = { it }) { dirPath ->
                                val dirName = if (dirPath == "/storage/emulated/0") "内部存储" else dirPath.substringAfterLast('/').ifEmpty { dirPath }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentDir = dirPath }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = dirName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { onSelectPath(dirPath) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) { Text("选中", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    placeholder = { Text("或直接输入目录路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = manualPath.trim().ifEmpty { currentDir }
                    onSelectPath(chosen)
                }
            ) {
                Text(if (manualPath.isNotBlank()) "添加输入路径" else "选定当前目录")
            }
        }
    )
}

@Composable
private fun ExactSizeInputDialog(
    title: String,
    initialMb: Int,
    onConfirmMb: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initialMb) { mutableStateOf(if (initialMb <= 0) "" else initialMb.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    if (input.length <= 4 && input.all { it.isDigit() }) {
                        text = input
                    }
                },
                placeholder = { Text("留空或 0 表示不限 (1–1024)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = { onConfirmMb(DocSizeSliderScale.UNLIMITED_MB) }) {
                Text("不限")
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val parsed = text.trim().toIntOrNull() ?: 0
                    val clamped = if (parsed <= 0) 0 else parsed.coerceIn(1, FilterConfig.MAX_FINITE_MB)
                    onConfirmMb(clamped)
                }
            ) { Text("确定") }
        }
    )
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

private fun modeScopeDescription(mode: AccessMode): String = when (mode) {
    AccessMode.NORMAL -> "共享存储"
    AccessMode.SHIZUKU -> "含 Android/data · obb"
    AccessMode.ROOT -> "全机根目录 /"
}

@Composable
fun ModeSwitchConfirmDialog(
    pending: PendingModeSwitch,
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
            Text(
                text = "将按 ${modeScopeDescription(pending.targetMode)} 重建索引并保留已有正文缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("切换")
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

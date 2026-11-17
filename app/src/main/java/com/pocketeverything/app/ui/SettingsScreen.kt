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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.components.BrandMarkIcon
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Emerald400
import com.pocketeverything.app.ui.theme.LocalCardOpacity
import com.pocketeverything.app.ui.theme.LocalHasCustomBg
import com.pocketeverything.app.ui.theme.Rose400
import com.pocketeverything.app.ui.theme.groupedBackdrop
import com.pocketeverything.app.ui.theme.groupedCard
import com.pocketeverything.app.ui.theme.rememberAppHaptics
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
    onSetShowRecentOnEmpty: (Boolean) -> Unit,
    onSetPreviewPreference: (Boolean) -> Unit,
    onSetRealtimeMonitor: (Boolean) -> Unit,
    onQuickSync: () -> Unit,
    onFullRebuild: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onReplayOnboarding: () -> Unit
) {
    val cfg = settings.filterConfig
    var skipNoise by remember(cfg) { mutableStateOf(cfg.skipNoiseCaches) }
    var dedupMounts by remember(cfg) { mutableStateOf(cfg.deduplicateMounts) }
    var maxTxtMb by remember(cfg) { mutableFloatStateOf((cfg.maxTxtMdBytes / (1024f * 1024f)).coerceIn(1f, 100f)) }
    var maxDocxMb by remember(cfg) { mutableFloatStateOf((cfg.maxDocxBytes / (1024f * 1024f)).coerceIn(1f, 100f)) }
    var maxPdfMb by remember(cfg) { mutableFloatStateOf((cfg.maxPdfBytes / (1024f * 1024f)).coerceIn(1f, 150f)) }
    var customExcludeText by remember(cfg) { mutableStateOf(cfg.customExcludedPaths.joinToString("\n")) }
    var tier2AllowedText by remember(cfg) { mutableStateOf(cfg.tier2AllowedPrefixes.joinToString("\n")) }
    var expandAdvancedFilters by remember { mutableStateOf(false) }

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
                }
            }

            // 2. Access Mode & Permissions
            SettingsSection(title = "访问模式与权限", cardColor = cardColor) {
                AccessMode.entries.forEachIndexed { idx, mode ->
                    if (idx > 0) SettingsDivider()
                    val status = modeStatuses[mode]
                    val selected = settings.accessMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelectMode(mode) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = when (mode) {
                                    AccessMode.NORMAL -> "扫描内部与外置公开存储"
                                    AccessMode.SHIZUKU -> "免 Root 穿透 /Android/data 与 /obb"
                                    AccessMode.ROOT -> "根目录 / 全盘扫描与挂载去重"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            mode == AccessMode.NORMAL && status !is ModeAvailability.Ready -> {
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    ModeDotBadge(status)
                                }
                            }
                            else -> ModeDotBadge(status)
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

            // 3. Index & Sync Management
            SettingsSection(title = "索引与同步", cardColor = cardColor) {
                val stats = indexProgress.stats
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

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onQuickSync,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("增量同步")
                    }
                    Button(
                        onClick = onFullRebuild,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重建全库")
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
                NavigationRow(
                    title = "重看新手操作引导",
                    onClick = onReplayOnboarding
                )
            }

            // 5. Filtering & Extraction Rules (with Progressive Disclosure for Sliders/Path Lists)
            SettingsSection(title = "过滤与提取规则", cardColor = cardColor) {
                ToggleRow(
                    title = "屏蔽缓存与缩略图目录",
                    checked = skipNoise,
                    onCheckedChange = {
                        skipNoise = it
                        onUpdateFilterConfig(cfg.copy(skipNoiseCaches = it, deduplicateMounts = dedupMounts))
                    }
                )
                SettingsDivider()
                ToggleRow(
                    title = "挂载点与重复路径去重",
                    checked = dedupMounts,
                    onCheckedChange = {
                        dedupMounts = it
                        onUpdateFilterConfig(cfg.copy(skipNoiseCaches = skipNoise, deduplicateMounts = it))
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
                        Text("正文体积上限与目录黑白名单", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "TXT ${maxTxtMb.roundToInt()}M · DOCX ${maxDocxMb.roundToInt()}M · PDF ${maxPdfMb.roundToInt()}M",
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
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text("TXT / MD 上限：${maxTxtMb.roundToInt()} MB", style = MaterialTheme.typography.bodySmall)
                        Slider(value = maxTxtMb, onValueChange = { maxTxtMb = it }, valueRange = 1f..100f)

                        Text("Word (.docx) 上限：${maxDocxMb.roundToInt()} MB", style = MaterialTheme.typography.bodySmall)
                        Slider(value = maxDocxMb, onValueChange = { maxDocxMb = it }, valueRange = 1f..100f)

                        Text("PDF 上限：${maxPdfMb.roundToInt()} MB", style = MaterialTheme.typography.bodySmall)
                        Slider(value = maxPdfMb, onValueChange = { maxPdfMb = it }, valueRange = 1f..150f)

                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customExcludeText,
                            onValueChange = { customExcludeText = it },
                            label = { Text("自定义排除目录前缀（每行一条）") },
                            placeholder = { Text("/storage/emulated/0/Android/media\n/storage/emulated/0/Movies") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tier2AllowedText,
                            onValueChange = { tier2AllowedText = it },
                            label = { Text("正文解析白名单前缀（每行一条）") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val def = FilterConfig()
                                    skipNoise = def.skipNoiseCaches
                                    dedupMounts = def.deduplicateMounts
                                    maxTxtMb = 10f
                                    maxDocxMb = 30f
                                    maxPdfMb = 50f
                                    customExcludeText = ""
                                    tier2AllowedText = def.tier2AllowedPrefixes.joinToString("\n")
                                    onUpdateFilterConfig(def)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("恢复默认") }
                            Button(
                                onClick = {
                                    val newConfig = cfg.copy(
                                        skipNoiseCaches = skipNoise,
                                        deduplicateMounts = dedupMounts,
                                        maxTxtMdBytes = maxTxtMb.roundToInt() * 1024L * 1024L,
                                        maxDocxBytes = maxDocxMb.roundToInt() * 1024L * 1024L,
                                        maxPdfBytes = maxPdfMb.roundToInt() * 1024L * 1024L,
                                        customExcludedPaths = customExcludeText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                        tier2AllowedPrefixes = tier2AllowedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                    )
                                    onUpdateFilterConfig(newConfig)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("保存规则") }
                        }
                    }
                }
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

    AlertDialog(
        onDismissRequest = onDismissLater,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "发现新版本 ${info.tagName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
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
                    if (ignoreChecked) onIgnoreVersion() else onDownloadApk()
                }
            ) {
                Text(
                    when {
                        ignoreChecked -> "确认忽略"
                        info.apkDownloadUrl != null -> "立即更新"
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
                                val cleanRel = rel.tagName.trim().removePrefix("v").removePrefix("V")
                                val cleanCur = currentVersionName.trim().removePrefix("v").removePrefix("V")
                                    .substringBefore('-').substringBefore('+')
                                val isCurrent = cleanRel == cleanCur
                                val isNewer = UpdateChecker.isNewerVersion(currentVersionName, rel.tagName)
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            when {
                                                isCurrent -> ChangelogBadge("当前版本", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                                isNewer -> ChangelogBadge("可升级", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                                                rel.isPrerelease -> ChangelogBadge("测试版", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            if (isNewer && rel.apkDownloadUrl != null) {
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
    onCheckedChange: (Boolean) -> Unit
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.click()
                onCheckedChange(!checked)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        ThumbSwitch(
            checked = checked,
            onCheckedChange = {
                haptics.click()
                onCheckedChange(it)
            }
        )
    }
}

@Composable
private fun ModeDotBadge(status: ModeAvailability?) {
    val (label, color) = when (status) {
        is ModeAvailability.Ready -> "可用" to Emerald400
        is ModeAvailability.NeedsPermission -> "需授权" to Amber400
        is ModeAvailability.Unavailable -> "未就绪" to Rose400
        null -> "检测中" to Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

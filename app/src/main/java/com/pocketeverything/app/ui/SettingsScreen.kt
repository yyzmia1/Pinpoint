package com.pocketeverything.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.BuildConfig
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.data.UpdateCheckState
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Emerald400
import com.pocketeverything.app.ui.theme.Rose400
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
    onDismissUpdateDialog: () -> Unit,
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

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onSetRealtimeMonitor(true)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            // 1. Access Mode
            SectionGroup(title = "文件访问模式") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccessMode.entries.forEach { mode ->
                        val status = modeStatuses[mode]
                        val selected = settings.accessMode == mode
                        Surface(
                            onClick = { onSelectMode(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = if (selected) 1.dp else 0.5.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(mode.label, style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.width(8.dp))
                                        ModeDotBadge(status)
                                    }
                                    Text(
                                        text = when (mode) {
                                            AccessMode.NORMAL -> "公开存储目录"
                                            AccessMode.SHIZUKU -> "免 Root 穿透 /Android/data 与 /obb"
                                            AccessMode.ROOT -> "根目录 / 全盘扫描与挂载去重"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRequestStoragePermission,
                        modifier = Modifier.weight(1f)
                    ) { Text("文件权限") }
                    OutlinedButton(
                        onClick = onOpenShizukuGuide,
                        modifier = Modifier.weight(1.1f)
                    ) { Text("Shizuku 向导") }
                    OutlinedButton(
                        onClick = onRefreshModes,
                        modifier = Modifier.weight(0.75f)
                    ) { Text("复检") }
                }
            }

            // 2. Search & Display Behavior
            SectionGroup(title = "搜索与交互") {
                ToggleRow(
                    title = "搜索栏为空时显示最近文件",
                    checked = settings.showRecentOnEmptyQuery,
                    onCheckedChange = onSetShowRecentOnEmpty
                )
                ThinDivider()
                ToggleRow(
                    title = "正文命中优先内置定位预览",
                    checked = settings.openContentHitsInPreview,
                    onCheckedChange = onSetPreviewPreference
                )
                ThinDivider()
                ToggleRow(
                    title = "常驻实时监控",
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

            // 3. Index Stats
            SectionGroup(title = "索引统计") {
                val stats = indexProgress.stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(label = "总条目", value = "${stats.totalEntries}", modifier = Modifier.weight(1f))
                    StatCell(label = "正文", value = "${stats.indexedDocs}", modifier = Modifier.weight(1f))
                    StatCell(label = "待抽", value = "${stats.pendingDocs}", modifier = Modifier.weight(1f))
                    StatCell(label = "库体积", value = FormatUtils.formatBytes(stats.dbSizeBytes), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onQuickSync,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("增量同步")
                    }
                    Button(
                        onClick = onFullRebuild,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重建全库")
                    }
                }
            }

            // 4. Filter Rules
            SectionGroup(title = "路径与大小过滤") {
                ToggleRow(
                    title = "屏蔽缓存与缩略图目录",
                    checked = skipNoise,
                    onCheckedChange = { skipNoise = it }
                )
                ThinDivider()
                ToggleRow(
                    title = "挂载点与重复路径去重",
                    checked = dedupMounts,
                    onCheckedChange = { dedupMounts = it }
                )

                Spacer(Modifier.height(10.dp))
                Text("TXT / MD 上限：${maxTxtMb.roundToInt()} MB", style = MaterialTheme.typography.bodyMedium)
                Slider(value = maxTxtMb, onValueChange = { maxTxtMb = it }, valueRange = 1f..100f)

                Text("Word (.docx) 上限：${maxDocxMb.roundToInt()} MB", style = MaterialTheme.typography.bodyMedium)
                Slider(value = maxDocxMb, onValueChange = { maxDocxMb = it }, valueRange = 1f..100f)

                Text("PDF 上限：${maxPdfMb.roundToInt()} MB", style = MaterialTheme.typography.bodyMedium)
                Slider(value = maxPdfMb, onValueChange = { maxPdfMb = it }, valueRange = 1f..150f)

                Spacer(Modifier.height(4.dp))
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

            // 5. About, Auto Git Version, GitHub & Update Check
            SectionGroup(title = "关于与更新") {
                InfoRow("当前版本", "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_HASH})")
                ThinDivider()
                InfoRow("构建日期", BuildConfig.BUILD_DATE)
                ThinDivider()
                InfoRow("开发者", BuildConfig.DEVELOPER_NAME)
                ThinDivider()
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
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                ThinDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReplayOnboarding() }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("重看新手引导", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCheckUpdates,
                        enabled = updateState !is UpdateCheckState.Checking,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (updateState is UpdateCheckState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("检测中")
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("检测更新")
                        }
                    }
                    OutlinedButton(
                        onClick = { onOpenUrl("${BuildConfig.GITHUB_URL}/releases") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Releases")
                    }
                }

                when (updateState) {
                    is UpdateCheckState.UpToDate -> {
                        Text(
                            text = "已是最新版本 (v${updateState.currentVersion})",
                            style = MaterialTheme.typography.labelMedium,
                            color = Emerald400,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    is UpdateCheckState.Error -> {
                        Text(
                            text = "检测失败：${updateState.message}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Rose400,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    else -> Unit
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onCopyUrl(BuildConfig.GITHUB_URL) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("复制仓库地址")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (updateState is UpdateCheckState.Available) {
        val info = updateState.info
        AlertDialog(
            onDismissRequest = onDismissUpdateDialog,
            title = { Text("发现新版本 ${info.tagName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (info.apkSizeBytes > 0L) {
                        Text(
                            "安装包大小：${FormatUtils.formatBytes(info.apkSizeBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = info.releaseNotes.ifBlank { "包含性能改进与问题修复。" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onOpenUrl(info.apkDownloadUrl ?: info.releaseUrl)
                        onDismissUpdateDialog()
                    }
                ) {
                    Text(if (info.apkDownloadUrl != null) "下载 APK" else "前往 Release")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdateDialog) { Text("稍后") }
            }
        )
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
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
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

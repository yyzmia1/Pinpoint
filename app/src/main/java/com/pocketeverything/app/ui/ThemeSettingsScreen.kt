package com.pocketeverything.app.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.data.ThemeAccent
import com.pocketeverything.app.data.ThemeMode
import com.pocketeverything.app.ui.components.FileTypeIcon
import com.pocketeverything.app.ui.components.MagneticSnapSlider
import com.pocketeverything.app.ui.components.SliderQuickNode
import com.pocketeverything.app.ui.theme.Amber200
import com.pocketeverything.app.ui.theme.LocalCardOpacity
import com.pocketeverything.app.ui.theme.LocalHasCustomBg
import com.pocketeverything.app.ui.theme.Slate950
import com.pocketeverything.app.ui.theme.groupedBackdrop
import com.pocketeverything.app.ui.theme.groupedCard
import com.pocketeverything.app.ui.theme.lowOpacityCardBorder
import com.pocketeverything.app.ui.theme.previewColors
import com.pocketeverything.app.ui.theme.rememberAppHaptics
import com.pocketeverything.app.ui.theme.seedColorScheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    settings: AppSettings,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSelectThemeAccent: (ThemeAccent) -> Unit,
    onSetPureBlackDark: (Boolean) -> Unit,
    onSetCustomBgEnabled: (Boolean) -> Unit,
    onPickCustomBgUri: (Uri) -> Unit,
    onClearCustomBg: () -> Unit,
    onSetCustomBgOpacity: (Float) -> Unit,
    onSetCustomBgDim: (Float) -> Unit,
    onSetCustomBgBlur: (Boolean) -> Unit,
    onSetExtractColorFromBg: (Boolean) -> Unit,
    onSetHapticStrength: (Float) -> Unit,
    onSetUseMonospaceFont: (Boolean) -> Unit,
    onSetCompactListMode: (Boolean) -> Unit,
    onResetThemeSettings: () -> Unit
) {
    val haptics = rememberAppHaptics()
    val hasCustomBg = LocalHasCustomBg.current
    val cardAlpha = LocalCardOpacity.current
    val pageBackdrop = if (hasCustomBg) Color.Transparent else MaterialTheme.colorScheme.groupedBackdrop
    val cardColor = MaterialTheme.colorScheme.groupedCard.copy(alpha = cardAlpha)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            haptics.click()
            onPickCustomBgUri(uri)
        }
    }

    val activeColorSourceLabel = when {
        hasCustomBg && settings.extractColorFromBg && settings.customBgSeedArgb != 0 ->
            "自定义背景图片取色"
        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            "系统壁纸动态颜色 (Monet)"
        else ->
            "精选色盘 · ${settings.themeAccent.label}"
    }

    Scaffold(
        containerColor = pageBackdrop,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (hasCustomBg) Color.Transparent else MaterialTheme.colorScheme.groupedBackdrop
                ),
                title = { Text("主题与外观", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = {
                        haptics.tick()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        haptics.click()
                        onResetThemeSettings()
                    }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认", style = MaterialTheme.typography.labelLarge)
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
            // 0. Live Preview Card
            ThemeLivePreviewCard(
                cardColor = cardColor,
                cardAlpha = cardAlpha,
                sourceLabel = activeColorSourceLabel,
                useMonospace = settings.useMonospaceFont,
                compactMode = settings.compactListMode
            )

            // 1. Appearance & Color Scheme
            ThemeGroupCard(
                title = "外观与色彩方案",
                cardColor = cardColor,
                cardAlpha = cardAlpha,
                hasCustomBg = hasCustomBg
            ) {
                // Theme Mode Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("主题模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = settings.themeMode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            val selected = settings.themeMode == mode
                            Surface(
                                onClick = {
                                    haptics.tick()
                                    onSelectThemeMode(mode)
                                },
                                shape = CircleShape,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                border = if (selected) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                } else {
                                    null
                                }
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                ThemeItemDivider()

                // Pure Black AMOLED Dark Mode
                ThemeSwitchRow(
                    icon = Icons.Default.Contrast,
                    title = "纯黑深邃底色 (AMOLED)",
                    subtitle = "黑夜模式下使用 #000000 纯黑背景，提升对比度并省电",
                    checked = settings.pureBlackDark,
                    onCheckedChange = {
                        haptics.click()
                        onSetPureBlackDark(it)
                    }
                )

                ThemeItemDivider()

                // Dynamic Color (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val context = LocalContext.current
                    val sysPreviewScheme = remember(isDarkTheme) {
                        if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    }
                    val bgOverride = hasCustomBg && settings.extractColorFromBg && settings.customBgSeedArgb != 0
                    val dynActive = settings.useDynamicColor && !bgOverride
                    ThemeSwitchRow(
                        icon = Icons.Default.Palette,
                        title = "动态颜色（跟随系统壁纸）",
                        subtitle = when {
                            bgOverride -> "当前已优先启用下方「从自定义背景图片中取色」"
                            settings.useDynamicColor -> "正在使用系统主题的动态颜色（圆球为当前系统色）"
                            else -> "已关闭，当前使用下方精选色盘：${settings.themeAccent.label}"
                        },
                        badgeContent = {
                            TwoToneSwatchDot(
                                primary = sysPreviewScheme.primary,
                                container = sysPreviewScheme.primaryContainer,
                                isSelected = dynActive
                            )
                        },
                        checked = settings.useDynamicColor,
                        onCheckedChange = { want ->
                            haptics.click()
                            if (want && bgOverride) {
                                onSetExtractColorFromBg(false)
                            }
                            onSetDynamicColor(want)
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("动态颜色（跟随系统壁纸）", style = MaterialTheme.typography.bodyMedium)
                        Text("需 Android 12+", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                ThemeItemDivider()

                // Preset Color Swatches
                val isUsingPreset = !settings.useDynamicColor && !(hasCustomBg && settings.extractColorFromBg && settings.customBgSeedArgb != 0)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "精选主题色盘",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isUsingPreset) "当前：${settings.themeAccent.label}" else "点击任意色球立即切换",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ThemeAccent.entries.forEach { accent ->
                            val (pri, cont) = accent.previewColors(isDarkTheme)
                            val selected = isUsingPreset && settings.themeAccent == accent
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptics.tick()
                                        onSelectThemeAccent(accent)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                TwoToneSwatchBall(
                                    primary = pri,
                                    container = cont,
                                    selected = selected
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = accent.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Custom Background & Frosted Glass
            ThemeGroupCard(
                title = "自定义背景与毛玻璃",
                cardColor = cardColor,
                cardAlpha = cardAlpha,
                hasCustomBg = hasCustomBg
            ) {
                ThemeSwitchRow(
                    icon = Icons.Default.Image,
                    title = "自定义背景",
                    subtitle = if (settings.customBgPath.isNotBlank()) {
                        if (settings.customBgEnabled) "已启用自定义背景图" else "已保存背景图（当前关闭）"
                    } else {
                        "选择一张图片作为应用全局背景"
                    },
                    checked = settings.customBgEnabled && settings.customBgPath.isNotBlank(),
                    onCheckedChange = { want ->
                        haptics.click()
                        if (want && settings.customBgPath.isBlank()) {
                            imagePickerLauncher.launch("image/*")
                        } else {
                            onSetCustomBgEnabled(want)
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptics.click()
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (settings.customBgPath.isBlank()) "选择背景图片" else "更换背景图片")
                    }
                    if (settings.customBgPath.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                haptics.click()
                                onClearCustomBg()
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("清除")
                        }
                    }
                }

                AnimatedVisibility(visible = settings.customBgEnabled && settings.customBgPath.isNotBlank()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ThemeItemDivider()

                        // Extract seed color from custom background image
                        val seedScheme = remember(settings.customBgSeedArgb, isDarkTheme) {
                            seedColorScheme(settings.customBgSeedArgb, isDarkTheme)
                        }
                        ThemeSwitchRow(
                            icon = Icons.Default.Colorize,
                            title = "从自定义背景图片中取色",
                            subtitle = "从当前背景图中提取动态颜色种子色",
                            badgeContent = if (settings.customBgSeedArgb != 0) {
                                {
                                    TwoToneSwatchDot(
                                        primary = seedScheme.primary,
                                        container = seedScheme.primaryContainer,
                                        isSelected = settings.extractColorFromBg
                                    )
                                }
                            } else null,
                            checked = settings.extractColorFromBg,
                            onCheckedChange = {
                                haptics.click()
                                onSetExtractColorFromBg(it)
                            }
                        )

                        ThemeItemDivider()

                        // Card Opacity Slider (0% - 100%, every 1% reachable + 10% magnetic nodes)
                        val opacityNodes = remember { (0..10).map { it / 10f } }
                        val opacityQuickNodes = remember {
                            listOf(
                                SliderQuickNode(0.0f, "0%"),
                                SliderQuickNode(0.2f, "20%"),
                                SliderQuickNode(0.3f, "30%"),
                                SliderQuickNode(0.5f, "50%"),
                                SliderQuickNode(0.8f, "80%"),
                                SliderQuickNode(1.0f, "100%")
                            )
                        }
                        MagneticSnapSlider(
                            icon = Icons.Default.Opacity,
                            title = "卡片不透明度",
                            valueText = "${(settings.customBgOpacity * 100).roundToInt()}%",
                            value = settings.customBgOpacity,
                            valueRange = 0.00f..1.00f,
                            snapNodes = opacityNodes,
                            snapRadius = 0.008f,
                            quantizeStep = 0.01f,
                            quickNodes = opacityQuickNodes,
                            onNodeSnapHaptic = { haptics.tick() },
                            onValueChange = { v -> onSetCustomBgOpacity(v) }
                        )

                        ThemeItemDivider()

                        // Background Dim Slider (0% - 80%, every 1% reachable + 10% magnetic nodes)
                        val dimNodes = remember { (0..8).map { it / 10f } }
                        val dimQuickNodes = remember {
                            listOf(
                                SliderQuickNode(0.0f, "0%"),
                                SliderQuickNode(0.2f, "20%"),
                                SliderQuickNode(0.4f, "40%"),
                                SliderQuickNode(0.6f, "60%"),
                                SliderQuickNode(0.8f, "80%")
                            )
                        }
                        MagneticSnapSlider(
                            icon = Icons.Default.Brightness6,
                            title = "背景暗度调节",
                            valueText = "${(settings.customBgDim * 100).roundToInt()}%",
                            value = settings.customBgDim,
                            valueRange = 0.00f..0.80f,
                            snapNodes = dimNodes,
                            snapRadius = 0.008f,
                            quantizeStep = 0.01f,
                            quickNodes = dimQuickNodes,
                            onNodeSnapHaptic = { haptics.tick() },
                            onValueChange = { v -> onSetCustomBgDim(v) }
                        )

                        ThemeItemDivider()

                        // Blur Background
                        ThemeSwitchRow(
                            icon = Icons.Default.BlurOn,
                            title = "将自定义背景渲染到模糊",
                            subtitle = "启用柔和毛玻璃背景模糊，提升文字与卡片清晰度",
                            checked = settings.customBgBlur,
                            onCheckedChange = {
                                haptics.click()
                                onSetCustomBgBlur(it)
                            }
                        )
                    }
                }
            }

            // 3. Haptics & Typography Details
            ThemeGroupCard(
                title = "触感与排版细节",
                cardColor = cardColor,
                cardAlpha = cardAlpha,
                hasCustomBg = hasCustomBg
            ) {
                val hapticNodes = remember { (0..10).map { it / 10f } }
                val hapticQuickNodes = remember {
                    listOf(
                        SliderQuickNode(0.0f, "关闭"),
                        SliderQuickNode(0.2f, "20%"),
                        SliderQuickNode(0.4f, "40%"),
                        SliderQuickNode(0.6f, "60%"),
                        SliderQuickNode(0.8f, "80%"),
                        SliderQuickNode(1.0f, "100%")
                    )
                }
                val pct = (settings.hapticStrength * 100).roundToInt()
                MagneticSnapSlider(
                    icon = Icons.Default.Vibration,
                    title = "触感震动强度",
                    valueText = if (pct <= 0) "关闭 (0%)" else "$pct%",
                    value = settings.hapticStrength,
                    valueRange = 0f..1f,
                    snapNodes = hapticNodes,
                    snapRadius = 0.008f,
                    quantizeStep = 0.01f,
                    quickNodes = hapticQuickNodes,
                    onNodeSnapHaptic = { v ->
                        haptics.click(forceEvenIfDisabled = true, overrideStrength = v)
                    },
                    onStepTickHaptic = { v ->
                        haptics.click(forceEvenIfDisabled = true, overrideStrength = v)
                    },
                    onValueChange = { v -> onSetHapticStrength(v) }
                )

                ThemeItemDivider()

                ThemeSwitchRow(
                    icon = Icons.Default.FormatSize,
                    title = "使用内置等宽字体",
                    subtitle = "使用内置等宽字体显示路径、体积与毫秒耗时，解决对齐异常",
                    checked = settings.useMonospaceFont,
                    onCheckedChange = {
                        haptics.click()
                        onSetUseMonospaceFont(it)
                    }
                )

                ThemeItemDivider()

                ThemeSwitchRow(
                    icon = Icons.Default.ViewCompact,
                    title = "紧凑列表模式",
                    subtitle = "缩小搜索结果行上下留白，一屏浏览更多文件条目",
                    checked = settings.compactListMode,
                    onCheckedChange = {
                        haptics.click()
                        onSetCompactListMode(it)
                    }
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ThemeLivePreviewCard(
    cardColor: Color,
    cardAlpha: Float,
    sourceLabel: String,
    useMonospace: Boolean,
    compactMode: Boolean
) {
    val monoFamily = if (useMonospace) FontFamily.Monospace else FontFamily.Default
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "实时效果预览",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Simulated Search Bar + Scope Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardAlpha),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "content:违约金",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = "2 ms",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = monoFamily,
                            fontFeatureSettings = "tnum"
                        ),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(if (compactMode) 6.dp else 10.dp))

            // Simulated Result Item
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (compactMode) 2.dp else 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                FileTypeIcon(isDir = false, ext = "pdf", size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "2026_合作补充协议.pdf",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "今天 14:20",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = monoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "第三条：逾期十日按万分之五计收 ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "违约金",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate950,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Amber200.copy(alpha = 0.85f))
                                .padding(horizontal = 3.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "/Download/Contracts",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = monoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "2.4 MB",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = monoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeGroupCard(
    title: String,
    cardColor: Color,
    cardAlpha: Float,
    hasCustomBg: Boolean,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                shadow = if (hasCustomBg) {
                    Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 4f)
                } else null
            ),
            fontWeight = FontWeight.SemiBold,
            color = if (hasCustomBg) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = cardColor,
            border = lowOpacityCardBorder(hasCustomBg = hasCustomBg, cardAlpha = cardAlpha),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ThemeSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badgeContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (badgeContent != null) {
            Spacer(Modifier.width(8.dp))
            badgeContent()
        }
        Spacer(Modifier.width(10.dp))
        ThumbSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Material 3 Switch with ✓ / ✕ thumb icons matching the reference theme settings UI.
 */
@Composable
fun ThumbSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize)
            )
        }
    )
}

@Composable
private fun TwoToneSwatchBall(
    primary: Color,
    container: Color,
    selected: Boolean
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = primary,
                startAngle = 135f,
                sweepAngle = 180f,
                useCenter = true,
                size = Size(size.width, size.height)
            )
            drawArc(
                color = container,
                startAngle = 315f,
                sweepAngle = 180f,
                useCenter = true,
                size = Size(size.width, size.height)
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun TwoToneSwatchDot(
    primary: Color,
    container: Color,
    isSelected: Boolean
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(
                width = if (isSelected) 1.5.dp else 0.8.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(color = primary, startAngle = 135f, sweepAngle = 180f, useCenter = true)
            drawArc(color = container, startAngle = 315f, sweepAngle = 180f, useCenter = true)
        }
    }
}

@Composable
private fun ThemeItemDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
}

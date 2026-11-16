package com.pocketeverything.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketeverything.app.ui.theme.Amber400
import com.pocketeverything.app.ui.theme.Emerald400
import com.pocketeverything.app.ui.theme.groupedBackdrop
import com.pocketeverything.app.ui.theme.groupedCard

/**
 * Interactive step-by-step Shizuku setup wizard that probes live installation,
 * binder service state, and permission grant status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuGuideScreen(
    status: ShizukuLiveStatus,
    onBack: () -> Unit,
    onRefreshStatus: () -> Unit,
    onOpenOrDownloadShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
    onEnableShizukuModeAndScan: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allReady = status.isInstalled && status.isServiceRunning && status.isPermissionGranted
    val backdrop = MaterialTheme.colorScheme.groupedBackdrop
    val cardColor = MaterialTheme.colorScheme.groupedCard

    Scaffold(
        containerColor = backdrop,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backdrop
                ),
                title = { Text("Shizuku 使用向导", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshStatus) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WizardStepCard(
                stepNumber = 1,
                title = "安装 Shizuku",
                completed = status.isInstalled,
                active = !status.isInstalled,
                cardColor = cardColor,
                body = if (status.isInstalled) {
                    "已检测到本机安装 Shizuku。"
                } else {
                    "未检测到 Shizuku，需先安装后才可免 Root 访问 /Android/data 与 /obb 目录。"
                }
            ) {
                if (!status.isInstalled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenOrDownloadShizuku) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("前往下载")
                        }
                        OutlinedButton(onClick = onRefreshStatus) {
                            Text("重新检测")
                        }
                    }
                }
            }

            WizardStepCard(
                stepNumber = 2,
                title = "启动 Shizuku 服务",
                completed = status.isServiceRunning,
                active = status.isInstalled && !status.isServiceRunning,
                cardColor = cardColor,
                body = if (status.isServiceRunning) {
                    "Shizuku 后台服务正在运行。"
                } else {
                    "在 Shizuku 中任选一种方式激活：\n1. 无线调试启动：开启系统开发者选项与无线调试，在 Shizuku 内完成配对并启动；\n2. 电脑 ADB 启动 / 已 Root 设备直接授权启动。"
                }
            ) {
                if (status.isInstalled && !status.isServiceRunning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenOrDownloadShizuku) {
                            Text("打开 Shizuku")
                        }
                        OutlinedButton(onClick = onRefreshStatus) {
                            Text("检测服务")
                        }
                    }
                }
            }

            WizardStepCard(
                stepNumber = 3,
                title = "授予本应用权限",
                completed = status.isPermissionGranted,
                active = status.isServiceRunning && !status.isPermissionGranted,
                cardColor = cardColor,
                body = if (status.isPermissionGranted) {
                    "已获得 Shizuku 授权，可切换至 Shizuku 模式并开始扫描。"
                } else {
                    "服务运行后，点击下方按钮在系统弹窗中允许授权。"
                }
            ) {
                if (status.isServiceRunning && !status.isPermissionGranted) {
                    Button(onClick = onRequestPermission) {
                        Text("请求 Shizuku 授权")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onEnableShizukuModeAndScan,
                enabled = allReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (allReady) "立即启用 Shizuku 模式并重建索引" else "完成以上三步后即可启用")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WizardStepCard(
    stepNumber: Int,
    title: String,
    completed: Boolean,
    active: Boolean,
    cardColor: Color,
    body: String,
    action: @Composable () -> Unit
) {
    val border = when {
        completed -> BorderStroke(1.dp, Emerald400.copy(alpha = 0.45f))
        active -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        else -> null
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        border = border,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badgeBg = when {
                    completed -> Emerald400
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                val badgeFg = when {
                    completed -> Color(0xFF052E16)
                    active -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(badgeBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (completed) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = badgeFg, modifier = Modifier.size(16.dp))
                    } else {
                        Text("$stepNumber", style = MaterialTheme.typography.labelLarge, color = badgeFg)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        completed -> "已完成"
                        active -> "当前"
                        else -> "等待"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        completed -> Emerald400
                        active -> Amber400
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}

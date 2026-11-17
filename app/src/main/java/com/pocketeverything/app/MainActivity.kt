package com.pocketeverything.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.data.ThemeMode
import com.pocketeverything.app.indexer.RealtimeMonitorService
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.ChangelogSheet
import com.pocketeverything.app.ui.FileDetailDialog
import com.pocketeverything.app.ui.MainViewModel
import com.pocketeverything.app.ui.OnboardingTargets
import com.pocketeverything.app.ui.ReaderPreviewSheet
import com.pocketeverything.app.ui.SearchScreen
import com.pocketeverything.app.ui.SettingsScreen
import com.pocketeverything.app.ui.ShizukuGuideScreen
import com.pocketeverything.app.ui.ThemeSettingsScreen
import com.pocketeverything.app.ui.UpdateAvailableDialog
import com.pocketeverything.app.ui.WelcomeGuideOverlay
import com.pocketeverything.app.ui.theme.PocketEverythingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class AppPage {
    SEARCH, SETTINGS, THEME_SETTINGS, SHIZUKU_GUIDE
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshModeStatuses()
        if (granted) {
            viewModel.triggerFullRebuild(clearFirst = false)
        }
    }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshModeStatuses()
        viewModel.triggerFullRebuild(clearFirst = false)
    }

    private val apkDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id > 0L && id == viewModel.activeDownloadId) {
                handleDownloadCompleted(id)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as PocketEverythingApp
        if (app.container.settingsRepository.settings.value.realtimeMonitorEnabled) {
            runCatching { RealtimeMonitorService.start(this) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this,
            apkDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        setContent {
            val settings by viewModel.settings.collectAsState()
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

            val customBgBitmap by produceState<Bitmap?>(
                initialValue = null,
                settings.customBgEnabled,
                settings.customBgPath,
                settings.customBgBlur
            ) {
                value = if (!settings.customBgEnabled || settings.customBgPath.isBlank()) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(settings.customBgPath)
                            if (!f.exists()) null else {
                                val raw = BitmapFactory.decodeFile(f.absolutePath)
                                if (raw != null && settings.customBgBlur && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    val sw = (raw.width / 10).coerceAtLeast(16)
                                    val sh = (raw.height / 10).coerceAtLeast(16)
                                    val small = Bitmap.createScaledBitmap(raw, sw, sh, true)
                                    Bitmap.createScaledBitmap(small, raw.width / 2, raw.height / 2, true)
                                } else {
                                    raw
                                }
                            }
                        }.getOrNull()
                    }
                }
            }

            val hasCustomBg = customBgBitmap != null

            PocketEverythingTheme(
                darkTheme = isDarkTheme,
                dynamicColor = settings.useDynamicColor,
                themeAccent = settings.themeAccent,
                pureBlackDark = settings.pureBlackDark,
                hasCustomBg = hasCustomBg,
                cardOpacity = settings.customBgOpacity,
                extractColorFromBg = settings.extractColorFromBg,
                customBgSeedArgb = settings.customBgSeedArgb,
                hapticsEnabled = settings.hapticFeedbackEnabled,
                hapticStrength = settings.hapticStrength
            ) {
                val query by viewModel.query.collectAsState()
                val quickScope by viewModel.quickScope.collectAsState()
                val sortOrder by viewModel.sortOrder.collectAsState()
                val searchResponse by viewModel.searchResponse.collectAsState()
                val modeStatuses by viewModel.modeStatuses.collectAsState()
                val shizukuStatus by viewModel.shizukuLiveStatus.collectAsState()
                val indexProgress by viewModel.indexProgress.collectAsState()
                val updateState by viewModel.updateState.collectAsState()
                val updateDialogInfo by viewModel.updateDialogInfo.collectAsState()
                val showChangelog by viewModel.showChangelog.collectAsState()
                val changelogState by viewModel.changelogState.collectAsState()
                val previewPayload by viewModel.previewPayload.collectAsState()
                val previewLoadingItem by viewModel.previewLoadingItem.collectAsState()
                val detailItem by viewModel.detailItem.collectAsState()

                var currentPage by remember { mutableStateOf(AppPage.SEARCH) }
                var previousPage by remember { mutableStateOf(AppPage.SEARCH) }
                var onboardingTargets by remember { mutableStateOf(OnboardingTargets()) }
                val snackbarHostState = remember { SnackbarHostState() }

                BackHandler(enabled = currentPage != AppPage.SEARCH) {
                    currentPage = when (currentPage) {
                        AppPage.SHIZUKU_GUIDE -> previousPage
                        AppPage.THEME_SETTINGS -> AppPage.SETTINGS
                        AppPage.SETTINGS -> AppPage.SEARCH
                        AppPage.SEARCH -> AppPage.SEARCH
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.messages.collect { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    customBgBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (settings.customBgBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(18.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                        if (settings.customBgDim > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = settings.customBgDim.coerceIn(0f, 0.85f)))
                            )
                        }
                    }

                    when (currentPage) {
                        AppPage.SEARCH -> {
                            SearchScreen(
                                query = query,
                                quickScope = quickScope,
                                sortOrder = sortOrder,
                                searchResponse = searchResponse,
                                settings = settings,
                                isDarkTheme = isDarkTheme,
                                modeStatuses = modeStatuses,
                                indexProgress = indexProgress,
                                onQueryChange = { viewModel.updateQuery(it) },
                                onScopeChange = { viewModel.updateQuickScope(it) },
                                onSortChange = { viewModel.updateSortOrder(it) },
                                onItemClick = { viewModel.onItemClicked(it) },
                                onItemLongPress = { viewModel.showDetail(it) },
                                onSelectMode = { viewModel.switchMode(it) },
                                onToggleTheme = { viewModel.toggleDayNight(isDarkTheme) },
                                onQuickSync = { viewModel.triggerQuickSync() },
                                onFullScan = { viewModel.triggerFullRebuild(clearFirst = false) },
                                onOpenSettings = { currentPage = AppPage.SETTINGS },
                                onOpenShizukuGuide = {
                                    previousPage = AppPage.SEARCH
                                    currentPage = AppPage.SHIZUKU_GUIDE
                                },
                                onRequestPermissionForCurrentMode = {
                                    when (settings.accessMode) {
                                        AccessMode.NORMAL -> requestAllFilesPermission()
                                        AccessMode.SHIZUKU -> viewModel.requestShizukuPermission()
                                        AccessMode.ROOT -> viewModel.switchMode(AccessMode.ROOT)
                                    }
                                },
                                onTargetsMeasured = { onboardingTargets = it }
                            )
                        }
                        AppPage.SETTINGS -> {
                            SettingsScreen(
                                settings = settings,
                                modeStatuses = modeStatuses,
                                indexProgress = indexProgress,
                                updateState = updateState,
                                onBack = { currentPage = AppPage.SEARCH },
                                onSelectMode = { viewModel.switchMode(it) },
                                onOpenThemeSettings = { currentPage = AppPage.THEME_SETTINGS },
                                onRequestStoragePermission = { requestAllFilesPermission() },
                                onOpenShizukuGuide = {
                                    previousPage = AppPage.SETTINGS
                                    currentPage = AppPage.SHIZUKU_GUIDE
                                },
                                onRefreshModes = { viewModel.refreshModeStatuses() },
                                onUpdateFilterConfig = { viewModel.updateFilterConfig(it) },
                                onSetShowRecentOnEmpty = { viewModel.setShowRecentOnEmptyQuery(it) },
                                onSetPreviewPreference = { viewModel.setOpenContentHitsInPreview(it) },
                                onSetRealtimeMonitor = { viewModel.setRealtimeMonitorEnabled(it) },
                                onQuickSync = { viewModel.triggerQuickSync() },
                                onFullRebuild = { viewModel.triggerFullRebuild(clearFirst = true) },
                                onCheckUpdates = { viewModel.checkForUpdates(silent = false) },
                                onOpenChangelog = { viewModel.openChangelog() },
                                onOpenUrl = { viewModel.openUrl(it) },
                                onCopyUrl = { viewModel.copyText("仓库地址", it) },
                                onReplayOnboarding = {
                                    viewModel.replayOnboarding()
                                    currentPage = AppPage.SEARCH
                                }
                            )
                        }
                        AppPage.THEME_SETTINGS -> {
                            ThemeSettingsScreen(
                                settings = settings,
                                isDarkTheme = isDarkTheme,
                                onBack = { currentPage = AppPage.SETTINGS },
                                onSelectThemeMode = { viewModel.setThemeMode(it) },
                                onSetDynamicColor = { viewModel.setUseDynamicColor(it) },
                                onSelectThemeAccent = { viewModel.setThemeAccent(it) },
                                onSetPureBlackDark = { viewModel.setPureBlackDark(it) },
                                onSetCustomBgEnabled = { viewModel.setCustomBgEnabled(it) },
                                onPickCustomBgUri = { viewModel.applyCustomBgUri(it) },
                                onClearCustomBg = { viewModel.clearCustomBg() },
                                onSetCustomBgOpacity = { viewModel.setCustomBgOpacity(it) },
                                onSetCustomBgDim = { viewModel.setCustomBgDim(it) },
                                onSetCustomBgBlur = { viewModel.setCustomBgBlur(it) },
                                onSetExtractColorFromBg = { viewModel.setExtractColorFromBg(it) },
                                onSetHapticStrength = { viewModel.setHapticStrength(it) },
                                onSetUseMonospaceFont = { viewModel.setUseMonospaceFont(it) },
                                onSetCompactListMode = { viewModel.setCompactListMode(it) },
                                onResetThemeSettings = { viewModel.resetThemeSettings() }
                            )
                        }
                        AppPage.SHIZUKU_GUIDE -> {
                            ShizukuGuideScreen(
                                status = shizukuStatus,
                                onBack = { currentPage = previousPage },
                                onRefreshStatus = { viewModel.refreshModeStatuses() },
                                onOpenOrDownloadShizuku = { viewModel.openShizukuOrDownload() },
                                onRequestPermission = { viewModel.requestShizukuPermission() },
                                onEnableShizukuModeAndScan = {
                                    viewModel.switchMode(AccessMode.SHIZUKU)
                                    currentPage = AppPage.SEARCH
                                }
                            )
                        }
                    }

                    if (currentPage == AppPage.SEARCH && !settings.hasSeenOnboarding) {
                        WelcomeGuideOverlay(
                            targets = onboardingTargets,
                            onFinish = { viewModel.completeOnboarding() }
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                if (previewPayload != null || previewLoadingItem != null) {
                    ReaderPreviewSheet(
                        payload = previewPayload,
                        loadingItem = previewLoadingItem,
                        onDismiss = { viewModel.closePreview() },
                        onNavigateBack = { viewModel.navigatePreviewBack() },
                        onOpenChild = { child -> viewModel.openPreview(child, pushCurrentToBackStack = true) },
                        onRefreshFolder = { folder -> viewModel.refreshDirectory(folder.path) },
                        onOpenExternal = { target -> viewModel.openExternally(target) },
                        onCopyPath = { target -> viewModel.copyText("文件路径", target.path) }
                    )
                }

                detailItem?.let { item ->
                    FileDetailDialog(
                        item = item,
                        onDismiss = { viewModel.closeDetail() },
                        onPreview = { viewModel.openPreview(item) },
                        onOpenExternal = { viewModel.openExternally(item) },
                        onCopyPath = { viewModel.copyText("文件路径", item.path) },
                        onCopyName = { viewModel.copyText("文件名", item.name) },
                        onShare = { viewModel.shareItem(item) },
                        onRefreshParent = {
                            val targetDir = if (item.isDir) item.path else item.parentPath
                            viewModel.refreshDirectory(targetDir)
                        }
                    )
                }

                updateDialogInfo?.let { info ->
                    UpdateAvailableDialog(
                        info = info,
                        onDownloadApk = {
                            viewModel.dismissUpdateDialog()
                            viewModel.downloadUpdateApk(info)
                        },
                        onOpenBrowser = {
                            viewModel.dismissUpdateDialog()
                            viewModel.openUrl(info.releaseUrl)
                        },
                        onIgnoreVersion = { viewModel.ignoreUpdateVersion(info.tagName) },
                        onDismissLater = { viewModel.dismissUpdateDialog() }
                    )
                }

                if (showChangelog) {
                    ChangelogSheet(
                        changelogState = changelogState,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        buildDate = BuildConfig.BUILD_DATE,
                        gitHash = BuildConfig.GIT_HASH,
                        onRefresh = { viewModel.refreshChangelog() },
                        onDownloadApk = { info -> viewModel.downloadUpdateApk(info) },
                        onOpenUrl = { viewModel.openUrl(it) },
                        onDismiss = { viewModel.closeChangelog() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(apkDownloadReceiver) }
        super.onDestroy()
    }

    private fun handleDownloadCompleted(downloadId: Long) {
        if (isFinishing || isDestroyed) return
        val dm = getSystemService(DownloadManager::class.java) ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusCol < 0) return
            when (cursor.getInt(statusCol)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = dm.getUriForDownloadedFile(downloadId)
                    if (uri == null) {
                        viewModel.notifyMessage("安装包已下载，请从系统“下载”应用打开安装")
                        return
                    }
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching {
                        startActivity(installIntent)
                    }.onFailure {
                        viewModel.notifyMessage("安装包已下载，请在通知栏或系统“下载”中点击安装")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    viewModel.notifyMessage("安装包下载失败，建议点击“浏览器打开”手动下载")
                }
            }
        }
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching {
                allFilesLauncher.launch(intent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesLauncher.launch(fallback)
            }
        } else {
            legacyStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

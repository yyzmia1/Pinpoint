package com.pocketeverything.app

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.data.ThemeMode
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.FileDetailDialog
import com.pocketeverything.app.ui.MainViewModel
import com.pocketeverything.app.ui.OnboardingTargets
import com.pocketeverything.app.ui.ReaderPreviewSheet
import com.pocketeverything.app.ui.SearchScreen
import com.pocketeverything.app.ui.SettingsScreen
import com.pocketeverything.app.ui.ShizukuGuideScreen
import com.pocketeverything.app.ui.WelcomeGuideOverlay
import com.pocketeverything.app.ui.theme.PocketEverythingTheme

private enum class AppPage {
    SEARCH, SETTINGS, SHIZUKU_GUIDE
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

            PocketEverythingTheme(darkTheme = isDarkTheme) {
                val query by viewModel.query.collectAsState()
                val quickScope by viewModel.quickScope.collectAsState()
                val sortOrder by viewModel.sortOrder.collectAsState()
                val searchResponse by viewModel.searchResponse.collectAsState()
                val modeStatuses by viewModel.modeStatuses.collectAsState()
                val shizukuStatus by viewModel.shizukuLiveStatus.collectAsState()
                val indexProgress by viewModel.indexProgress.collectAsState()
                val updateState by viewModel.updateState.collectAsState()
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
                                onSelectThemeMode = { viewModel.setThemeMode(it) },
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
                                onCheckUpdates = { viewModel.checkForUpdates() },
                                onDismissUpdateDialog = { viewModel.dismissUpdateDialog() },
                                onOpenUrl = { viewModel.openUrl(it) },
                                onCopyUrl = { viewModel.copyText("仓库地址", it) },
                                onReplayOnboarding = {
                                    viewModel.replayOnboarding()
                                    currentPage = AppPage.SEARCH
                                }
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

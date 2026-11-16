package com.pocketeverything.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.ui.FileDetailDialog
import com.pocketeverything.app.ui.MainViewModel
import com.pocketeverything.app.ui.ReaderPreviewSheet
import com.pocketeverything.app.ui.SearchScreen
import com.pocketeverything.app.ui.SettingsScreen
import com.pocketeverything.app.ui.theme.PocketEverythingTheme

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
            PocketEverythingTheme {
                val query by viewModel.query.collectAsState()
                val quickScope by viewModel.quickScope.collectAsState()
                val sortOrder by viewModel.sortOrder.collectAsState()
                val searchResponse by viewModel.searchResponse.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val modeStatuses by viewModel.modeStatuses.collectAsState()
                val indexProgress by viewModel.indexProgress.collectAsState()
                val previewPayload by viewModel.previewPayload.collectAsState()
                val detailItem by viewModel.detailItem.collectAsState()

                var showSettings by remember { mutableStateOf(false) }
                val snackbarHostState = remember { SnackbarHostState() }

                BackHandler(enabled = showSettings) {
                    showSettings = false
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
                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            modeStatuses = modeStatuses,
                            indexProgress = indexProgress,
                            onBack = { showSettings = false },
                            onSelectMode = { viewModel.switchMode(it) },
                            onRequestStoragePermission = { requestAllFilesPermission() },
                            onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                            onRefreshModes = { viewModel.refreshModeStatuses() },
                            onUpdateFilterConfig = { viewModel.updateFilterConfig(it) },
                            onSetPreviewPreference = { viewModel.setOpenContentHitsInPreview(it) },
                            onSetRealtimeMonitor = { viewModel.setRealtimeMonitorEnabled(it) },
                            onQuickSync = { viewModel.triggerQuickSync() },
                            onFullRebuild = { viewModel.triggerFullRebuild(clearFirst = true) }
                        )
                    } else {
                        SearchScreen(
                            query = query,
                            quickScope = quickScope,
                            sortOrder = sortOrder,
                            searchResponse = searchResponse,
                            settings = settings,
                            modeStatuses = modeStatuses,
                            indexProgress = indexProgress,
                            onQueryChange = { viewModel.updateQuery(it) },
                            onScopeChange = { viewModel.updateQuickScope(it) },
                            onSortChange = { viewModel.updateSortOrder(it) },
                            onItemClick = { viewModel.onItemClicked(it) },
                            onItemLongPress = { viewModel.showDetail(it) },
                            onSelectMode = { viewModel.switchMode(it) },
                            onQuickSync = { viewModel.triggerQuickSync() },
                            onOpenSettings = { showSettings = true },
                            onRequestPermissionForCurrentMode = {
                                when (settings.accessMode) {
                                    AccessMode.NORMAL -> requestAllFilesPermission()
                                    AccessMode.SHIZUKU -> viewModel.requestShizukuPermission()
                                    AccessMode.ROOT -> viewModel.switchMode(AccessMode.ROOT)
                                }
                            }
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

                previewPayload?.let { payload ->
                    ReaderPreviewSheet(
                        payload = payload,
                        onDismiss = { viewModel.closePreview() },
                        onOpenExternal = { viewModel.openExternally(payload.item) },
                        onCopyPath = { viewModel.copyText("文件路径", payload.item.path) }
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

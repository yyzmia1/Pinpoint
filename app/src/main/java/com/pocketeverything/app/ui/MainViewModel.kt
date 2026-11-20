package com.pocketeverything.app.ui

import android.app.Application
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketeverything.app.BuildConfig
import com.pocketeverything.app.FilePickerActivity
import com.pocketeverything.app.PocketEverythingApp
import com.pocketeverything.app.picker.PinpointDocumentsProvider
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.data.ChangelogState
import com.pocketeverything.app.data.ReleaseUpdateInfo
import com.pocketeverything.app.data.ThemeAccent
import com.pocketeverything.app.data.ThemeMode
import com.pocketeverything.app.data.UpdateCheckState
import com.pocketeverything.app.data.UpdateChecker
import com.pocketeverything.app.ui.theme.extractSeedColorArgb
import java.io.FileOutputStream
import kotlin.math.max
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.analyzer.AnalyzerTreeSort
import com.pocketeverything.app.analyzer.AndroidAppInfoResolver
import com.pocketeverything.app.analyzer.WizTreeEngine
import com.pocketeverything.app.fs.DeletionRisk
import com.pocketeverything.app.fs.DeletionSafetyGuard
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.fs.StorageRootsHelper
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.RealtimeMonitorService
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.QuerySyntaxParser
import com.pocketeverything.app.search.QuickScope
import com.pocketeverything.app.search.SearchResponse
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.search.SortOrder
import com.pocketeverything.app.ui.preview.PreviewPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

data class ShizukuLiveStatus(
    val isInstalled: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isPermissionGranted: Boolean = false
)

data class PendingModeSwitch(
    val targetMode: AccessMode
)

data class UiNotice(
    val text: String,
    val jumpToSearch: Boolean = false
)

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PocketEverythingApp
    private val container = app.container
    private val db = container.database
    private val settingsRepo = container.settingsRepository
    private val indexManager = container.indexManager

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val indexProgress: StateFlow<IndexProgressState> = indexManager.progress

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _quickScope = MutableStateFlow(QuickScope.ALL)
    val quickScope: StateFlow<QuickScope> = _quickScope.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.RELEVANCE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _searchResponse = MutableStateFlow(SearchResponse(emptyList(), 0, 0L))
    val searchResponse: StateFlow<SearchResponse> = _searchResponse.asStateFlow()

    private val _modeStatuses = MutableStateFlow<Map<AccessMode, ModeAvailability>>(emptyMap())
    val modeStatuses: StateFlow<Map<AccessMode, ModeAvailability>> = _modeStatuses.asStateFlow()

    private val _shizukuLiveStatus = MutableStateFlow(ShizukuLiveStatus())
    val shizukuLiveStatus: StateFlow<ShizukuLiveStatus> = _shizukuLiveStatus.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

    private val _updateDialogInfo = MutableStateFlow<ReleaseUpdateInfo?>(null)
    val updateDialogInfo: StateFlow<ReleaseUpdateInfo?> = _updateDialogInfo.asStateFlow()

    private val _showChangelog = MutableStateFlow(false)
    val showChangelog: StateFlow<Boolean> = _showChangelog.asStateFlow()

    private val _changelogState = MutableStateFlow<ChangelogState>(ChangelogState.Idle)
    val changelogState: StateFlow<ChangelogState> = _changelogState.asStateFlow()

    private var updateCheckJob: Job? = null

    @Volatile
    var activeDownloadId: Long = settingsRepo.getActiveDownloadId()
        private set

    fun clearActiveDownloadId() {
        activeDownloadId = -1L
        settingsRepo.setActiveDownloadId(-1L)
    }

    private val _previewPayload = MutableStateFlow<PreviewPayload?>(null)
    val previewPayload: StateFlow<PreviewPayload?> = _previewPayload.asStateFlow()

    private val _previewLoadingItem = MutableStateFlow<SearchResultItem?>(null)
    val previewLoadingItem: StateFlow<SearchResultItem?> = _previewLoadingItem.asStateFlow()

    private val previewHistory = ArrayDeque<SearchResultItem>()
    private var previewLoadJob: Job? = null

    private val _detailItem = MutableStateFlow<SearchResultItem?>(null)
    val detailItem: StateFlow<SearchResultItem?> = _detailItem.asStateFlow()

    private val _pendingModeSwitch = MutableStateFlow<PendingModeSwitch?>(null)
    val pendingModeSwitch: StateFlow<PendingModeSwitch?> = _pendingModeSwitch.asStateFlow()

    private val _analyzerState = MutableStateFlow(StorageAnalyzerUiState())
    val analyzerState: StateFlow<StorageAnalyzerUiState> = _analyzerState.asStateFlow()

    private var analyzerBuildJob: Job? = null
    private var analyzerChildrenJob: Job? = null
    private var analyzerLargeFilesJob: Job? = null
    private var analyzerBuiltGeneration: Long = -1L
    private val appInfoResolver by lazy { AndroidAppInfoResolver(app) }

    private val _messages = MutableSharedFlow<UiNotice>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiNotice> = _messages.asSharedFlow()

    private fun emitMessage(text: String, jumpToSearch: Boolean = false) {
        _messages.tryEmit(UiNotice(text, jumpToSearch))
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        viewModelScope.launch {
            refreshModeStatuses()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                emitMessage("Shizuku 授权成功")
                if (settings.value.accessMode == AccessMode.SHIZUKU) {
                    indexManager.startFullScan(clearExisting = false)
                } else {
                    switchMode(AccessMode.SHIZUKU)
                }
            } else {
                emitMessage("未授予 Shizuku 权限")
            }
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        refreshModeStatuses()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        refreshModeStatuses()
    }

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        }

        refreshModeStatuses()
        indexManager.triggerStartupSync()

        viewModelScope.launch {
            delay(1500L)
            checkForUpdates(silent = true)
        }

        viewModelScope.launch {
            val indexMutationTick = indexManager.progress
                .map { Triple(it.stats.totalEntries, it.stats.indexedDocs, it.syncGeneration) }
                .distinctUntilChanged()
                .debounce(250)

            combine(
                _query.debounce(65),
                _quickScope,
                _sortOrder,
                settingsRepo.settings.map { it.showRecentOnEmptyQuery }.distinctUntilChanged(),
                indexMutationTick
            ) { q, scope, sort, showRecent, tick ->
                SearchTriggerParams(q, scope, sort, showRecent, tick)
            }.distinctUntilChanged().collectLatest { params ->
                val resp = withContext(Dispatchers.IO) {
                    try {
                        db.search(
                            rawQuery = params.query,
                            quickScope = params.scope,
                            sortOrder = params.sort,
                            limit = 150,
                            showRecentOnEmpty = params.showRecentOnEmpty
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        SearchResponse(emptyList(), 0, 0L, queryError = "检索异常：${t.message ?: "请重试"}")
                    }
                }
                _searchResponse.value = resp
            }
        }
    }

    private data class SearchTriggerParams(
        val query: String,
        val scope: QuickScope,
        val sort: SortOrder,
        val showRecentOnEmpty: Boolean,
        val indexTick: Triple<Long, Long, Long>
    )

    fun onAppForegrounded() {
        refreshModeStatuses()
        indexManager.triggerForegroundSync()
    }

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }

    fun updateQuickScope(scope: QuickScope) {
        _quickScope.value = scope
    }

    fun updateSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun refreshModeStatuses(probeRoot: Boolean = settings.value.accessMode == AccessMode.ROOT) {
        viewModelScope.launch(Dispatchers.IO) {
            val prevRoot = _modeStatuses.value[AccessMode.ROOT]
            val map = linkedMapOf<AccessMode, ModeAvailability>()
            for (mode in AccessMode.entries) {
                map[mode] = if (mode == AccessMode.ROOT && !probeRoot) {
                    prevRoot ?: ModeAvailability.Unavailable("点击检测或切换至 Root 模式")
                } else {
                    container.providerFor(mode).checkAvailability()
                }
            }
            _modeStatuses.value = map

            val running = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
            val installed = running || runCatching {
                app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            }.getOrDefault(false)
            val granted = running && runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            _shizukuLiveStatus.value = ShizukuLiveStatus(installed, running, granted)
        }
    }

    fun retryCurrentModeAndScan() {
        viewModelScope.launch {
            val mode = settings.value.accessMode
            val availability = withContext(Dispatchers.IO) {
                container.providerFor(mode).checkAvailability()
            }
            refreshModeStatuses(probeRoot = mode == AccessMode.ROOT)
            when (availability) {
                is ModeAvailability.Ready -> {
                    if (indexManager.isTier1Busy()) {
                        emitMessage("索引任务正在执行中（已扫描 ${indexProgress.value.tier1ScannedCount} 项）")
                    } else if (indexProgress.value.stats.totalEntries == 0L || settings.value.lastFullScanAtMillis == 0L) {
                        indexManager.startFullScan(clearExisting = false)
                        emitMessage("${mode.label} 已就绪，正在后台扫描建库")
                    } else {
                        indexManager.startQuickHotDirsRefresh()
                        emitMessage("${mode.label} 已就绪，正在增量同步")
                    }
                }
                is ModeAvailability.NeedsPermission -> emitMessage(availability.reason)
                is ModeAvailability.Unavailable -> emitMessage(availability.reason)
            }
        }
    }

    fun switchMode(mode: AccessMode) {
        viewModelScope.launch {
            val availability = withContext(Dispatchers.IO) {
                container.providerFor(mode).checkAvailability()
            }
            refreshModeStatuses(probeRoot = mode == AccessMode.ROOT || settings.value.accessMode == AccessMode.ROOT)
            when (availability) {
                is ModeAvailability.Ready -> {
                    val curSettings = settings.value
                    if (mode == curSettings.accessMode) {
                        if (indexProgress.value.stats.totalEntries == 0L) {
                            indexManager.startFullScan(clearExisting = false)
                            emitMessage("${mode.label} 已就绪，正在扫描建库")
                        } else {
                            emitMessage("当前已处于 ${mode.label}")
                        }
                        return@launch
                    }
                    _pendingModeSwitch.value = PendingModeSwitch(targetMode = mode)
                }
                is ModeAvailability.NeedsPermission -> {
                    if (mode == AccessMode.SHIZUKU) {
                        requestShizukuPermission()
                    } else {
                        emitMessage(availability.reason)
                    }
                }
                is ModeAvailability.Unavailable -> {
                    emitMessage(availability.reason)
                }
            }
        }
    }

    fun dismissPendingModeSwitch() {
        _pendingModeSwitch.value = null
    }

    fun confirmPendingModeSwitch() {
        val pending = _pendingModeSwitch.value ?: return
        _pendingModeSwitch.value = null
        settingsRepo.updateAccessMode(pending.targetMode)
        indexManager.startFullScan(clearExisting = false)
        emitMessage("已切换至 ${pending.targetMode.label}，正在后台重建索引", jumpToSearch = true)
    }

    fun requestShizukuPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                emitMessage("Shizuku 服务未运行，请先启动 Shizuku")
                return
            }
            if (Shizuku.isPreV11()) {
                emitMessage("Shizuku 版本过低（需 v11+），请更新后再试")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshModeStatuses()
                if (settings.value.accessMode == AccessMode.SHIZUKU) {
                    retryCurrentModeAndScan()
                } else {
                    switchMode(AccessMode.SHIZUKU)
                }
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                emitMessage("请在 Shizuku 应用的授权管理中手动允许 Pinpoint")
                openShizukuOrDownload()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        }.onFailure {
            emitMessage("调用 Shizuku 失败：${it.message}")
        }
    }

    fun openShizukuOrDownload() {
        val launchIntent = app.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launchIntent)
        } else {
            openUrl("https://shizuku.rikka.app/zh-hans/download/")
        }
    }

    fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure {
            emitMessage("无法打开链接：$url")
        }
    }

    fun setReceiveStableUpdates(enabled: Boolean) {
        settingsRepo.setReceiveStableUpdates(enabled)
        val curState = _updateState.value
        if (!enabled) {
            if ((curState is UpdateCheckState.Available && !curState.info.isPrerelease) ||
                (!settings.value.receiveBetaUpdates && (curState is UpdateCheckState.UpToDate || curState is UpdateCheckState.Error))
            ) {
                _updateState.value = UpdateCheckState.Idle
            }
            if (_updateDialogInfo.value?.isPrerelease == false) {
                _updateDialogInfo.value = null
            }
        } else if (curState is UpdateCheckState.UpToDate || curState is UpdateCheckState.Error) {
            _updateState.value = UpdateCheckState.Idle
        }
    }

    fun setReceiveBetaUpdates(enabled: Boolean) {
        settingsRepo.setReceiveBetaUpdates(enabled)
        val curState = _updateState.value
        if (!enabled) {
            if ((curState is UpdateCheckState.Available && curState.info.isPrerelease) ||
                (!settings.value.receiveStableUpdates && (curState is UpdateCheckState.UpToDate || curState is UpdateCheckState.Error))
            ) {
                _updateState.value = UpdateCheckState.Idle
            }
            if (_updateDialogInfo.value?.isPrerelease == true) {
                _updateDialogInfo.value = null
            }
        } else if (curState is UpdateCheckState.UpToDate || curState is UpdateCheckState.Error) {
            _updateState.value = UpdateCheckState.Idle
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        val cur = settings.value
        if (!cur.receiveStableUpdates && !cur.receiveBetaUpdates) {
            if (!silent) {
                _updateState.value = UpdateCheckState.Idle
                emitMessage("已关闭正式版与测试版更新接收，请先开启至少一项")
            }
            return
        }
        if (!silent && _updateState.value is UpdateCheckState.Checking) return
        val now = System.currentTimeMillis()
        if (silent && !UpdateChecker.shouldRunSilentCheck(cur.lastUpdateCheckAtMillis, now)) {
            return
        }
        if (!silent) {
            updateCheckJob?.cancel()
            _updateState.value = UpdateCheckState.Checking
        } else if (updateCheckJob?.isActive == true) {
            return
        }

        updateCheckJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkLatestRelease(
                    repoSlug = BuildConfig.GITHUB_REPO,
                    currentVersion = BuildConfig.VERSION_NAME,
                    receiveStable = cur.receiveStableUpdates,
                    receiveBeta = cur.receiveBetaUpdates
                )
            }
            val reachedServer = result !is UpdateCheckState.Error || result.httpStatusCode != null
            if (reachedServer) {
                settingsRepo.markUpdateChecked()
            }
            val latestSettings = settings.value
            val stillEligible = result is UpdateCheckState.Available &&
                ((!result.info.isPrerelease && latestSettings.receiveStableUpdates) ||
                    (result.info.isPrerelease && latestSettings.receiveBetaUpdates))
            if (silent) {
                if (stillEligible &&
                    UpdateChecker.shouldPromptSilentUpdate(
                        latestTag = (result as UpdateCheckState.Available).info.tagName,
                        currentVersion = BuildConfig.VERSION_NAME,
                        ignoredTag = latestSettings.ignoredUpdateTag
                    )
                ) {
                    _updateState.value = result
                    _updateDialogInfo.value = result.info
                }
            } else {
                _updateState.value = if (result is UpdateCheckState.Available && !stillEligible) {
                    UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME)
                } else {
                    result
                }
                if (stillEligible) {
                    _updateDialogInfo.value = (result as UpdateCheckState.Available).info
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateDialogInfo.value = null
    }

    fun ignoreUpdateVersion(tagName: String) {
        settingsRepo.setIgnoredUpdateTag(tagName)
        _updateDialogInfo.value = null
        emitMessage("已忽略 $tagName，后续不再自动弹出提醒")
    }

    fun downloadUpdateApk(info: ReleaseUpdateInfo) {
        val url = info.apkDownloadUrl
        if (url.isNullOrBlank()) {
            openUrl(info.releaseUrl)
            return
        }
        runCatching {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val safeFileName = "Pinpoint-${info.tagName}.apk".replace(Regex("[/\\\\:*?\"<>|]"), "_")
            app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
                val old = File(dir, safeFileName)
                if (old.exists()) old.delete()
            }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Pinpoint ${info.tagName}")
                .setDescription("正在下载安装包…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, safeFileName)
            activeDownloadId = dm.enqueue(request)
            settingsRepo.setActiveDownloadId(activeDownloadId)
            emitMessage("已调用系统下载器下载，可在通知栏查看进度")
        }.onFailure {
            emitMessage("系统下载器不可用，已转为浏览器打开")
            openUrl(info.releaseUrl)
        }
    }

    fun notifyMessage(message: String) {
        emitMessage(message)
    }

    fun openChangelog() {
        _showChangelog.value = true
        if (_changelogState.value is ChangelogState.Idle || _changelogState.value is ChangelogState.Error) {
            refreshChangelog()
        }
    }

    fun refreshChangelog() {
        if (_changelogState.value is ChangelogState.Loading) return
        _changelogState.value = ChangelogState.Loading
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                UpdateChecker.fetchReleaseHistory(BuildConfig.GITHUB_REPO, perPage = 15)
            }
            _changelogState.value = res.fold(
                onSuccess = { list -> ChangelogState.Loaded(list) },
                onFailure = { err -> ChangelogState.Error(err.message ?: "无法加载版本历史") }
            )
        }
    }

    fun closeChangelog() {
        _showChangelog.value = false
    }

    fun completeOnboarding() {
        settingsRepo.setHasSeenOnboarding(true)
    }

    fun replayOnboarding() {
        settingsRepo.setHasSeenOnboarding(false)
    }

    fun triggerQuickSync() {
        if (indexManager.isTier1Busy()) {
            emitMessage("索引任务正在执行中（已扫描 ${indexProgress.value.tier1ScannedCount} 项）", jumpToSearch = true)
            return
        }
        indexManager.startQuickHotDirsRefresh()
        emitMessage("正在全范围增量校验目录变更…")
    }

    fun triggerFullRebuild(clearFirst: Boolean = false) {
        if (indexManager.isTier1Busy()) {
            emitMessage("全盘建库正在进行中（已扫 ${indexProgress.value.tier1ScannedCount} 项），可回首页查看", jumpToSearch = true)
            return
        }
        indexManager.startFullScan(clearExisting = clearFirst)
        emitMessage("已开始全盘重建索引，可返回首页查看实时进度", jumpToSearch = true)
    }

    fun cancelScan() {
        indexManager.cancelCurrentScan()
        emitMessage("已取消当前扫描")
    }

    fun dismissScanError() {
        indexManager.clearErrorMessage()
    }

    fun refreshDirectory(dirPath: String) {
        indexManager.startDirectoryRefresh(dirPath)
        emitMessage("正在刷新目录：$dirPath")
    }

    suspend fun listChildDirectories(parentPath: String): List<String> = withContext(Dispatchers.IO) {
        val cleanParent = parentPath.trim().trimEnd('/').ifEmpty { "/" }
        val openFilter = FilterEngine(
            FilterConfig(
                skipNoiseCaches = false,
                deduplicateMounts = false,
                customExcludedPaths = emptyList()
            )
        )
        val dirs = LinkedHashSet<String>()
        val mode = settings.value.accessMode
        val provider = container.providerFor(mode)
        val ready = runCatching { provider.checkAvailability() is ModeAvailability.Ready }.getOrDefault(false)
        if (ready) {
            if (cleanParent == "/" && mode != AccessMode.ROOT) {
                dirs.addAll(StorageRootsHelper.getNormalStorageRoots(app))
            } else {
                runCatching {
                    provider.listDirectChildren(listOf(cleanParent), openFilter) { batch ->
                        for (rec in batch) {
                            if (rec.isDir) dirs.add(rec.path)
                        }
                    }
                }
            }
        }
        if (dirs.isEmpty()) {
            db.listChildren(cleanParent, 400).asSequence().filter { it.isDir }.forEach { dirs.add(it.path) }
        }
        dirs.sortedBy { it.lowercase() }
    }

    fun updateFilterConfig(config: FilterConfig) {
        val old = settings.value.filterConfig
        val mode = settings.value.accessMode
        settingsRepo.updateFilterConfig(config)
        val cleanOld = FilterConfig.normalizePaths(old.customExcludedPaths)
        val cleanNew = FilterConfig.normalizePaths(config.customExcludedPaths)
        val pruneRelevantChanged =
            old.skipNoiseCaches != config.skipNoiseCaches ||
                old.deduplicateMounts != config.deduplicateMounts ||
                cleanOld != cleanNew ||
                FilterConfig.normalizeTier2AllowedPrefixes(old.tier2AllowedPrefixes) !=
                FilterConfig.normalizeTier2AllowedPrefixes(config.tier2AllowedPrefixes) ||
                FilterConfig.normalizePaths(old.tier2BlockedPrefixes) !=
                FilterConfig.normalizePaths(config.tier2BlockedPrefixes) ||
                old.maxTxtMdBytes != config.maxTxtMdBytes ||
                old.maxDocxBytes != config.maxDocxBytes ||
                old.maxPdfBytes != config.maxPdfBytes

        if (!pruneRelevantChanged) {
            emitMessage("正文提取优先级已更新")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val summary = indexManager.pruneByCurrentFilter()
            val removedExcludes = cleanOld.filter { prev ->
                prev !in cleanNew && cleanNew.none { cur -> cur == "/" || prev.startsWith("$cur/") }
            }
            val accessibleNewDirs = removedExcludes.filter { mode == AccessMode.ROOT || !it.startsWith("/data") }
            val fullRescanNeeded = (old.skipNoiseCaches && !config.skipNoiseCaches) ||
                (old.deduplicateMounts && !config.deduplicateMounts)

            when {
                fullRescanNeeded -> indexManager.startFullScan(clearExisting = false)
                accessibleNewDirs.isNotEmpty() -> indexManager.startDirectoriesRefresh(accessibleNewDirs)
            }

            val freedNote = if (summary.freedBytes >= 64 * 1024L) {
                " · 释放 ${FormatUtils.formatBytes(summary.freedBytes)}"
            } else ""
            val msg = when {
                fullRescanNeeded || accessibleNewDirs.isNotEmpty() ->
                    "规则已更新，正在补扫新增范围"
                summary.removedEntries > 0 && summary.clearedDocBodies > 0 ->
                    "已清理 ${summary.removedEntries} 项索引与 ${summary.clearedDocBodies} 篇正文$freedNote"
                summary.removedEntries > 0 ->
                    "已从索引库移除 ${summary.removedEntries} 条记录$freedNote"
                summary.clearedDocBodies > 0 ->
                    "已清理 ${summary.clearedDocBodies} 篇超范围正文$freedNote"
                summary.requeuedDocs > 0 ->
                    "规则已放宽，已将 ${summary.requeuedDocs} 篇文档加入提取队列"
                else ->
                    "规则已应用"
            }
            emitMessage(msg)
        }
    }

    fun setOpenContentHitsInPreview(enabled: Boolean) {
        settingsRepo.setOpenContentHitsInPreview(enabled)
    }

    fun setShowRecentOnEmptyQuery(enabled: Boolean) {
        settingsRepo.setShowRecentOnEmptyQuery(enabled)
    }

    fun setSystemFilePickerEnabled(enabled: Boolean) {
        settingsRepo.setSystemFilePickerEnabled(enabled)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            val pm = app.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(app, FilePickerActivity::class.java),
                state,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(app, PinpointDocumentsProvider::class.java),
                state,
                PackageManager.DONT_KILL_APP
            )
            app.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri("${app.packageName}.documents"),
                null
            )
        }
        emitMessage(if (enabled) "已开启系统文件选择器入口" else "已关闭系统文件选择器入口")
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsRepo.setThemeMode(mode)
    }

    fun toggleDayNight(currentlyDark: Boolean) {
        settingsRepo.setThemeMode(if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    fun setUseDynamicColor(enabled: Boolean) {
        settingsRepo.setUseDynamicColor(enabled)
    }

    fun setThemeAccent(accent: ThemeAccent) {
        settingsRepo.setThemeAccent(accent)
    }

    fun setPureBlackDark(enabled: Boolean) {
        settingsRepo.setPureBlackDark(enabled)
    }

    fun setCustomBgEnabled(enabled: Boolean) {
        settingsRepo.setCustomBgEnabled(enabled)
    }

    fun setCustomBgOpacity(opacity: Float) {
        settingsRepo.setCustomBgOpacity(opacity)
    }

    fun setCustomBgDim(dim: Float) {
        settingsRepo.setCustomBgDim(dim)
    }

    fun setCustomBgBlur(enabled: Boolean) {
        settingsRepo.setCustomBgBlur(enabled)
    }

    fun setExtractColorFromBg(enabled: Boolean) {
        settingsRepo.setExtractColorFromBg(enabled)
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        settingsRepo.setHapticFeedbackEnabled(enabled)
    }

    fun setHapticStrength(strength: Float) {
        settingsRepo.setHapticStrength(strength)
    }

    fun setUseMonospaceFont(enabled: Boolean) {
        settingsRepo.setUseMonospaceFont(enabled)
    }

    fun setCompactListMode(enabled: Boolean) {
        settingsRepo.setCompactListMode(enabled)
    }

    fun resetThemeSettings() {
        settingsRepo.resetThemeSettings()
        emitMessage("已恢复默认主题与外观设置")
    }

    fun applyCustomBgUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    app.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, boundsOpts)
                    }
                    val maxEdge = max(boundsOpts.outWidth, boundsOpts.outHeight)
                    var sample = 1
                    while (maxEdge / sample > 1440) {
                        sample *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = app.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    } ?: error("无法解码所选图片")

                    val seedArgb = extractSeedColorArgb(bmp)
                    val outFile = File(app.filesDir, "custom_bg_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(outFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    bmp.recycle()

                    app.filesDir.listFiles()?.forEach { f ->
                        if (f.name.startsWith("custom_bg_") && f.absolutePath != outFile.absolutePath) {
                            runCatching { f.delete() }
                        }
                    }
                    outFile.absolutePath to seedArgb
                }
            }
            result.fold(
                onSuccess = { (path, seed) ->
                    settingsRepo.setCustomBgImage(path, seed)
                    emitMessage("已应用自定义背景图片")
                },
                onFailure = { err ->
                    emitMessage("设置背景失败：${err.message ?: "文件不可读"}")
                }
            )
        }
    }

    fun clearCustomBg() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                app.filesDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("custom_bg_")) {
                        runCatching { f.delete() }
                    }
                }
            }
            settingsRepo.setCustomBgImage("", 0)
            emitMessage("已清除自定义背景图片")
        }
    }

    fun setRealtimeMonitorEnabled(enabled: Boolean) {
        settingsRepo.setRealtimeMonitorEnabled(enabled)
        if (enabled) {
            runCatching { RealtimeMonitorService.start(app) }
            emitMessage("已开启常驻实时监听")
        } else {
            runCatching { RealtimeMonitorService.stop(app) }
            emitMessage("已关闭常驻实时监听")
        }
    }

    fun onItemClicked(item: SearchResultItem) {
        if (item.isDir) {
            openPreview(item)
            return
        }
        if (settings.value.openContentHitsInPreview || item.snippets.isNotEmpty()) {
            openPreview(item)
        } else {
            openExternally(item)
        }
    }

    fun openPreview(item: SearchResultItem, pushCurrentToBackStack: Boolean = false) {
        if (!pushCurrentToBackStack) {
            previewHistory.clear()
        } else {
            _previewPayload.value?.item?.let { current ->
                if (current.path != item.path) {
                    previewHistory.addLast(current)
                }
            }
        }
        launchPreviewLoad(item)
    }

    fun navigatePreviewBack() {
        val prev = previewHistory.removeLastOrNull() ?: return
        launchPreviewLoad(prev)
    }

    private fun launchPreviewLoad(item: SearchResultItem) {
        previewLoadJob?.cancel()
        _previewLoadingItem.value = item
        previewLoadJob = viewModelScope.launch {
            val rules = QuerySyntaxParser.parse(_query.value).positiveHighlightRules
            val payload = container.previewLoader.load(
                item = item,
                rules = rules,
                filterConfig = settings.value.filterConfig,
                canNavigateBack = previewHistory.isNotEmpty()
            )
            _previewPayload.value = payload
            _previewLoadingItem.value = null
        }
    }

    fun closePreview() {
        previewLoadJob?.cancel()
        previewHistory.clear()
        _previewLoadingItem.value = null
        _previewPayload.value = null
    }

    fun showDetail(item: SearchResultItem) {
        _detailItem.value = item
    }

    fun closeDetail() {
        _detailItem.value = null
    }

    fun openExternally(item: SearchResultItem) {
        viewModelScope.launch {
            val res = container.fileOpener.openWithSystemApp(item)
            res.onFailure {
                emitMessage(it.message ?: "无法打开文件")
                if (item.isDir) {
                    container.fileOpener.copyToClipboard("目录路径", item.path)
                    emitMessage("已复制目录路径：${item.path}")
                }
            }
        }
    }

    fun shareItem(item: SearchResultItem) {
        viewModelScope.launch {
            container.fileOpener.shareFile(item).onFailure {
                emitMessage(it.message ?: "分享失败")
            }
        }
    }

    fun copyText(label: String, text: String) {
        container.fileOpener.copyToClipboard(label, text)
        emitMessage("已复制：$text")
    }

    fun openStorageAnalyzer(initialDirPath: String? = null) {
        val normalizedTarget = initialDirPath?.let { WizTreeEngine.normalizeDirPath(it) }?.takeIf { it.isNotEmpty() }
        if (normalizedTarget != null) {
            _analyzerState.update {
                it.copy(
                    selectedTab = AnalyzerTab.TREE,
                    currentDirPath = normalizedTarget
                )
            }
        }
        val currentGen = indexProgress.value.syncGeneration
        val existingSnap = _analyzerState.value.snapshot

        // Fast path 1: in-memory snapshot already loaded & up-to-date
        if (existingSnap != null && !existingSnap.isStale && analyzerBuiltGeneration == currentGen) {
            if (normalizedTarget != null) {
                navigateAnalyzerDir(normalizedTarget)
            }
            return
        }

        // Fast path 2: load persisted SQLite materialized cache (< 10ms, O(1) JVM heap), then silently refresh if stale
        analyzerBuildJob?.cancel()
        analyzerChildrenJob?.cancel()
        analyzerLargeFilesJob?.cancel()
        analyzerBuildJob = viewModelScope.launch {
            val currentSort = _analyzerState.value.treeSort
            val currentFoldersFirst = _analyzerState.value.foldersFirst
            val currentExt = _analyzerState.value.selectedExtFilter
            val accessMode = settings.value.accessMode

            val cachedBundle = withContext(Dispatchers.IO) {
                runCatching {
                    val roots = StorageRootsHelper.getNormalStorageRoots(app)
                    val snap = WizTreeEngine.loadFromCache(
                        db = db,
                        appInfoResolver = appInfoResolver,
                        accessMode = accessMode,
                        preferredRoots = roots,
                        currentGeneration = currentGen
                    ) ?: return@runCatching null
                    val desiredDir = normalizedTarget ?: _analyzerState.value.currentDirPath ?: snap.defaultRootPath
                    val dirNode = desiredDir?.let { WizTreeEngine.loadDirNode(db, it, appInfoResolver) }
                        ?: snap.defaultRootPath?.let { WizTreeEngine.loadDirNode(db, it, appInfoResolver) }
                    val dirChildren = if (dirNode != null) {
                        WizTreeEngine.loadChildrenForDirectory(db, dirNode, currentSort, currentFoldersFirst, appInfoResolver)
                    } else {
                        emptyList()
                    }
                    val largeFiles = if (currentExt == null) {
                        snap.topLargeFiles
                    } else {
                        WizTreeEngine.loadLargeFilesForExtension(db, currentExt, appInfoResolver)
                    }
                    val topPkgs = snap.appRankings
                        .asSequence()
                        .filter { it.isInstalled }
                        .take(48)
                        .map { it.packageName }
                        .toList()
                    val icons = appInfoResolver.loadIconsForPackages(topPkgs)
                    CachedAnalyzerPayload(snap, dirNode, dirChildren, largeFiles, icons)
                }.getOrNull()
            }

            if (cachedBundle != null && (cachedBundle.snap.totalFiles > 0L || cachedBundle.snap.totalDirs > 0L)) {
                _analyzerState.update { cur ->
                    cur.copy(
                        isLoading = false,
                        isRefreshingInBackground = cachedBundle.snap.isStale,
                        loadingMessage = if (cachedBundle.snap.isStale) "正在后台刷新最新空间变动…" else null,
                        snapshot = cachedBundle.snap,
                        currentDirPath = cachedBundle.dirNode?.path ?: cachedBundle.snap.defaultRootPath,
                        activeDirNode = cachedBundle.dirNode,
                        activeDirChildren = cachedBundle.dirChildren,
                        activeLargeFiles = cachedBundle.largeFiles,
                        appIcons = cachedBundle.icons,
                        errorMessage = null
                    )
                }
                if (!cachedBundle.snap.isStale) {
                    analyzerBuiltGeneration = currentGen
                } else {
                    refreshStorageAnalyzer(
                        preferredDirPath = cachedBundle.dirNode?.path ?: normalizedTarget,
                        isBackgroundRefresh = true
                    )
                }
            } else {
                refreshStorageAnalyzer(preferredDirPath = normalizedTarget, isBackgroundRefresh = false)
            }
        }
    }

    private data class CachedAnalyzerPayload(
        val snap: com.pocketeverything.app.analyzer.WizTreeSnapshot,
        val dirNode: com.pocketeverything.app.analyzer.DirNodeStat?,
        val dirChildren: List<com.pocketeverything.app.analyzer.TreeChildEntry>,
        val largeFiles: List<com.pocketeverything.app.analyzer.LargeFileEntry>,
        val icons: Map<String, androidx.compose.ui.graphics.ImageBitmap>
    )

    fun refreshStorageAnalyzer(
        preferredDirPath: String? = null,
        isBackgroundRefresh: Boolean = false
    ) {
        analyzerBuildJob?.cancel()
        analyzerChildrenJob?.cancel()
        analyzerLargeFilesJob?.cancel()
        analyzerBuildJob = viewModelScope.launch {
            _analyzerState.update {
                it.copy(
                    isLoading = !isBackgroundRefresh,
                    isRefreshingInBackground = isBackgroundRefresh,
                    loadingMessage = if (isBackgroundRefresh) "后台更新空间缓存…" else "正在构建空间缓存…",
                    errorMessage = null
                )
            }
            val genAtStart = indexProgress.value.syncGeneration
            val currentSort = _analyzerState.value.treeSort
            val currentFoldersFirst = _analyzerState.value.foldersFirst
            val currentExt = _analyzerState.value.selectedExtFilter
            val accessMode = settings.value.accessMode

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val roots = StorageRootsHelper.getNormalStorageRoots(app)
                    val snap = WizTreeEngine.buildFromDatabase(
                        db = db,
                        appInfoResolver = appInfoResolver,
                        preferredRoots = roots,
                        accessMode = accessMode,
                        syncGeneration = genAtStart,
                        onProgress = { msg ->
                            _analyzerState.update { cur -> cur.copy(loadingMessage = msg) }
                        }
                    )
                    val desiredDir = preferredDirPath ?: _analyzerState.value.currentDirPath
                    val dirNode = desiredDir?.let { WizTreeEngine.loadDirNode(db, it, appInfoResolver) }
                        ?: snap.defaultRootPath?.let { WizTreeEngine.loadDirNode(db, it, appInfoResolver) }
                    val dirChildren = if (dirNode != null) {
                        WizTreeEngine.loadChildrenForDirectory(db, dirNode, currentSort, currentFoldersFirst, appInfoResolver)
                    } else {
                        emptyList()
                    }
                    val largeFiles = if (currentExt == null) {
                        snap.topLargeFiles
                    } else {
                        WizTreeEngine.loadLargeFilesForExtension(db, currentExt, appInfoResolver)
                    }
                    val topPkgs = snap.appRankings
                        .asSequence()
                        .filter { it.isInstalled }
                        .take(48)
                        .map { it.packageName }
                        .toList()
                    val icons = appInfoResolver.loadIconsForPackages(topPkgs)
                    CachedAnalyzerPayload(snap, dirNode, dirChildren, largeFiles, icons)
                }
            }
            result.onSuccess { payload ->
                analyzerBuiltGeneration = genAtStart
                _analyzerState.update { cur ->
                    cur.copy(
                        isLoading = false,
                        isRefreshingInBackground = false,
                        loadingMessage = null,
                        snapshot = payload.snap,
                        currentDirPath = payload.dirNode?.path ?: payload.snap.defaultRootPath,
                        activeDirNode = payload.dirNode,
                        activeDirChildren = payload.dirChildren,
                        activeLargeFiles = payload.largeFiles,
                        appIcons = payload.icons,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _analyzerState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshingInBackground = false,
                        loadingMessage = null,
                        errorMessage = if (it.snapshot == null) (err.message ?: "空间分析失败") else null
                    )
                }
            }
        }
    }

    private fun loadActiveAnalyzerDir(
        targetPath: String,
        sort: AnalyzerTreeSort,
        foldersFirst: Boolean
    ) {
        analyzerChildrenJob?.cancel()
        val normalized = WizTreeEngine.normalizeDirPath(targetPath)
        _analyzerState.update { it.copy(currentDirPath = normalized) }
        analyzerChildrenJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val node = WizTreeEngine.loadDirNode(db, normalized, appInfoResolver) ?: return@runCatching null
                    val children = WizTreeEngine.loadChildrenForDirectory(db, node, sort, foldersFirst, appInfoResolver)
                    node to children
                }.getOrNull()
            }
            if (loaded != null &&
                _analyzerState.value.currentDirPath == normalized &&
                _analyzerState.value.treeSort == sort &&
                _analyzerState.value.foldersFirst == foldersFirst
            ) {
                _analyzerState.update {
                    it.copy(
                        activeDirNode = loaded.first,
                        activeDirChildren = loaded.second
                    )
                }
            }
        }
    }

    fun selectAnalyzerTab(tab: AnalyzerTab) {
        _analyzerState.update { it.copy(selectedTab = tab) }
    }

    fun navigateAnalyzerDir(path: String) {
        val cur = _analyzerState.value
        if (cur.snapshot == null) return
        val normalized = WizTreeEngine.normalizeDirPath(path)
        if (normalized.isEmpty()) return
        loadActiveAnalyzerDir(normalized, cur.treeSort, cur.foldersFirst)
    }

    fun navigateAnalyzerUp(): Boolean {
        val cur = _analyzerState.value
        val snap = cur.snapshot ?: return false
        val active = cur.effectiveDirNode ?: return false
        if (active.path in snap.rootPaths || active.path == "/") return false
        val parent = active.parentPath
        if (parent.isEmpty()) return false
        loadActiveAnalyzerDir(parent, cur.treeSort, cur.foldersFirst)
        return true
    }

    fun selectAnalyzerTreeSort(sort: AnalyzerTreeSort) {
        val cur = _analyzerState.value
        val activeNode = cur.effectiveDirNode
        val resorted = com.pocketeverything.app.analyzer.sortTreeChildren(
            cur.effectiveDirChildren,
            sort,
            cur.foldersFirst
        )
        _analyzerState.update { it.copy(treeSort = sort, activeDirChildren = resorted) }
        if (activeNode != null &&
            (activeNode.directFileCount > WizTreeEngine.DEFAULT_DIRECT_FILES_LIMIT ||
                activeNode.dirCount > WizTreeEngine.DEFAULT_CHILD_DIRS_LIMIT)
        ) {
            loadActiveAnalyzerDir(activeNode.path, sort, cur.foldersFirst)
        }
    }

    fun toggleAnalyzerFoldersFirst() {
        val cur = _analyzerState.value
        val next = !cur.foldersFirst
        val resorted = com.pocketeverything.app.analyzer.sortTreeChildren(
            cur.effectiveDirChildren,
            cur.treeSort,
            next
        )
        _analyzerState.update { it.copy(foldersFirst = next, activeDirChildren = resorted) }
    }

    fun selectAnalyzerExtFilter(ext: String?) {
        _analyzerState.update { it.copy(selectedExtFilter = ext) }
        analyzerLargeFilesJob?.cancel()
        val snap = _analyzerState.value.snapshot ?: return
        if (ext == null) {
            _analyzerState.update { it.copy(activeLargeFiles = snap.topLargeFiles) }
            return
        }
        analyzerLargeFilesJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { WizTreeEngine.loadLargeFilesForExtension(db, ext, appInfoResolver) }
                    .getOrDefault(emptyList())
            }
            if (_analyzerState.value.selectedExtFilter == ext) {
                _analyzerState.update { it.copy(activeLargeFiles = list) }
            }
        }
    }

    fun toggleAnalyzerUninstalledOnly(enabled: Boolean) {
        _analyzerState.update { it.copy(onlyUninstalledApps = enabled) }
    }

    fun toggleAnalyzerTreemap() {
        _analyzerState.update { it.copy(showTreemap = !it.showTreemap) }
    }

    fun openAnalyzerFile(path: String) {
        viewModelScope.launch {
            val row = withContext(Dispatchers.IO) { db.getFileMetaByPath(path) }
            if (row == null) {
                emitMessage("文件不在当前索引中，请先刷新")
                return@launch
            }
            val item = SearchResultItem(
                id = row.id,
                name = row.name,
                path = row.path,
                parentPath = row.parentPath,
                ext = row.ext,
                size = row.size,
                mtime = row.mtime,
                isDir = row.isDir,
                accessMode = row.accessMode,
                tier2Status = row.tier2Status,
                matchedNameRanges = emptyList(),
                matchedPathRanges = emptyList(),
                snippets = emptyList(),
                contentMatchCount = 0,
                score = 0.0
            )
            onItemClicked(item)
        }
    }

    fun deleteSearchResultItem(item: SearchResultItem) {
        deletePathCore(
            path = item.path,
            preferredMode = item.accessMode,
            isDir = item.isDir,
            knownBytes = if (item.isDir) 0L else item.size,
            knownExt = item.ext,
            reloadAnalyzerAfter = false
        )
    }

    fun deletePathFromAnalyzer(path: String, preferredMode: AccessMode, isDir: Boolean) {
        deletePathCore(
            path = path,
            preferredMode = preferredMode,
            isDir = isDir,
            knownBytes = 0L,
            knownExt = "",
            reloadAnalyzerAfter = true
        )
    }

    private fun deletePathCore(
        path: String,
        preferredMode: AccessMode,
        isDir: Boolean,
        knownBytes: Long,
        knownExt: String,
        reloadAnalyzerAfter: Boolean
    ) {
        val check = DeletionSafetyGuard.evaluate(path)
        if (check.risk == DeletionRisk.BLOCKED) {
            emitMessage(check.reason ?: "受保护路径，禁止删除")
            return
        }
        viewModelScope.launch {
            if (reloadAnalyzerAfter) {
                _analyzerState.update { it.copy(deletingPath = check.normalizedPath) }
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val activeMode = settings.value.accessMode
                    val modesToTry = linkedSetOf(activeMode, preferredMode, AccessMode.NORMAL)
                    var deleted = false
                    var lastErr: String? = null
                    for (mode in modesToTry) {
                        val provider = container.providerFor(mode)
                        val avail = provider.checkAvailability()
                        if (avail !is ModeAvailability.Ready) continue
                        val ok = runCatching { provider.deletePath(check.normalizedPath) }
                            .onFailure { lastErr = it.message }
                            .getOrDefault(false)
                        if (ok) {
                            deleted = true
                            break
                        }
                    }
                    if (!deleted) {
                        throw IllegalStateException(lastErr ?: "权限不足或路径被占用，删除失败")
                    }
                    val metaBefore = if (!isDir && knownBytes <= 0L) db.getFileMetaByPath(check.normalizedPath) else null
                    val fileBytes = if (knownBytes > 0L) knownBytes else (metaBefore?.size ?: 0L)
                    val fileExt = knownExt.ifEmpty { metaBefore?.ext.orEmpty() }

                    // O(depth) incremental delta on persisted analyzer cache (< 2ms) before removing files_meta rows
                    runCatching {
                        db.applyAnalyzerDeletionDelta(
                            deletedPath = check.normalizedPath,
                            isDir = isDir,
                            knownFileBytes = fileBytes,
                            knownFileExt = fileExt
                        )
                    }
                    val removedRows = db.deleteSubtree(check.normalizedPath)
                    indexManager.notifyExternalMutation()
                    removedRows
                }
            }
            if (reloadAnalyzerAfter) {
                _analyzerState.update { it.copy(deletingPath = null) }
            }
            outcome.onSuccess { removedRows ->
                val targetName = check.normalizedPath.substringAfterLast('/').ifEmpty { check.normalizedPath }
                val typeText = if (isDir) "目录" else "文件"
                emitMessage("已永久删除$typeText「$targetName」（同步清理 $removedRows 条索引）")
                if (reloadAnalyzerAfter) {
                    val fallbackDir = if (isDir && _analyzerState.value.currentDirPath == check.normalizedPath) {
                        WizTreeEngine.parentOf(check.normalizedPath)
                    } else {
                        _analyzerState.value.currentDirPath
                    }
                    reloadAnalyzerFromCacheFast(preferredDirPath = fallbackDir)
                }
            }.onFailure { err ->
                emitMessage("删除失败：${err.message ?: "请检查权限"}")
            }
        }
    }

    /**
     * Ultra-fast (< 5ms) reload from incrementally-updated SQLite analyzer cache after deletion.
     */
    private fun reloadAnalyzerFromCacheFast(preferredDirPath: String?) {
        viewModelScope.launch {
            val cur = _analyzerState.value
            val currentGen = indexProgress.value.syncGeneration
            val accessMode = settings.value.accessMode
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val roots = StorageRootsHelper.getNormalStorageRoots(app)
                    val snap = WizTreeEngine.loadFromCache(
                        db = db,
                        appInfoResolver = appInfoResolver,
                        accessMode = accessMode,
                        preferredRoots = roots,
                        currentGeneration = currentGen
                    ) ?: return@runCatching null
                    val desired = preferredDirPath ?: cur.currentDirPath ?: snap.defaultRootPath
                    val node = desired?.let { WizTreeEngine.loadDirNode(db, it, appInfoResolver) }
                        ?: snap.defaultRootPath?.let { WizTreeEngine.loadDirNode(db, it, appInfoResolver) }
                    val children = if (node != null) {
                        WizTreeEngine.loadChildrenForDirectory(db, node, cur.treeSort, cur.foldersFirst, appInfoResolver)
                    } else {
                        emptyList()
                    }
                    val largeFiles = if (cur.selectedExtFilter == null) {
                        snap.topLargeFiles
                    } else {
                        WizTreeEngine.loadLargeFilesForExtension(db, cur.selectedExtFilter, appInfoResolver)
                    }
                    CachedAnalyzerPayload(snap, node, children, largeFiles, cur.appIcons)
                }.getOrNull()
            }
            if (payload != null) {
                analyzerBuiltGeneration = currentGen
                _analyzerState.update {
                    it.copy(
                        snapshot = payload.snap,
                        currentDirPath = payload.dirNode?.path ?: payload.snap.defaultRootPath,
                        activeDirNode = payload.dirNode,
                        activeDirChildren = payload.dirChildren,
                        activeLargeFiles = payload.largeFiles
                    )
                }
            }
        }
    }

    fun excludeDirFromAnalyzer(dirPath: String) {
        val normalized = FilterConfig.normalizePaths(listOf(dirPath)).firstOrNull().orEmpty()
        if (normalized.isEmpty()) return
        val curFilter = settings.value.filterConfig
        if (normalized in curFilter.customExcludedPaths) {
            emitMessage("该目录已在排除列表中")
            return
        }
        val updated = curFilter.copy(
            customExcludedPaths = curFilter.customExcludedPaths + normalized
        )
        updateFilterConfig(updated)
        viewModelScope.launch {
            delay(350L)
            refreshStorageAnalyzer()
        }
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        }
        container.shizukuProvider.unbind()
        container.rootProvider.unbind()
        super.onCleared()
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 2001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}

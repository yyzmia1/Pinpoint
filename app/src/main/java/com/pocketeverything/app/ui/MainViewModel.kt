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
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.RealtimeMonitorService
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.CrowdedDirStat
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
    val targetMode: AccessMode,
    val includeDataForRoot: Boolean,
    val syncRulesToPreset: Boolean,
    val hasCustomRules: Boolean
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

    private val _crowdedDirs = MutableStateFlow<List<CrowdedDirStat>>(emptyList())
    val crowdedDirs: StateFlow<List<CrowdedDirStat>> = _crowdedDirs.asStateFlow()

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
            indexManager.progress
                .map { it.stats.totalEntries }
                .distinctUntilChanged()
                .debounce(400)
                .collectLatest {
                    refreshCrowdedDirs()
                }
        }

        viewModelScope.launch {
            delay(1500L)
            checkForUpdates(silent = true)
        }

        viewModelScope.launch {
            val indexMutationTick = indexManager.progress
                .map { it.stats.totalEntries to it.stats.indexedDocs }
                .distinctUntilChanged()
                .debounce(250)

            combine(
                _query.debounce(65),
                _quickScope,
                _sortOrder,
                settingsRepo.settings.map { it.showRecentOnEmptyQuery }.distinctUntilChanged(),
                indexMutationTick
            ) { q, scope, sort, showRecent, _ ->
                SearchTriggerParams(q, scope, sort, showRecent)
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
        val showRecentOnEmpty: Boolean
    )

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
                    val hasCustom = curSettings.filterConfig.differsFromPreset(mode)
                    _pendingModeSwitch.value = PendingModeSwitch(
                        targetMode = mode,
                        includeDataForRoot = if (mode == AccessMode.ROOT) curSettings.filterConfig.rootIncludeData else false,
                        syncRulesToPreset = false,
                        hasCustomRules = hasCustom
                    )
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

    fun updatePendingModeSwitch(includeDataForRoot: Boolean? = null, syncRulesToPreset: Boolean? = null) {
        val current = _pendingModeSwitch.value ?: return
        _pendingModeSwitch.value = current.copy(
            includeDataForRoot = includeDataForRoot ?: current.includeDataForRoot,
            syncRulesToPreset = syncRulesToPreset ?: current.syncRulesToPreset
        )
    }

    fun dismissPendingModeSwitch() {
        _pendingModeSwitch.value = null
    }

    fun confirmPendingModeSwitch() {
        val pending = _pendingModeSwitch.value ?: return
        _pendingModeSwitch.value = null
        val curCfg = settings.value.filterConfig
        val nextCfg = if (pending.syncRulesToPreset) {
            curCfg.recommendedForMode(pending.targetMode, includeData = pending.includeDataForRoot)
        } else {
            curCfg.copy(rootIncludeData = if (pending.targetMode == AccessMode.ROOT) pending.includeDataForRoot else false)
        }
        settingsRepo.updateFilterConfig(nextCfg)
        settingsRepo.updateAccessMode(pending.targetMode)
        indexManager.startFullScan(clearExisting = false)
        emitMessage("已切换至 ${pending.targetMode.label}，正在后台全盘重建索引", jumpToSearch = true)
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

    fun refreshCrowdedDirs() {
        viewModelScope.launch(Dispatchers.IO) {
            _crowdedDirs.value = runCatching { db.getTopCrowdedDirectories(5) }.getOrDefault(emptyList())
        }
    }

    fun refreshDirectory(dirPath: String) {
        indexManager.startDirectoryRefresh(dirPath)
        emitMessage("正在刷新目录：$dirPath")
    }

    fun updateFilterConfig(config: FilterConfig) {
        val old = settings.value.filterConfig
        settingsRepo.updateFilterConfig(config)
        viewModelScope.launch(Dispatchers.IO) {
            val pruned = indexManager.pruneByCurrentFilter()
            _crowdedDirs.value = runCatching { db.getTopCrowdedDirectories(5) }.getOrDefault(emptyList())
            val msg = when {
                !old.rootIncludeData && config.rootIncludeData && settings.value.accessMode == AccessMode.ROOT ->
                    "已允许扫描 /data（体积与耗时将明显增加），请点击「重建全库」纳入索引"
                pruned > 0 ->
                    "规则已保存并清理 $pruned 条已过滤项目"
                else ->
                    "过滤规则已保存，后续同步生效（扩大范围建议重建一次）"
            }
            emitMessage(msg)
        }
    }

    fun excludeCrowdedPath(path: String) {
        val cfg = settings.value.filterConfig
        val clean = path.trimEnd('/')
        if (clean.isEmpty() || clean in cfg.customExcludedPaths) return
        updateFilterConfig(cfg.copy(customExcludedPaths = cfg.customExcludedPaths + clean))
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

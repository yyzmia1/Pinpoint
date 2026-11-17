package com.pocketeverything.app.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketeverything.app.BuildConfig
import com.pocketeverything.app.PocketEverythingApp
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.data.ChangelogState
import com.pocketeverything.app.data.ReleaseUpdateInfo
import com.pocketeverything.app.data.ThemeMode
import com.pocketeverything.app.data.UpdateCheckState
import com.pocketeverything.app.data.UpdateChecker
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.fs.ModeAvailability
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

data class ShizukuLiveStatus(
    val isInstalled: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isPermissionGranted: Boolean = false
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
    var activeDownloadId: Long = -1L
        private set

    private val _previewPayload = MutableStateFlow<PreviewPayload?>(null)
    val previewPayload: StateFlow<PreviewPayload?> = _previewPayload.asStateFlow()

    private val _previewLoadingItem = MutableStateFlow<SearchResultItem?>(null)
    val previewLoadingItem: StateFlow<SearchResultItem?> = _previewLoadingItem.asStateFlow()

    private val previewHistory = ArrayDeque<SearchResultItem>()
    private var previewLoadJob: Job? = null

    private val _detailItem = MutableStateFlow<SearchResultItem?>(null)
    val detailItem: StateFlow<SearchResultItem?> = _detailItem.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        viewModelScope.launch {
            refreshModeStatuses()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                if (settings.value.accessMode == AccessMode.SHIZUKU) {
                    indexManager.startFullScan(clearExisting = false)
                }
                _messages.tryEmit("Shizuku 授权成功")
            } else {
                _messages.tryEmit("未授予 Shizuku 权限")
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

    fun refreshModeStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            val map = linkedMapOf<AccessMode, ModeAvailability>()
            for (mode in AccessMode.entries) {
                map[mode] = container.providerFor(mode).checkAvailability()
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

    fun switchMode(mode: AccessMode) {
        viewModelScope.launch {
            val availability = withContext(Dispatchers.IO) {
                container.providerFor(mode).checkAvailability()
            }
            refreshModeStatuses()
            when (availability) {
                is ModeAvailability.Ready -> {
                    settingsRepo.updateAccessMode(mode)
                    _messages.tryEmit("已切换至 ${mode.label}")
                    indexManager.startFullScan(clearExisting = false)
                }
                is ModeAvailability.NeedsPermission -> {
                    settingsRepo.updateAccessMode(mode)
                    if (mode == AccessMode.SHIZUKU) {
                        requestShizukuPermission()
                    } else {
                        _messages.tryEmit(availability.reason)
                    }
                }
                is ModeAvailability.Unavailable -> {
                    _messages.tryEmit(availability.reason)
                }
            }
        }
    }

    fun requestShizukuPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                _messages.tryEmit("Shizuku 服务未运行，请先启动 Shizuku")
                return
            }
            if (Shizuku.isPreV11()) {
                _messages.tryEmit("Shizuku 版本过低（需 v11+），请更新后再试")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshModeStatuses()
                indexManager.startFullScan(clearExisting = false)
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                _messages.tryEmit("请在 Shizuku 应用的授权管理中手动允许 Pinpoint")
                openShizukuOrDownload()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        }.onFailure {
            _messages.tryEmit("调用 Shizuku 失败：${it.message}")
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
            _messages.tryEmit("无法打开链接：$url")
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (!silent && _updateState.value is UpdateCheckState.Checking) return
        val now = System.currentTimeMillis()
        if (silent && !UpdateChecker.shouldRunSilentCheck(settings.value.lastUpdateCheckAtMillis, now)) {
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
                    currentVersion = BuildConfig.VERSION_NAME
                )
            }
            val reachedServer = result !is UpdateCheckState.Error || result.httpStatusCode != null
            if (reachedServer) {
                settingsRepo.markUpdateChecked()
            }
            if (silent) {
                if (result is UpdateCheckState.Available &&
                    UpdateChecker.shouldPromptSilentUpdate(
                        latestTag = result.info.tagName,
                        currentVersion = BuildConfig.VERSION_NAME,
                        ignoredTag = settings.value.ignoredUpdateTag
                    )
                ) {
                    _updateState.value = result
                    _updateDialogInfo.value = result.info
                }
            } else {
                _updateState.value = result
                if (result is UpdateCheckState.Available) {
                    _updateDialogInfo.value = result.info
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
        _messages.tryEmit("已忽略 $tagName 的自动弹窗提醒")
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
            _messages.tryEmit("已调用系统下载器下载，可在通知栏查看进度")
        }.onFailure {
            _messages.tryEmit("系统下载器不可用，已转为浏览器打开")
            openUrl(info.releaseUrl)
        }
    }

    fun notifyMessage(message: String) {
        _messages.tryEmit(message)
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
        indexManager.startQuickHotDirsRefresh()
        _messages.tryEmit("正在增量校验目录")
    }

    fun triggerFullRebuild(clearFirst: Boolean = false) {
        indexManager.startFullScan(clearExisting = clearFirst)
    }

    fun refreshDirectory(dirPath: String) {
        indexManager.startDirectoryRefresh(dirPath)
        _messages.tryEmit("正在刷新目录：$dirPath")
    }

    fun updateFilterConfig(config: FilterConfig) {
        settingsRepo.updateFilterConfig(config)
        _messages.tryEmit("过滤规则已保存，重建索引后全面生效")
    }

    fun setOpenContentHitsInPreview(enabled: Boolean) {
        settingsRepo.setOpenContentHitsInPreview(enabled)
    }

    fun setShowRecentOnEmptyQuery(enabled: Boolean) {
        settingsRepo.setShowRecentOnEmptyQuery(enabled)
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

    fun setRealtimeMonitorEnabled(enabled: Boolean) {
        settingsRepo.setRealtimeMonitorEnabled(enabled)
        if (enabled) {
            runCatching { RealtimeMonitorService.start(app) }
            _messages.tryEmit("已开启常驻实时监听")
        } else {
            runCatching { RealtimeMonitorService.stop(app) }
            _messages.tryEmit("已关闭常驻实时监听")
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
                _messages.tryEmit(it.message ?: "无法打开文件")
                if (item.isDir) {
                    container.fileOpener.copyToClipboard("目录路径", item.path)
                    _messages.tryEmit("已复制目录路径：${item.path}")
                }
            }
        }
    }

    fun shareItem(item: SearchResultItem) {
        viewModelScope.launch {
            container.fileOpener.shareFile(item).onFailure {
                _messages.tryEmit(it.message ?: "分享失败")
            }
        }
    }

    fun copyText(label: String, text: String) {
        container.fileOpener.copyToClipboard(label, text)
        _messages.tryEmit("已复制：$text")
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

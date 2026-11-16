package com.pocketeverything.app.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketeverything.app.PocketEverythingApp
import com.pocketeverything.app.data.AppSettings
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.indexer.IndexProgressState
import com.pocketeverything.app.indexer.RealtimeMonitorService
import com.pocketeverything.app.parser.ExtractionResult
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.QuerySyntaxParser
import com.pocketeverything.app.search.QuickScope
import com.pocketeverything.app.search.SearchResponse
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.search.SnippetHighlighter
import com.pocketeverything.app.search.SortOrder
import com.pocketeverything.app.search.Tier2Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class PreviewPayload(
    val item: SearchResultItem,
    val fullText: String,
    val matchRanges: List<IntRange>,
    val statusNote: String? = null
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

    private val _previewPayload = MutableStateFlow<PreviewPayload?>(null)
    val previewPayload: StateFlow<PreviewPayload?> = _previewPayload.asStateFlow()

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

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        }

        refreshModeStatuses()
        indexManager.triggerStartupSync()

        viewModelScope.launch {
            combine(
                _query.debounce(65),
                _quickScope,
                _sortOrder,
                indexManager.progress
            ) { q, scope, sort, _ ->
                Triple(q, scope, sort)
            }.collectLatest { (q, scope, sort) ->
                val resp = withContext(Dispatchers.IO) {
                    db.search(q, quickScope = scope, sortOrder = sort, limit = 150)
                }
                _searchResponse.value = resp
            }
        }
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

    fun refreshModeStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            val map = linkedMapOf<AccessMode, ModeAvailability>()
            for (mode in AccessMode.entries) {
                map[mode] = container.providerFor(mode).checkAvailability()
            }
            _modeStatuses.value = map
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
                    _messages.tryEmit("已切换至 ${mode.label}，开始同步索引")
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
                _messages.tryEmit("Shizuku 未启动，请先打开 Shizuku 应用")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshModeStatuses()
                indexManager.startFullScan(clearExisting = false)
            } else {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        }.onFailure {
            _messages.tryEmit("调用 Shizuku 失败：${it.message}")
        }
    }

    fun triggerQuickSync() {
        indexManager.startQuickHotDirsRefresh()
        _messages.tryEmit("正在快速增量校对目录…")
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

    fun setRealtimeMonitorEnabled(enabled: Boolean) {
        settingsRepo.setRealtimeMonitorEnabled(enabled)
        if (enabled) {
            runCatching { RealtimeMonitorService.start(app) }
            _messages.tryEmit("已开启常驻实时文件监控")
        } else {
            runCatching { RealtimeMonitorService.stop(app) }
            _messages.tryEmit("已关闭常驻实时文件监控")
        }
    }

    fun onItemClicked(item: SearchResultItem) {
        if (item.isDir) {
            _detailItem.value = item
            return
        }
        val preferPreview = settings.value.openContentHitsInPreview
        val isDocExt = item.ext in setOf("txt", "md", "markdown", "docx", "pdf", "doc")
        if (preferPreview && (item.snippets.isNotEmpty() || (isDocExt && item.tier2Status == Tier2Status.INDEXED))) {
            openPreview(item)
        } else {
            openExternally(item)
        }
    }

    fun openPreview(item: SearchResultItem) {
        viewModelScope.launch {
            val terms = QuerySyntaxParser.parse(_query.value).positiveHighlightTerms
            if (item.ext == "doc") {
                _previewPayload.value = PreviewPayload(
                    item = item,
                    fullText = "该文件为旧版二进制 Word (.doc) 格式。\n\n为保证手机端极致速度与极小体积，本应用仅对 .docx / .pdf / .txt / .md 提供正文解析。\n请转换为 .docx 或 PDF，或点击下方“用其他应用打开”。",
                    matchRanges = emptyList(),
                    statusNote = "旧版 .doc 暂不支持正文索引"
                )
                return@launch
            }

            val cachedText = withContext(Dispatchers.IO) { db.getRawDocumentText(item.id) }
            if (cachedText != null) {
                val ranges = SnippetHighlighter.findMatchRanges(cachedText, terms, maxMatches = 500)
                _previewPayload.value = PreviewPayload(item, cachedText, ranges)
                return@launch
            }

            // Fallback: live extraction if file wasn't extracted yet
            val liveResult = withContext(Dispatchers.IO) {
                val provider = container.providerFor(item.accessMode)
                val maxBytes = settings.value.filterConfig.maxBytesForExtensionOrDefault(item.ext)
                runCatching {
                    provider.openFileStream(item.path, maxBytes) { input ->
                        container.extractorDispatcher.extractByExtension(item.ext, input, maxBytes)
                    }
                }.getOrElse {
                    ExtractionResult.Failed(Tier2Status.ERROR, it.message)
                }
            }

            when (liveResult) {
                is ExtractionResult.Success -> {
                    val ranges = SnippetHighlighter.findMatchRanges(liveResult.text, terms, maxMatches = 500)
                    _previewPayload.value = PreviewPayload(item, liveResult.text, ranges)
                }
                is ExtractionResult.Failed -> {
                    _previewPayload.value = PreviewPayload(
                        item = item,
                        fullText = "无法内置预览文本（原因：${liveResult.status.label} ${liveResult.message.orEmpty()}）。\n\n可点击下方“用其他应用打开”直接调用系统阅读器查看。",
                        matchRanges = emptyList(),
                        statusNote = liveResult.status.label
                    )
                }
            }
        }
    }

    fun closePreview() {
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

    private fun FilterConfig.maxBytesForExtensionOrDefault(ext: String): Long {
        return when (ext.lowercase()) {
            "txt", "md", "markdown" -> maxTxtMdBytes
            "docx" -> maxDocxBytes
            "pdf" -> maxPdfBytes
            else -> 10L * 1024L * 1024L
        }
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        }
        container.shizukuProvider.unbind()
        container.rootProvider.unbind()
        super.onCleared()
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 2001
    }
}

package com.pocketeverything.app.filter

/**
 * Root-mode `/data` scan presets fused directly into `customExcludedPaths` prefixes.
 */
enum class RootDataScope(val label: String) {
    EXCLUDE_ALL("跳过 /data"),
    EXCLUDE_PRIVATE("排除应用私有区"),
    INCLUDE_ALL("全含 /data")
}

/**
 * Ordering dimensions for Tier-2 background document content extraction.
 */
enum class Tier2PriorityRule(val code: String, val label: String) {
    USER_DIRS_FIRST("user_dirs", "常用个人目录优先"),
    MTIME_DESC("mtime_desc", "最近修改时间优先"),
    FAST_FORMAT_FIRST("fast_format", "文本格式优先 (TXT > DOCX > PDF)"),
    SMALL_SIZE_FIRST("small_first", "小文件快速优先");

    companion object {
        fun fromCode(code: String): Tier2PriorityRule? = entries.firstOrNull { it.code == code }
    }
}

data class Tier2PriorityEntry(
    val rule: Tier2PriorityRule,
    val enabled: Boolean = true
)

data class FilterConfig(
    val skipNoiseCaches: Boolean = true,
    val deduplicateMounts: Boolean = true,
    val customExcludedPaths: List<String> = DEFAULT_EXCLUDED_PATHS,
    val tier2AllowedPrefixes: List<String> = DEFAULT_TIER2_ALLOWED_PREFIXES,
    val tier2BlockedPrefixes: List<String> = DEFAULT_TIER2_BLOCKED_PREFIXES,
    val tier2PriorityOrder: List<Tier2PriorityEntry> = DEFAULT_TIER2_PRIORITY_ORDER,
    val maxTxtMdBytes: Long = 10L * 1024L * 1024L,
    val maxDocxBytes: Long = 30L * 1024L * 1024L,
    val maxPdfBytes: Long = 50L * 1024L * 1024L
) {
    fun rootDataScope(): RootDataScope? = detectRootDataScope(customExcludedPaths)

    fun withRootDataScope(scope: RootDataScope): FilterConfig =
        copy(customExcludedPaths = withRootDataScopeInPaths(customExcludedPaths, scope))

    companion object {
        const val UNLIMITED_BYTES: Long = 0L
        const val MAX_FINITE_MB: Int = 1024

        val ROOT_DATA_PRIVATE_EXCLUDES = listOf(
            "/data/data",
            "/data/user",
            "/data/user_de",
            "/data/dalvik-cache",
            "/data/cache"
        )

        val DEFAULT_EXCLUDED_PATHS = listOf("/data")

        val VIRTUAL_FS_EXCLUSIONS = listOf(
            "/proc",
            "/sys",
            "/dev",
            "/acct",
            "/apex",
            "/debug_ramdisk",
            "/linkerconfig",
            "/mnt/runtime",
            "/mnt/pass_through",
            "/mnt/installer",
            "/mnt/androidwritable",
            "/mnt/user",
            "/storage/self",
            "/data_mirror"
        )

        val BIND_MOUNT_DUPLICATE_PREFIXES = listOf(
            "/data/media"
        )

        val DEFAULT_NOISE_DIR_NAMES = setOf(
            ".thumbnails",
            ".thumb",
            "thumbnails",
            "thumb",
            "thumbs",
            ".thumbs",
            ".previews",
            "previews",
            "imgcache",
            "imagecache",
            "image_cache",
            "piccache",
            "glide_cache",
            "coil_cache",
            "fresco_cache",
            "cache",
            "code_cache",
            "gpucache",
            "diskcache",
            "httpcache",
            "mediacache",
            "videocache",
            "audiocache",
            "shader_cache",
            "webview",
            "app_webview",
            "x5webview",
            "app_tbs",
            "temp",
            ".temp",
            ".tmp",
            "tmp",
            "tombstones",
            "anr",
            ".git",
            "node_modules",
            "__pycache__",
            "lost+found",
            ".trash",
            ".trashbin",
            ".recycle",
            ".mediatrash",
            ".filermanagerrecycler"
        )

        val DEFAULT_TIER2_ALLOWED_PREFIXES = listOf(
            "/storage/emulated",
            "/storage/"
        )

        val DEFAULT_TIER2_BLOCKED_PREFIXES = listOf(
            "/system",
            "/vendor",
            "/product",
            "/system_ext",
            "/odm",
            "/apex",
            "/data/data",
            "/data/user",
            "/data/user_de",
            "/data/app",
            "/data/system",
            "/cache"
        )

        val DEFAULT_TIER2_PRIORITY_ORDER = listOf(
            Tier2PriorityEntry(Tier2PriorityRule.USER_DIRS_FIRST, enabled = true),
            Tier2PriorityEntry(Tier2PriorityRule.MTIME_DESC, enabled = true),
            Tier2PriorityEntry(Tier2PriorityRule.FAST_FORMAT_FIRST, enabled = true),
            Tier2PriorityEntry(Tier2PriorityRule.SMALL_SIZE_FIRST, enabled = true)
        )

        val SUPPORTED_DOC_EXTENSIONS = setOf("txt", "md", "markdown", "docx", "pdf")

        fun normalizePaths(raw: List<String>): List<String> =
            raw.map { p ->
                val t = p.trim()
                if (t.length > 1) t.trimEnd('/') else t
            }.filter { it.isNotEmpty() }.distinct()

        fun normalizeTier2AllowedPrefixes(raw: List<String>): List<String> =
            raw.map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it == "/" || it == "*") "/" else it.trimEnd('/') }
                .distinct()

        fun normalizePriorityOrder(raw: List<Tier2PriorityEntry>): List<Tier2PriorityEntry> {
            val seen = HashSet<Tier2PriorityRule>(Tier2PriorityRule.entries.size)
            val result = ArrayList<Tier2PriorityEntry>(Tier2PriorityRule.entries.size)
            for (item in raw) {
                if (seen.add(item.rule)) result.add(item)
            }
            for (rule in Tier2PriorityRule.entries) {
                if (seen.add(rule)) result.add(Tier2PriorityEntry(rule, enabled = true))
            }
            return result
        }

        fun detectRootDataScope(paths: List<String>): RootDataScope? {
            val clean = normalizePaths(paths).toSet()
            if ("/data" in clean || "/" in clean) return RootDataScope.EXCLUDE_ALL
            if (ROOT_DATA_PRIVATE_EXCLUDES.all { it in clean }) return RootDataScope.EXCLUDE_PRIVATE
            val anyPresetDataExclude = (ROOT_DATA_PRIVATE_EXCLUDES + "/data").any { it in clean }
            return if (!anyPresetDataExclude) RootDataScope.INCLUDE_ALL else null
        }

        fun withRootDataScopeInPaths(paths: List<String>, scope: RootDataScope): List<String> {
            val stripSet = (ROOT_DATA_PRIVATE_EXCLUDES + "/data").toSet()
            val kept = normalizePaths(paths).filterNot { it in stripSet }
            val added = when (scope) {
                RootDataScope.EXCLUDE_ALL -> listOf("/data")
                RootDataScope.EXCLUDE_PRIVATE -> ROOT_DATA_PRIVATE_EXCLUDES
                RootDataScope.INCLUDE_ALL -> emptyList()
            }
            return normalizePaths(kept + added)
        }
    }
}

package com.pocketeverything.app.filter

import com.pocketeverything.app.search.AccessMode

data class FilterConfig(
    val skipNoiseCaches: Boolean = true,
    val deduplicateMounts: Boolean = true,
    val rootIncludeData: Boolean = false,
    val customExcludedPaths: List<String> = emptyList(),
    val customExcludedDirNames: List<String> = emptyList(),
    val tier2AllowedPrefixes: List<String> = DEFAULT_TIER2_ALLOWED_PREFIXES,
    val tier2BlockedPrefixes: List<String> = DEFAULT_TIER2_BLOCKED_PREFIXES,
    val maxTxtMdBytes: Long = 10L * 1024L * 1024L,
    val maxDocxBytes: Long = 30L * 1024L * 1024L,
    val maxPdfBytes: Long = 50L * 1024L * 1024L
) {
    /** Returns the recommended rule baseline for [mode] while keeping user size caps. */
    fun recommendedForMode(
        mode: AccessMode,
        includeData: Boolean = false
    ): FilterConfig = copy(
        skipNoiseCaches = true,
        deduplicateMounts = true,
        rootIncludeData = if (mode == AccessMode.ROOT) includeData else false,
        customExcludedPaths = emptyList(),
        customExcludedDirNames = emptyList(),
        tier2AllowedPrefixes = DEFAULT_TIER2_ALLOWED_PREFIXES,
        tier2BlockedPrefixes = DEFAULT_TIER2_BLOCKED_PREFIXES
    )

    fun differsFromPreset(mode: AccessMode): Boolean {
        val target = recommendedForMode(mode, includeData = if (mode == AccessMode.ROOT) rootIncludeData else false)
        return skipNoiseCaches != target.skipNoiseCaches ||
            deduplicateMounts != target.deduplicateMounts ||
            customExcludedPaths.isNotEmpty() ||
            customExcludedDirNames.isNotEmpty() ||
            normalizeTier2AllowedPrefixes(tier2AllowedPrefixes) !=
                normalizeTier2AllowedPrefixes(target.tier2AllowedPrefixes)
    }

    companion object {
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

        val SUGGESTED_CUSTOM_DIR_NAMES = listOf(
            "preview", "logs", "log", "backup", "avatar", "splash", "ad"
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

        val SUPPORTED_DOC_EXTENSIONS = setOf("txt", "md", "markdown", "docx", "pdf")

        fun normalizeTier2AllowedPrefixes(raw: List<String>): List<String> =
            raw.map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it == "/" || it == "*") "/" else it.trimEnd('/') }
    }
}

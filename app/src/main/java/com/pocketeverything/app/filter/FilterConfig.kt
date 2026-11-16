package com.pocketeverything.app.filter

data class FilterConfig(
    val skipNoiseCaches: Boolean = true,
    val deduplicateMounts: Boolean = true,
    val customExcludedPaths: List<String> = emptyList(),
    val tier2AllowedPrefixes: List<String> = DEFAULT_TIER2_ALLOWED_PREFIXES,
    val tier2BlockedPrefixes: List<String> = DEFAULT_TIER2_BLOCKED_PREFIXES,
    val maxTxtMdBytes: Long = 10L * 1024L * 1024L,
    val maxDocxBytes: Long = 30L * 1024L * 1024L,
    val maxPdfBytes: Long = 50L * 1024L * 1024L
) {
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
            "cache",
            "code_cache",
            "gpucache",
            ".git",
            "lost+found",
            ".trash",
            ".recycle",
            "webview"
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
    }
}

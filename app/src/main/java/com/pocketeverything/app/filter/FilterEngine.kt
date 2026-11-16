package com.pocketeverything.app.filter

import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.Tier2Status

/**
 * Pure filtering logic for Tier 1 path traversal and Tier 2 document content extraction eligibility.
 */
class FilterEngine(
    val config: FilterConfig = FilterConfig()
) {
    private val normalizedCustomExcludes = config.customExcludedPaths
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }

    private val normalizedTier2Allowed = config.tier2AllowedPrefixes
        .map { it.trim().let { p -> if (p == "/") "/" else p.trimEnd('/') } }
        .filter { it.isNotEmpty() }

    private val normalizedTier2Blocked = config.tier2BlockedPrefixes
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }

    fun getRootExcludedPrefixes(mode: AccessMode): Array<String> {
        val list = ArrayList<String>(24)
        if (mode == AccessMode.ROOT) {
            list.addAll(FilterConfig.VIRTUAL_FS_EXCLUSIONS)
            if (config.deduplicateMounts) {
                list.addAll(FilterConfig.BIND_MOUNT_DUPLICATE_PREFIXES)
            }
        }
        list.addAll(normalizedCustomExcludes)
        return list.distinct().toTypedArray()
    }

    fun getExcludedDirNames(): Array<String> {
        return if (config.skipNoiseCaches) {
            FilterConfig.DEFAULT_NOISE_DIR_NAMES.toTypedArray()
        } else {
            emptyArray()
        }
    }

    fun shouldSkipTier1Path(path: String, name: String, isDir: Boolean, mode: AccessMode): Boolean {
        val clean = if (path.length > 1) path.trimEnd('/') else path
        if (mode == AccessMode.ROOT) {
            for (prefix in FilterConfig.VIRTUAL_FS_EXCLUSIONS) {
                if (clean == prefix || clean.startsWith("$prefix/")) return true
            }
            if (config.deduplicateMounts) {
                for (dup in FilterConfig.BIND_MOUNT_DUPLICATE_PREFIXES) {
                    if (clean == dup || clean.startsWith("$dup/")) return true
                }
            }
        }

        for (custom in normalizedCustomExcludes) {
            if (clean == custom || clean.startsWith("$custom/")) return true
        }

        if (config.skipNoiseCaches && isDir) {
            if (name.lowercase() in FilterConfig.DEFAULT_NOISE_DIR_NAMES) {
                return true
            }
        }

        return false
    }

    fun evaluateTier2Status(path: String, ext: String, size: Long, isDir: Boolean): Tier2Status {
        if (isDir) return Tier2Status.NOT_APPLICABLE
        val lowerExt = ext.lowercase()
        if (lowerExt == "doc") return Tier2Status.UNSUPPORTED_LEGACY_DOC
        if (lowerExt !in FilterConfig.SUPPORTED_DOC_EXTENSIONS) return Tier2Status.NOT_APPLICABLE

        val clean = path.trimEnd('/')
        for (custom in normalizedCustomExcludes) {
            if (clean == custom || clean.startsWith("$custom/")) return Tier2Status.SKIPPED_FILTER
        }
        for (blocked in normalizedTier2Blocked) {
            if (clean == blocked || clean.startsWith("$blocked/")) return Tier2Status.SKIPPED_FILTER
        }
        if (normalizedTier2Allowed.isNotEmpty()) {
            val allowed = normalizedTier2Allowed.any { prefix ->
                prefix == "/" || clean == prefix || clean.startsWith("$prefix/") ||
                    (prefix.endsWith("/") && clean.startsWith(prefix))
            }
            if (!allowed) return Tier2Status.SKIPPED_FILTER
        }

        if (size <= 0L) return Tier2Status.SCANNED_OR_EMPTY

        val maxAllowed = maxBytesForExtension(lowerExt)
        if (size > maxAllowed) return Tier2Status.SKIPPED_SIZE

        return Tier2Status.PENDING
    }

    fun maxBytesForExtension(ext: String): Long {
        return when (ext.lowercase()) {
            "txt", "md", "markdown" -> config.maxTxtMdBytes
            "docx" -> config.maxDocxBytes
            "pdf" -> config.maxPdfBytes
            else -> 0L
        }
    }
}

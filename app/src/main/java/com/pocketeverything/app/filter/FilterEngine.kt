package com.pocketeverything.app.filter

import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.Tier2Status

/**
 * Pure filtering logic for Tier 1 path traversal and Tier 2 document content extraction eligibility.
 */
class FilterEngine(
    val config: FilterConfig = FilterConfig()
) {
    private val normalizedCustomExcludes = FilterConfig.normalizePaths(config.customExcludedPaths)
    private val normalizedIncludes = FilterConfig.normalizePaths(config.customIncludedPaths)

    fun includedPrefixes(): Array<String> = normalizedIncludes.toTypedArray()

    fun isIncluded(path: String, isDir: Boolean): Boolean = normalizedIncludes.isEmpty() ||
        normalizedIncludes.any { matchesPathPrefix(path, it) || (isDir && matchesPathPrefix(it, path)) }

    private val excludedDirNameSet: Set<String> = if (config.skipNoiseCaches) {
        FilterConfig.DEFAULT_NOISE_DIR_NAMES
    } else {
        emptySet()
    }

    private val normalizedTier2Allowed = FilterConfig.normalizeTier2AllowedPrefixes(config.tier2AllowedPrefixes)

    private val normalizedTier2Blocked = FilterConfig.normalizePaths(config.tier2BlockedPrefixes)

    fun isDataPotentiallyIndexed(mode: AccessMode): Boolean =
        mode == AccessMode.ROOT && !shouldSkipTier1Path("/data", "data", isDir = true, mode = mode)

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

    fun getExcludedDirNames(): Array<String> = excludedDirNameSet.toTypedArray()

    fun shouldSkipTier1Path(path: String, name: String, isDir: Boolean, mode: AccessMode): Boolean {
        val clean = if (path.length > 1) path.trimEnd('/') else path
        if (!isIncluded(clean, isDir)) return true
        if (mode == AccessMode.ROOT) {
            for (prefix in FilterConfig.VIRTUAL_FS_EXCLUSIONS) {
                if (matchesPathPrefix(clean, prefix)) return true
            }
            if (config.deduplicateMounts) {
                for (dup in FilterConfig.BIND_MOUNT_DUPLICATE_PREFIXES) {
                    if (matchesPathPrefix(clean, dup)) return true
                }
            }
        }

        for (custom in normalizedCustomExcludes) {
            if (matchesPathPrefix(clean, custom)) return true
        }

        if (excludedDirNameSet.isNotEmpty()) {
            val endBound = if (isDir) clean.length else clean.lastIndexOf('/').coerceAtLeast(0)
            var pos = 0
            while (pos < endBound) {
                if (clean[pos] == '/') {
                    pos++
                    continue
                }
                var nextSlash = clean.indexOf('/', pos)
                if (nextSlash < 0 || nextSlash > endBound) nextSlash = endBound
                if (nextSlash > pos) {
                    val seg = clean.substring(pos, nextSlash).lowercase()
                    if (seg in excludedDirNameSet &&
                        !(nextSlash == DATA_LOCAL_TMP.length && clean.regionMatches(0, DATA_LOCAL_TMP, 0, DATA_LOCAL_TMP.length))
                    ) {
                        return true
                    }
                }
                pos = nextSlash + 1
            }
        }

        return false
    }

    fun evaluateTier2Status(path: String, ext: String, size: Long, isDir: Boolean): Tier2Status {
        if (isDir) return Tier2Status.NOT_APPLICABLE
        val lowerExt = ext.lowercase()
        if (lowerExt == "doc") return Tier2Status.UNSUPPORTED_LEGACY_DOC
        if (lowerExt !in FilterConfig.SUPPORTED_DOC_EXTENSIONS) return Tier2Status.NOT_APPLICABLE

        val clean = if (path.length > 1) path.trimEnd('/') else path
        if (!isIncluded(clean, false)) return Tier2Status.SKIPPED_FILTER
        for (custom in normalizedCustomExcludes) {
            if (matchesPathPrefix(clean, custom)) return Tier2Status.SKIPPED_FILTER
        }
        for (blocked in normalizedTier2Blocked) {
            if (matchesPathPrefix(clean, blocked)) return Tier2Status.SKIPPED_FILTER
        }
        // Empty tier2AllowedPrefixes means unrestricted (only blocked/custom exclusions apply).
        if (normalizedTier2Allowed.isNotEmpty()) {
            val allowed = normalizedTier2Allowed.any { prefix -> matchesPathPrefix(clean, prefix) }
            if (!allowed) return Tier2Status.SKIPPED_FILTER
        }

        if (size <= 0L) return Tier2Status.SCANNED_OR_EMPTY

        val maxAllowed = maxBytesForExtension(lowerExt)
        if (maxAllowed > 0L && size > maxAllowed) return Tier2Status.SKIPPED_SIZE

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

    private fun matchesPathPrefix(clean: String, prefix: String): Boolean {
        if (prefix == "/") return clean.startsWith("/")
        return clean.startsWith(prefix) && (clean.length == prefix.length || clean[prefix.length] == '/')
    }

    private companion object {
        const val DATA_LOCAL_TMP = "/data/local/tmp"
    }
}

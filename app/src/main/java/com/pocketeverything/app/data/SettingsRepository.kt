package com.pocketeverything.app.data

import android.content.Context
import android.content.SharedPreferences
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.search.AccessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val code: Int, val label: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "白天"),
    DARK(2, "黑夜");

    companion object {
        fun fromCode(code: Int): ThemeMode = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

data class AppSettings(
    val accessMode: AccessMode = AccessMode.NORMAL,
    val filterConfig: FilterConfig = FilterConfig(),
    val realtimeMonitorEnabled: Boolean = false,
    val openContentHitsInPreview: Boolean = true,
    val showRecentOnEmptyQuery: Boolean = true,
    val hasSeenOnboarding: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val lastFullScanAtMillis: Long = 0L,
    val lastUpdateCheckAtMillis: Long = 0L,
    val ignoredUpdateTag: String = ""
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocket_everything_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val mode = AccessMode.fromCode(prefs.getInt(KEY_MODE, AccessMode.NORMAL.code))
        val skipNoise = prefs.getBoolean(KEY_SKIP_NOISE, true)
        val dedupMounts = prefs.getBoolean(KEY_DEDUP_MOUNTS, true)
        val customExcludes = prefs.getString(KEY_CUSTOM_EXCLUDES, "")
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val allowedPrefixes = prefs.getString(KEY_TIER2_ALLOWED, null)
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: FilterConfig.DEFAULT_TIER2_ALLOWED_PREFIXES
        val blockedPrefixes = prefs.getString(KEY_TIER2_BLOCKED, null)
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: FilterConfig.DEFAULT_TIER2_BLOCKED_PREFIXES
        val maxTxtMb = prefs.getInt(KEY_MAX_TXT_MB, 10).coerceIn(1, 200)
        val maxDocxMb = prefs.getInt(KEY_MAX_DOCX_MB, 30).coerceIn(1, 200)
        val maxPdfMb = prefs.getInt(KEY_MAX_PDF_MB, 50).coerceIn(1, 300)

        return AppSettings(
            accessMode = mode,
            filterConfig = FilterConfig(
                skipNoiseCaches = skipNoise,
                deduplicateMounts = dedupMounts,
                customExcludedPaths = customExcludes,
                tier2AllowedPrefixes = allowedPrefixes,
                tier2BlockedPrefixes = blockedPrefixes,
                maxTxtMdBytes = maxTxtMb * 1024L * 1024L,
                maxDocxBytes = maxDocxMb * 1024L * 1024L,
                maxPdfBytes = maxPdfMb * 1024L * 1024L
            ),
            realtimeMonitorEnabled = prefs.getBoolean(KEY_REALTIME_MONITOR, false),
            openContentHitsInPreview = prefs.getBoolean(KEY_PREVIEW_ON_CONTENT, true),
            showRecentOnEmptyQuery = prefs.getBoolean(KEY_SHOW_RECENT_ON_EMPTY, true),
            hasSeenOnboarding = prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false),
            themeMode = ThemeMode.fromCode(prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.code)),
            useDynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            lastFullScanAtMillis = prefs.getLong(KEY_LAST_FULL_SCAN, 0L),
            lastUpdateCheckAtMillis = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L),
            ignoredUpdateTag = prefs.getString(KEY_IGNORED_UPDATE_TAG, "").orEmpty()
        )
    }

    fun updateAccessMode(mode: AccessMode) {
        prefs.edit().putInt(KEY_MODE, mode.code).apply()
        _settings.value = _settings.value.copy(accessMode = mode)
    }

    fun updateFilterConfig(config: FilterConfig) {
        prefs.edit()
            .putBoolean(KEY_SKIP_NOISE, config.skipNoiseCaches)
            .putBoolean(KEY_DEDUP_MOUNTS, config.deduplicateMounts)
            .putString(KEY_CUSTOM_EXCLUDES, config.customExcludedPaths.joinToString("\n"))
            .putString(KEY_TIER2_ALLOWED, config.tier2AllowedPrefixes.joinToString("\n"))
            .putString(KEY_TIER2_BLOCKED, config.tier2BlockedPrefixes.joinToString("\n"))
            .putInt(KEY_MAX_TXT_MB, (config.maxTxtMdBytes / (1024L * 1024L)).toInt())
            .putInt(KEY_MAX_DOCX_MB, (config.maxDocxBytes / (1024L * 1024L)).toInt())
            .putInt(KEY_MAX_PDF_MB, (config.maxPdfBytes / (1024L * 1024L)).toInt())
            .apply()
        _settings.value = _settings.value.copy(filterConfig = config)
    }

    fun setRealtimeMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REALTIME_MONITOR, enabled).apply()
        _settings.value = _settings.value.copy(realtimeMonitorEnabled = enabled)
    }

    fun setOpenContentHitsInPreview(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_ON_CONTENT, enabled).apply()
        _settings.value = _settings.value.copy(openContentHitsInPreview = enabled)
    }

    fun setShowRecentOnEmptyQuery(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RECENT_ON_EMPTY, enabled).apply()
        _settings.value = _settings.value.copy(showRecentOnEmptyQuery = enabled)
    }

    fun setHasSeenOnboarding(seen: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, seen).apply()
        _settings.value = _settings.value.copy(hasSeenOnboarding = seen)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode.code).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun setUseDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _settings.value = _settings.value.copy(useDynamicColor = enabled)
    }

    fun markFullScanCompleted(timestampMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FULL_SCAN, timestampMillis).apply()
        _settings.value = _settings.value.copy(lastFullScanAtMillis = timestampMillis)
    }

    fun markUpdateChecked(timestampMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, timestampMillis).apply()
        _settings.value = _settings.value.copy(lastUpdateCheckAtMillis = timestampMillis)
    }

    fun setIgnoredUpdateTag(tagName: String) {
        prefs.edit().putString(KEY_IGNORED_UPDATE_TAG, tagName).apply()
        _settings.value = _settings.value.copy(ignoredUpdateTag = tagName)
    }

    companion object {
        private const val KEY_MODE = "access_mode"
        private const val KEY_SKIP_NOISE = "skip_noise_caches"
        private const val KEY_DEDUP_MOUNTS = "dedup_mounts"
        private const val KEY_CUSTOM_EXCLUDES = "custom_excludes"
        private const val KEY_TIER2_ALLOWED = "tier2_allowed"
        private const val KEY_TIER2_BLOCKED = "tier2_blocked"
        private const val KEY_MAX_TXT_MB = "max_txt_mb"
        private const val KEY_MAX_DOCX_MB = "max_docx_mb"
        private const val KEY_MAX_PDF_MB = "max_pdf_mb"
        private const val KEY_REALTIME_MONITOR = "realtime_monitor"
        private const val KEY_PREVIEW_ON_CONTENT = "preview_on_content"
        private const val KEY_SHOW_RECENT_ON_EMPTY = "show_recent_on_empty"
        private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "use_dynamic_color"
        private const val KEY_LAST_FULL_SCAN = "last_full_scan"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        private const val KEY_IGNORED_UPDATE_TAG = "ignored_update_tag"
    }
}

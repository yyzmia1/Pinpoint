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

enum class ThemeAccent(val code: Int, val label: String) {
    SKY(0, "晴空蓝"),
    SAGE(1, "莫奈森绿"),
    ROSE(2, "樱花雾粉"),
    AMBER(3, "落日琥珀"),
    VIOLET(4, "极光鸢尾"),
    MONO(5, "极简石墨");

    companion object {
        fun fromCode(code: Int): ThemeAccent = entries.firstOrNull { it.code == code } ?: SKY
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
    val useDynamicColor: Boolean = false,
    val themeAccent: ThemeAccent = ThemeAccent.SKY,
    val pureBlackDark: Boolean = false,
    val customBgEnabled: Boolean = false,
    val customBgPath: String = "",
    val customBgOpacity: Float = 0.80f,
    val customBgDim: Float = 0.20f,
    val customBgBlur: Boolean = false,
    val extractColorFromBg: Boolean = true,
    val customBgSeedArgb: Int = 0,
    val hapticFeedbackEnabled: Boolean = true,
    val hapticStrength: Float = 0.20f,
    val useMonospaceFont: Boolean = false,
    val compactListMode: Boolean = false,
    val lastFullScanAtMillis: Long = 0L,
    val lastUpdateCheckAtMillis: Long = 0L,
    val ignoredUpdateTag: String = "",
    val receiveStableUpdates: Boolean = true,
    val receiveBetaUpdates: Boolean = false
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
        val customExcludedDirs = prefs.getString(KEY_CUSTOM_EXCLUDED_DIRS, "")
            ?.split('\n', ',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val rootIncludeData = prefs.getBoolean(KEY_ROOT_INCLUDE_DATA, false)
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
                rootIncludeData = rootIncludeData,
                customExcludedPaths = customExcludes,
                customExcludedDirNames = customExcludedDirs,
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
            useDynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
            themeAccent = ThemeAccent.fromCode(prefs.getInt(KEY_THEME_ACCENT, ThemeAccent.SKY.code)),
            pureBlackDark = prefs.getBoolean(KEY_PURE_BLACK_DARK, false),
            customBgEnabled = prefs.getBoolean(KEY_CUSTOM_BG_ENABLED, false),
            customBgPath = prefs.getString(KEY_CUSTOM_BG_PATH, "").orEmpty(),
            customBgOpacity = prefs.getFloat(KEY_CUSTOM_BG_OPACITY, 0.80f).coerceIn(0.0f, 1.0f),
            customBgDim = prefs.getFloat(KEY_CUSTOM_BG_DIM, 0.20f).coerceIn(0.0f, 0.80f),
            customBgBlur = prefs.getBoolean(KEY_CUSTOM_BG_BLUR, false),
            extractColorFromBg = prefs.getBoolean(KEY_EXTRACT_COLOR_FROM_BG, true),
            customBgSeedArgb = prefs.getInt(KEY_CUSTOM_BG_SEED_ARGB, 0),
            hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true),
            hapticStrength = prefs.getFloat(
                KEY_HAPTIC_STRENGTH,
                if (prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)) 0.20f else 0f
            ).coerceIn(0f, 1f),
            useMonospaceFont = prefs.getBoolean(KEY_USE_MONOSPACE_FONT, false),
            compactListMode = prefs.getBoolean(KEY_COMPACT_LIST_MODE, false),
            lastFullScanAtMillis = prefs.getLong(KEY_LAST_FULL_SCAN, 0L),
            lastUpdateCheckAtMillis = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L),
            ignoredUpdateTag = prefs.getString(KEY_IGNORED_UPDATE_TAG, "").orEmpty(),
            receiveStableUpdates = prefs.getBoolean(KEY_RECEIVE_STABLE_UPDATES, true),
            receiveBetaUpdates = prefs.getBoolean(KEY_RECEIVE_BETA_UPDATES, false)
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
            .putBoolean(KEY_ROOT_INCLUDE_DATA, config.rootIncludeData)
            .putString(KEY_CUSTOM_EXCLUDES, config.customExcludedPaths.joinToString("\n"))
            .putString(KEY_CUSTOM_EXCLUDED_DIRS, config.customExcludedDirNames.joinToString("\n"))
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

    fun setThemeAccent(accent: ThemeAccent) {
        prefs.edit()
            .putInt(KEY_THEME_ACCENT, accent.code)
            .putBoolean(KEY_DYNAMIC_COLOR, false)
            .putBoolean(KEY_EXTRACT_COLOR_FROM_BG, false)
            .apply()
        _settings.value = _settings.value.copy(
            themeAccent = accent,
            useDynamicColor = false,
            extractColorFromBg = false
        )
    }

    fun setPureBlackDark(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PURE_BLACK_DARK, enabled).apply()
        _settings.value = _settings.value.copy(pureBlackDark = enabled)
    }

    fun setCustomBgEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CUSTOM_BG_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(customBgEnabled = enabled)
    }

    fun setCustomBgImage(path: String, seedArgb: Int) {
        val enabled = path.isNotBlank()
        prefs.edit()
            .putString(KEY_CUSTOM_BG_PATH, path)
            .putInt(KEY_CUSTOM_BG_SEED_ARGB, seedArgb)
            .putBoolean(KEY_CUSTOM_BG_ENABLED, enabled)
            .apply()
        _settings.value = _settings.value.copy(
            customBgPath = path,
            customBgSeedArgb = seedArgb,
            customBgEnabled = enabled
        )
    }

    fun setCustomBgOpacity(opacity: Float) {
        val v = opacity.coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat(KEY_CUSTOM_BG_OPACITY, v).apply()
        _settings.value = _settings.value.copy(customBgOpacity = v)
    }

    fun setCustomBgDim(dim: Float) {
        val v = dim.coerceIn(0.0f, 0.80f)
        prefs.edit().putFloat(KEY_CUSTOM_BG_DIM, v).apply()
        _settings.value = _settings.value.copy(customBgDim = v)
    }

    fun setCustomBgBlur(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CUSTOM_BG_BLUR, enabled).apply()
        _settings.value = _settings.value.copy(customBgBlur = enabled)
    }

    fun setExtractColorFromBg(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTRACT_COLOR_FROM_BG, enabled).apply()
        _settings.value = _settings.value.copy(extractColorFromBg = enabled)
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        val strength = if (enabled) (_settings.value.hapticStrength.takeIf { it > 0.02f } ?: 0.20f) else 0f
        setHapticStrength(strength)
    }

    fun setHapticStrength(strength: Float) {
        val v = strength.coerceIn(0f, 1f)
        val enabled = v > 0.01f
        prefs.edit()
            .putFloat(KEY_HAPTIC_STRENGTH, v)
            .putBoolean(KEY_HAPTIC_FEEDBACK, enabled)
            .apply()
        _settings.value = _settings.value.copy(
            hapticStrength = v,
            hapticFeedbackEnabled = enabled
        )
    }

    fun setUseMonospaceFont(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_MONOSPACE_FONT, enabled).apply()
        _settings.value = _settings.value.copy(useMonospaceFont = enabled)
    }

    fun setCompactListMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPACT_LIST_MODE, enabled).apply()
        _settings.value = _settings.value.copy(compactListMode = enabled)
    }

    fun resetThemeSettings() {
        prefs.edit()
            .putInt(KEY_THEME_MODE, ThemeMode.SYSTEM.code)
            .putBoolean(KEY_DYNAMIC_COLOR, false)
            .putInt(KEY_THEME_ACCENT, ThemeAccent.SKY.code)
            .putBoolean(KEY_PURE_BLACK_DARK, false)
            .putBoolean(KEY_CUSTOM_BG_ENABLED, false)
            .putFloat(KEY_CUSTOM_BG_OPACITY, 0.80f)
            .putFloat(KEY_CUSTOM_BG_DIM, 0.20f)
            .putBoolean(KEY_CUSTOM_BG_BLUR, false)
            .putBoolean(KEY_EXTRACT_COLOR_FROM_BG, true)
            .putBoolean(KEY_HAPTIC_FEEDBACK, true)
            .putFloat(KEY_HAPTIC_STRENGTH, 0.20f)
            .putBoolean(KEY_USE_MONOSPACE_FONT, false)
            .putBoolean(KEY_COMPACT_LIST_MODE, false)
            .apply()
        _settings.value = _settings.value.copy(
            themeMode = ThemeMode.SYSTEM,
            useDynamicColor = false,
            themeAccent = ThemeAccent.SKY,
            pureBlackDark = false,
            customBgEnabled = false,
            customBgOpacity = 0.80f,
            customBgDim = 0.20f,
            customBgBlur = false,
            extractColorFromBg = true,
            hapticFeedbackEnabled = true,
            hapticStrength = 0.20f,
            useMonospaceFont = false,
            compactListMode = false
        )
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

    fun setReceiveStableUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIVE_STABLE_UPDATES, enabled).apply()
        _settings.value = _settings.value.copy(receiveStableUpdates = enabled)
    }

    fun setReceiveBetaUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIVE_BETA_UPDATES, enabled).apply()
        _settings.value = _settings.value.copy(receiveBetaUpdates = enabled)
    }

    fun getActiveDownloadId(): Long = prefs.getLong(KEY_ACTIVE_DOWNLOAD_ID, -1L)

    fun setActiveDownloadId(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_DOWNLOAD_ID, id).apply()
    }

    companion object {
        private const val KEY_MODE = "access_mode"
        private const val KEY_SKIP_NOISE = "skip_noise_caches"
        private const val KEY_DEDUP_MOUNTS = "dedup_mounts"
        private const val KEY_ROOT_INCLUDE_DATA = "root_include_data"
        private const val KEY_CUSTOM_EXCLUDES = "custom_excludes"
        private const val KEY_CUSTOM_EXCLUDED_DIRS = "custom_excluded_dirs"
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
        private const val KEY_THEME_ACCENT = "theme_accent"
        private const val KEY_PURE_BLACK_DARK = "pure_black_dark"
        private const val KEY_CUSTOM_BG_ENABLED = "custom_bg_enabled"
        private const val KEY_CUSTOM_BG_PATH = "custom_bg_path"
        private const val KEY_CUSTOM_BG_OPACITY = "custom_bg_opacity"
        private const val KEY_CUSTOM_BG_DIM = "custom_bg_dim"
        private const val KEY_CUSTOM_BG_BLUR = "custom_bg_blur"
        private const val KEY_EXTRACT_COLOR_FROM_BG = "extract_color_from_bg"
        private const val KEY_CUSTOM_BG_SEED_ARGB = "custom_bg_seed_argb"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback_enabled"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        private const val KEY_USE_MONOSPACE_FONT = "use_monospace_font"
        private const val KEY_COMPACT_LIST_MODE = "compact_list_mode"
        private const val KEY_LAST_FULL_SCAN = "last_full_scan"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        private const val KEY_IGNORED_UPDATE_TAG = "ignored_update_tag"
        private const val KEY_RECEIVE_STABLE_UPDATES = "receive_stable_updates"
        private const val KEY_RECEIVE_BETA_UPDATES = "receive_beta_updates"
        private const val KEY_ACTIVE_DOWNLOAD_ID = "active_download_id"
    }
}

package com.pocketeverything.app.data

import java.net.HttpURLConnection
import java.net.URL

data class ReleaseUpdateInfo(
    val tagName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val currentVersion: String) : UpdateCheckState
    data class Available(val info: ReleaseUpdateInfo) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

/**
 * Dependency-free GitHub Releases API checker & SemVer comparator.
 */
object UpdateChecker {

    fun checkLatestRelease(
        repoSlug: String,
        currentVersion: String,
        timeoutMs: Int = 8000
    ): UpdateCheckState {
        var conn: HttpURLConnection? = null
        return try {
            val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Pinpoint-Android")
            }
            val code = conn.responseCode
            if (code == 404) {
                return UpdateCheckState.UpToDate(currentVersion)
            }
            if (code !in 200..299) {
                return UpdateCheckState.Error("GitHub 返回状态码 $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val info = parseReleaseJson(body)
                ?: return UpdateCheckState.Error("无法解析版本信息")

            if (isNewerVersion(currentVersion, info.tagName)) {
                UpdateCheckState.Available(info)
            } else {
                UpdateCheckState.UpToDate(currentVersion)
            }
        } catch (t: Throwable) {
            UpdateCheckState.Error(t.message ?: "网络连接失败")
        } finally {
            conn?.disconnect()
        }
    }

    fun parseReleaseJson(jsonText: String): ReleaseUpdateInfo? {
        val root = SimpleJsonParser(jsonText).parseValue() as? Map<*, *> ?: return null
        val tagName = root["tag_name"] as? String ?: return null
        val htmlUrl = root["html_url"] as? String ?: ""
        val notes = (root["body"] as? String).orEmpty().trim()
        val assets = root["assets"] as? List<*> ?: emptyList<Any>()

        var apkUrl: String? = null
        var apkSize = 0L
        for (rawAsset in assets) {
            val asset = rawAsset as? Map<*, *> ?: continue
            val name = (asset["name"] as? String).orEmpty()
            val url = asset["browser_download_url"] as? String
            if (name.endsWith(".apk", ignoreCase = true) && !url.isNullOrBlank()) {
                val size = (asset["size"] as? Number)?.toLong() ?: 0L
                if (apkUrl == null || name.contains("release", ignoreCase = true)) {
                    apkUrl = url
                    apkSize = size
                }
            }
        }

        return ReleaseUpdateInfo(
            tagName = tagName,
            releaseNotes = notes,
            releaseUrl = htmlUrl,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize
        )
    }

    fun isNewerVersion(currentVersion: String, remoteTag: String): Boolean {
        val cur = parseSemVer(currentVersion)
        val rem = parseSemVer(remoteTag)
        val maxLen = maxOf(cur.parts.size, rem.parts.size)
        for (i in 0 until maxLen) {
            val c = cur.parts.getOrElse(i) { 0 }
            val r = rem.parts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        // Same numeric base (e.g. current is 1.0.0-2-g1234 vs tag v1.0.0 -> local is ahead or equal)
        return false
    }

    private data class ParsedVersion(val parts: List<Int>)

    private fun parseSemVer(raw: String): ParsedVersion {
        val clean = raw.trim().removePrefix("v").removePrefix("V")
        val base = clean.substringBefore('-').substringBefore('+')
        val nums = base.split('.').map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedVersion(nums)
    }

    /**
     * Minimal RFC-8259 compliant recursive-descent JSON parser for standard Kotlin/JVM & Android.
     */
    private class SimpleJsonParser(private val src: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < src.length && src[pos] == ':') pos++
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else {
                    if (pos < src.length && src[pos] == '}') pos++
                    break
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(parseValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else {
                    if (pos < src.length && src[pos] == ']') pos++
                    break
                }
            }
            return list
        }

        private fun parseString(): String {
            if (pos >= src.length || src[pos] != '"') return ""
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 <= src.length) {
                                val hex = src.substring(pos, pos + 4)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                pos += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < src.length) {
                val c = src[pos]
                if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++
                else break
            }
            val raw = src.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: 0L
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}

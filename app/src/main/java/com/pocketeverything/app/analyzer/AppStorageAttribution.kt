package com.pocketeverything.app.analyzer

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Locale

enum class AppPathCategory(val label: String) {
    APP_DATA("应用数据"),
    APP_OBB("数据包 OBB"),
    APP_MEDIA("应用媒体"),
    PRIVATE_DATA("私有数据"),
    APP_APK("安装包"),
    SHARED_STORAGE("公共目录")
}

data class PathAttributionMatch(
    val packageName: String,
    val ownerRootPath: String,
    val category: AppPathCategory,
    val fallbackLabel: String? = null
)

data class ResolvedAppMeta(
    val packageName: String,
    val displayLabel: String,
    val isInstalled: Boolean
)

fun interface AppInfoResolver {
    fun resolve(packageName: String, fallbackLabel: String?): ResolvedAppMeta
}

/**
 * Maps Android app-scoped directories and notorious shared-storage app folders
 * back to their owning package name so WizTree can attribute space hogs and uninstalled residue.
 */
object AppStorageAttribution {

    private data class SharedFolderRule(
        val relativePrefixLower: String,
        val canonicalRelative: String,
        val packageName: String,
        val defaultLabel: String
    )

    // Ordered most-specific first so e.g. Download/WeiXin matches before Download.
    private val SHARED_FOLDER_RULES: List<SharedFolderRule> = listOf(
        SharedFolderRule("tencent/micromsg", "Tencent/MicroMsg", "com.tencent.mm", "微信"),
        SharedFolderRule("pictures/weixin", "Pictures/WeiXin", "com.tencent.mm", "微信"),
        SharedFolderRule("download/weixin", "Download/WeiXin", "com.tencent.mm", "微信"),
        SharedFolderRule("download/wechat", "Download/WeChat", "com.tencent.mm", "微信"),
        SharedFolderRule("movies/weixin", "Movies/WeiXin", "com.tencent.mm", "微信"),
        SharedFolderRule("tencent/mobileqq", "Tencent/MobileQQ", "com.tencent.mobileqq", "QQ"),
        SharedFolderRule("tencent/qqfile_recv", "Tencent/QQfile_recv", "com.tencent.mobileqq", "QQ"),
        SharedFolderRule("tencent/qq_images", "Tencent/QQ_Images", "com.tencent.mobileqq", "QQ"),
        SharedFolderRule("pictures/qq", "Pictures/QQ", "com.tencent.mobileqq", "QQ"),
        SharedFolderRule("download/qq", "Download/QQ", "com.tencent.mobileqq", "QQ"),
        SharedFolderRule("download/quarkdownloads", "Download/QuarkDownloads", "com.quark.browser", "夸克"),
        SharedFolderRule("quark", "Quark", "com.quark.browser", "夸克"),
        SharedFolderRule("download/baidunetdisk", "Download/BaiduNetdisk", "com.baidu.netdisk", "百度网盘"),
        SharedFolderRule("baidunetdisk", "BaiduNetdisk", "com.baidu.netdisk", "百度网盘"),
        SharedFolderRule("movies/telegram", "Movies/Telegram", "org.telegram.messenger", "Telegram"),
        SharedFolderRule("pictures/telegram", "Pictures/Telegram", "org.telegram.messenger", "Telegram"),
        SharedFolderRule("documents/telegram", "Documents/Telegram", "org.telegram.messenger", "Telegram"),
        SharedFolderRule("download/telegram", "Download/Telegram", "org.telegram.messenger", "Telegram"),
        SharedFolderRule("telegram", "Telegram", "org.telegram.messenger", "Telegram"),
        SharedFolderRule("download/dingtalk", "Download/DingTalk", "com.alibaba.android.rimet", "钉钉"),
        SharedFolderRule("dingtalk", "DingTalk", "com.alibaba.android.rimet", "钉钉"),
        SharedFolderRule("download/bilibili", "Download/bilibili", "tv.danmaku.bili", "哔哩哔哩"),
        SharedFolderRule("pictures/bilibili", "Pictures/bilibili", "tv.danmaku.bili", "哔哩哔哩"),
        SharedFolderRule("bilibili", "bilibili", "tv.danmaku.bili", "哔哩哔哩"),
        SharedFolderRule("pictures/zhihu", "Pictures/zhihu", "com.zhihu.android", "知乎"),
        SharedFolderRule("zhihu", "zhihu", "com.zhihu.android", "知乎"),
        SharedFolderRule("ucdownloads", "UCDownloads", "com.UCMobile", "UC 浏览器"),
        SharedFolderRule("netease/cloudmusic", "netease/cloudmusic", "com.netease.cloudmusic", "网易云音乐"),
        SharedFolderRule("music/netease", "Music/Netease", "com.netease.cloudmusic", "网易云音乐"),
        SharedFolderRule("qqmusic", "qqmusic", "com.tencent.qqmusic", "QQ 音乐"),
        SharedFolderRule("alipay", "Alipay", "com.eg.android.AlipayGphone", "支付宝"),
        SharedFolderRule("taobao", "Taobao", "com.taobao.taobao", "淘宝"),
        SharedFolderRule("dcim/douyin", "DCIM/Douyin", "com.ss.android.ugc.aweme", "抖音"),
        SharedFolderRule("movies/douyin", "Movies/Douyin", "com.ss.android.ugc.aweme", "抖音"),
        SharedFolderRule("douyin", "Douyin", "com.ss.android.ugc.aweme", "抖音"),
        SharedFolderRule("pictures/redbook", "Pictures/RedBook", "com.xingin.xhs", "小红书"),
        SharedFolderRule("dcim/redbook", "DCIM/RedBook", "com.xingin.xhs", "小红书"),
        SharedFolderRule("xhs", "xhs", "com.xingin.xhs", "小红书"),
        SharedFolderRule("documents/wps", "Documents/WPS", "cn.wps.moffice_eng", "WPS Office"),
        SharedFolderRule("wps", "WPS", "cn.wps.moffice_eng", "WPS Office"),
        SharedFolderRule("taptap", "TapTap", "com.taptap", "TapTap"),
        SharedFolderRule("mt2", "MT2", "bin.mt.plus", "MT 管理器"),
        SharedFolderRule("baidumap", "BaiduMap", "com.baidu.BaiduMap", "百度地图"),
        SharedFolderRule("autonavi", "autonavi", "com.autonavi.minimap", "高德地图"),
        SharedFolderRule("amap", "amap", "com.autonavi.minimap", "高德地图"),
        SharedFolderRule("tencent/tim", "Tencent/TIM", "com.tencent.tim", "TIM")
    )

    private val KNOWN_PACKAGE_LABELS: Map<String, String> = mapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.tim" to "TIM",
        "com.quark.browser" to "夸克",
        "com.baidu.netdisk" to "百度网盘",
        "org.telegram.messenger" to "Telegram",
        "com.alibaba.android.rimet" to "钉钉",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.zhihu.android" to "知乎",
        "com.UCMobile" to "UC 浏览器",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ 音乐",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.taobao.taobao" to "淘宝",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.xingin.xhs" to "小红书",
        "cn.wps.moffice_eng" to "WPS Office",
        "com.taptap" to "TapTap",
        "bin.mt.plus" to "MT 管理器",
        "com.baidu.BaiduMap" to "百度地图",
        "com.autonavi.minimap" to "高德地图",
        "com.coolapk.market" to "酷安",
        "com.sina.weibo" to "微博",
        "com.xunlei.downloadprovider" to "迅雷"
    )

    fun knownLabelForPackage(packageName: String): String? = KNOWN_PACKAGE_LABELS[packageName]

    fun isPlausiblePackageName(candidate: String): Boolean {
        if (candidate.length < 3 || candidate.startsWith('.') || candidate.endsWith('.')) return false
        var hasDot = false
        for (ch in candidate) {
            when {
                ch == '.' -> hasDot = true
                ch.isLetterOrDigit() || ch == '_' -> Unit
                else -> return false
            }
        }
        return hasDot
    }

    /**
     * Resolves whether [path] belongs to an Android application's storage area.
     */
    fun matchPath(path: String): PathAttributionMatch? {
        if (!path.startsWith('/')) return null

        // 1. /Android/{data,obb,media}/<pkg> under any storage root (/storage/emulated/0, /sdcard, /storage/XXXX-XXXX, /data/media/0)
        val androidIdx = path.indexOf("/Android/")
        if (androidIdx > 0) {
            val afterAndroid = path.substring(androidIdx + "/Android/".length)
            val slash1 = afterAndroid.indexOf('/')
            if (slash1 > 0) {
                val bucket = afterAndroid.substring(0, slash1)
                val category = when (bucket) {
                    "data" -> AppPathCategory.APP_DATA
                    "obb" -> AppPathCategory.APP_OBB
                    "media" -> AppPathCategory.APP_MEDIA
                    else -> null
                }
                if (category != null) {
                    val afterBucket = afterAndroid.substring(slash1 + 1)
                    val slash2 = afterBucket.indexOf('/')
                    val pkg = if (slash2 < 0) afterBucket else afterBucket.substring(0, slash2)
                    if (isPlausiblePackageName(pkg)) {
                        val ownerRoot = path.substring(0, androidIdx) + "/Android/$bucket/$pkg"
                        return PathAttributionMatch(
                            packageName = pkg,
                            ownerRootPath = ownerRoot,
                            category = category,
                            fallbackLabel = KNOWN_PACKAGE_LABELS[pkg]
                        )
                    }
                }
            }
        }

        // 2. Root private data: /data/data/<pkg>, /data/user/<uid>/<pkg>, /data/user_de/<uid>/<pkg>
        if (path.startsWith("/data/data/")) {
            val rest = path.removePrefix("/data/data/")
            val pkg = rest.substringBefore('/')
            if (isPlausiblePackageName(pkg)) {
                return PathAttributionMatch(
                    packageName = pkg,
                    ownerRootPath = "/data/data/$pkg",
                    category = AppPathCategory.PRIVATE_DATA,
                    fallbackLabel = KNOWN_PACKAGE_LABELS[pkg]
                )
            }
        } else if (path.startsWith("/data/user/") || path.startsWith("/data/user_de/")) {
            val prefix = if (path.startsWith("/data/user/")) "/data/user/" else "/data/user_de/"
            val rest = path.removePrefix(prefix)
            val slash1 = rest.indexOf('/')
            if (slash1 > 0) {
                val uid = rest.substring(0, slash1)
                if (uid.all { it.isDigit() }) {
                    val afterUid = rest.substring(slash1 + 1)
                    val pkg = afterUid.substringBefore('/')
                    if (isPlausiblePackageName(pkg)) {
                        return PathAttributionMatch(
                            packageName = pkg,
                            ownerRootPath = "$prefix$uid/$pkg",
                            category = AppPathCategory.PRIVATE_DATA,
                            fallbackLabel = KNOWN_PACKAGE_LABELS[pkg]
                        )
                    }
                }
            }
        } else if (path.startsWith("/data/app/")) {
            // /data/app/~~random==/com.pkg.name-random==/... or legacy /data/app/com.pkg.name-1/...
            val segments = path.removePrefix("/data/app/").split('/')
            for (i in segments.indices) {
                val seg = segments[i]
                if (seg.startsWith("~~")) continue
                val dashIdx = seg.indexOf('-')
                val candidate = if (dashIdx > 0) seg.substring(0, dashIdx) else seg
                if (isPlausiblePackageName(candidate)) {
                    val ownerRoot = "/data/app/" + segments.take(i + 1).joinToString("/")
                    return PathAttributionMatch(
                        packageName = candidate,
                        ownerRootPath = ownerRoot,
                        category = AppPathCategory.APP_APK,
                        fallbackLabel = KNOWN_PACKAGE_LABELS[candidate]
                    )
                }
                break
            }
        }

        // 3. Known shared-storage folders under primary/external storage roots
        val rootPrefix = extractStorageRootPrefix(path) ?: return null
        val rel = path.removePrefix("$rootPrefix/")
        if (rel.isEmpty() || rel == path) return null
        val relLower = rel.lowercase(Locale.ROOT)
        for (rule in SHARED_FOLDER_RULES) {
            if (relLower == rule.relativePrefixLower || relLower.startsWith("${rule.relativePrefixLower}/")) {
                val matchedSegCount = rule.canonicalRelative.count { it == '/' } + 1
                val actualPrefix = rel.split('/').take(matchedSegCount).joinToString("/")
                return PathAttributionMatch(
                    packageName = rule.packageName,
                    ownerRootPath = "$rootPrefix/$actualPrefix",
                    category = AppPathCategory.SHARED_STORAGE,
                    fallbackLabel = rule.defaultLabel
                )
            }
        }

        return null
    }

    private fun extractStorageRootPrefix(path: String): String? {
        return when {
            path.startsWith("/storage/emulated/0/") -> "/storage/emulated/0"
            path.startsWith("/sdcard/") -> "/sdcard"
            path.startsWith("/data/media/0/") -> "/data/media/0"
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                val vol = rest.substringBefore('/')
                if (vol.isNotEmpty() && vol != "emulated" && vol != "self" && rest.contains('/')) {
                    "/storage/$vol"
                } else null
            }
            else -> null
        }
    }
}

/**
 * Android PackageManager-backed resolver + bounded icon decoder for the WizTree UI.
 */
class AndroidAppInfoResolver(context: Context) : AppInfoResolver {
    private val pm: PackageManager = context.applicationContext.packageManager
    private val metaCache = HashMap<String, ResolvedAppMeta>()

    override fun resolve(packageName: String, fallbackLabel: String?): ResolvedAppMeta {
        metaCache[packageName]?.let { return it }
        val resolved = try {
            val info = pm.getApplicationInfo(packageName, 0)
            val label = runCatching { pm.getApplicationLabel(info)?.toString()?.trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackLabel
                ?: packageName
            ResolvedAppMeta(packageName = packageName, displayLabel = label, isInstalled = true)
        } catch (_: PackageManager.NameNotFoundException) {
            ResolvedAppMeta(
                packageName = packageName,
                displayLabel = fallbackLabel ?: AppStorageAttribution.knownLabelForPackage(packageName) ?: packageName,
                isInstalled = false
            )
        } catch (_: Throwable) {
            ResolvedAppMeta(
                packageName = packageName,
                displayLabel = fallbackLabel ?: packageName,
                isInstalled = true
            )
        }
        metaCache[packageName] = resolved
        return resolved
    }

    fun loadIconsForPackages(packages: Collection<String>, iconSizePx: Int = 72): Map<String, ImageBitmap> {
        val result = LinkedHashMap<String, ImageBitmap>(packages.size)
        for (pkg in packages) {
            val bmp = loadSingleIcon(pkg, iconSizePx) ?: continue
            result[pkg] = bmp
        }
        return result
    }

    private fun loadSingleIcon(packageName: String, sizePx: Int): ImageBitmap? {
        return runCatching {
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null &&
                drawable.bitmap.width <= sizePx * 2 && drawable.bitmap.height <= sizePx * 2
            ) {
                drawable.bitmap
            } else {
                val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                out
            }
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

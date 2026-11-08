package com.airmouse.gyro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查。
 *
 * 数据源就是仓库里的 update.json（纯静态文件，不用 GitHub API，不受限流影响）：
 *
 *   https://raw.githubusercontent.com/<你的账号>/<仓库>/main/update.json
 *
 * 改版本时只改这个文件和 GitHub Release 里的附件，两边程序都会自己发现。
 */
object Updater {

    /**
     * 多个源，按顺序试，第一个能通的就用（国内直连 raw.githubusercontent 常常不通，
     * jsDelivr 走国内 CDN 一般没问题；第三条留给 Gitee 镜像或自己的服务器）。
     * 发布前把 CHANGE_ME / <账号> 换成你自己的。
     */
    val MANIFEST_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/update.json",
        "https://raw.githubusercontent.com/Tang-Yongqiang/AirMouse/main/update.json",
        "https://gitee.com/Tang-Yongqiang/AirMouse/raw/main/update.json"
    )

    private const val OWNER = "Tang-Yongqiang"
    private const val REPO = "AirMouse"

    /** GitHub 的 tag 列表接口：不走 CDN，也不受分支快照缓存影响（未登录按 IP 限流 60 次/小时，够用） */
    private const val TAGS_URL = "https://api.github.com/repos/$OWNER/$REPO/tags?per_page=20"

    // 注意：jsDelivr 的 @main / @latest 分支快照会长期卡住，只能按具体 tag 取。
    // 而且返回顺序不保证；@main 的清单在每次发版时由 publish.ps1 主动 purge，
    // 实测 purge 之后几秒内就是新的，够用了。

    /** 一个版本的更新说明（清单 history[] 里的一条） */
    data class VersionNote(val versionCode: Int, val versionName: String, val notes: String)

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String?,
        val exeUrl: String?,
        /** 只放手机端的更新说明；电脑端内容在清单 server.notes 里，由电脑端自己显示 */
        val notes: String?,
        /** 这一版要求的最低电脑端版本（清单 server.minForApk）；没写就是不限制 */
        val minServer: String? = null,
        /** 逐版本说明，用来把「当前版本 → 最新版本」之间的改动全部展示出来 */
        val history: List<VersionNote> = emptyList()
    )

    data class Result(val info: Info?, val error: String?) {
        val ok: Boolean get() = info != null
    }

    private const val PREF_LAST_CHECK = "updateLastCheck"

    /**
     * 启动自检：**每次打开 App 都查一次**。
     * 只挡掉 60 秒内的重复（切主题/横竖屏会重建 Activity，别连着查两遍）。
     * 查完由调用方决定怎么提示 —— 首次启动发现新版会弹一次，同一次运行里不再重复弹。
     */
    fun checkOnLaunch(ctx: Context, onResult: (Result) -> Unit) {
        val prefs = ctx.getSharedPreferences("airmouse", Context.MODE_PRIVATE)
        val last = prefs.getLong(PREF_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < 60_000L) return
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        check(ctx, onResult)
    }

    /** 自动检查：24 小时最多一次（手动检查走 [checkNow]） */
    fun checkQuietly(ctx: Context, onResult: (Result) -> Unit) {
        val prefs = ctx.getSharedPreferences("airmouse", Context.MODE_PRIVATE)
        val last = prefs.getLong(PREF_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        check(ctx, onResult)
    }

    fun checkNow(ctx: Context, onResult: (Result) -> Unit) {
        ctx.getSharedPreferences("airmouse", Context.MODE_PRIVATE)
            .edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        check(ctx, onResult)
    }

    /**
     * 按 tag 取清单。**raw 放第一位**：jsDelivr 的 tag 快照会缓存很久（实测清单改完几小时
     * 还是旧的，purge 也刷不掉），raw 是 Git 服务器直出、实时；失败再退回 CDN 和 Gitee 镜像。
     */
    private fun fetchByTag(tag: String): String {
        var last: Exception? = null
        for (u in listOf(
            "https://raw.githubusercontent.com/$OWNER/$REPO/$tag/update.json",
            "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$tag/update.json",
            "https://gitee.com/$OWNER/$REPO/raw/$tag/update.json"
        )) {
            try {
                return fetch(u)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("no source")
    }
    /** 取「最新」的那份：版本号大的赢；一样大时用 @main（说明和电脑端版本信息更新）。 */
    private fun fresher(a: Info?, b: Info?): Info? = when {
        a == null -> b
        b == null -> a
        b.versionCode >= a.versionCode -> b
        else -> a
    }

    /**
     * 直接读仓库根目录的 @main 清单（raw 优先）。tag 清单是「某一版」的定格，
     * 而 @main 才是当前状态——只发电脑端（不动 APK 版本）时，只有这里能拿到新 server 版本。
     */
    private fun fetchMainInfo(): Info? {
        for (url in MANIFEST_URLS) {
            try {
                return parse(fetch(url))
            } catch (e: Exception) {
                Diag.warn("UPD", "✗ @main " + hostOf(url) + " " + (e.message ?: ""))
            }
        }
        return null
    }

    /** 本机版本名，例如 1.0.3（用来往后猜下一个版本） */
    private fun myVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    private fun check(ctx: Context, onResult: (Result) -> Unit) {
        val me = myVersionName(ctx)
        val currentCode: Long = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        } catch (e: Exception) {
            0L
        }
        Diag.log("UPD", "开始检查：本机 $me ($currentCode)")
        val handler = Handler(Looper.getMainLooper())
        Thread {
            var result: Result = Result(null, "没有可用的源")
            val errors = StringBuilder()

            // 为什么不是只读 @main：jsDelivr 的分支快照会卡住（我们实测过一直停在旧版本），
            // 而按 tag 取是不可变快照、发版后立刻就能拿到。所以先问 GitHub 的 tags 接口，
            // 再按 tag 逐个取清单，挑 versionCode 最大的那个（不依赖接口返回顺序）。
            // 先用「本地版本号往后猜」：我们是自己按 1.0.1、1.0.2 顺序发版，猜 3 个就够。
            // 这条路不碰任何接口/CDN 缓存，最稳。
            val guessed = ArrayList<String>()
            val lp = me.split(".")
            if (lp.size >= 2) {
                // 版本名可能带测试后缀（1.1.10-test / 1.1.11-beta1），逐段只取前导数字，
                // 否则 toIntOrNull() 全是 null，猜出来的 tag 会从 1.1.0 开始（实测踩过）。
                fun seg(s: String?): Int? {
                    if (s == null) return null
                    val digits = s.takeWhile { it.isDigit() }
                    return digits.toIntOrNull()
                }
                val major = seg(lp[0])
                val minor = seg(lp[1])
                val patch = seg(lp.getOrNull(2)) ?: 0
                if (major != null && minor != null) {
                    // 往后猜 3 个（有新版本时最常用），再补上「当前版本本身」和往前 2 个：
                    // 这样「已经是最新」时也能拿到自己的清单，不会被 CDN 缓存里的旧版本骗到。
                    for (d in listOf(1, 2, 3, 0, -1, -2)) {
                        if (patch + d >= 0) guessed.add("v$major.$minor.${patch + d}")
                    }
                }

            }
            // 并发去猜：串行的话「不存在的 tag」每条都要等满超时（raw 更是 6 秒），
            // 实测 6 个 tag 串行要 30 秒，用户早就以为卡死了。
            val bestGuessRef = java.util.concurrent.atomic.AtomicReference<Info?>(null)
            val latch = java.util.concurrent.CountDownLatch(guessed.size)
            for (t in guessed) {
                Thread {
                    try {
                        val info = parse(fetchByTag(t))
                        Diag.log("UPD", "✓ 猜测 @" + t + " versionCode=" + info.versionCode)
                        bestGuessRef.getAndUpdate { cur ->
                            if (cur == null || info.versionCode > cur.versionCode) info else cur
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            // @main 清单永远是最新的：和 tag 结果比一下，谁新用谁（只发电脑端时靠它传播）
            val mainInfo = fetchMainInfo()
            if (mainInfo != null) Diag.log("UPD", "✓ @main versionCode=" + mainInfo.versionCode)
            val bestGuess: Info? = fresher(bestGuessRef.get(), mainInfo)
            if (bestGuess != null) {
                handler.post { onResult(Result(bestGuess, null)) }
                return@Thread
            }

            val tags = try { fetchTags() } catch (e: Exception) {
                Diag.warn("UPD", "✗ tags 接口 " + (e.message ?: ""))
                emptyList()
            }
            var best: Info? = null
            for (t in tags.take(5)) {
                try {
                    val info = parse(fetchByTag(t))
                    Diag.log("UPD", "✓ @" + t + " versionCode=" + info.versionCode)
                    if (best == null || info.versionCode > best.versionCode) best = info
                } catch (e: Exception) {
                    Diag.warn("UPD", "✗ @" + t + " " + (e.message ?: ""))
                }
            }
            if (best != null || mainInfo != null) {
                handler.post { onResult(Result(fresher(best, mainInfo), null)) }
                return@Thread
            }

            // 兜底：tags 接口不通时，再按固定清单试一遍（可能偏旧，但总比没有强）
            for (url in MANIFEST_URLS) {
                try {
                    val info = parse(fetch(url))
                    Diag.log("UPD", "✓ " + hostOf(url) + " versionCode=" + info.versionCode)
                    // 固定清单是分支快照，可能比本机还旧：那样只能说明「没有可用信息」，
                    // 不能拿去告诉用户「线上最新是 X」（X 比他还旧），否则就成了误报。
                    if (info.versionCode.toLong() >= currentCode) result = Result(info, null)
                    else errors.append("清单偏旧(").append(info.versionName).append(") ")
                    break
                } catch (e: Exception) {
                    val why = e.javaClass.simpleName + ": " + (e.message ?: "")
                    Diag.warn("UPD", "✗ " + hostOf(url) + " " + why)
                    if (errors.length > 0) errors.append("；")
                    errors.append(hostOf(url)).append(" ").append(why)
                    result = Result(null, errors.toString())
                }
            }
            handler.post { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    private fun parse(json: String): Info {
        val o = JSONObject(json)
        val apk = o.optJSONObject("apk")
        val srv = o.optJSONObject("server")
        // 逐版本说明：老清单没有 history，就退化成只有最新一版
        val hist = ArrayList<VersionNote>()
        o.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val vc = e.optInt("versionCode", 0)
                val vn = e.optString("versionName", "")
                val nt = e.optString("notes", "")
                if (vc > 0 && nt.isNotBlank()) hist.add(VersionNote(vc, vn, nt))
            }
        }
        hist.sortBy { it.versionCode }
        val info = Info(
            versionCode = apk?.optInt("versionCode", 0) ?: o.optInt("versionCode", 0),
            versionName = apk?.optString("versionName", "")?.ifEmpty { null } ?: o.optString("versionName", ""),
            apkUrl = apk?.optString("url", "")?.ifEmpty { null } ?: o.optString("apk").ifEmpty { null },
            exeUrl = srv?.optString("url", "")?.ifEmpty { null } ?: o.optString("exe").ifEmpty { null },
            // 说明只取手机端那段（旧的顶层 notes 兼容一下）
            notes = apk?.optString("notes", "")?.ifEmpty { null } ?: o.optString("notes").ifEmpty { null },
            minServer = srv?.optString("minForApk", "")?.ifEmpty { null },
            history = hist
        )
        if (info.versionCode <= 0) throw RuntimeException("update.json 里没有 versionCode")
        return info
    }

    /** 取最近发布的若干 tag 名（"v1.0.2" 这种），失败抛异常 */
    private fun fetchTags(): List<String> {
        val arr = org.json.JSONArray(fetch(TAGS_URL))
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.getJSONObject(i).optString("name")
            if (name.startsWith("v")) out.add(name)
        }
        return out
    }

    private fun hostOf(url: String): String = try {
        URL(url).host ?: url
    } catch (e: Exception) {
        url
    }
    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3500
        conn.readTimeout = 4500
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            try {
                android.widget.Toast.makeText(ctx, "打不开链接，已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
            copyToClipboard(ctx, url)
        }
    }

    fun copyToClipboard(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AirMouse", text))
        } catch (_: Exception) {
        }
    }

    /** 系统允许本应用装 APK 吗（Android 8+ 按来源授权） */
    fun canInstallApk(ctx: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true
    } catch (e: Exception) {
        true
    }

    /** 跳到「安装未知应用」授权页，本应用高亮 */
    fun openInstallSettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                android.widget.Toast.makeText(ctx, "请到系统设置里允许本应用安装应用", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 把下载好的 APK 交给系统安装器 */
    fun installApk(ctx: Context, file: java.io.File): String? = try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", file
        )
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        Diag.log("UPD", "已拉起系统安装器：" + file.name)
        null
    } catch (e: Exception) {
        val why = e.javaClass.simpleName + ": " + (e.message ?: "")
        Diag.warn("UPD", "拉起安装器失败 " + why)
        why
    }
}

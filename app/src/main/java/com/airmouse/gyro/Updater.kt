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

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String?,
        val exeUrl: String?,
        val notes: String?
    )

    data class Result(val info: Info?, val error: String?) {
        val ok: Boolean get() = info != null
    }

    private const val PREF_LAST_CHECK = "updateLastCheck"

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
     * 按 tag 取清单：先 jsDelivr（快），失败再 raw.githubusercontent（刚推的新 tag 在 jsDelivr 上
     * 可能要几分钟才索引到，raw 是 Git 服务器直出、立即可用，只是慢一点、国内偶尔被墙）。
     */
    private fun fetchByTag(tag: String): String {
        var last: Exception? = null
        for (u in listOf(
            "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$tag/update.json",
            "https://raw.githubusercontent.com/$OWNER/$REPO/$tag/update.json",
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
                val major = lp[0].toIntOrNull()
                val minor = lp[1].toIntOrNull()
                val patch = lp.getOrNull(2)?.toIntOrNull() ?: 0
                if (major != null && minor != null) {
                    // 往后猜 3 个（有新版本时最常用），再补上「当前版本本身」和往前 2 个：
                    // 这样「已经是最新」时也能拿到自己的清单，不会被 CDN 缓存里的旧版本骗到。
                    for (d in listOf(1, 2, 3, 0, -1, -2)) {
                        if (patch + d >= 0) guessed.add("v$major.$minor.${patch + d}")
                    }
                }

            }
            var bestGuess: Info? = null
            for (t in guessed) {
                try {
                    val info = parse(fetchByTag(t))
                    Diag.log("UPD", "✓ 猜测 @" + t + " versionCode=" + info.versionCode)
                    if (bestGuess == null || info.versionCode > bestGuess.versionCode) bestGuess = info
                } catch (_: Exception) {
                }
            }
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
            if (best != null) {
                handler.post { onResult(Result(best, null)) }
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
        val info = Info(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            apkUrl = o.optString("apk").ifEmpty { null },
            exeUrl = o.optString("exe").ifEmpty { null },
            notes = o.optString("notes").ifEmpty { null }
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
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
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

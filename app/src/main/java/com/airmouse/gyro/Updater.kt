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
        "https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@latest/update.json",
        "https://raw.githubusercontent.com/Tang-Yongqiang/AirMouse/main/update.json",
        "https://gitee.com/Tang-Yongqiang/AirMouse/raw/main/update.json"
    )

    private const val OWNER = "Tang-Yongqiang"
    private const val REPO = "AirMouse"

    /**
     * GitHub 的 tag 列表接口。它不走 CDN，所以**发版后立刻就能看到新 tag**，
     * 而 jsDelivr 的分支（@main）快照会被缓存最长 12 小时——这就是「明明发了新版，
     * App 还说已是最新」的根因。所以先问 tag，再按 tag 去 jsDelivr 取那个 tag 的清单
     * （tag 是不可变快照，路径天然带版本，不会被旧缓存命中）。
     */
    private const val TAGS_URL = "https://api.github.com/repos/$OWNER/$REPO/tags?per_page=100"

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

    private fun check(ctx: Context, onResult: (Result) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            var result: Result = Result(null, "没有可用的源")
            val errors = StringBuilder()

            // 第一步：问 GitHub 最新 tag（不走 CDN，无缓存）
            val tag = try { latestTag() } catch (e: Exception) {
                Diag.warn("UPD", "✗ tags 接口 " + (e.message ?: ""))
                null
            }
            val urls = ArrayList<String>()
            if (tag != null) {
                urls.add("https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$tag/update.json")
                Diag.log("UPD", "最新 tag = $tag")
            }
            urls.addAll(MANIFEST_URLS)

            for (url in urls) {
                try {
                    val json = fetch(url)
                    val o = JSONObject(json)
                    val info = Info(
                        versionCode = o.optInt("versionCode", 0),
                        versionName = o.optString("versionName", ""),
                        apkUrl = o.optString("apk").ifEmpty { null },
                        exeUrl = o.optString("exe").ifEmpty { null },
                        notes = o.optString("notes").ifEmpty { null }
                    )
                    if (info.versionCode <= 0) throw RuntimeException("update.json 里没有 versionCode")
                    result = Result(info, null)
                    Diag.log("UPD", "✓ " + hostOf(url) + " versionCode=" + info.versionCode)
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

    /**
     * 取「最近发布」的 tag（形如 v1.0.0）。
     *
     * GitHub 的 tags 接口是按时间倒序返回的，所以第一个 v 开头、并且版本号能解析的，
     * 就是最新发的那个。不按版本号大小挑，是因为版本号可能重新开始编号
     * （比如内部迭代到 2.34 之后，对外首发叫 1.0.0）。
     */
    private fun latestTag(): String? {
        val json = fetch(TAGS_URL)
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val name = arr.getJSONObject(i).optString("name")
            if (!name.startsWith("v")) continue
            if (versionCodeOf(name.substring(1)) >= 0) return name
        }
        return null
    }

    /** "1.2.3" -> 10203；"2.28" -> 22800；"2.9" -> 20900，只用来判断「像不像版本号」 */
    private fun versionCodeOf(v: String): Int {
        val p = v.trim().split(".")
        val major = p.getOrNull(0)?.toIntOrNull() ?: return -1
        val minor = p.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = p.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
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

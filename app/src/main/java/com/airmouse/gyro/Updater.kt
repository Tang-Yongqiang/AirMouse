package com.airmouse.gyro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
            for (url in MANIFEST_URLS) {
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
}

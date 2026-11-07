package com.airmouse.gyro

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：把关键事件同时写进 logcat 和内存环形缓冲，
 * 出问题时用户可以「设置 → 诊断日志」一键复制/导出，把 txt 发回来定位。
 *
 * 只记事件（连接、模式、收发、异常），不记鼠标坐标细节，体积很小。
 */
object Diag {

    private const val MAX_LINES = 1200
    private val lines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var enabled: Boolean = true

    /** 启动时记一次环境，方便判断「哪台机器、什么系统、什么版本」 */
    fun logEnv(ctx: Context, version: String) {
        log("App", "start v$version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    fun log(tag: String, msg: String) {
        // 同时进 logcat，方便 adb 直接看
        Log.i(tag, msg)
        if (!enabled) return
        val line = timeFmt.format(Date()) + " [" + tag + "] " + msg
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun warn(tag: String, msg: String) {
        Log.w(tag, msg)
        if (!enabled) return
        val line = timeFmt.format(Date()) + " [" + tag + "] ! " + msg
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        log("Diag", "cleared")
    }

    fun dump(): String = synchronized(lines) {
        if (lines.isEmpty()) "（还没有日志）" else lines.joinToString("\n")
    }

    /** 存到「下载」目录（API 29+ 免权限；老系统退回 App 私有目录），返回给用户看的路径 */
    fun saveToDownloads(ctx: Context, text: String): String {
        val name = "airmouse-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) return "保存失败：无法创建文件"
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "已保存到 下载/" + name
            } else {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val f = File(dir, name)
                f.writeText(text)
                "已保存到 " + f.absolutePath
            }
        } catch (e: Exception) {
            warn("Diag", "save failed: ${e.message}")
            "保存失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }
}

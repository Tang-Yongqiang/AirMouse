package com.airmouse.gyro

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内下载 APK（带进度、可取消，不需要存储权限）。
 *
 * 为什么不直接用系统的 DownloadManager：
 * 它的进度只能靠 ContentObserver 轮询，而且下载条会先跑到通知栏，
 * 做不到「面板里实时百分比 + 速度」。这里自己读 socket 计数，简单可控。
 *
 * 文件放在应用私有目录（getExternalFilesDir），装完可以随手删掉，
 * 不会污染用户的「下载」文件夹，也不需要任何存储权限。
 */
object Downloader {

    data class Progress(
        val bytes: Long,
        val total: Long,
        val bytesPerSec: Long
    ) {
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        val done: Boolean get() = total > 0 && bytes >= total
    }

    /** 用来取消：cancel() 之后线程里的循环会自己退出 */
    class Task internal constructor(private val flag: AtomicBoolean) {
        fun cancel() {
            flag.set(true)
        }
    }

    private fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.filesDir, "downloads")
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** 已经下好并且大小对得上的文件就直接复用；否则删掉重下 */
    fun existing(ctx: Context, name: String, size: Long): File? {
        val f = File(dir(ctx), name)
        if (!f.exists() || f.length() < 100_000) return null
        return if (size <= 0 || f.length() == size) f else null
    }

    /** 目录里最近下载的那个 APK（调试/兜底用） */
    fun latestApk(ctx: Context): File? = try {
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() }
    } catch (e: Exception) {
        null
    }

    fun clear(ctx: Context, name: String) {
        try {
            File(dir(ctx), name).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * 开始下载。[onProgress] 在主线程回调（约每 200ms 一次）。
     * 下载完成后 [onDone] 给出文件路径。
     */
    fun start(
        ctx: Context,
        url: String,
        fileName: String,
        expectedSize: Long,
        onProgress: (Progress) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ): Task {
        val flag = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val app = ctx.applicationContext
        val target = File(dir(app), fileName)

        Thread {
            var conn: HttpURLConnection? = null
            var out: java.io.OutputStream? = null
            try {
                if (target.exists()) target.delete()
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AirMouse-Downloader")
                }
                val code = conn.responseCode
                if (code != 200) throw RuntimeException("HTTP $code")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                Diag.log("DL", "开始下载 $url (${total} B)")

                conn.inputStream.use { input ->
                    out = target.outputStream()
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastReport = 0L
                    var lastBytes = 0L
                    val t0 = System.currentTimeMillis()
                    while (true) {
                        if (flag.get()) throw Cancelled()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out!!.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 200) {
                            val dt = (now - t0).coerceAtLeast(1)
                            val speed = got * 1000L / dt
                            val p = Progress(got, total, speed)
                            handler.post { onProgress(p) }
                            lastReport = now
                            lastBytes = got
                        }
                    }
                    out!!.flush()
                    if (total > 0 && got != total) throw RuntimeException("下载不完整：$got / $total")
                    val p = Progress(got, total, got)
                    handler.post {
                        onProgress(p)
                        onDone(target)
                    }
                    Diag.log("DL", "下载完成 ${target.name} ${target.length()} B")
                }
            } catch (e: Cancelled) {
                try {
                    target.delete()
                } catch (_: Exception) {
                }
                handler.post { onError("已取消") }
                Diag.log("DL", "已取消")
            } catch (e: Exception) {
                try {
                    target.delete()
                } catch (_: Exception) {
                }
                val why = e.javaClass.simpleName + ": " + (e.message ?: "")
                Diag.warn("DL", "✗ " + why)
                handler.post { onError(why) }
            } finally {
                try {
                    out?.close()
                } catch (_: Exception) {
                }
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()

        return Task(flag)
    }

    private class Cancelled : RuntimeException("cancelled")

    /** 人类可读的大小，例如 4.0 MB */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 人类可读的速度，例如 130 KB/s */
    fun speedText(bps: Long): String = when {
        bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / 1048576.0)
        bps >= 1024 -> String.format("%.0f KB/s", bps / 1024.0)
        else -> "$bps B/s"
    }
}

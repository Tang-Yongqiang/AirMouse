package com.airmouse.gyro

import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 网络模式：把动作以「一行一个 JSON」的协议发给 PC 端的 airmouse_server.py。
 * 好处是 PC 端用 SendInput 可以打任意 Unicode（中文），也能当普通鼠标用。
 * 协议：
 *   {"t":"m","dx":1,"dy":-2}          移动
 *   {"t":"b","b":1,"d":true}          按键（1左 2右 3中）
 *   {"t":"w","v":1}                   滚轮
 *   {"t":"k","code":40,"mods":0}      单个 HID 键码
 *   {"t":"s","text":"你好"}           整段文字
 */
class NetInput : InputSink {

    enum class State { IDLE, CONNECTING, CONNECTED, ERROR, FAILED }

    interface Listener {
        fun onNetState(state: State, message: String)
    }

    companion object {
        private const val TAG = "NetInput"
        const val DEFAULT_PORT = 47777
        private const val MAX_QUEUE = 4000

        /**
         * USB 直连靠 PC 端这条命令打通：adb reverse tcp:47777 tcp:47777
         * 打通后手机访问 127.0.0.1:47777 就等于访问电脑上的接收端，不需要同一网段、也不需要 WiFi。
         * 接收端启动时会自动执行这条命令（见 pc/airmouse_server.py 的 adb_reverse_watch）。
         */
        private const val LOOPBACK = "127.0.0.1"
    }

    var listener: Listener? = null

    @Volatile
    var host: String = ""

    @Volatile
    var port: Int = DEFAULT_PORT

    /** true = USB 模式（连 127.0.0.1）；false = WiFi 模式（连用户填的 IP） */
    @Volatile
    var usbMode: Boolean = false

    @Volatile
    var state: State = State.IDLE
        private set

    /** "USB" 或 "WiFi"，给界面显示「走的是哪条线」 */
    val transport: String get() = if (usbMode) "USB" else "WiFi"

    override val label: String get() = "网络模式"
    val isConnected: Boolean get() = state == State.CONNECTED

    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)
    private var worker: Thread? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var running = false

    /** 上一次连接失败的原因，用来避免每秒刷一条同样的日志 */
    private var diagLastError: String? = null

    /** 连续失败次数 / 允许的最大次数（<=0 表示不限） */
    @Volatile
    var failedAttempts = 0
        private set

    @Volatile
    var maxAttempts = 10

    /** 用户点「重连」：清零计数，线程还活着就让它接着试，已经停了就重新起一个 */
    fun retryNow() {
        failedAttempts = 0
        diagLastError = null
        if (running) {
            closeSocket()
            return
        }
        start()
    }

    fun start() {
        if (running) return
        val target = if (usbMode) LOOPBACK else host
        if (target.isBlank()) {
            publish(State.ERROR, "请先填写电脑的 IP 地址")
            return
        }
        running = true
        failedAttempts = 0
        queue.clear()
        val t = Thread({ loop() }, "airmouse-net")
        t.isDaemon = true
        worker = t
        t.start()
    }

    fun stop() {
        running = false
        queue.clear()
        closeSocket()
        worker?.interrupt()
        worker = null
        publish(State.IDLE, "已断开")
    }

    private fun loop() {
        val target = if (usbMode) LOOPBACK else host
        while (running) {
            if (socket == null) {
                if (!tryConnect()) {
                    // 连续失败到上限就停下等人点「重连」，别在后台无限刷（耗电、刷日志、看着像卡死）
                    failedAttempts++
                    if (maxAttempts > 0 && failedAttempts >= maxAttempts) {
                        running = false
                        publish(
                            State.FAILED,
                            "连不上 $target，已试 $failedAttempts 次，已暂停自动重试 · 点这里重连"
                        )
                        Diag.warn("NET", "已停止重试（$failedAttempts 次）")
                        return
                    }
                    sleepQuiet(1500)
                    continue
                }
                failedAttempts = 0
            }
            try {
                val line = queue.poll(1, TimeUnit.SECONDS)
                if (line == null) continue
                val w = writer
                if (w == null) {
                    closeSocket()
                    continue
                }
                w.write(line)
                w.write("\n")
                w.flush()
                if (line.contains("\"t\":\"s\"")) Log.i(TAG, "wrote text line (${line.length} chars)")
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
                Diag.warn("NET", "发送失败: ${e.message}")
                publish(State.ERROR, "连接被中断，正在重连…")
                closeSocket()
            }
        }
    }

    /**
     * 读线程只为「发现断开」而存在：电脑端进程没了 / 网线拔了，这里会立刻读到 EOF，
     * 把状态改成重连中，而不是等到下一次发指令才发现。
     */
    private fun startReader(s: Socket) {
        val t = Thread({
            val buf = ByteArray(128)
            try {
                val ins = s.getInputStream()
                while (running && socket === s) {
                    if (ins.read(buf) < 0) break
                }
            } catch (_: Exception) {
            } finally {
                if (socket === s) {
                    closeSocket()
                    if (running) publish(State.ERROR, "电脑端断开了，正在重连…")
                }
            }
        }, "airmouse-net-read")
        t.isDaemon = true
        t.start()
    }

    private fun tryConnect(): Boolean {
        val target = if (usbMode) LOOPBACK else host
        publish(State.CONNECTING, if (usbMode) "正在连接 USB（$target:$port）…" else "正在连接 $target:$port …")
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(target, port), 2500)
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            // 断线期间攒下的旧指令没必要补发，否则一连上光标会突然跳一下
            queue.clear()
            startReader(s)
            Log.i(TAG, "connected to $target:$port usb=$usbMode")
            Diag.log("NET", "已连接 $target:$port" + if (usbMode) " (USB)" else "")
            publish(State.CONNECTED, "已连接 $target:$port")
            true
        } catch (e: Exception) {
            // 诊断日志里留一条：这类失败最常见，必须能看出是超时、被拒还是 DNS/路由问题
            val reason = e.javaClass.simpleName + ": " + (e.message ?: "")
            if (diagLastError != reason) {
                diagLastError = reason
                Diag.warn("NET", (if (usbMode) "USB" else "WiFi") + " 连不上 $target:$port · $reason")
            }
            if (usbMode) {
                publish(
                    State.ERROR,
                    "USB 连不上：确认数据线已插好、手机允许了「USB 调试」，电脑端接收端已启动"
                )
            } else {
                publish(State.ERROR, "连不上 $target:$port，请确认电脑端程序已启动、手机在同一 WiFi")
            }
            false
        }
    }

    private fun closeSocket() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        writer = null
        socket = null
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun push(json: String) {
        if (!running) return
        if (!queue.offer(json)) {
            // 队列满了说明链路卡住，丢掉旧的
            queue.poll()
            queue.offer(json)
        }
    }

    // ---------------- InputSink ----------------

    override fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        push(JSONObject().put("t", "m").put("dx", dx).put("dy", dy).toString())
    }

    override fun mouseButton(button: Int, down: Boolean) {
        push(JSONObject().put("t", "b").put("b", button).put("d", down).toString())
    }

    /**
     * 绝对定位（射线指向模式）。u/v 是 0..1 的屏幕归一化坐标；
     * 电脑端用 SendInput 的 MOUSEEVENTF_ABSOLUTE 直接摆到那一格。
     */
    override fun pointerAbs(u: Float, v: Float) {
        push(
            JSONObject()
                .put("t", "a")
                .put("u", (u.coerceIn(0f, 1f) * 10000f).roundToInt() / 10000.0)
                .put("v", (v.coerceIn(0f, 1f) * 10000f).roundToInt() / 10000.0)
                .toString()
        )
    }

    override fun wheel(notches: Int) {
        if (notches == 0) return
        push(JSONObject().put("t", "w").put("v", notches).toString())
    }

    override fun keyPress(hidKey: Int, mods: Int) {
        push(JSONObject().put("t", "k").put("code", hidKey).put("mods", mods).toString())
    }

    override fun typeText(text: String) {
        if (text.isEmpty()) return
        android.util.Log.i("NetInput", "typeText len=${text.length} connected=$isConnected")
        Diag.log("NET", "发送文字 ${text.length} 字 connected=$isConnected")
        push(JSONObject().put("t", "s").put("text", text).toString())
    }

    override fun close() {
        stop()
    }

    private fun publish(s: State, msg: String) {
        state = s
        listener?.onNetState(s, msg)
    }
}

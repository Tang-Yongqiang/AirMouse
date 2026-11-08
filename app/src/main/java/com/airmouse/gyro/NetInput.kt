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

        /**
         * 协议版本。只要「指令的写法」变了就 +1，两端不一致说明装的是不配套的版本，
         * 连上握手时会互相提醒（见 [onServerInfo]）。
         */
        const val PROTO = 1
    }

    /**
     * 连上后电脑端会回一条 `{"t":"hi","server":"1.1.8","proto":1}`，
     * 这里把它的版本号和协议号交给界面，用来提示「两端版本不配套」。
     */
    @Volatile
    var onServerInfo: ((serverVersion: String, proto: Int) -> Unit)? = null

    /** 界面启动时填进来，握手时告诉电脑端「我这边是哪一版」 */
    @Volatile
    var appVersion: String = ""

    @Volatile
    var appCode: Long = 0

    var listener: Listener? = null

    @Volatile
    var host: String = ""

    /**
     * 备用地址列表：电脑有多块网卡（局域网 / 自家热点 / VPN / WSL）时，
     * 用户可以把窗口里那串地址整段粘进来，这里按顺序挨个试，连上哪个算哪个。
     * 为空时退回 [host] 单个地址。
     */
    @Volatile
    var hosts: List<String> = emptyList()

    /** 当前正试到第几个地址（多地址时才用） */
    private var hostIndex = 0

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

    /** 上一次连上的时刻：用来判断「连上又被立刻踢掉」也算一次失败 */
    @Volatile
    private var connectedAt = 0L

    /** 最近一次失败的中文原因，直接拼进状态栏那行字 */
    @Volatile
    private var lastReason = ""

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
        val first = target()
        if (first.isBlank()) {
            publish(State.ERROR, "请先填写电脑的 IP 地址")
            return
        }
        running = true
        failedAttempts = 0
        hostIndex = 0
        queue.clear()
        val t = Thread({ loop() }, "airmouse-net")
        t.isDaemon = true
        worker = t
        t.start()
    }

    /** 这次该连哪个地址：USB 固定回环；WiFi 优先用备用列表，否则单地址 */
    private fun targets(): List<String> {
        if (usbMode) return listOf(LOOPBACK)
        if (hosts.isNotEmpty()) return hosts
        return if (host.isBlank()) emptyList() else listOf(host)
    }

    private fun target(): String {
        val t = targets()
        if (t.isEmpty()) return ""
        if (t.size == 1) return t[0]
        return t[hostIndex % t.size]
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
        while (running) {
            if (socket == null) {
                if (!tryConnect()) {
                    // 连续失败到上限就停下等人点「重连」，别在后台无限刷（耗电、刷日志、看着像卡死）
                    if (!registerFailure(target())) return
                    sleepQuiet(1500)
                    continue
                }
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
                closeSocket()
            }
        }
    }

    /**
     * 记一次失败：没到上限就把状态栏改成「正在重连 第 N/10 次」（不再显示「点这里重连」，
     * 因为这时候还在自动重试，点它没意义）；到上限才切到 FAILED 并返回 false 让循环退出。
     */
    private fun registerFailure(target: String): Boolean {
        failedAttempts++
        // 有多个候选地址就换下一个再试：用户把整串 IP 粘进来时，这里自动帮他挨个试
        val all = targets()
        if (all.size > 1) {
            hostIndex = (hostIndex + 1) % all.size
            Diag.warn("NET", "换下一个地址试：${target()}（共 ${all.size} 个）")
        }
        if (maxAttempts > 0 && failedAttempts >= maxAttempts) {
            running = false
            val where = if (all.size > 1) "（试过 ${all.size} 个地址）" else ""
            publish(
                State.FAILED,
                "连不上 $target$where，已试 $failedAttempts 次，已暂停自动重试 · 点这里重连"
            )
            Diag.warn("NET", "已停止重试（$failedAttempts 次）")
            return false
        }
        publish(State.CONNECTING, retryText())
        return true
    }

    /** 「第 3/10 次 · 连接被拒」——次数在标题里，这里只补原因，避免重复 */
    private fun retryText(): String {
        val nth = if (maxAttempts > 0) "第 ${failedAttempts + 1}/$maxAttempts 次" else "第 ${failedAttempts + 1} 次"
        return if (lastReason.isEmpty()) nth else "$nth · $lastReason"
    }

    /** 把异常翻成一句人话，状态栏放得下 */
    private fun shortReason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "连接被拒（电脑端没启动？）"
        is java.net.UnknownHostException -> "地址解析失败"
        is java.net.NoRouteToHostException -> "网络不可达"
        else -> (e.message ?: e.javaClass.simpleName).take(40)
    }

    /**
     * 读线程只为「发现断开」而存在：电脑端进程没了 / 网线拔了，这里会立刻读到 EOF，
     * 把状态改成重连中，而不是等到下一次发指令才发现。
     */
    private fun startReader(s: Socket) {
        val t = Thread({
            try {
                val ins = s.getInputStream()
                // 电脑端只会回很短的几行（现在的握手 hi），按行读即可；
                // readLine() 返回 null 就是对面关了，正好用来发现断开。
                val reader = java.io.BufferedReader(java.io.InputStreamReader(ins, Charsets.UTF_8))
                while (running && socket === s) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) handleServerLine(line)
                }
            } catch (_: Exception) {
            } finally {
                if (socket === s) {
                    // 连上不到 3 秒就被断开，和「压根连不上」一样算一次失败；
                    // 否则 USB 隧道在、电脑端没启动时会出现「连上→立刻断→重连」无限循环
                    val lived = System.currentTimeMillis() - connectedAt
                    closeSocket()
                    if (running) {
                        if (lived in 0..3000) {
                            if (!registerFailure(target())) return@Thread
                        } else {
                            failedAttempts = 0
                            publish(State.CONNECTING, "电脑端断开了，正在重连…")
                        }
                    }
                }
            }
        }, "airmouse-net-read")
        t.isDaemon = true
        t.start()
    }

    /** 电脑端下发的行（目前只有握手 hi），解析后交给界面做「版本不配套」提醒 */
    private fun handleServerLine(line: String) {
        try {
            val o = org.json.JSONObject(line)
            if (o.optString("t") != "hi") return
            val server = o.optString("server", "")
            val proto = o.optInt("proto", 0)
            Diag.log("NET", "电脑端 $server · 协议 $proto")
            if (proto != PROTO) {
                Diag.warn("NET", "协议不一致：手机端 $PROTO / 电脑端 $proto")
            }
            onServerInfo?.invoke(server, proto)
        } catch (_: Exception) {
        }
    }

    private fun tryConnect(): Boolean {
        val target = target()
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(target, port), 2500)
            socket = s
            connectedAt = System.currentTimeMillis()
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            // 断线期间攒下的旧指令没必要补发，否则一连上光标会突然跳一下
            queue.clear()
            startReader(s)
            // 主动报一下自己是谁：电脑端据此知道我这边装的是哪一版、协议对不对
            try {
                push(
                    org.json.JSONObject().put("t", "hi")
                        .put("app", appVersion)
                        .put("code", appCode)
                        .put("proto", PROTO)
                        .toString()
                )
            } catch (_: Exception) {
            }
            if (!usbMode) host = target
            val nth = if (targets().size > 1) "（第 ${hostIndex % targets().size + 1}/${targets().size} 个地址）" else ""
            Log.i(TAG, "connected to $target:$port usb=$usbMode")
            Diag.log("NET", "已连接 $target:$port" + if (usbMode) " (USB)" else nth)
            publish(State.CONNECTED, "已连接 $target:$port")
            true
        } catch (e: Exception) {
            // 诊断日志里留一条：这类失败最常见，必须能看出是超时、被拒还是 DNS/路由问题
            val reason = e.javaClass.simpleName + ": " + (e.message ?: "")
            if (diagLastError != reason) {
                diagLastError = reason
                Diag.warn("NET", (if (usbMode) "USB" else "WiFi") + " 连不上 $target:$port · $reason")
            }
            // 这里不直接 publish(ERROR)：还在自动重试，由 registerFailure() 统一显示「正在重连 第 N 次」
            lastReason = if (usbMode) "USB 没通（查数据线 / USB 调试授权）" else shortReason(e)
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

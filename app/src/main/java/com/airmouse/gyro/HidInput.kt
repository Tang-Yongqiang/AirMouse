package com.airmouse.gyro

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt
import java.util.concurrent.Executor

/**
 * 蓝牙 HID 设备端：把手机伪装成一个「鼠标 + 键盘」的复合 HID 设备。
 * 电脑端不需要装任何东西，配对之后就是一个普通蓝牙鼠标。
 */
class HidInput(private val context: Context) : InputSink {

    enum class State {
        INIT, NO_PERMISSION, NO_BLUETOOTH, BLUETOOTH_OFF,
        UNSUPPORTED, REGISTERING, READY, CONNECTED, ERROR
    }

    interface Listener {
        fun onHidState(state: State, message: String)
    }

    companion object {
        private const val TAG = "HidInput"

        /**
         * 蓝牙里显示的名字。带上机型，装在两台手机上时电脑那边能分清是谁。
         * SDP 里这个名字最常用，所以尽量短：AirMouse-23113RKC6C
         */
        val DEVICE_NAME: String by lazy {
            val model = try {
                Build.MODEL ?: ""
            } catch (e: Exception) {
                ""
            }
            if (model.isBlank()) "AirMouse" else "AirMouse-$model"
        }

        private const val REPORT_MOUSE = 1
        private const val REPORT_KEYBOARD = 2
        private const val REPORT_ABS = 3

        /**
         * 位移合并窗口（毫秒）。
         *
         * 实测（OPPO + Windows，合成位移 200px 看电脑端真正走了多少）：
         *   8ms → 36px（18%）  16ms → 76px（38%）  20ms → 161px（80%）
         *  24ms → 173px（86%） 32ms → 196px（98%）
         * 也就是说蓝牙 HID 这条链路每 30ms 左右才真正被主机取走一次，
         * 发得比这快只会互相盖掉 —— 光标于是「追不上手指」。
         * 32ms≈31Hz 是实测能把位移几乎完整送达的节奏。
         */
        private const val MOVE_FLUSH_MS = 32L

        /**
         * 实际发送间隔（毫秒）。默认 32ms，可以用 adb 改：
         * `adb shell am start -n com.airmouse.gyro/.MainActivity --ei flushms 16`
         * 用来验证「链路只能承载 N Hz，发太快会积压」这类问题。
         */
        var flushMs: Long = MOVE_FLUSH_MS
    }

    var listener: Listener? = null

    var state: State = State.INIT
        private set

    override val label: String get() = "蓝牙 HID"
    val isConnected: Boolean get() = state == State.CONNECTED

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { r -> main.post(r) }
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter

    private var hid: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private var registered = false
    private var proxyRequested = false
    private var buttons = 0
    private var pendingConnect: BluetoothDevice? = null

    // 移动合并：触摸屏采样率高的机器（比如 240Hz）每个事件都发一次报告会把蓝牙链路灌满，
    // 表现就是光标一卡一卡。这里攒 8ms 发一次（≈125Hz，正好是 Windows 常见的鼠标轮询率）。
    // 移动合并 + 独立发送线程：触摸事件只往这里堆 delta（不碰蓝牙），
    // 由 airmouse-hid-send 线程按固定 8ms 节奏发出去。
    private val sendLock = Any()
    private var pendX = 0
    private var pendY = 0
    private var wheelPend = 0
    private var buttonDirty = false
    private var senderRunning = false
    private var senderThread: Thread? = null

    // 绝对指针（射线指向模式）：坐标是 0..1 归一化，主机按 0..32767 摆放到屏幕上
    private var absMode = false
    private var absU = 0.5f
    private var absV = 0.5f
    private var absDirty = false
    private var absLogCount = 0

    // 诊断用：每 2 秒打一次「触摸事件到达率 / sendReport 发送耗时」，
    // 用来判断卡顿来自手机端处理不过来，还是蓝牙链路本身慢。
    private var statEv = 0
    private var statRep = 0
    private var statSumUs = 0L
    private var statMaxUs = 0L
    private var statGapMaxMs = 0L
    private var statWindowStart = 0L
    private var statLastEv = 0L

    val bluetoothAdapter: BluetoothAdapter? get() = adapter

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /** 打开 HID 服务并注册应用。可重复调用。 */
    fun start() {
        if (hid != null) {
            if (registered) {
                // 已注册：状态可能已经连上了，别拿「HID 就绪」把已连接的那行盖掉
                if (isConnected) publish(State.CONNECTED, "已连接 ${safeName(device)}")
                else publish(State.READY, "HID 就绪")
            } else {
                registerApp()
            }
            return
        }
        val a = adapter
        if (a == null) {
            publish(State.NO_BLUETOOTH, "本机没有蓝牙模块")
            return
        }
        if (!hasPermission()) {
            publish(State.NO_PERMISSION, "缺少蓝牙权限，请先授权")
            return
        }
        if (!a.isEnabled) {
            publish(State.BLUETOOTH_OFF, "蓝牙未开启")
            return
        }
        if (proxyRequested) return
        proxyRequested = true
        publish(State.REGISTERING, "正在打开 HID 服务…")

        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = proxy as BluetoothHidDevice
                Log.i(TAG, "HID proxy connected")
                registerApp()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = null
                registered = false
                proxyRequested = false
            }
        }, BluetoothProfile.HID_DEVICE)

        if (!ok) {
            proxyRequested = false
            publish(State.UNSUPPORTED, "系统拒绝提供 HID 服务")
            return
        }
        // 有些 ROM 没有 HID profile，超时兜底
        main.postDelayed({
            if (hid == null) publish(State.UNSUPPORTED, "机型不支持蓝牙 HID 设备模式")
        }, 4000L)
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val h = hid ?: return
        if (!hasPermission()) {
            publish(State.NO_PERMISSION, "缺少蓝牙权限，请先授权")
            return
        }
        publish(State.REGISTERING, "注册 HID 设备…")
        val sdp = BluetoothHidDeviceAppSdpSettings(
            DEVICE_NAME,
            "Gyro air mouse and keyboard",
            "AirMouse",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptor.COMBO
        )
        val ok = try {
            h.registerApp(sdp, null, null, executor, callback)
        } catch (e: SecurityException) {
            publish(State.NO_PERMISSION, "蓝牙权限被拒绝")
            return
        } catch (e: Exception) {
            publish(State.ERROR, "注册异常：${e.message}")
            return
        }
        Log.i(TAG, "registerApp -> $ok")
        if (!ok) publish(State.ERROR, "HID 注册失败")
    }

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidInput.registered = registered
            Log.i(TAG, "onAppStatusChanged registered=$registered")
            Diag.log("HID", if (registered) "registerApp 成功，等待电脑连接" else "registerApp 被取消")
            if (registered) {
                publish(State.READY, "HID 就绪")
                val p = pendingConnect
                if (p != null) {
                    pendingConnect = null
                    connectNow(p)
                }
            } else {
                publish(State.ERROR, "HID 注册被系统取消")
            }
        }

        override fun onConnectionStateChanged(dev: BluetoothDevice, st: Int) {
            Log.i(TAG, "onConnectionStateChanged ${safeName(dev)} -> $st")
            Diag.log("HID", "state ${safeName(dev)} -> " + when (st) {
                BluetoothProfile.STATE_CONNECTED -> "已连接"
                BluetoothProfile.STATE_DISCONNECTED -> "已断开"
                BluetoothProfile.STATE_CONNECTING -> "连接中"
                BluetoothProfile.STATE_DISCONNECTING -> "断开中"
                else -> "$st"
            })
            when (st) {
                BluetoothProfile.STATE_CONNECTED -> {
                    device = dev
                    synchronized(sendLock) {
                        buttons = 0
                        buttonDirty = false
                    }
                    ensureSender()
                    publish(State.CONNECTED, "已连接 ${safeName(dev)}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == dev) device = null
                    synchronized(sendLock) {
                        buttons = 0
                        pendX = 0
                        pendY = 0
                        wheelPend = 0
                        buttonDirty = false
                    }
                    publish(State.READY, "连接已断开")
                }
            }
        }

        override fun onGetReport(dev: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            try {
                hid?.replyReport(dev, type, id, ByteArray(bufferSize.coerceAtLeast(1)))
            } catch (e: Exception) {
                Log.w(TAG, "replyReport failed: ${e.message}")
            }
        }

        override fun onSetReport(dev: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            try {
                hid?.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (e: Exception) {
                Log.w(TAG, "reportError failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(dev: BluetoothDevice) {
        if (!registered) {
            pendingConnect = dev
            start()
            return
        }
        connectNow(dev)
    }

    @SuppressLint("MissingPermission")
    private fun connectNow(dev: BluetoothDevice) {
        val h = hid
        if (h == null) {
            pendingConnect = dev
            start()
            return
        }
        try {
            publish(State.REGISTERING, "正在连接 ${safeName(dev)}…")
            val ok = h.connect(dev)
            Log.i(TAG, "connect(${safeName(dev)}) -> $ok")
            Diag.log("HID", "发起连接 ${safeName(dev)} -> $ok")
            if (!ok) publish(State.ERROR, "发起连接失败，请确认电脑蓝牙已打开")
        } catch (e: SecurityException) {
            Diag.warn("HID", "蓝牙权限被拒绝")
            publish(State.NO_PERMISSION, "蓝牙权限被拒绝")
        }
    }

    @SuppressLint("MissingPermission")
    fun safeName(dev: BluetoothDevice?): String {
        if (dev == null) return "未知设备"
        return try {
            dev.name ?: dev.address
        } catch (e: SecurityException) {
            dev.address
        }
    }

    // ---------------- InputSink ----------------

    override fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val now = SystemClock.uptimeMillis()
        if (statLastEv != 0L) {
            val gap = now - statLastEv
            if (gap > statGapMaxMs) statGapMaxMs = gap
        }
        statLastEv = now
        statEv++
        synchronized(sendLock) {
            pendX += dx
            pendY += dy
        }
    }

    /**
     * 绝对定位。射线指向模式每算出一个新位置就调一次，由发送线程按 [flushMs] 的节奏落地。
     * 这里只记状态，不做 IO（会被传感器线程直接调用）。
     */
    override fun pointerAbs(u: Float, v: Float) {
        synchronized(sendLock) {
            absMode = true
            absU = if (u.isNaN()) 0.5f else u.coerceIn(0f, 1f)
            absV = if (v.isNaN()) 0.5f else v.coerceIn(0f, 1f)
            absDirty = true
        }
    }

    /** 切回相对模式时清掉绝对指针状态，并在下一次 tick 发一帧全零，避免按键卡住。 */
    fun setAbsolute(enabled: Boolean) {
        synchronized(sendLock) {
            absMode = enabled
            absDirty = true
        }
        Log.i(TAG, "pointer mode -> ${if (enabled) "absolute" else "relative"}")
    }

    /**
     * 发送线程：固定 [flushMs] 一拍，把攒下的位移/滚轮/按键状态发出去。
     * 不放在主线程有两个原因：① sendReport 是跨进程调用（实测单次 ~1.7ms），压在主线程会把触摸处理拖慢；
     * ② 主线程上 postDelayed(8ms) 实测只跑到 60Hz 上下，节奏被 UI 帧率带跑。
     */
    private fun senderLoop() {
        while (senderRunning) {
            try {
                Thread.sleep(flushMs)
            } catch (e: InterruptedException) {
                break
            }
            val x: Int
            val y: Int
            val btn: Int
            val wheelN: Int
            val dirty: Boolean
            val aMode: Boolean
            val au: Float
            val av: Float
            val aDirty: Boolean
            synchronized(sendLock) {
                x = pendX
                y = pendY
                pendX = 0
                pendY = 0
                btn = buttons
                wheelN = wheelPend
                wheelPend = 0
                dirty = buttonDirty
                buttonDirty = false
                aMode = absMode
                au = absU
                av = absV
                aDirty = absDirty
                absDirty = false
            }
            if (aMode) {
                // 绝对模式：位置、按键、滚轮全走 Report 3，避免两条指针集合互相打架
                if (aDirty || wheelN != 0 || dirty || x != 0 || y != 0) {
                    sendAbs(au, av, btn, wheelN)
                }
                continue
            }
            if (x != 0 || y != 0) {
                sendMove(x, y, btn)
                maybeLogStats()
            }
            if (wheelN != 0) sendWheel(wheelN, btn)
            if (dirty || aDirty) send(REPORT_MOUSE, byteArrayOf(btn.toByte(), 0, 0, 0))
        }
    }

    private fun ensureSender() {
        if (senderRunning) return
        senderRunning = true
        val t = Thread { senderLoop() }
        t.name = "airmouse-hid-send"
        t.isDaemon = true
        senderThread = t
        t.start()
    }

    /** 调试用：一句话说清「现在到底能不能发」。 */
    fun debugState(): String =
        "state=$state dev=${device?.address ?: "null"} proxy=${hid != null} sender=$senderRunning pend=$pendX,$pendY"

    /** 调试用：直接发一条 +5px 的位移，绕过所有上层逻辑。 */
    fun debugNudge(): Boolean {
        val ok = send(REPORT_MOUSE, byteArrayOf(0, 5, 0, 0))
        Log.i(TAG, "debugNudge ok=$ok ${debugState()}")
        return ok
    }

    private fun maybeLogStats() {
        val now = SystemClock.uptimeMillis()
        if (statWindowStart == 0L) {
            statWindowStart = now
            return
        }
        val elapsed = now - statWindowStart
        if (elapsed < 2000) return
        val secs = elapsed / 1000.0
        val avgUs = if (statRep > 0) statSumUs / statRep else 0L
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "move stats: ev=%.0f/s rep=%.0f/s sendAvg=%dus sendMax=%dus evGapMax=%dms",
                statEv / secs, statRep / secs, avgUs, statMaxUs, statGapMaxMs
            )
        )
        statEv = 0
        statRep = 0
        statSumUs = 0L
        statMaxUs = 0L
        statGapMaxMs = 0L
        statWindowStart = now
    }

    private fun sendMove(dx: Int, dy: Int, btn: Int) {
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val ok = send(REPORT_MOUSE, byteArrayOf(btn.toByte(), sx.toByte(), sy.toByte(), 0))
            val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
            statRep++
            statSumUs += us
            if (us > statMaxUs) statMaxUs = us
            if (!ok) return
            x -= sx
            y -= sy
        }
    }

    private fun sendWheel(notches: Int, btn: Int) {
        var n = notches
        while (n != 0) {
            val s = n.coerceIn(-127, 127)
            if (!send(REPORT_MOUSE, byteArrayOf(btn.toByte(), 0, 0, s.toByte()))) return
            n -= s
        }
    }

    /**
     * 绝对指针报告（Report 3）：[按键, X_lo, X_hi, Y_lo, Y_hi, 滚轮]，X/Y 是 0..32767。
     * 主机（Windows）收到后直接把光标摆到对应比例的位置，不受指针加速影响。
     */
    private fun sendAbs(u: Float, v: Float, btn: Int, wheel: Int) {
        val x = (u * 32767f).roundToInt().coerceIn(0, 32767)
        val y = (v * 32767f).roundToInt().coerceIn(0, 32767)
        val data = byteArrayOf(
            btn.toByte(),
            (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
        val t0 = SystemClock.elapsedRealtimeNanos()
        val ok = send(REPORT_ABS, data)
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        if (absLogCount++ % 20 == 0) {
            Log.d(TAG, "abs send u=${"%.3f".format(java.util.Locale.US, u)} v=${
                "%.3f".format(java.util.Locale.US, v)
            } x=$x y=$y ok=$ok ${us}us")
        }
        statRep++
        statSumUs += us
        if (us > statMaxUs) statMaxUs = us
        if (!ok) return
        statEv++
        maybeLogStats()
    }

    override fun wheel(notches: Int) {
        if (notches == 0) return
        Log.d(TAG, "wheel $notches")
        synchronized(sendLock) { wheelPend += notches }
    }

    override fun mouseButton(button: Int, down: Boolean) {
        val bit = when (button) {
            InputSink.BTN_RIGHT -> 0x02
            InputSink.BTN_MIDDLE -> 0x04
            else -> 0x01
        }
        synchronized(sendLock) {
            buttons = if (down) buttons or bit else buttons and bit.inv()
            buttonDirty = true
        }
    }

    override fun keyPress(hidKey: Int, mods: Int) {
        val down = byteArrayOf(
            mods.toByte(), 0, hidKey.toByte(), 0, 0, 0, 0, 0
        )
        if (!send(REPORT_KEYBOARD, down)) return
        send(REPORT_KEYBOARD, ByteArray(8))
    }

    override fun typeText(text: String) {
        if (!registered) return
        Thread {
            for (ch in text) {
                when (ch) {
                    '\n', '\r' -> keyPress(HidKeys.ENTER, 0)
                    '\t' -> keyPress(HidKeys.TAB, 0)
                    else -> {
                        val k = HidKeys.charKey(ch) ?: continue
                        keyPress(k.first, k.second)
                    }
                }
                Thread.sleep(8)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun send(reportId: Int, data: ByteArray): Boolean {
        val h = hid ?: return false
        val d = device ?: return false
        return try {
            h.sendReport(d, reportId, data)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 「停用」用：主动断开和电脑的那条 HID 连接，但保留 App 注册。
     * 这样电脑侧立刻看不见这只鼠标（不会跟真鼠标抢指针），重新启用时直接 connect 回来即可。
     */
    @SuppressLint("MissingPermission")
    fun suspendHost() {
        val h = hid
        val d = device
        if (h != null && d != null) {
            try {
                h.disconnect(d)
                Log.i(TAG, "suspendHost: disconnect(${safeName(d)})")
            } catch (e: Exception) {
                Log.w(TAG, "suspendHost disconnect: ${e.message}")
            }
        }
        device = null
        synchronized(sendLock) {
            buttons = 0
            pendX = 0
            pendY = 0
            wheelPend = 0
            buttonDirty = false
        }
        if (registered) publish(State.READY, "已停用（不接管电脑）")
    }

    override fun close() {
        pendingConnect = null
        senderRunning = false
        senderThread?.interrupt()
        senderThread = null
        try {
            hid?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterApp: ${e.message}")
        }
        registered = false
        device = null
        synchronized(sendLock) {
            buttons = 0
            pendX = 0
            pendY = 0
            wheelPend = 0
            buttonDirty = false
        }
        state = State.INIT
    }

    private fun publish(s: State, msg: String) {
        state = s
        listener?.onHidState(s, msg)
    }
}

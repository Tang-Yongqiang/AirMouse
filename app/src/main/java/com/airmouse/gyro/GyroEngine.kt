package com.airmouse.gyro

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 陀螺仪 -> 光标位移。
 *
 * 解算思路（不依赖磁力计，竖拿、斜拿都成立）：
 *   up     = - 重力单位向量（设备坐标系里的"世界正上方"）
 *   yawRate   = ω · up      → 手腕左右转（像打方向盘 / 指哪看哪）
 *   pitchRate = ω.x         → 手机面上沿前后俯仰
 * 再经过 死区 → 幂曲线 → 增益，按 60Hz 积分成像素发出去。
 */
class GyroEngine(context: Context) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
    }

    companion object {
        private const val DEAD_ZONE = 0.022f          // rad/s
        private const val GAIN_PX_PER_RAD = 480f     // 1 rad/s 对应多少 px/s
        private const val CURVE_EXP = 1.35f
        private const val PIXELS_PER_NOTCH = 90f     // 每像素对应多少像素
        private const val BIAS_LERP = 0.03f
        private const val STILL_LIMIT = 0.05f
    }

    var listener: Listener? = null

    /** 0.2 ~ 2.0，来自灵敏度滑杆 */
    var sensitivity: Float = 1f
        set(value) {
            field = value.coerceIn(0.15f, 2.5f)
        }

    var running: Boolean = false
        private set

    /** 调试：把原始角速度/零偏打到 logcat（adb 里调参数用） */
    var debugLogging: Boolean = false

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravRaw: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val grav: Sensor? = gravRaw ?: accel
    private val useLowPass = gravRaw == null

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var wx = 0f
    @Volatile private var wy = 0f
    @Volatile private var wz = 0f

    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    @Volatile private var gz = 9.81f

    // 零偏
    private var bx = 0f
    private var by = 0f
    private var bz = 0f
    private var stillSince = 0L
    private var calibrated = false

    private var lastTs = 0L
    private var accX = 0f
    private var accY = 0f
    private var logTick = 0

    val hasGyroscope: Boolean get() = gyro != null
    val hasGravity: Boolean get() = grav != null

    fun start(): Boolean {
        if (running) return true
        val g = gyro ?: run {
            Diag.warn("GYRO", "没有陀螺仪传感器")
            return false
        }
        running = true
        lastTs = System.nanoTime()
        sm.registerListener(gyroListener, g, SensorManager.SENSOR_DELAY_GAME, 0)
        grav?.let { sm.registerListener(gravListener, it, SensorManager.SENSOR_DELAY_GAME, 0) }
        handler.postDelayed(tick, 16)
        Diag.log("GYRO", "开启相对模式（陀螺仪 ${g.name}）")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        sm.unregisterListener(gyroListener)
        sm.unregisterListener(gravListener)
        Diag.log("GYRO", "关闭相对模式")
    }

    /** 把当前姿态当作零偏基准（静止状态下调用） */
    fun recalibrate() {
        bx = wx
        by = wy
        bz = wz
        calibrated = true
        stillSince = 0L
        accX = 0f
        accY = 0f
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            wx = event.values[0]
            wy = event.values[1]
            wz = event.values[2]
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gravListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            var x = event.values[0]
            var y = event.values[1]
            var z = event.values[2]
            if (useLowPass) {
                val a = 0.15f
                gx += (x - gx) * a
                gy += (y - gy) * a
                gz += (z - gz) * a
                x = gx
                y = gy
                z = gz
            } else {
                gx = x
                gy = y
                gz = z
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = ((now - lastTs) / 1_000_000_000.0).coerceIn(0.001, 0.06).toFloat()
            lastTs = now
            step(dt)
            handler.postDelayed(this, 16)
        }
    }

    private fun step(dt: Float) {
        // 1. 去零偏
        val ax = wx - bx
        val ay = wy - by
        val az = wz - bz
        val mag = sqrt(ax * ax + ay * ay + az * az)

        // 2. 静止时缓慢学习零偏
        if (mag < STILL_LIMIT) {
            if (stillSince == 0L) stillSince = System.nanoTime()
            if (!calibrated && System.nanoTime() - stillSince > 600_000_000L) {
                bx = wx
                by = wy
                bz = wz
                calibrated = true
            } else if (calibrated) {
                bx += (wx - bx) * BIAS_LERP
                by += (wy - by) * BIAS_LERP
                bz += (wz - bz) * BIAS_LERP
            }
        } else {
            stillSince = 0L
        }

        // 3. 世界竖直方向（设备坐标）
        var ux = -gx
        var uy = -gy
        var uz = -gz
        val un = sqrt(ux * ux + uy * uy + uz * uz)
        if (un < 0.1f) return
        ux /= un
        uy /= un
        uz /= un

        val yawRate = ax * ux + ay * uy + az * uz
        val pitchRate = ax

        var rateX = curve(yawRate) * GAIN_PX_PER_RAD * sensitivity
        var rateY = -curve(pitchRate) * GAIN_PX_PER_RAD * sensitivity

        accX += rateX * dt
        accY += rateY * dt

        if (debugLogging && (logTick++ % 30) == 0) {
            Log.d(
                "AirMouse",
                "gyro w=(%.3f,%.3f,%.3f) b=(%.3f,%.3f,%.3f) yaw=%.3f pitch=%.3f rate=(%.1f,%.1f)".format(
                    wx, wy, wz, bx, by, bz, yawRate, pitchRate, rateX, rateY
                )
            )
        }

        val dx = accX.toInt()
        val dy = accY.toInt()
        if (dx != 0 || dy != 0) {
            accX -= dx
            accY -= dy
            listener?.onMove(dx, dy)
        }
    }

    /** 死区 + 幂曲线 */
    private fun curve(v: Float): Float {
        val a = abs(v)
        if (a <= DEAD_ZONE) return 0f
        val x = a - DEAD_ZONE
        return sign(v) * x.pow(CURVE_EXP)
    }

    /** 供设置页显示当前角速度 */
    fun currentRates(): Pair<Float, Float> = wx to wy
}

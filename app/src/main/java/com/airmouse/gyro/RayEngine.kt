package com.airmouse.gyro

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 「射线指向」模式：把手机当一支激光笔。
 *
 * 手机平放（屏幕朝上）、前端对着显示器，手机 +Y 轴（顶端朝外的方向）就是那条射线。
 * 转动手机让射线扫过屏幕，射线与屏幕平面的交点就是光标位置 —— 所以这是**绝对定位**，
 * 不像 [GyroEngine] 那样靠角速度积分（那个是相对移动，飘了要人手动拉回来）。
 *
 * 解算：
 *   d      = R · (0,1,0)          设备 +Y 轴在世界坐标系里的方向（R 来自旋转矢量传感器）
 *   yaw    = atan2(d·right0, d·fwd0)    相对「中心射线」的水平偏角
 *   pitch  = atan2(d·up0, |水平分量|)    竖直偏角
 *   u = 0.5 + tan(yaw)  · k              k = 0.5 / tan(spanDeg)
 *   v = 0.5 - tan(pitch)· k · aspect     屏幕比横向矮，同样的角度在竖直方向占更大比例
 *
 * 参考系（fwd0/right0/up0）在开启时抓一次 = 「此刻手指的方向就是屏幕中心」，
 * 之后随时可以点「校准」重新对准。用 tan 而不是线性角度，是因为这才是真实的透视投影：
 * 射线指向屏幕哪里，光标就落在哪里。
 */
class RayEngine(context: Context) {

    interface Listener {
        /** u/v 是 0..1 的归一化屏幕坐标，左上角是 (0,0) */
        fun onPoint(u: Float, v: Float)
    }

    var listener: Listener? = null

    /** 水平半角：从屏幕中心指到左右边缘需要转多少度。越小越灵敏 */
    var spanDeg: Float = 27f

    /** 屏幕宽高比，用来把水平角度换算成竖直方向的比例 */
    var aspect: Float = 16f / 9f

    /** 1€ 滤波器的静止截止频率（Hz）：越小越稳，但停下来时回正越慢 */
    var minCutoff: Float = 0.9f

    /** 1€ 滤波器对速度的敏感度：撞动时截止频率 = minCutoff + beta·|速度|，越大越跟手 */
    var beta: Float = 14f

    var debugLogging = false

    // ---- 两点校准：把原始角度坐标线性拉伸到 0..1 ----
    /** 做过两点校准（左上角 + 右下角）就为 true，此时走线性映射，滑杆的视场角不再影响结果 */
    var cornerCalibrated = false
        private set

    private var calU1 = 0f
    private var calV1 = 0f
    private var calU2 = 1f
    private var calV2 = 1f

    /** 最近一帧滤波后的原始坐标（还没做两点映射），给「采角」用 */
    private var rawU = 0.5f
    private var rawV = 0.5f
    private var rawValid = false

    var running = false
        private set

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val main = Handler(Looper.getMainLooper())

    /** 构造时就把传感器找好，这样 hasSensor 任何时候都可信（不要只在 start() 里赋值） */
    private val sensor: Sensor? = sm?.let {
        it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    // 参考坐标系（recenter() 后重新抓）
    private var fx = 0f
    private var fy = 0f
    private var fz = 1f
    private var rx = 1f
    private var ry = 0f
    private var rz = 0f
    private var ux = 0f
    private var uy = 0f
    private var uz = 1f
    private var hasRef = false

    private val filterU = OneEuroFilter(minCutoff, beta)
    private val filterV = OneEuroFilter(minCutoff, beta)
    private var lastTs = 0L
    private var lastSentU = -1f
    private var lastSentV = -1f
    private var logCounter = 0

    private val rot = FloatArray(9)

    private val events = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = handle(event)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    val hasSensor: Boolean get() = sensor != null

    fun start(): Boolean {
        if (running) return true
        val mgr = sm ?: return false
        val s = sensor ?: run {
            Log.w(TAG, "no rotation vector sensor")
            return false
        }
        // 没做两点校准时，进模式就把当前朝向重新对中心；做了两点校准就保留（否则开合键盘会丢校准）
        if (!cornerCalibrated) hasRef = false
        filterU.reset()
        filterV.reset()
        lastTs = 0L
        lastSentU = -1f
        lastSentV = -1f
        rawValid = false
        running = mgr.registerListener(events, s, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "ray start $running sensor=${s.name}")
        Diag.log("RAY", "开启射线模式 ok=$running 传感器=${s.name}")
        return running
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(events)
        Log.i(TAG, "ray stop")
        Diag.log("RAY", "关闭射线模式")
    }

    /** 把当前朝向当成屏幕中心。开启时自动调一次，也可以点「校准」手动重来。 */
    fun recenter() {
        hasRef = false
        filterU.reset()
        filterV.reset()
        lastTs = 0L
        rawValid = false
        // 参考系换了，之前采的两个角就没有意义了
        if (cornerCalibrated) {
            cornerCalibrated = false
            Log.i(TAG, "ray recenter (corner cal cleared)")
        } else {
            Log.i(TAG, "ray recenter")
        }
    }

    /** 取当前这一瞬的原始屏幕坐标 [u, v]，给两点校准「采角」用；还没收到传感器数据就返回 null */
    fun peekPoint(): FloatArray? = if (rawValid) floatArrayOf(rawU, rawV) else null

    /**
     * 用「左上角」和「右下角」两次采样建立线性映射：u1/v1 对应屏幕 (0,0)，u2/v2 对应 (1,1)。
     * 两点几乎重合（没真的转动手机）返回 false。
     *
     * 为什么线性是对的：射线打在垂直于中心方向的平面上时，平面坐标正比于 tan(偏角)，
     * 而原始 u/v 本身就是 tan 的线性函数，所以「减去角点再按跨度归一化」就是精确解，
     * 顺带把「人站偏了 / 视场角设错了」造成的缩放和偏移一起修掉。
     */
    fun applyCorners(u1: Float, v1: Float, u2: Float, v2: Float): Boolean {
        if (abs(u2 - u1) < 0.02f || abs(v2 - v1) < 0.02f) return false
        calU1 = u1
        calV1 = v1
        calU2 = u2
        calV2 = v2
        cornerCalibrated = true
        Log.i(
            TAG,
            String.format(java.util.Locale.US, "corner cal (%.3f,%.3f) -> (%.3f,%.3f)", u1, v1, u2, v2)
        )
        return true
    }

    /** 取消两点校准，回到「中心 + 视场角」的默认映射 */
    fun clearCorners() {
        cornerCalibrated = false
        Log.i(TAG, "corner cal cleared")
    }

    private fun handle(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        SensorManager.getRotationMatrixFromVector(rot, v)
        // 设备 +Y 轴在世界里的方向：世界向量 = R · 设备向量，(0,1,0) 取出来就是第二列
        var dx = rot[1]
        var dy = rot[4]
        var dz = rot[7]
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        if (n < 1e-4f) return
        dx /= n
        dy /= n
        dz /= n

        if (!hasRef) {
            if (!captureRef(dx, dy, dz)) return
        }

        val f = dx * fx + dy * fy + dz * fz          // 朝前（对着屏幕）的分量
        if (f <= 0.2f) return                         // 指向背后/几乎平行，别乱跳
        val right = dx * rx + dy * ry + dz * rz
        val up = dx * ux + dy * uy + dz * uz

        val yaw = atan2(right, f)
        val pitch = atan2(up, hypot(right, f))

        val k = 0.5f / tan(spanDeg * PI.toFloat() / 180f)
        val nu = 0.5f + tan(yaw) * k
        val nv = 0.5f - tan(pitch) * k * aspect

        // 1€ 滤波：静止时滤得狠（不抖），动起来截止频率随速度抬高（不粘手）
        val ts = event.timestamp
        val dt = if (lastTs == 0L) 1f / 50f else ((ts - lastTs) / 1e9f).coerceIn(0.004f, 0.1f)
        lastTs = ts
        filterU.minCutoff = minCutoff
        filterU.beta = beta
        filterV.minCutoff = minCutoff
        filterV.beta = beta
        val uRaw = filterU.filter(nu, dt)
        val vRaw = filterV.filter(nv, dt)
        rawU = uRaw
        rawV = vRaw
        rawValid = true

        var u = uRaw
        var vv = vRaw
        if (cornerCalibrated) {
            // 采角时的那两个方向对应屏幕左上角和右下角，中间线性插值
            u = (uRaw - calU1) / (calU2 - calU1)
            vv = (vRaw - calV1) / (calV2 - calV1)
        }
        u = u.coerceIn(0f, 1f)
        vv = vv.coerceIn(0f, 1f)

        if (lastSentU >= 0f && abs(u - lastSentU) < 0.0008f && abs(vv - lastSentV) < 0.0008f) return
        lastSentU = u
        lastSentV = vv
        main.post { listener?.onPoint(u, vv) }

        if (debugLogging && (logCounter++ % 30 == 0)) {
            Log.d(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "ray yaw=%.3f pitch=%.3f raw=(%.3f,%.3f) u=%.3f v=%.3f span=%.0f cal=%b",
                    yaw, pitch, uRaw, vRaw, u, vv, spanDeg, cornerCalibrated
                )
            )
        }
    }

    /** 用当前射线方向建立参考系：forward=射线，up=世界竖直方向，right=forward×up。 */
    private fun captureRef(dx: Float, dy: Float, dz: Float): Boolean {
        // 世界 up 近似 (0,0,1)（旋转矢量的世界系 Z 指向天空）
        val dotUp = dz
        if (abs(dotUp) > 0.94f) {
            // 手机几乎正对天花板/地面，构不出参考系，等下一个姿态
            return false
        }
        fx = dx
        fy = dy
        fz = dz
        // right = normalize(forward × up)
        var rxx = fy * 1f - fz * 0f
        var ryy = fz * 0f - fx * 1f
        var rzz = fx * 0f - fy * 0f
        val rn = sqrt(rxx * rxx + ryy * ryy + rzz * rzz)
        if (rn < 1e-4f) return false
        rxx /= rn
        ryy /= rn
        rzz /= rn
        rx = rxx
        ry = ryy
        rz = rzz
        // up' = right × forward（保证三轴正交）
        ux = ry * fz - rz * fy
        uy = rz * fx - rx * fz
        uz = rx * fy - ry * fx
        hasRef = true
        Log.i(TAG, String.format(java.util.Locale.US, "ray ref captured fwd=(%.2f,%.2f,%.2f)", fx, fy, fz))
        return true
    }

    companion object {
        private const val TAG = "RayEngine"
    }
}

/**
 * 1€ 滤波器（Casiez et al.）：截止频率随信号速度自适应。
 * 手停下时截止频率低 → 抖动被压掉；手快速扫动时截止频率高 → 不拖影。
 * 这就是「瞄准」类输入最常用的滤波，比固定系数的一阶低通好调。
 */
private class OneEuroFilter(
    var minCutoff: Float,
    var beta: Float,
    private val dCutoff: Float = 1f
) {
    private var xPrev = Float.NaN
    private var dxPrev = 0f

    fun reset() {
        xPrev = Float.NaN
        dxPrev = 0f
    }

    fun filter(x: Float, dt: Float): Float {
        if (xPrev.isNaN()) {
            xPrev = x
            return x
        }
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        dxPrev = aD * dx + (1f - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxPrev)
        val a = alpha(cutoff, dt)
        xPrev = a * x + (1f - a) * xPrev
        return xPrev
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

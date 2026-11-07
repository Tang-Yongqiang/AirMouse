package com.airmouse.gyro

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 触摸板：
 *   单指拖    → 移动光标
 *   轻点      → 左键单击
 *   长按 420ms → 按住左键拖动
 *   双指上下  → 滚轮
 *   双指轻点  → 右键
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onButton(button: Int, down: Boolean)
        fun onWheel(notches: Int)

        /** 总开关关着时碰触摸板：不产生任何手势，只让界面提示「先打开开关」 */
        fun onBlockedTouch() {}
    }

    var listener: Listener? = null

    /**
     * 总开关（主控）关着时置 false：触摸板完全不响应手势，
     * 只在按下那一刻回调 onBlockedTouch()，让界面提示先开开关。
     */
    var controlEnabled = true

    /**
     * 射线指向模式下由手机的姿态直接决定光标位置，触摸板的「移动光标」就没意义了；
     * 但点击/滚轮手势仍然能用（方便一边指一边点），所以只关移动。
     */
    var moveEnabled = true

    /**
     * 单指上下滑 = 滚轮。射线模式（moveEnabled == false）下打开：
     * 这时光标不靠触摸板移动，单指上下滑正好腾出来做滚轮，不用去够那条竖条。
     */
    var singleFingerScroll = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = 420L
    private val wheelStep = 52f

    /** 单指滚轮比双指更省距离（只有一根手指在滑动），步长小一点更跟手 */
    private val wheelStepSingle = 40f
    private val gain = 1.7f

    // ---- 单指滚轮的惯性（甩一下继续滚，像拨了一下真实滚轮）----
    private val flingHandler = Handler(Looper.getMainLooper())
    private val flingTickMs = 16L

    /** 每 16ms 衰减到 flingDecay：总滑行距离 ≈ 初速 × 16/(1-decay) px。由设置里的档位改。 */
    var flingDecay = 0.92f
    var flingEnabled = true

    private val flingStartVel = 0.35f   // px/ms，慢拖不甩
    private val flingStopVel = 0.10f
    private val flingMaxVel = 9f
    private var flingVel = 0f
    private var flinging = false
    private var lastMoveTime = 0L

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressed = false
    private var twoFinger = false
    private var twoFingerMoved = false
    private var wheelAcc = 0f
    private var lastWheelY = 0f

    // 亚像素余量：手感灵敏的机器一个事件可能不到 1px，直接 toInt() 会把慢速拖动全丢掉，
    // 表现出来就是「动得很慢时没反应、要拖快才动」。把不足 1px 的零头攒着下次一起算。
    private var accX = 0f
    private var accY = 0f

    // ---- 视觉反馈：手指位置的柔光点 + 四角描边 ----
    // 触摸板是一大块深色区域，手指按下去没有任何回馈会让人怀疑「到底有没有抓到」，
    // 所以触点处画一圈柔光 + 一个小圆点，抬起后淡出；四角圆弧常驻，按下时变亮。
    private val density = resources.displayMetrics.density
    private val accentColor = ContextCompat.getColor(context, R.color.accent)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }

    private var touchX = 0f
    private var touchY = 0f
    private var touchAlpha = 0f
    private var fadeAnim: ValueAnimator? = null

    private val longPressRunnable = Runnable {
        if (!moved && !twoFinger) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onButton(InputSink.BTN_LEFT, true)
        }
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            val l = listener
            if (l == null || !singleFingerScroll) {
                flinging = false
                return
            }
            wheelAcc += flingVel * flingTickMs
            flushSingleWheel(l)
            flingVel *= flingDecay
            if (abs(flingVel) >= flingStopVel) {
                flingHandler.postDelayed(this, flingTickMs)
            } else {
                flinging = false
            }
        }
    }

    /** 单指滚轮：把攒下的像素换成整格发出去 */
    private fun flushSingleWheel(l: Listener) {
        var notches = 0
        while (abs(wheelAcc) >= wheelStepSingle) {
            notches += if (wheelAcc > 0) 1 else -1
            wheelAcc -= if (wheelAcc > 0) wheelStepSingle else -wheelStepSingle
        }
        if (notches != 0) l.onWheel(notches)
    }

    /** 手指离开时如果还在快速滑动，就让它带着惯性继续滚一会儿 */
    fun startFling() {
        if (!flingEnabled || flinging) return
        val v = flingVel.coerceIn(-flingMaxVel, flingMaxVel)
        if (abs(v) < flingStartVel) return
        flingVel = v
        flinging = true
        flingHandler.postDelayed(flingRunnable, flingTickMs)
    }

    /** 新手势开始、切换模式、控件销毁时都要停掉惯性 */
    fun stopFling() {
        flinging = false
        flingVel = 0f
        flingHandler.removeCallbacks(flingRunnable)
    }

    // ---------------- 触点反馈 ----------------

    private fun startTouch(x: Float, y: Float) {
        fadeAnim?.cancel()
        touchX = x
        touchY = y
        touchAlpha = 1f
        invalidate()
    }

    private fun moveTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
        invalidate()
    }

    private fun endTouch() {
        if (touchAlpha <= 0f) return
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(touchAlpha, 0f).apply {
            duration = 220
            addUpdateListener {
                touchAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 四角圆弧：常驻淡描边，手指按下时变亮 —— 一眼看出「板子在待命」
        cornerPaint.alpha = if (touchAlpha > 0.02f) (40 + 150 * touchAlpha).toInt().coerceIn(0, 255) else 55
        val p = 1.2f * density
        val r = 20f * density
        val d = 2f * r
        canvas.drawArc(RectF(p, p, p + d, p + d), 180f, 90f, false, cornerPaint)
        canvas.drawArc(RectF(w - p - d, p, w - p, p + d), 270f, 90f, false, cornerPaint)
        canvas.drawArc(RectF(w - p - d, h - p - d, w - p, h - p), 0f, 90f, false, cornerPaint)
        canvas.drawArc(RectF(p, h - p - d, p + d, h - p), 90f, 90f, false, cornerPaint)

        if (touchAlpha > 0.01f) {
            glowPaint.alpha = (30 * touchAlpha).toInt()
            canvas.drawCircle(touchX, touchY, 46f * density, glowPaint)
            glowPaint.alpha = (58 * touchAlpha).toInt()
            canvas.drawCircle(touchX, touchY, 22f * density, glowPaint)
            ringPaint.alpha = (150 * touchAlpha).toInt()
            canvas.drawCircle(touchX, touchY, 13f * density, ringPaint)
            dotPaint.alpha = (210 * touchAlpha).toInt()
            canvas.drawCircle(touchX, touchY, 5.5f * density, dotPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = listener ?: return false
        if (!isEnabled) return false
        if (!controlEnabled) {
            // 停用状态：不接受任何手势，只提示一次，别让触点光晕假装在工作
            if (event.actionMasked == MotionEvent.ACTION_DOWN) l.onBlockedTouch()
            return true
        }


        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                startTouch(event.x, event.y)
                lastMoveTime = 0L
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                moved = false
                longPressed = false
                twoFinger = false
                twoFingerMoved = false
                wheelAcc = 0f
                accX = 0f
                accY = 0f
                postDelayed(longPressRunnable, longPressMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable)
                moveTouch(averageX(event), averageY(event))
                if (event.pointerCount >= 2) {
                    twoFinger = true
                    twoFingerMoved = false
                    wheelAcc = 0f
                    lastWheelY = averageY(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && event.pointerCount >= 2) {
                    moveTouch(averageX(event), averageY(event))
                    val y = averageY(event)
                    wheelAcc += y - lastWheelY
                    lastWheelY = y
                    var notches = 0
                    while (abs(wheelAcc) >= wheelStep) {
                        val dir = if (wheelAcc > 0) -1 else 1 // 手指下拖 = 向下滚
                        notches += dir
                        wheelAcc -= if (wheelAcc > 0) wheelStep else -wheelStep
                    }
                    if (notches != 0) {
                        twoFingerMoved = true
                        l.onWheel(notches)
                    }
                } else if (event.pointerCount == 1) {
                    moveTouch(event.x, event.y)
                    if (!moved) {
                        val dd = abs(event.x - downX) + abs(event.y - downY)
                        if (dd > touchSlop) {
                            moved = true
                            removeCallbacks(longPressRunnable)
                        }
                    }
                    // 长按后拖动 = 按住左键；只有「相对模式」要靠拖动移动光标
                    if (longPressed) {
                        if (moveEnabled) {
                            accX += (event.x - lastX) * gain
                            accY += (event.y - lastY) * gain
                            val ix = accX.toInt()
                            val iy = accY.toInt()
                            if (ix != 0 || iy != 0) {
                                accX -= ix
                                accY -= iy
                                l.onMove(ix, iy)
                            }
                        }
                    } else if (moved && singleFingerScroll) {
                        // 射线模式：单指上下滑直接当滚轮（左右方向忽略）
                        // 方向按「拖页面」的直觉来：手指往上推 = 页面往上走 = 滚轮向下
                        val now = SystemClock.uptimeMillis()
                        val dt = if (lastMoveTime == 0L) 16f
                        else (now - lastMoveTime).toFloat().coerceIn(4f, 60f)
                        lastMoveTime = now
                        val dy = event.y - lastY
                        wheelAcc += dy
                        // 速度用指数平滑，免得最后一下抖动把甩动速度算歪
                        flingVel = flingVel * 0.6f + (dy / dt) * 0.4f
                        flushSingleWheel(l)
                    } else if (moved && moveEnabled) {
                        accX += (event.x - lastX) * gain
                        accY += (event.y - lastY) * gain
                        val ix = accX.toInt()
                        val iy = accY.toInt()
                        if (ix != 0 || iy != 0) {
                            accX -= ix
                            accY -= iy
                            l.onMove(ix, iy)
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 从双指回到单指：以剩下那根手指重新定位，避免跳变
                val idx = event.actionIndex
                var rest = 0
                for (i in 0 until event.pointerCount) {
                    if (i != idx) rest = i
                }
                lastX = event.getX(rest)
                lastY = event.getY(rest)
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val dt = System.currentTimeMillis() - downTime
                if (longPressed) {
                    l.onButton(InputSink.BTN_LEFT, false)
                } else if (twoFinger) {
                    if (!twoFingerMoved && dt < 400) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        l.onButton(InputSink.BTN_RIGHT, true)
                        l.onButton(InputSink.BTN_RIGHT, false)
                    }
                } else if (!moved && dt < 400) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    l.onButton(InputSink.BTN_LEFT, true)
                    l.onButton(InputSink.BTN_LEFT, false)
                } else if (singleFingerScroll && moved && !longPressed) {
                    // 甩一把：手已经抬起，靠惯性继续滚
                    startFling()
                }
                twoFinger = false
                longPressed = false
                moved = false
                endTouch()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (longPressed) l.onButton(InputSink.BTN_LEFT, false)
                twoFinger = false
                longPressed = false
                moved = false
                endTouch()
            }
        }
        return true
    }

    private fun averageX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun averageY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        stopFling()
        fadeAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

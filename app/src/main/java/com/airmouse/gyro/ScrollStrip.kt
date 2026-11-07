package com.airmouse.gyro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 右侧细条：上下拖动做「精细滚轮」，比双指手势更容易控制幅度。
 */
class ScrollStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onWheel(notches: Int)
    }

    var listener: Listener? = null

    private val step = 26f
    private var lastY = 0f
    private var acc = 0f
    private var grabbing = false
    private var thumbY = -1f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_primary), 0x14)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.accent), 0x88)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_primary), 0x99)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_dim), 0xAA)
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = listener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabbing = true
                lastY = event.y
                acc = 0f
                thumbY = event.y
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!grabbing) return true
                val dy = event.y - lastY
                lastY = event.y
                thumbY = event.y
                acc += dy
                var notches = 0
                while (acc >= step) {
                    notches += 1
                    acc -= step
                }
                while (acc <= -step) {
                    notches -= 1
                    acc += step
                }
                if (notches != 0) {
                    Log.d("ScrollStrip", "wheel $notches (dy=$dy)")
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    l.onWheel(notches)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                grabbing = false
                thumbY = -1f
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // 上下箭头
        val cx = w / 2f
        val top = 18f
        val bottom = h - 18f
        val aw = w * 0.26f
        canvas.drawLine(cx - aw, top + aw, cx, top, arrowPaint)
        canvas.drawLine(cx + aw, top + aw, cx, top, arrowPaint)
        canvas.drawLine(cx - aw, bottom - aw, cx, bottom, arrowPaint)
        canvas.drawLine(cx + aw, bottom - aw, cx, bottom, arrowPaint)

        // 拖动时的拇指
        if (grabbing && thumbY > 0) {
            val th = h * 0.22f
            val cy = thumbY.coerceIn(th / 2, h - th / 2)
            rect.set(4f, cy - th / 2, w - 4f, cy + th / 2)
            canvas.drawRoundRect(rect, (w - 8f) / 2f, (w - 8f) / 2f, thumbPaint)
        } else {
            // 闲着时竖排写「滚轮」，免得看不出这条是干什么用的
            canvas.save()
            canvas.rotate(-90f, cx, h / 2f)
            val fm = labelPaint.fontMetrics
            canvas.drawText("滚轮", cx, h / 2f - (fm.ascent + fm.descent) / 2f, labelPaint)
            canvas.restore()
        }
    }
}

package com.airmouse.gyro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 手柄模式的手柄本体：整块画布自己画、自己分触摸，不依赖任何子 View。
 *
 * 为什么不用一堆 ImageButton + 自定义摇杆：手柄要的是「一根手指按下去马上响应，
 * 手指滑出去就是摇杆量」这种连续操作，用 View 拼会有一堆边距、焦点、点击延迟的坑。
 * 这里直接用 Canvas 画，坐标全按屏幕比例算，横屏随便一个尺寸都能摆得下。
 *
 * 对外只暴露一个回调：把「按了哪些键 + 两个摇杆 + 两个扳机」的整体状态交出去，
 * 电脑端原样塞进 ViGEm 的 XUSB_REPORT，中间不做任何解释。
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 状态回调：按键位 + 左摇杆 + 右摇杆 + 左扳机 + 右扳机（摇杆 -32768..32767，扳机 0..255） */
    fun interface Listener {
        fun onPad(buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int)
    }

    var listener: Listener? = null

    /** 震动指示：电脑端让手柄震的时候，摇杆外圈亮一下，手上有个反馈 */
    private var feedback = 0f

    fun pulse() {
        feedback = 1f
        invalidate()
    }

    companion object {
        // 和 XInput 的 XUSB_GAMEPAD_* 完全一致，电脑端拿来直接当 wButtons 用
        const val BTN_UP = 0x0001
        const val BTN_DOWN = 0x0002
        const val BTN_LEFT = 0x0004
        const val BTN_RIGHT = 0x0008
        const val BTN_START = 0x0010
        const val BTN_BACK = 0x0020
        const val BTN_LS = 0x0040
        const val BTN_RS = 0x0080
        const val BTN_LB = 0x0100
        const val BTN_RB = 0x0200
        const val BTN_A = 0x1000
        const val BTN_B = 0x2000
        const val BTN_X = 0x4000
        const val BTN_Y = 0x8000

        private const val DEADZONE = 0.14f
        // 触摸板的死区小一些：它是按位移算的，手指本来就很难一动不动
        private const val PAD_DEADZONE = 0.06f
        // 布局编辑里大小滑条的范围（相对默认尺寸的倍数）
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f
    }

    // ---------- 控件 ----------

    private enum class Kind { STICK, PAD, BUTTON, TRIGGER, DPAD }

    private class Control(val kind: Kind, val bit: Int = 0, var label: String = "") {
        val rect = RectF()
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pressed = false
        var value = 0f          // 扳机/摇杆用：0..1
        var nx = 0f             // 摇杆归一化坐标 -1..1
        var ny = 0f
        var pointerId = -1
        var tint = 0            // ABXY 各自的颜色（0 = 没指定）
        var key = ""            // 布局存档用的名字（空 = 不参与存档）
        var group = ""          // 同一个 group 的控件一起移动/缩放（十字键的四个方向）
        var round = false       // 圆形控件（摇杆/触摸板/ABXY）；矩形控件（肩键/扳机/十字键）保持 rr=0
        var baseW = 0f          // 默认宽高（量尺寸时记下来，滑条按它的倍数缩放）
        var baseH = 0f
    }

    private val controls = ArrayList<Control>()
    // 先给两个空壳：还没量到尺寸的时候（比如刚 setContentView 就 reset()）读它们不能炸
    private var lstick = Control(Kind.STICK)
    // 右摇杆换成了触摸板：手指落下去的地方就是摇杆中心，往哪儿滑就往哪儿拨
    private var touchpad = Control(Kind.PAD)
    private var emitPending = false

    // ---------- 布局（可编辑、可存档） ----------

    /** 存档里的一格：cx/fw, cy/fh, w/unit, h/unit */
    private val saved = HashMap<String, FloatArray>()

    /** 编辑模式：所有控件都能拖，双指能捏合缩放 */
    var editMode = false
        private set

    /** 编辑模式里松手 / 退出编辑时回调，把最新布局交出去（由 Activity 存进 Prefs） */
    var onLayoutChanged: ((String) -> Unit)? = null

    private var viewW = 0
    private var viewH = 0
    private var selected: Control? = null
    private var dragDx = 0f
    private var dragDy = 0f

    /**
     * 选中了某个控件（编辑态点一下）时回调当前倍数：>0 表示可以显示大小滑条，
     * 传 -1 表示没选中 / 已经退出编辑态，调用方据此把滑条收起来。
     */
    var onSelectionChanged: ((scale: Float) -> Unit)? = null

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isFakeBoldText = true
    }
    private val accent = 0xFF1B8FA8.toInt()
    private val idleFill = 0x22FFFFFF
    private val idleStroke = 0x59FFFFFF
    private val hotFill = 0x551B8FA8
    private val hotStroke = 0xCC1B8FA8.toInt()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---------- 尺寸变化时重排 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w, h)
        // 量到尺寸之后再把用户自己调过的布局盖上去（存档按比例存，所以换屏幕也不会跑偏）
        applySaved()
    }

    private fun rebuild(w: Int, h: Int) {
        viewW = w
        viewH = h
        controls.clear()
        val fw = w.toFloat()
        val fh = h.toFloat()
        val unit = min(fw, fh)

        // 扳机：左右各一条竖条，贴着屏幕边（真实手柄上它们就在这个位置）
        val trigW = unit * 0.075f
        val trigH = fh * 0.30f
        controls += Control(Kind.TRIGGER, 0, "LT").apply {
            rect.set(dp(10f), fh * 0.14f, dp(10f) + trigW, fh * 0.14f + trigH)
        }
        controls += Control(Kind.TRIGGER, 0, "RT").apply {
            rect.set(fw - dp(10f) - trigW, fh * 0.14f, fw - dp(10f), fh * 0.14f + trigH)
        }

        // 肩键：扳机右边/左边横条
        val shW = unit * 0.16f
        controls += Control(Kind.BUTTON, BTN_LB, "LB").apply {
            rect.set(dp(10f) + trigW + dp(8f), fh * 0.16f, dp(10f) + trigW + dp(8f) + shW, fh * 0.16f + fh * 0.11f)
        }
        controls += Control(Kind.BUTTON, BTN_RB, "RB").apply {
            rect.set(fw - dp(18f) - trigW - shW, fh * 0.16f, fw - dp(18f) - trigW, fh * 0.16f + fh * 0.11f)
        }

        // 左摇杆：左上方（和 Xbox 手柄一样，摇杆在上，十字键在下）
        val sr = unit * 0.145f
        lstick = Control(Kind.STICK).apply {
            cx = fw * 0.155f; cy = fh * 0.32f; r = sr
            rect.set(cx - sr, cy - sr, cx + sr, cy + sr)
        }
        controls += lstick

        // 右摇杆 → 触摸板：正方形，放在 ABXY 的左下（也是 Xbox 的相对位置）。
        // 手指落在哪就从哪起算，滑出一个半径 = 拨到底
        val padSide = unit * 0.36f
        touchpad = Control(Kind.PAD).apply {
            cx = fw * 0.70f
            cy = fh * 0.74f
            rect.set(cx - padSide / 2f, cy - padSide / 2f, cx + padSide / 2f, cy + padSide / 2f)
            r = padSide * 0.42f
        }
        controls += touchpad

        // ABXY：右上方菱形（下 A 右 B 左 X 上 Y），在触摸板的右上
        val br = unit * 0.068f
        val bx = fw * 0.845f
        val by = fh * 0.33f
        val gap = br * 1.85f
        fun diamond(bit: Int, label: String, dx: Float, dy: Float, color: Int) {
            controls += Control(Kind.BUTTON, bit, label).apply {
                cx = bx + dx; cy = by + dy; r = br
                rect.set(cx - br, cy - br, cx + br, cy + br)
                tint = color
            }
        }
        diamond(BTN_A, "A", 0f, gap, 0xFF4CAF50.toInt())
        diamond(BTN_B, "B", gap, 0f, 0xFFE5533D.toInt())
        diamond(BTN_X, "X", -gap, 0f, 0xFF3D7BE5.toInt())
        diamond(BTN_Y, "Y", 0f, -gap, 0xFFE5C13D.toInt())

        // 十字键：左下方（Xbox 上手柄上它就在左摇杆的右下侧），四向各占一边
        val dx = fw * 0.305f
        val dy = fh * 0.72f
        val arm = unit * 0.062f
        controls += Control(Kind.BUTTON, BTN_UP, "▲").apply {
            rect.set(dx - arm, dy - arm * 2f, dx + arm, dy)
        }
        controls += Control(Kind.BUTTON, BTN_DOWN, "▼").apply {
            rect.set(dx - arm, dy, dx + arm, dy + arm * 2f)
        }
        controls += Control(Kind.BUTTON, BTN_LEFT, "◀").apply {
            rect.set(dx - arm * 2f, dy - arm, dx, dy + arm)
        }
        controls += Control(Kind.BUTTON, BTN_RIGHT, "▶").apply {
            rect.set(dx, dy - arm, dx + arm * 2f, dy + arm)
        }

        // 中间：Back / Start（稍微大一点，和两边的手感一致）
        val mw = unit * 0.11f
        val mh = unit * 0.08f
        controls += Control(Kind.BUTTON, BTN_BACK, "⧉").apply {
            rect.set(fw * 0.45f - mw / 2f, fh * 0.105f, fw * 0.45f + mw / 2f, fh * 0.105f + mh)
        }
        controls += Control(Kind.BUTTON, BTN_START, "≡").apply {
            rect.set(fw * 0.55f - mw / 2f, fh * 0.105f, fw * 0.55f + mw / 2f, fh * 0.105f + mh)
        }

        // 存档用的名字：编辑布局时按这个名字记住每个控件的位置和大小。
        // 十字键四个方向归到一组（DPAD），拖/缩放的时候一起动，不然会被拆散。
        lstick.key = "LS"
        touchpad.key = "PAD"
        for (c in controls) {
            // 建的时候给了 r 的就是圆形控件；其余（肩键/扳机/十字键）一直是圆角矩形
            c.round = c.r > 0f
            // 默认尺寸记下来，滑条后面按「默认 × 倍数」缩放，复位也就是倍数回 1
            c.baseW = c.rect.width()
            c.baseH = c.rect.height()
            when (c.label) {
                "LT" -> c.key = "LT"
                "RT" -> c.key = "RT"
                "LB" -> c.key = "LB"
                "RB" -> c.key = "RB"
                "A" -> c.key = "A"
                "B" -> c.key = "B"
                "X" -> c.key = "X"
                "Y" -> c.key = "Y"
                "▲" -> { c.key = "UP"; c.group = "DPAD" }
                "▼" -> { c.key = "DOWN"; c.group = "DPAD" }
                "◀" -> { c.key = "LEFT"; c.group = "DPAD" }
                "▶" -> { c.key = "RIGHT"; c.group = "DPAD" }
                "⧉" -> c.key = "BACK"
                "≡" -> c.key = "START"
            }
        }
    }

    // ---------- 布局存档 ----------

    private fun r3(v: Float) = Math.round(v * 1000f) / 1000f

    /**
     * 导出当前布局：`KEY:cx,cy,w,h;KEY:...`，全部按屏幕比例存
     * （cx/宽、cy/高、w 和 h 按短边），换手机、转个方向都不会跑偏。
     */
    fun exportLayout(): String {
        val fw = viewW.toFloat()
        val fh = viewH.toFloat()
        if (fw <= 0f || fh <= 0f) return ""
        val unit = min(fw, fh)
        val sb = StringBuilder()
        for (c in controls) {
            if (c.key.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(c.key).append(':')
            sb.append(r3(c.rect.centerX() / fw)).append(',')
            sb.append(r3(c.rect.centerY() / fh)).append(',')
            sb.append(r3(c.rect.width() / unit)).append(',')
            sb.append(r3(c.rect.height() / unit))
        }
        return sb.toString()
    }

    /** 套用存下来的布局；传空串等于全用默认 */
    fun importLayout(s: String) {
        saved.clear()
        for (part in s.split(';')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val nums = part.substring(i + 1).split(',')
            if (nums.size != 4) continue
            val v = FloatArray(4)
            var ok = true
            for (k in 0 until 4) {
                val f = nums[k].trim().toFloatOrNull()
                if (f == null) { ok = false; break }
                v[k] = f
            }
            if (ok) saved[part.substring(0, i).trim()] = v
        }
        applySaved()
        invalidate()
    }

    /** 恢复出厂布局 */
    fun resetLayout() {
        saved.clear()
        if (viewW > 0 && viewH > 0) rebuild(viewW, viewH)
        invalidate()
        onLayoutChanged?.invoke(exportLayout())
    }

    private fun applySaved() {
        val fw = viewW.toFloat()
        val fh = viewH.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val unit = min(fw, fh)
        for (c in controls) {
            val v = saved[c.key] ?: continue
            if (c.key.isEmpty()) continue
            val w = (v[2] * unit).coerceAtLeast(dp(26f))
            val h = (v[3] * unit).coerceAtLeast(dp(26f))
            val cx = (v[0] * fw).coerceIn(0f, fw)
            val cy = (v[1] * fh).coerceIn(0f, fh)
            c.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            syncCenter(c)
        }
    }

    /**
     * 控件的圆心/半径跟着矩形走。
     * 只有圆形控件才更新 r —— 肩键、扳机、十字键那类圆角矩形靠 r == 0 区分，
     * 一视同仁地给 r 会把它们全画成圆（导入存档的时候就踩过这个坑）。
     */
    private fun syncCenter(c: Control) {
        c.cx = c.rect.centerX()
        c.cy = c.rect.centerY()
        if (!c.round) return
        c.r = if (c.kind == Kind.PAD) min(c.rect.width(), c.rect.height()) * 0.42f
        else min(c.rect.width(), c.rect.height()) / 2f
    }

    // ---------- 编辑模式 ----------

    fun setEditMode(on: Boolean) {
        // 进编辑态之前先松手发一帧：不然手指正按着 A 进编辑，电脑端会一直按着 A 不放
        if (on) reset()
        editMode = on
        if (!on) {
            selected = null
            onSelectionChanged?.invoke(-1f)
            onLayoutChanged?.invoke(exportLayout())
        }
        invalidate()
    }

    /** 按名字选中一个控件（调试/测试用，省得靠点） */
    fun selectForEdit(key: String) {
        val c = controls.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: return
        selected = c
        onSelectionChanged?.invoke(selectedScale())
        invalidate()
    }

    /** 当前选中控件相对默认大小的倍数（1 = 默认；没选中时返回 1） */
    fun selectedScale(): Float {
        val c = selected ?: return 1f
        if (c.baseW <= 0f) return 1f
        return (c.rect.width() / c.baseW).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * 按滑条的数值缩放当前选中的控件：以它自己的默认尺寸为基准，中心点不动。
     * 十字键是四个方向一组，整组等比缩放，不然会散架。
     */
    fun setSelectedScale(s: Float) {
        val c = selected ?: return
        val k = s.coerceIn(MIN_SCALE, MAX_SCALE)
        for (g in groupOf(c)) {
            if (g.baseW <= 0f || g.baseH <= 0f) continue
            val w = (g.baseW * k).coerceAtLeast(dp(20f))
            val h = (g.baseH * k).coerceAtLeast(dp(20f))
            val cx = g.rect.centerX()
            val cy = g.rect.centerY()
            g.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            syncCenter(g)
        }
        invalidate()
    }

    private fun groupOf(c: Control): List<Control> =
        if (c.group.isEmpty()) listOf(c) else controls.filter { it.group == c.group }

    /** 编辑模式下点到了谁：命中区都往外放 6dp，取面积最小的那个（免得大控件抢小的） */
    private fun pickEditable(x: Float, y: Float): Control? {
        var best: Control? = null
        var bestArea = Float.MAX_VALUE
        for (c in controls) {
            if (c.key.isEmpty()) continue
            val hit = if (c.kind == Kind.STICK || c.kind == Kind.PAD) {
                hypot(x - c.cx, y - c.cy) <= c.r * 1.15f || c.rect.contains(x, y)
            } else {
                val t = RectF(c.rect)
                t.inset(-dp(6f), -dp(6f))
                t.contains(x, y)
            }
            if (hit) {
                val area = c.rect.width() * c.rect.height()
                if (area < bestArea) { bestArea = area; best = c }
            }
        }
        return best
    }

    /**
     * 编辑态的手势：点一下选中（并把大小滑条的数值交出去），按住拖着走位。
     * 缩放交给下方滑条，所以这里不再处理双指 —— 拖动时手抖多落一根手指也不会突然变大变小。
     */
    private fun editTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = pickEditable(e.getX(), e.getY())
                selected = c
                if (c != null) {
                    dragDx = e.getX() - c.rect.centerX()
                    dragDy = e.getY() - c.rect.centerY()
                }
                // 点空白 = 取消选中，滑条跟着收起来
                onSelectionChanged?.invoke(if (c == null) -1f else selectedScale())
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val c = selected ?: return true
                val dx = (e.getX() - dragDx) - c.rect.centerX()
                val dy = (e.getY() - dragDy) - c.rect.centerY()
                for (g in groupOf(c)) {
                    g.rect.offset(dx, dy)
                    syncCenter(g)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onLayoutChanged?.invoke(exportLayout())
            }
        }
        return true
    }

    // ---------- 画 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (feedback > 0f) {
            feedback = (feedback - 0.06f).coerceAtLeast(0f)
            postInvalidateDelayed(40)
        }

        for (c in controls) {
            when (c.kind) {
                Kind.STICK -> drawStick(canvas, c)
                Kind.PAD -> drawPad(canvas, c)
                Kind.BUTTON -> drawButton(canvas, c)
                Kind.TRIGGER -> drawTrigger(canvas, c)
                Kind.DPAD -> {}
            }
        }
        if (editMode) drawEditOverlay(canvas)
    }

    /**
     * 编辑模式：每个控件套一个虚线框（选中的用主题色），名字写在框上方 ——
     * 写在框里会和按键本来的字（A/B/X/Y、方向箭头）糊在一起。
     * 十字键的四个方向归成一组，只写一次组名「DPAD」。
     */
    private fun drawEditOverlay(canvas: Canvas) {
        text.textSize = dp(10f)
        val named = HashSet<String>()
        for (c in controls) {
            if (c.key.isEmpty()) continue
            val sel = if (c.group.isEmpty()) c === selected else selected?.group == c.group
            dash.color = if (sel) accent else 0x66FFFFFF
            dash.strokeWidth = dp(if (sel) 2.4f else 1.2f)
            // 触摸板的可点区域是整个方块，所以虚线框画方框；其余圆形控件画圆
            if (c.round && c.kind != Kind.PAD) {
                canvas.drawCircle(c.cx, c.cy, c.r, dash)
            } else {
                canvas.drawRect(c.rect, dash)
            }

            if (c.group.isEmpty()) {
                text.color = if (sel) accent else 0x99FFFFFF.toInt()
                val ty = (c.rect.top - dp(4f)).coerceAtLeast(dp(13f))
                canvas.drawText(c.key, c.rect.centerX(), ty, text)
            } else if (named.add(c.group)) {
                val box = RectF()
                var first = true
                for (g in controls) {
                    if (g.group != c.group) continue
                    if (first) { box.set(g.rect); first = false } else box.union(g.rect)
                }
                text.color = if (sel) accent else 0x99FFFFFF.toInt()
                val ty = (box.top - dp(4f)).coerceAtLeast(dp(13f))
                canvas.drawText(c.group, box.centerX(), ty, text)
            }
        }
        text.color = 0xCCFFFFFF.toInt()
        text.textSize = dp(12f)
        canvas.drawText("拖动摆放 · 点一下用下方滑条调大小", viewW / 2f, dp(20f), text)
    }

    private fun drawStick(canvas: Canvas, c: Control) {
        fill.color = if (c.pointerId >= 0) 0x33FFFFFF else 0x14FFFFFF
        canvas.drawCircle(c.cx, c.cy, c.r, fill)
        stroke.color = if (feedback > 0f) withAlpha(accent, (60 + 195 * feedback).toInt()) else idleStroke
        canvas.drawCircle(c.cx, c.cy, c.r, stroke)

        // 摇杆帽：跟着手指走，最多到半径的 60%
        val kx = c.cx + c.nx * c.r * 0.60f
        val ky = c.cy + c.ny * c.r * 0.60f
        fill.color = if (c.pointerId >= 0) hotFill else 0x33FFFFFF
        canvas.drawCircle(kx, ky, c.r * 0.42f, fill)
        stroke.color = if (c.pointerId >= 0) hotStroke else idleStroke
        canvas.drawCircle(kx, ky, c.r * 0.42f, stroke)
    }

    /**
     * 触摸板：一块圆角区域，按下去的地方就是摇杆中心，往哪儿滑就往哪儿拨，松手回中。
     * 比起固定摇杆，好处是不用瞄着那个圈按 —— 右下角随手一放就能调视角，
     * 而且滑多远能自己控制：小位移是微调，滑满一个半径就是拨到底。
     */
    private fun drawPad(canvas: Canvas, c: Control) {
        val rad = dp(16f)
        fill.color = 0x0DFFFFFF
        canvas.drawRoundRect(c.rect, rad, rad, fill)
        stroke.color = if (c.pointerId >= 0) withAlpha(accent, 130) else idleStroke
        canvas.drawRoundRect(c.rect, rad, rad, stroke)

        if (c.pointerId < 0) {
            text.color = 0x59FFFFFF
            text.textSize = min(c.rect.width(), c.rect.height()) * 0.085f
            canvas.drawText(
                "右摇杆触摸板 · 按住滑动",
                c.rect.centerX(),
                c.rect.centerY() + text.textSize * 0.36f,
                text,
            )
            return
        }

        // 基准圈：告诉手指「拨到底大概要走这么远」
        stroke.color = withAlpha(accent, 90)
        canvas.drawCircle(c.cx, c.cy, c.r, stroke)

        // 摇杆帽在「手指相对按下点」的位移上，最多到半径
        val kx = c.cx + c.nx * c.r
        val ky = c.cy + c.ny * c.r
        stroke.color = withAlpha(accent, 160)
        canvas.drawLine(c.cx, c.cy, kx, ky, stroke)
        fill.color = hotFill
        canvas.drawCircle(kx, ky, c.r * 0.38f, fill)
        stroke.color = hotStroke
        canvas.drawCircle(kx, ky, c.r * 0.38f, stroke)
    }

    private fun drawButton(canvas: Canvas, c: Control) {
        val pressed = c.pressed
        fill.color = when {
            pressed -> hotFill
            c.tint != 0 -> withAlpha(c.tint, 40)
            else -> idleFill
        }
        stroke.color = when {
            pressed -> hotStroke
            c.tint != 0 -> withAlpha(c.tint, 150)
            else -> idleStroke
        }
        val rr = if (c.kind == Kind.BUTTON && c.r == 0f) 0f else c.r
        if (rr > 0f) {
            canvas.drawCircle(c.cx, c.cy, c.r, fill)
            canvas.drawCircle(c.cx, c.cy, c.r, stroke)
        } else {
            val rad = dp(10f)
            canvas.drawRoundRect(c.rect, rad, rad, fill)
            canvas.drawRoundRect(c.rect, rad, rad, stroke)
        }
        text.color = if (pressed) Color.WHITE else 0xCCFFFFFF.toInt()
        text.textSize = if (rr > 0f) c.r * 0.72f else min(c.rect.width(), c.rect.height()) * 0.42f
        val ty = if (rr > 0f) c.cy + text.textSize * 0.36f else c.rect.centerY() + text.textSize * 0.36f
        val tx = if (rr > 0f) c.cx else c.rect.centerX()
        canvas.drawText(c.label, tx, ty, text)
    }

    private fun drawTrigger(canvas: Canvas, c: Control) {
        val rad = dp(12f)
        fill.color = if (c.pressed) hotFill else idleFill
        canvas.drawRoundRect(c.rect, rad, rad, fill)
        // 按下进度：从下往上填
        if (c.value > 0f) {
            val h = c.rect.height() * c.value
            val sub = RectF(c.rect.left, c.rect.bottom - h, c.rect.right, c.rect.bottom)
            canvas.drawRoundRect(sub, rad, rad, fill.apply { color = 0xAA1B8FA8.toInt() })
        }
        stroke.color = if (c.pressed) hotStroke else idleStroke
        canvas.drawRoundRect(c.rect, rad, rad, stroke)
        text.color = 0xCCFFFFFF.toInt()
        text.textSize = c.rect.width() * 0.42f
        canvas.drawText(c.label, c.rect.centerX(), c.rect.centerY() + text.textSize * 0.36f, text)
    }

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

    // ---------- 触摸 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return editTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                grab(e.getX(i), e.getY(i), e.getPointerId(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val c = owner(id) ?: continue
                    if (c.kind == Kind.STICK) {
                        updateStick(c, e.getX(i), e.getY(i))
                    } else if (c.kind == Kind.PAD) {
                        updatePad(c, e.getX(i), e.getY(i))
                    } else if (c.kind == Kind.TRIGGER) {
                        // 扳机是模拟量：手指在条上越靠上，压得越深
                        val v = ((c.rect.bottom - e.getY(i)) / c.rect.height()).coerceIn(0f, 1f)
                        c.value = v
                        emit()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val c = owner(id)
                if (c != null) {
                    c.pointerId = -1
                    if (c.kind == Kind.STICK || c.kind == Kind.PAD) {
                        c.nx = 0f; c.ny = 0f
                        if (c.kind == Kind.PAD) { c.cx = c.rect.centerX(); c.cy = c.rect.centerY() }
                    } else if (c.kind == Kind.TRIGGER) {
                        c.value = 0f; c.pressed = false
                    } else {
                        c.pressed = false
                    }
                    emit()
                }
            }
        }
        invalidate()
        return true
    }

    private fun owner(id: Int): Control? = controls.firstOrNull { it.pointerId == id }

    private fun grab(x: Float, y: Float, id: Int) {
        // 先找按钮（区域小、要求精确），再找摇杆（区域大、按中了就吃住）
        var hit: Control? = null
        for (c in controls) {
            if (c.kind == Kind.BUTTON || c.kind == Kind.TRIGGER) {
                if (c.rect.contains(x, y)) { hit = c; break }
            }
        }
        if (hit == null) {
            for (c in controls) {
                if (c.kind == Kind.STICK && hypot(x - c.cx, y - c.cy) <= c.r * 1.15f) { hit = c; break }
            }
        }
        if (hit == null) {
            for (c in controls) {
                if (c.kind == Kind.PAD && c.rect.contains(x, y)) { hit = c; break }
            }
        }
        val c = hit ?: return
        c.pointerId = id
        if (c.kind == Kind.STICK) {
            updateStick(c, x, y)
        } else if (c.kind == Kind.PAD) {
            // 手指落在哪，哪就是中心：这一下只把基准点挪过去，摇杆还是回中的
            c.cx = x
            c.cy = y
            c.nx = 0f
            c.ny = 0f
            emit()
        } else if (c.kind == Kind.TRIGGER) {
            c.pressed = true
            c.value = ((c.rect.bottom - y) / c.rect.height()).coerceIn(0f, 1f)
            emit()
        } else {
            c.pressed = true
            emit()
        }
    }

    private fun updateStick(c: Control, x: Float, y: Float) {
        var dx = (x - c.cx) / c.r
        var dy = (y - c.cy) / c.r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        // 死区：小抖动不算操作，但出了死区要平滑地回到 0，不能跳
        val mag = hypot(dx, dy)
        if (mag < DEADZONE) {
            c.nx = 0f; c.ny = 0f
        } else {
            val scaled = (mag - DEADZONE) / (1f - DEADZONE)
            c.nx = dx / mag * scaled
            c.ny = dy / mag * scaled
        }
        emit()
    }

    private fun updatePad(c: Control, x: Float, y: Float) {
        var dx = (x - c.cx) / c.r
        var dy = (y - c.cy) / c.r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        val mag = hypot(dx, dy)
        if (mag < PAD_DEADZONE) {
            c.nx = 0f; c.ny = 0f
        } else {
            // 出了死区先重标定，再过一道 smoothstep：中心附近细，外圈照样能拨满 ——
            // 调视角要的是「按住一点点能慢慢转，按住很多能快转」
            val scaled = (mag - PAD_DEADZONE) / (1f - PAD_DEADZONE)
            val shaped = scaled * scaled * (3f - 2f * scaled)
            c.nx = dx / mag * shaped
            c.ny = dy / mag * shaped
        }
        emit()
    }

    private fun emit() {
        var buttons = 0
        for (c in controls) if (c.kind == Kind.BUTTON && c.pressed) buttons = buttons or c.bit
        val lt = (triggerValue("LT") * 255).toInt()
        val rt = (triggerValue("RT") * 255).toInt()
        listener?.onPad(
            buttons,
            (lstick.nx * 32767).toInt(),
            (-lstick.ny * 32767).toInt(),   // 屏幕 Y 向下，手柄 Y 向上
            (touchpad.nx * 32767).toInt(),
            (-touchpad.ny * 32767).toInt(),
            lt, rt,
        )
    }

    private fun triggerValue(label: String): Float =
        controls.firstOrNull { it.kind == Kind.TRIGGER && it.label == label }?.value ?: 0f

    /** 松手/退出时把状态清零，别让电脑端卡在「一直按着 A」 */
    fun reset() {
        for (c in controls) {
            c.pointerId = -1
            c.pressed = false
            c.value = 0f
            c.nx = 0f
            c.ny = 0f
            if (c.kind == Kind.PAD) { c.cx = c.rect.centerX(); c.cy = c.rect.centerY() }
        }
        emit()
        invalidate()
    }
}

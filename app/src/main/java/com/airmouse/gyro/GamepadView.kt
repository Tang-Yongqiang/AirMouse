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

        /**
         * 出厂布局（用户亲手摆的那一版，2026-11-14 固化）。
         * 格式 `KEY:cx/fw,cy/fh,w/unit,h/unit;…`：
         *   中心点按屏宽/屏高的比例，宽高按短边的比例 —— 换分辨率也按比例铺开。
         * 想调默认值：在 App 里摆好 → `--ez dumplayout true` 把导出的串贴到这里即可。
         */
        private const val DEFAULT_LAYOUT =
            "LT:0.042,0.29,0.075,0.3;RT:0.955,0.3,0.075,0.3;" +
                "LB:0.125,0.2,0.16,0.11;RB:0.872,0.2,0.16,0.11;" +
                "A:0.726,0.493,0.154,0.154;B:0.786,0.35,0.154,0.154;" +
                "X:0.667,0.35,0.154,0.154;Y:0.726,0.207,0.154,0.154;" +
                "UP:0.27,0.255,0.19,0.19;DOWN:0.27,0.445,0.19,0.19;" +
                "LEFT:0.23,0.35,0.19,0.19;RIGHT:0.309,0.35,0.19,0.19;" +
                "BACK:0.436,0.15,0.166,0.121;START:0.56,0.15,0.166,0.121"
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
        var baseCx = 0f         // 默认圆心（组内缩放时按「相对组心的距离」等比放大）
        var baseCy = 0f
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

    /** 调试用：把每次 emit 的摇杆值打到 logcat（--ez padsim 验证跟手时开） */
    var debugEmit = false

    /** 调试用：把编辑态拖拽的吸附计算打到 logcat（--ez gridsim 验证网格时开） */
    var debugEdit = false

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
        // 手柄铺满整屏时，别让系统的「边缘返回手势」把左右的 LT/RT 抢走
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, h))
        }
        rebuild(w, h)
        // 出厂布局：用户亲手摆的那一版（见 DEFAULT_LAYOUT），按比例铺开
        applyDefaultLayout()
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

        // 扳机：左右各一条竖条，贴着屏幕边（真实手柄上它们就在这个位置）。
        // 往里让 24dp：太贴边会被系统的「返回」手势抢走触摸。
        val trigW = unit * 0.075f
        val trigH = fh * 0.30f
        val edge = dp(24f)
        controls += Control(Kind.TRIGGER, 0, "LT").apply {
            rect.set(edge, fh * 0.14f, edge + trigW, fh * 0.14f + trigH)
        }
        controls += Control(Kind.TRIGGER, 0, "RT").apply {
            rect.set(fw - edge - trigW, fh * 0.14f, fw - edge, fh * 0.14f + trigH)
        }

        // 肩键：扳机右边/左边横条
        val shW = unit * 0.16f
        controls += Control(Kind.BUTTON, BTN_LB, "LB").apply {
            rect.set(edge + trigW + dp(8f), fh * 0.16f, edge + trigW + dp(8f) + shW, fh * 0.16f + fh * 0.11f)
        }
        controls += Control(Kind.BUTTON, BTN_RB, "RB").apply {
            rect.set(fw - edge - dp(8f) - trigW - shW, fh * 0.16f, fw - edge - dp(8f) - trigW, fh * 0.16f + fh * 0.11f)
        }

        // 左摇杆：不画 —— 整个左半边空白处都是它的区域，手指落下去才在落点长出来。
        // 区域给满，是为了「随手一按就在摇杆上」，不用瞄着某个圈去摸。
        val sr = unit * 0.145f
        lstick = Control(Kind.STICK).apply {
            r = sr
            rect.set(0f, 0f, fw * 0.5f, fh)
            cx = rect.centerX(); cy = rect.centerY()
        }
        controls += lstick

        // 右摇杆：同样不画，整个右半边空白处都是它（调视角用），落点即中心、推出去就转。
        touchpad = Control(Kind.PAD).apply {
            r = unit * 0.135f
            rect.set(fw * 0.5f, 0f, fw, fh)
            cx = rect.centerX(); cy = rect.centerY()
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
                // 四个键当成一个整体：调大小时整块区域等比放大，相对距离跟着走
                group = "ABXY"
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
            c.baseCx = c.rect.centerX()
            c.baseCy = c.rect.centerY()
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
            // 两个摇杆是「整半屏」的区域，位置/大小没有意义，不参与存档
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
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
        saved.putAll(parseLayout(s))
        applySaved()
        invalidate()
    }

    /** 恢复出厂布局 */
    fun resetLayout() {
        saved.clear()
        if (viewW > 0 && viewH > 0) {
            rebuild(viewW, viewH)
            applyDefaultLayout()
        }
        invalidate()
        onLayoutChanged?.invoke(exportLayout())
    }

    /** 把 "KEY:cx/fw,cy/fh,w/unit,h/unit;…" 解析成 key → 四个比例 */
    private fun parseLayout(s: String): HashMap<String, FloatArray> {
        val map = HashMap<String, FloatArray>()
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
            if (ok) map[part.substring(0, i).trim()] = v
        }
        return map
    }

    /**
     * 出厂布局：见上面的 DEFAULT_LAYOUT 常量。
     * 它和「用户自己调的」走同一套解析，只不过这套位置是随包发布的，
     * 所以尺寸/位置都按比例存 —— 换一台分辨率不同的手机也按比例铺开。
     */
    private fun applyDefaultLayout() {
        if (DEFAULT_LAYOUT.isEmpty()) return
        val fw = viewW.toFloat()
        val fh = viewH.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val unit = min(fw, fh)
        val map = parseLayout(DEFAULT_LAYOUT)
        for (c in controls) {
            if (c.key.isEmpty()) continue
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
            val v = map[c.key] ?: continue
            val w = (v[2] * unit).coerceAtLeast(dp(26f))
            val h = (v[3] * unit).coerceAtLeast(dp(26f))
            val cx = (v[0] * fw).coerceIn(0f, fw)
            val cy = (v[1] * fh).coerceIn(0f, fh)
            c.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            syncCenter(c)
            // 出厂位置就是「默认位置」：滑条缩放按它当 1.0×
            c.baseW = w
            c.baseH = h
            c.baseCx = cx
            c.baseCy = cy
        }
    }

    private fun applySaved() {
        val fw = viewW.toFloat()
        val fh = viewH.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val unit = min(fw, fh)
        for (c in controls) {
            val v = saved[c.key] ?: continue
            if (c.key.isEmpty()) continue
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
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
        // 两个摇杆的半径是固定的（决定推多远算拨到底），不跟着区域大小变
        if (c.kind == Kind.STICK || c.kind == Kind.PAD) return
        c.r = min(c.rect.width(), c.rect.height()) / 2f
    }

    // ---------- 编辑模式 ----------

    fun setEditMode(on: Boolean) {
        // 进编辑态之前先松手发一帧：不然手指正按着 A 进编辑，电脑端会一直按着 A 不放
        if (on) {
            reset()
            // 一进来就把所有控件对到网格上：默认布局本身不是照网格摆的，
            // 不先吸一遍的话「所有控件的中心都在交叉点」这句就不成立。
            snapAllToGrid()
        }
        editMode = on
        if (!on) {
            selected = null
            onSelectionChanged?.invoke(-1f)
            onLayoutChanged?.invoke(exportLayout())
        }
        invalidate()
    }

    /**
     * 把所有可编辑控件的中心吸到最近的网格交叉点。
     * 成组的（ABXY / 十字键）按组走：以组里第一个成员的中心为准，整组平移，相对位置不变。
     */
    /**
     * 吸网格的统一定义：**成组的用整组的几何中心**（四个键中心的中点，也就是外接框中心），
     * 单个控件用自己的中心。这样 ABXY / 十字键永远是「整块」对齐到交叉点，
     * 不会出现「按住的这颗对上了、别的三颗全歪着」。
     */
    private fun snapTargetCenter(c: Control): FloatArray {
        val box = groupBox(c)
        return floatArrayOf(box.centerX(), box.centerY())
    }

    /** 把控件（或它那一组）整体平移，让它的几何中心落在网格交叉点上 */
    private fun snapGroupToGrid(c: Control): Boolean {
        val gx = groupOf(c)
        val cur = snapTargetCenter(c)
        var dx = snapToGrid(cur[0], gridOriginX()) - cur[0]
        var dy = snapToGrid(cur[1], gridOriginY()) - cur[1]
        if (dx == 0f && dy == 0f) return false
        // 别把整组推出屏幕
        val box = groupBox(c)
        if (box.left + dx < 0f) dx = -box.left
        if (box.right + dx > viewW) dx = viewW - box.right
        if (box.top + dy < 0f) dy = -box.top
        if (box.bottom + dy > viewH) dy = viewH - box.bottom
        for (g in gx) {
            g.rect.offset(dx, dy)
            g.baseCx += dx
            g.baseCy += dy
            syncCenter(g)
        }
        return true
    }

    private fun snapAllToGrid() {
        if (viewW <= 0 || viewH <= 0) return
        val step = gridStep()
        if (step <= 0f) return

        // 先把「单元」列出来：单键一个单元，成组的整组一个单元
        val done = HashSet<Control>()
        val units = ArrayList<Control>()
        for (c in controls) {
            if (c.key.isEmpty()) continue
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
            if (done.contains(c)) continue
            units += c
            done.addAll(groupOf(c))
        }
        // 大块先摆，小块后摆 —— 小块还能绕着大块找空位
        units.sortByDescending { val b = groupBox(it); b.width() * b.height() }

        val placed = ArrayList<Control>()
        // 出厂布局里本来就叠着的（比如 RB 压在 RT 竖条上）不算「新压上去」，不拦它吸网格
        val origRects = HashMap<Control, RectF>()
        for (c in controls) origRects[c] = RectF(c.rect)
        // 候选偏移：从最近的格子开始一圈圈往外找（最多两格），先保证「落在交叉点上」，
        // 再保证「不压到别的键」—— 两个都做不到才留在原地。
        val cand = ArrayList<IntArray>()
        for (r in 0..2) {
            for (ox in -r..r) for (oy in -r..r) {
                if (maxOf(Math.abs(ox), Math.abs(oy)) != r) continue
                cand.add(intArrayOf(ox, oy))
            }
        }

        for (u in units) {
            val gx = groupOf(u)
            val box = groupBox(u)
            val cx = box.centerX()
            val cy = box.centerY()
            val baseX = snapToGrid(cx, gridOriginX())
            val baseY = snapToGrid(cy, gridOriginY())

            // 本来就和它叠着的控件，吸过去时不算冲突
            val tolerated = HashSet<Control>()
            for (g in gx) {
                val rg = origRects[g] ?: continue
                for (c in controls) {
                    if (c.key.isEmpty()) continue
                    if (gx.contains(c)) continue
                    val rc = origRects[c] ?: continue
                    if (RectF.intersects(rg, rc)) tolerated.add(c)
                }
            }

            var dx = 0f
            var dy = 0f
            var ok = false
            for (c0 in cand) {
                val ddx = baseX + c0[0] * step - cx
                val ddy = baseY + c0[1] * step - cy
                if (box.left + ddx < 0f || box.right + ddx > viewW) continue
                if (box.top + ddy < 0f || box.bottom + ddy > viewH) continue
                if (!collides(gx, ddx, ddy, placed, tolerated)) {
                    dx = ddx; dy = ddy; ok = true
                    break
                }
            }
            // 九个位置都塞不下就先不动它（一般不会发生），总比压在一起强
            if (!ok) {
                placed.addAll(gx)
                continue
            }
            if (dx != 0f || dy != 0f) {
                for (g in gx) {
                    g.rect.offset(dx, dy)
                    g.baseCx += dx
                    g.baseCy += dy
                    syncCenter(g)
                }
            }
            placed.addAll(gx)
        }
    }

    /** 把这一组按 (dx,dy) 预移一下，会不会压到已经摆好的那些（贴边擦过不算压，留 3dp 余量） */
    private fun collides(
        gx: List<Control>,
        dx: Float,
        dy: Float,
        placed: List<Control>,
        tolerated: Set<Control>
    ): Boolean {
        val pad = dp(3f)
        for (g in gx) {
            val r = RectF(g.rect)
            r.offset(dx, dy)
            r.inset(pad, pad)
            for (p in placed) {
                if (gx.contains(p) || tolerated.contains(p)) continue
                val q = RectF(p.rect)
                q.inset(pad, pad)
                if (RectF.intersects(r, q)) return true
            }
        }
        return false
    }

    /** 这一组（按外接矩形算）有没有压到组外的其它控件 */
    private fun overlapsOther(gx: List<Control>): Boolean {
        for (c in controls) {
            if (c.key.isEmpty()) continue
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
            if (gx.contains(c)) continue
            for (g in gx) if (g.rect.intersect(c.rect)) return true
        }
        return false
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
     * 按滑条的数值缩放当前选中的控件：以「默认尺寸」为基准，中心点不动。
     * 成组的东西（十字键四个方向、ABXY 四个键）整体缩放 —— 不是各自放大：
     * 那样中心不动、按键变大，很快就互相压在一起了。这里把整组的相对间距一起放大。
     */
    fun setSelectedScale(s: Float) {
        val c = selected ?: return
        val k = s.coerceIn(MIN_SCALE, MAX_SCALE)
        val members = groupOf(c)

        // 组心（没分组时就是它自己的中心）
        var gx = c.baseCx
        var gy = c.baseCy
        if (members.size > 1) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (g in members) {
                l = min(l, g.baseCx - g.baseW / 2f); t = min(t, g.baseCy - g.baseH / 2f)
                r = kotlin.math.max(r, g.baseCx + g.baseW / 2f)
                b = kotlin.math.max(b, g.baseCy + g.baseH / 2f)
            }
            gx = (l + r) / 2f
            gy = (t + b) / 2f
        }

        for (g in members) {
            if (g.baseW <= 0f || g.baseH <= 0f) continue
            val w = (g.baseW * k).coerceAtLeast(dp(20f))
            val h = (g.baseH * k).coerceAtLeast(dp(20f))
            // 中心相对组心的偏移也乘 k —— 组越大，键与键之间越开
            val cx = gx + (g.baseCx - gx) * k
            val cy = gy + (g.baseCy - gy) * k
            g.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            syncCenter(g)
        }

        // 放大后整组可能顶出屏幕（尤其贴着右/上边的那几个键），整组平移回来
        if (viewW > 0 && viewH > 0) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (g in members) {
                l = min(l, g.rect.left); t = min(t, g.rect.top)
                r = kotlin.math.max(r, g.rect.right); b = kotlin.math.max(b, g.rect.bottom)
            }
            var sx = 0f
            var sy = 0f
            if (l < 0f) sx = -l
            if (r > viewW) sx = min(sx, viewW - r)
            if (t < 0f) sy = -t
            if (b > viewH) sy = min(sy, viewH - b)
            if (sx != 0f || sy != 0f) {
                for (g in members) {
                    g.rect.offset(sx, sy)
                    g.baseCx += sx
                    g.baseCy += sy
                    syncCenter(g)
                }
            }
        }
        // 缩放后整组（按几何中心）吸回网格，保证它仍然落在交叉点上
        if (editMode) snapGroupToGrid(c)

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
            // 摇杆占的是整半屏，不参与拖拽/缩放（拖它等于拖整块背景）
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
            val hit = run {
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
     * 网格以**屏幕中线**为原点铺开（不是从左上角 0 开始铺）。
     * 理由：手柄层在 x = 屏宽/2 处画了一条「左右两半」的分界线，
     * 如果网格从 0 开始铺，这条线正好落在格子中间 —— 布局模式里就会看到
     * 分界线和它旁边那条网格线挨在一起，像两条线。以中线为原点，分界线本身就是一条网格线。
     */
    private fun gridOriginX(): Float = viewW / 2f
    private fun gridOriginY(): Float = viewH / 2f

    /** 网格步长：短边 20 等分（20×20 格），保证格子是正方形 */
    private fun gridStep(): Float {
        if (viewW <= 0 || viewH <= 0) return 0f
        return (min(viewW, viewH) / 20f).coerceAtLeast(dp(12f))
    }

    /** 把一个坐标吸到最近的网格线（原点在屏幕中线，所以减掉原点再取整） */
    private fun snapToGrid(v: Float, origin: Float): Float {
        val step = gridStep()
        if (step <= 0f) return v
        return origin + Math.round((v - origin) / step) * step
    }

    /**
     * 编辑态的手势：点一下选中（并把大小滑条的数值交出去），按住拖着走位（中心吸附网格）。
     * 缩放交给下方滑条，所以这里不再处理双指 —— 拖动时手抖多落一根手指也不会突然变大变小。
     */
    private fun editTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = pickEditable(e.getX(), e.getY())
                selected = c
                if (c != null) {
                    // 抓手记在「整组的几何中心」上：成组的东西按整块中心吸附，单键就是它自己
                    val cur = snapTargetCenter(c)
                    dragDx = e.getX() - cur[0]
                    dragDy = e.getY() - cur[1]
                }
                // 点空白 = 取消选中，滑条跟着收起来
                onSelectionChanged?.invoke(if (c == null) -1f else selectedScale())
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val c = selected ?: return true
                // 目标位置先吸附到网格交叉点，再按差值整体平移（按整组中心算）
                val cur = snapTargetCenter(c)
                val tx = snapToGrid((e.getX() - dragDx).coerceIn(0f, viewW.toFloat()), gridOriginX())
                val ty = snapToGrid((e.getY() - dragDy).coerceIn(0f, viewH.toFloat()), gridOriginY())
                val dx = tx - cur[0]
                val dy = ty - cur[1]
                if (debugEdit) {
                    android.util.Log.i("PAD", "edit move key=${c.key} center=" + cur[0] + "," + cur[1]
                        + " target=" + tx + "," + ty + " d=" + dx + "," + dy)
                }
                for (g in groupOf(c)) {
                    g.rect.offset(dx, dy)
                    // 默认位置也跟着挪，否则缩放时会「弹回」原位
                    g.baseCx += dx
                    g.baseCy += dy
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

    /** 一个控件（或它那一组）当前占的外接框 */
    private fun groupBox(c: Control): RectF {
        val box = RectF(c.rect)
        for (g in groupOf(c)) box.union(g.rect)
        return box
    }

    // ---------- 画 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (feedback > 0f) {
            feedback = (feedback - 0.06f).coerceAtLeast(0f)
            postInvalidateDelayed(40)
        }

        // 左右各半：中间一条很淡的分割线，告诉手指「这边是左摇杆，那边是视角」
        stroke.color = 0x1AFFFFFF
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(viewW / 2f, dp(8f), viewW / 2f, viewH - dp(8f), stroke)
        stroke.strokeWidth = dp(1.4f)

        val dpad = controls.filter { it.group == "DPAD" }
        for (c in controls) {
            when (c.kind) {
                // 两个摇杆平时什么都不画，按下去才在落点长出来
                Kind.STICK, Kind.PAD -> if (c.pointerId >= 0) drawStick(canvas, c)
                // 十字键的四个方向拼成一个整体画（分四块画会在中间叠出接缝）
                Kind.BUTTON -> if (c.group != "DPAD") drawButton(canvas, c)
                Kind.TRIGGER -> drawTrigger(canvas, c)
                Kind.DPAD -> {}
            }
        }
        if (dpad.isNotEmpty()) drawDpad(canvas, dpad)
        if (editMode) drawEditOverlay(canvas)
    }

    /**
     * 编辑模式：每个控件套一个虚线框（选中的用主题色），名字写在框上方 ——
     * 写在框里会和按键本来的字（A/B/X/Y、方向箭头）糊在一起。
     * 十字键的四个方向归成一组，只写一次组名「DPAD」。
     */
    /**
     * 编辑态：先铺一层网格，所有控件的中心都吸附到网格交叉点上 ——
     * 手工拖很难让两个键真的对齐，吸附之后「差一点点」就没有了。
     */
    private fun drawEditOverlay(canvas: Canvas) {
        // 网格：短边 20 等分，方格是正方形；原点在屏幕中线（和左右分界线重合）
        val step = gridStep()
        if (step > 0f) {
            stroke.color = 0x14FFFFFF
            stroke.strokeWidth = dp(1f)
            val ox = gridOriginX()
            val oy = gridOriginY()
            // 从中线往两边铺，中线那条线一定存在（就是左右分界线本身）
            var k = 0
            while (ox + k * step < viewW - 1f || ox - k * step > 0f) {
                val xr = ox + k * step
                val xl = ox - k * step
                if (xr < viewW - 1f) canvas.drawLine(xr, 0f, xr, viewH.toFloat(), stroke)
                if (k > 0 && xl > 0f) canvas.drawLine(xl, 0f, xl, viewH.toFloat(), stroke)
                k++
                if (k > 400) break
            }
            k = 0
            while (oy + k * step < viewH - 1f || oy - k * step > 0f) {
                val yb = oy + k * step
                val yt = oy - k * step
                if (yb < viewH - 1f) canvas.drawLine(0f, yb, viewW.toFloat(), yb, stroke)
                if (k > 0 && yt > 0f) canvas.drawLine(0f, yt, viewW.toFloat(), yt, stroke)
                k++
                if (k > 400) break
            }
            stroke.strokeWidth = dp(1.4f)
        }

        text.textSize = dp(10f)
        val named = HashSet<String>()
        for (c in controls) {
            if (c.key.isEmpty()) continue
            if (c.kind == Kind.STICK || c.kind == Kind.PAD) continue
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

    /**
     * 摇杆：只在按着的时候画 —— 圈在手指落点上，帽子按拨动方向偏出去，推过头圈会跟着走。
     * 左右两个摇杆画法一样，区别只有死区和手感曲线（见 updateStick）。
     */
    private fun drawStick(canvas: Canvas, c: Control) {
        fill.color = 0x22FFFFFF
        canvas.drawCircle(c.cx, c.cy, c.r, fill)
        stroke.color = if (feedback > 0f) withAlpha(accent, (60 + 195 * feedback).toInt()) else idleStroke
        canvas.drawCircle(c.cx, c.cy, c.r, stroke)

        // 摇杆帽：跟着手指走，最多到半径的 60%
        val kx = c.cx + c.nx * c.r * 0.60f
        val ky = c.cy + c.ny * c.r * 0.60f
        fill.color = hotFill
        canvas.drawCircle(kx, ky, c.r * 0.42f, fill)
        stroke.color = hotStroke
        canvas.drawCircle(kx, ky, c.r * 0.42f, stroke)
    }

    /**
     * 十字键：四个方向先并成一个「十」字整体再画 —— 分成四块圆角矩形画，
     * 中间那块会互相压出四道接缝，看着像四个键挤在一起（之前就是这样）。
     * 按下哪个方向，就在这个整体上把那一臂填成高亮色，边缘仍由整块的描边收口。
     */
    private fun drawDpad(canvas: Canvas, members: List<Control>) {
        val up = members.firstOrNull { it.label == "▲" } ?: return
        val down = members.firstOrNull { it.label == "▼" } ?: return
        val left = members.firstOrNull { it.label == "◀" } ?: return
        val right = members.firstOrNull { it.label == "▶" } ?: return

        val rad = dp(10f)
        // 横臂 = 左 ∪ 右，竖臂 = 上 ∪ 下；两条臂在中心重叠，用 union 合成一个轮廓
        val hor = android.graphics.Path().apply {
            addRoundRect(RectF(left.rect.left, left.rect.top, right.rect.right, left.rect.bottom),
                rad, rad, android.graphics.Path.Direction.CW)
        }
        val ver = android.graphics.Path().apply {
            addRoundRect(RectF(up.rect.left, up.rect.top, down.rect.right, down.rect.bottom),
                rad, rad, android.graphics.Path.Direction.CW)
        }
        val cross = android.graphics.Path().apply { op(hor, ver, android.graphics.Path.Op.UNION) }

        fill.color = idleFill
        canvas.drawPath(cross, fill)

        // 按下的那一臂在里面填高亮（裁剪到整块十字里，不会画到轮廓外面）
        for (c in members) {
            if (!c.pressed) continue
            canvas.save()
            canvas.clipPath(cross)
            fill.color = hotFill
            canvas.drawRoundRect(c.rect, rad, rad, fill)
            canvas.restore()
        }

        stroke.color = idleStroke
        canvas.drawPath(cross, stroke)

        // 四个方向箭头（字号按一条臂的宽度收着，别糊到中心去）
        text.textSize = min(up.rect.width(), left.rect.height()) * 0.38f
        for (c in members) {
            text.color = if (c.pressed) Color.WHITE else 0xCCFFFFFF.toInt()
            canvas.drawText(c.label, c.rect.centerX(),
                c.rect.centerY() + text.textSize * 0.36f, text)
        }
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
                    if (c.kind == Kind.STICK || c.kind == Kind.PAD) {
                        updateStick(c, e.getX(i), e.getY(i))
                    } else if (c.kind == Kind.TRIGGER) {
                        // 扳机是「按下就到底」，滑动不再改它（滑出去也不会误松）
                        if (!c.pressed) { c.pressed = true; c.value = 1f; emit() }
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
                        // 松手回到布局位置：摇杆中心可能跟着手指飘走过
                        c.cx = c.rect.centerX(); c.cy = c.rect.centerY()
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

    /**
     * 十字键的命中判定：在整块十字范围内，按「相对中心的主方向」取一条臂。
     * 用一个一个矩形去判的话，中心那一小块四个方向都算命中，谁排前面谁赢，手感是错的。
     */
    private fun hitDpad(x: Float, y: Float): Control? {
        val members = controls.filter { it.group == "DPAD" }
        if (members.isEmpty()) return null
        val box = RectF(members[0].rect)
        for (m in members) box.union(m.rect)
        if (!box.contains(x, y)) return null
        val dx = x - box.centerX()
        val dy = y - box.centerY()
        val label = if (abs(dx) > abs(dy)) {
            if (dx > 0f) "▶" else "◀"
        } else {
            if (dy > 0f) "▼" else "▲"
        }
        return members.firstOrNull { it.label == label }
    }

    private fun grab(x: Float, y: Float, id: Int) {
        // 顺序有讲究：先按钮/扳机（区域小、要求精确），再十字键，最后左右两块摇杆区域（整半屏，随便按）
        var hit: Control? = null
        for (c in controls) {
            if ((c.kind == Kind.BUTTON && c.group != "DPAD") || c.kind == Kind.TRIGGER) {
                if (c.rect.contains(x, y)) { hit = c; break }
            }
        }
        if (hit == null) hit = hitDpad(x, y)
        if (hit == null) {
            for (c in controls) {
                if ((c.kind == Kind.STICK || c.kind == Kind.PAD) && c.rect.contains(x, y)) { hit = c; break }
            }
        }
        val c = hit ?: return
        c.pointerId = id
        if (c.kind == Kind.STICK || c.kind == Kind.PAD) {
            // 手指落在哪，摇杆中心就在哪 —— 不用瞄着某个圈按，半屏里随便摸
            c.cx = x
            c.cy = y
            c.nx = 0f
            c.ny = 0f
            c.pressed = true
            emit()
        } else if (c.kind == Kind.TRIGGER) {
            // 手机上按扳机就是「按下去」：一碰就到底，松手回 0。
            // 以前按手指在条上的高度算模拟量，手指落在条的下半截就几乎没输出，点了像没反应。
            c.pressed = true
            c.value = 1f
            emit()
        } else {
            c.pressed = true
            emit()
        }
    }

    private fun updateStick(c: Control, x: Float, y: Float) {
        // 摇杆中心跟着手指走：推出去超过一个半径之后，基准点顺着手指方向挪，
        // 摇杆帽就被「钉」在圈边上（一直是拨到底），既能一直推着跑，往回拉又能立刻反向 ——
        // 固定中心的摇杆滑到边就没法再往前，手指还会顶出圈外。
        var dx = x - c.cx
        var dy = y - c.cy
        val dist = hypot(dx, dy)
        if (dist > c.r) {
            val keep = c.r / dist
            c.cx += dx * (1f - keep)
            c.cy += dy * (1f - keep)
            // 别让中心飘出画面（留一点边，圈不至于半个跑出去）
            val m = c.r * 0.4f
            c.cx = c.cx.coerceIn(m, (viewW - m).coerceAtLeast(m))
            c.cy = c.cy.coerceIn(m, (viewH - m).coerceAtLeast(m))
            dx = x - c.cx
            dy = y - c.cy
        }
        var ux = dx / c.r
        var uy = dy / c.r
        val len = hypot(ux, uy)
        if (len > 1f) { ux /= len; uy /= len }
        // 死区：小抖动不算操作，但出了死区要平滑地回到 0，不能跳。
        // 右边是调视角，死区给小一点、曲线前段更细（smoothstep），微调才跟手。
        val dead = if (c.kind == Kind.PAD) PAD_DEADZONE else DEADZONE
        val mag = hypot(ux, uy)
        if (mag < dead) {
            c.nx = 0f; c.ny = 0f
        } else {
            var scaled = (mag - dead) / (1f - dead)
            if (c.kind == Kind.PAD) scaled = scaled * scaled * (3f - 2f * scaled)
            c.nx = ux / mag * scaled
            c.ny = uy / mag * scaled
        }
        emit()
    }

    private fun emit() {
        var buttons = 0
        for (c in controls) if (c.kind == Kind.BUTTON && c.pressed) buttons = buttons or c.bit
        val lt = (triggerValue("LT") * 255).toInt()
        val rt = (triggerValue("RT") * 255).toInt()
        if (debugEmit) {
            android.util.Log.i("PAD", String.format(java.util.Locale.US,
                "emit b=%d lx=%d ly=%d ls(%.0f,%.0f) nx=%.2f ny=%.2f",
                buttons, (lstick.nx * 32767).toInt(), (-lstick.ny * 32767).toInt(),
                lstick.cx, lstick.cy, lstick.nx, lstick.ny))
        }
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
            if (c.kind == Kind.PAD || c.kind == Kind.STICK) { c.cx = c.rect.centerX(); c.cy = c.rect.centerY() }
        }
        emit()
        invalidate()
    }

    /** 调试用：任意控件当前的圆心和半径（"cx,cy,r"），布局被改过时也照样准 */
    fun centerOf(key: String): String {
        val c = controls.firstOrNull { it.key == key } ?: return "none"
        return String.format(java.util.Locale.US, "%.0f,%.0f,%.0f", c.rect.centerX(), c.rect.centerY(), c.r)
    }

    /** 调试用：网格步长 */
    fun gridStepForDebug(): Float = gridStep()

    /** 调试用：所有控件中心（"KEY:x,y;KEY:x,y;…"），用来检查是不是都落在网格交叉点上 */
    fun dumpCenters(): String {
        val sb = StringBuilder()
        for (c in controls) {
            if (c.key.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(c.key).append(':').append(Math.round(c.rect.centerX())).append(',')
                .append(Math.round(c.rect.centerY()))
        }
        return sb.toString()
    }

    /** 调试用：左摇杆当前的圆心和半径（"cx,cy,r"）；布局被改过时也照样准 */    fun stickCenter(): String {
        val c = controls.firstOrNull { it.key == "LS" } ?: return "none"
        return String.format(java.util.Locale.US, "%.0f,%.0f,%.0f", c.cx, c.cy, c.r)
    }
}

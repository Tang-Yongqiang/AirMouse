package com.airmouse.gyro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.core.content.ContextCompat
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 键盘面板，两种打字方式可以随时切：
 *
 *  - 直通键码：自绘 QWERTY，一个键直接对应一个 HID 键码，电脑收到的是真实按键。
 *  - 手机输入法：调出系统输入法（拼音/手写/表情都行），把成型的整段文字发给电脑。
 *    蓝牙 HID 只能送键码，所以中文要靠 WiFi/USB 模式才送得出去。
 *
 * 修饰键是「一次性」的：点一下变亮，下一个键带上修饰，然后自动熄灭。
 */
class KeyboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onKey(hidKey: Int, mods: Int)
        fun onImeRequest()
        fun onTypingModeChanged(ime: Boolean)
        fun onClose()
    }

    var listener: Listener? = null

    /** true = 手机输入法模式，false = 直通键码模式 */
    var imeMode = false
        set(value) {
            if (field == value) return
            field = value
            tintTabs()
            render()
        }

    private var pendingMods = 0
    private var symbolPage = false
    private val modKeys = mutableMapOf<Int, TextView>()

    private val body = LinearLayout(context)
    private lateinit var tabKeys: TextView
    private lateinit var tabIme: TextView


    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_panel)
        setPadding(dp(12), dp(10), dp(12), dp(12))

        // 顶栏
        val bar = LinearLayout(context)
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context)
        title.text = "键盘"
        title.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        bar.addView(title, LinearLayout.LayoutParams(0, dp(30), 1f))


        val close = keyView("收起", 0f) { listener?.onClose() }
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        bar.addView(close, LinearLayout.LayoutParams(dp(56), dp(30)))
        addView(bar, LinearLayout.LayoutParams(MATCH, dp(34)))

        // 打字方式切换
        val tabs = LinearLayout(context)
        tabs.orientation = HORIZONTAL
        tabKeys = keyView("直通键码") { switchMode(false) }
        tabKeys.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tabIme = keyView("手机输入法") { switchMode(true) }
        tabIme.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tabs.addView(tabKeys, tabLp())
        tabs.addView(tabIme, tabLp())
        addView(tabs, LinearLayout.LayoutParams(MATCH, dp(36)))

        body.orientation = VERTICAL
        addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        tintTabs()
        render()
    }

    fun show() {
        visibility = View.VISIBLE
        resetMods()
    }

    fun hide() {
        visibility = View.GONE
        resetMods()
    }

    fun toggle() {
        if (visibility == View.VISIBLE) hide() else show()
    }

    private fun switchMode(ime: Boolean) {
        if (imeMode != ime) {
            imeMode = ime
            listener?.onTypingModeChanged(ime)
        }
        // 点「手机输入法」这个 tab，意图就是要打字：直接把输入法调起来，省掉再点一次中间的按钮
        if (ime) listener?.onImeRequest()
    }

    private fun tintTabs() {
        val accent = ContextCompat.getColor(context, R.color.accent)
        val normal = ContextCompat.getColor(context, R.color.text_primary)
        tabKeys.setTextColor(if (imeMode) normal else accent)
        tabIme.setTextColor(if (imeMode) accent else normal)
        tabKeys.setBackgroundResource(if (imeMode) R.drawable.bg_key else R.drawable.bg_key_on)
        tabIme.setBackgroundResource(if (imeMode) R.drawable.bg_key_on else R.drawable.bg_key)

    }

    private fun render() {
        body.removeAllViews()
        modKeys.clear()
        if (imeMode) buildImePage() else if (!symbolPage) buildLetterPage() else buildSymbolPage()
    }

    // ---------- 手机输入法 ----------

    private fun buildImePage() {
        val tip = TextView(context)
        tip.text = "手机输入法会自己弹出来，打好字点「发送」就整段送到电脑。\n" +
            "蓝牙 HID 只能送按键码，中文要走 WiFi/USB 模式。"
        tip.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tip.setGravity(Gravity.CENTER)
        tip.setLineSpacing(dp(3).toFloat(), 1f)
        body.addView(tip, LinearLayout.LayoutParams(MATCH, WRAP))

        val quick = LinearLayout(context)
        quick.orientation = HORIZONTAL
        // 顺序按「发送完最可能接着按哪个」排：先 Esc/Tab 这类控制键，删除居中，回车放最右（最好按）
        quick.addView(keyView("Esc") { sendKey(HidKeys.ESC) }, keyLp())
        quick.addView(keyView("Tab") { sendKey(HidKeys.TAB) }, keyLp())
        quick.addView(keyView("⌫") { sendKey(HidKeys.BACKSPACE) }, keyLp())
        quick.addView(keyView("⏎") { sendKey(HidKeys.ENTER) }, keyLp())
        body.addView(quick, rowLp())
    }

    // ---------- 直通键码 ----------

    private fun buildLetterPage() {
        row(
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p"
        )
        row(
            "a", "s", "d", "f", "g", "h", "j", "k", "l"
        )
        val r3 = LinearLayout(context)
        r3.orientation = HORIZONTAL
        r3.addView(modKey("⇧", HidKeys.MOD_SHIFT), keyLp())
        for (c in "zxcvbnm") {
            r3.addView(keyView(c.toString()) { sendChar(c) }, keyLp())
        }
        r3.addView(keyView("⌫") { sendKey(HidKeys.BACKSPACE) }, keyLp())
        body.addView(r3, rowLp())

        val r4 = LinearLayout(context)
        r4.orientation = HORIZONTAL
        r4.addView(keyView("123") { symbolPage = true; render() }, keyLp(1.2f))
        r4.addView(modKey("Ctrl", HidKeys.MOD_CTRL), keyLp())
        r4.addView(modKey("Alt", HidKeys.MOD_ALT), keyLp())
        val space = keyView(" ") { sendKey(HidKeys.SPACE) }
        r4.addView(space, weightedLp(3.2f))
        r4.addView(keyView("Esc") { sendKey(HidKeys.ESC) }, keyLp())
        body.addView(r4, rowLp())

        val r5 = LinearLayout(context)
        r5.orientation = HORIZONTAL
        r5.addView(keyView("←") { sendKey(HidKeys.ARROW_LEFT) }, keyLp())
        r5.addView(keyView("↑") { sendKey(HidKeys.ARROW_UP) }, keyLp())
        r5.addView(keyView("↓") { sendKey(HidKeys.ARROW_DOWN) }, keyLp())
        r5.addView(keyView("→") { sendKey(HidKeys.ARROW_RIGHT) }, keyLp())
        r5.addView(keyView("Tab") { sendKey(HidKeys.TAB) }, keyLp(1.2f))
        r5.addView(keyView("⏎") { sendKey(HidKeys.ENTER) }, keyLp(1.6f))
        body.addView(r5, rowLp())
    }

    private fun buildSymbolPage() {
        row("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        row("-", "=", "[", "]", ";", "'", ",", ".", "/", "\\")
        row("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
        row("_", "+", "{", "}", ":", "\"", "<", ">", "?", "~")

        val r5 = LinearLayout(context)
        r5.orientation = HORIZONTAL
        r5.addView(keyView("abc") { symbolPage = false; render() }, keyLp(1.2f))
        r5.addView(modKey("Shift", HidKeys.MOD_SHIFT), keyLp(1.2f))
        r5.addView(modKey("Ctrl", HidKeys.MOD_CTRL), keyLp())
        val space = keyView(" ") { sendKey(HidKeys.SPACE) }
        r5.addView(space, weightedLp(2.4f))
        r5.addView(keyView("⏎") { sendKey(HidKeys.ENTER) }, keyLp(1.6f))
        body.addView(r5, rowLp())
    }

    private fun row(vararg labels: String) {
        val r = LinearLayout(context)
        r.orientation = HORIZONTAL
        for (lb in labels) {
            val c = lb[0]
            r.addView(keyView(lb) { sendChar(c) }, keyLp())
        }
        body.addView(r, rowLp())
    }

    // ---------- 发送 ----------

    private fun sendChar(c: Char) {
        val pair = HidKeys.charKey(c) ?: return
        sendKey(pair.first, pair.second)
    }

    private fun sendKey(usage: Int, extraMods: Int = 0) {
        val mods = pendingMods or extraMods
        listener?.onKey(usage, mods)
        if (pendingMods != 0) resetMods()
    }

    private fun resetMods() {
        pendingMods = 0
        for ((_, v) in modKeys) v.setBackgroundResource(R.drawable.bg_key)
    }

    private fun toggleMod(mod: Int) {
        pendingMods = pendingMods xor mod
        val v = modKeys[mod] ?: return
        if (pendingMods and mod != 0) {
            v.setBackgroundResource(R.drawable.bg_key_on)
        } else {
            v.setBackgroundResource(R.drawable.bg_key)
        }
    }

    // ---------- 视图工具 ----------

    private fun modKey(label: String, mod: Int): TextView {
        val v = keyView(label) { toggleMod(mod) }
        modKeys[mod] = v
        return v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun keyView(label: String, unused: Float = 0f, onClick: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        tv.setBackgroundResource(R.drawable.bg_key)
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun tabLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
        lp.setMargins(dp(2), dp(4), dp(2), dp(2))
        return lp
    }

    private fun keyLp(weight: Float = 1f): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, MATCH, weight)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        return lp
    }

    private fun weightedLp(weight: Float): LinearLayout.LayoutParams = keyLp(weight)

    private fun rowLp(): LinearLayout.LayoutParams {
        // 高度走资源：横屏（values-land）压矮一些，五排才放得下
        val h = resources.getDimensionPixelSize(R.dimen.key_row_h)
        val lp = LinearLayout.LayoutParams(MATCH, h)
        lp.setMargins(0, dp(2), 0, dp(2))
        return lp
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}

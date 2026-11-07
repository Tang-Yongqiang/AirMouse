package com.airmouse.gyro

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity(), HidInput.Listener, NetInput.Listener {

    companion object {
        private const val REQ_BT = 1001
        private const val KEY_UI_ENABLED = "uiEnabled"
        private const val KEY_UI_GYRO = "uiGyro"
        private const val KEY_UI_GYRO_SUSPENDED = "uiGyroSuspended"

        /** 陀螺仪按钮的三态 */
        const val GYRO_OFF = 0      // 不动光标（触摸板仍可用）
        const val GYRO_RATE = 1     // 相对：手腕角速度积分
        const val GYRO_RAY = 2      // 绝对：手机当激光笔指向屏幕
    }

    // 状态点的颜色：走资源，深浅色各一套（原来写死 ARGB，浅色下会看不清）
    private val COLOR_OK get() = ContextCompat.getColor(this, R.color.ok)
    private val COLOR_WARN get() = ContextCompat.getColor(this, R.color.danger)
    private val COLOR_MID get() = ContextCompat.getColor(this, R.color.accent)
    private val COLOR_IDLE get() = ContextCompat.getColor(this, R.color.text_dim)

    private val main = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var hid: HidInput
    private lateinit var net: NetInput
    private lateinit var gyro: GyroEngine
    private lateinit var ray: RayEngine

    /** 陀螺仪按钮的三种状态 */
    private var gyroMode = GYRO_OFF
    // 重建前最后一次的非关模式：onPause 会把 gyroMode 清零，保存状态时得靠它兜底
    private var lastNonOffGyro = GYRO_OFF
    // 横屏期间被「临时收起来」的模式：转回竖屏时用它恢复
    private var gyroSuspendedByLandscape = GYRO_OFF
    // onPause 会把陀螺仪停掉，停之前先留个快照给 onSaveInstanceState（它比 onPause 晚）
    private var gyroModeAtPause = GYRO_OFF

    /**
     * 总开关：false = 完全不接管电脑（蓝牙断开、网络停止、输入丢弃）。
     * 每次打开 App 默认是 false —— 免得随手开着就去用真鼠标，两个指针互相抢。
     */
    private var enabled = false

    private var mode = Prefs.MODE_BT
    private val sink: InputSink get() = if (mode == Prefs.MODE_BT) hid else net

    private lateinit var statusDot: View
    private lateinit var statusBox: View
    private var netState = NetInput.State.IDLE
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var btnModeMenu: MaterialButton
    private lateinit var btnGyroMenu: MaterialButton
    private lateinit var switchEnable: SwitchCompat
    private lateinit var btnCalib: TextView
    private lateinit var touchpadFrame: View
    private lateinit var rowButtons: View
    private lateinit var rowSens: View
    private lateinit var touchpad: TouchpadView
    private lateinit var scrollStrip: ScrollStrip
    private lateinit var keyboard: KeyboardPanelView
    private lateinit var kbScrim: View
    /** 关总开关时记下当时开着的陀螺仪模式，等下次连上电脑再恢复；用户在菜单里手动改过就作废 */
    private var gyroRestorePending = GYRO_OFF
    private lateinit var seekSens: SeekBar
    private lateinit var tvSens: TextView
    private lateinit var hintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppearance()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        prefs = Prefs(this)
        mode = prefs.mode
        Diag.logEnv(this, versionLabelFull())

        statusDot = findViewById(R.id.statusDot)
        statusBox = findViewById(R.id.statusBox)
        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)
        btnModeMenu = findViewById(R.id.btnModeMenu)
        btnGyroMenu = findViewById(R.id.btnGyroMenu)
        switchEnable = findViewById(R.id.switchEnable)
        btnCalib = findViewById(R.id.btnCalib)
        touchpadFrame = findViewById(R.id.touchpadFrame)
        rowButtons = findViewById(R.id.rowButtons)
        rowSens = findViewById(R.id.rowSens)
        touchpad = findViewById(R.id.touchpad)
        scrollStrip = findViewById(R.id.scrollStrip)
        keyboard = findViewById(R.id.keyboard)
        seekSens = findViewById(R.id.seekSens)
        tvSens = findViewById(R.id.tvSens)
        hintText = findViewById(R.id.hint)

        hid = HidInput(this)
        hid.listener = this
        net = NetInput()
        net.listener = this
        net.host = prefs.host
        net.port = prefs.port

        gyro = GyroEngine(this)
        gyro.listener = object : GyroEngine.Listener {
            override fun onMove(dx: Int, dy: Int) {
                sink.mouseMove(dx, dy)
            }
        }

        ray = RayEngine(this)
        ray.listener = object : RayEngine.Listener {
            override fun onPoint(u: Float, v: Float) {
                sink.pointerAbs(u, v)
            }
        }
        ray.debugLogging = intent?.getBooleanExtra("raylog", false) == true

        bindUi()
        paintGyroMenu()
        requestBluetoothPermissions()
        applyMode()
        applyDebugConfig(intent)

        // 调试用：adb 里 am start ... --ez gyro true --ez gyrolog true 可免点击开启陀螺仪并打点
        gyro.debugLogging = intent?.getBooleanExtra("gyrolog", false) == true
        if (intent?.getBooleanExtra("gyro", false) == true) {
            main.postDelayed({ if (gyroMode == GYRO_OFF) setGyroMode(GYRO_RATE) }, 400)
        }
        // 调试用：--ei gyromode 2 直接进射线模式（0 关 / 1 相对 / 2 射线）
        val gmExtra = intent?.getIntExtra("gyromode", -1) ?: -1
        if (gmExtra in 0..2) main.postDelayed({ setGyroMode(gmExtra) }, 400)
        applyCornerExtras(intent)
        // 调试用：--ez autotext true --es imetext "文字" 直接走一遍「输入法→电脑」的链路
        val autoText = intent?.getStringExtra("imetext")
        if (intent?.getBooleanExtra("autotext", false) == true && !autoText.isNullOrEmpty()) {
            main.postDelayed({ sendComposedText(autoText) }, 1200)
        }
        // 调试用：--ez keyboard true 直接展开键盘面板；--ez imemode true 直接切到输入法页
        if (intent?.getBooleanExtra("keyboard", false) == true) {
            val iem = intent?.getBooleanExtra("imemode", false) == true
            main.postDelayed({
                if (iem) keyboard.imeMode = true
                showKeyboardPanel()
                updateGyroForKeyboard(true)
            }, 500)
        }
        // 调试用：--ez imedlg true 直接弹出输入法对话框
        if (intent?.getBooleanExtra("imedlg", false) == true) {
            main.postDelayed({ showImeDialog() }, 900)
        }
        // 调试用：--ez diag true 直接打开诊断日志抽屉
        if (intent?.getBooleanExtra("updatecheck", false) == true) {
            main.postDelayed({ Updater.checkNow(this) { _ -> } }, 800)
        }
        // 调试用：--ez apkdl true 直接走一遍「应用内下载 → 安装器」（不发版也能测下载面板）
        if (intent?.getBooleanExtra("apkdl", false) == true) {
            val u = intent?.getStringExtra("apkurl")
                ?: "https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/releases/v2.33/AirMouse-2.33.apk"
            val vm = intent?.getStringExtra("apkver") ?: "2.34-测试"
            main.postDelayed({
                startApkDownload(Updater.Info(9999, vm, u, null, null))
            }, 900)
        }
        // 调试用：--ez apkinstall true 直接拿已下载的 APK 走一遍「拉起系统安装器」
        if (intent?.getBooleanExtra("apkinstall", false) == true) {
            main.postDelayed({
                val f = Downloader.latestApk(this)
                if (f == null) toast("还没有下载好的 APK") else {
                    toast("尝试安装 " + f.name)
                    Updater.installApk(this, f)?.let { why -> toast("打不开安装器：" + why) }
                }
            }, 900)
        }
        if (intent?.getBooleanExtra("settings", false) == true) {
            main.postDelayed({ showSettings() }, 600)
        }
        if (intent?.getBooleanExtra("diag", false) == true) {
            main.postDelayed({ showDiag() }, 900)
        }
        // 调试用：--ez diagsave true 直接把日志存到「下载」（验证写入权限/路径）
        if (intent?.getBooleanExtra("diagsave", false) == true) {
            main.postDelayed({ toast(Diag.saveToDownloads(this, Diag.dump())) }, 1200)
        }
        // 调试用：--ei flushms 16 改蓝牙报告的发送间隔（默认 8ms≈125Hz），验证链路能承载多少 Hz
        val flushMs = intent?.getIntExtra("flushms", 0) ?: 0
        if (flushMs > 0) {
            HidInput.flushMs = flushMs.toLong()
            Log.i("AirMouse", "flushMs -> $flushMs")
        }
        // 调试用：--ez autodrag true 让 App 自己按 120Hz 喂合成位移（画小圈，净位移为零），
        // 这样不用手指也能测「手机 → 电脑」这条链路到底跟不跟手。
        if (intent?.getBooleanExtra("autodrag", false) == true) {
            val dragMs = intent?.getIntExtra("dragms", 3000) ?: 3000
            val startDelay = intent?.getIntExtra("dragdelay", 2000) ?: 2000
            main.postDelayed({ startSyntheticDrag(dragMs) }, startDelay.toLong())
        }
        // 调试用：--ez enable true 直接打开总开关（省得手指点；MIUI 上 adb input 会被丢掉）
        if (intent?.getBooleanExtra("enable", false) == true) {
            main.postDelayed({ setEnabled(true, false) }, 300)
        }

        // 切主题/横竖屏会重建 Activity：把停用前开着的东西接回来（冷启动 savedInstanceState 为 null，仍是默认停用）
        if (savedInstanceState != null) {
            val wasGyro = savedInstanceState.getInt(KEY_UI_GYRO, GYRO_OFF)
            val wasSuspended = savedInstanceState.getInt(KEY_UI_GYRO_SUSPENDED, GYRO_OFF)
            if (isLandscape()) {
                // 转进横屏：把竖屏里的模式记成「横屏暂存」，横屏期间一律不开
                gyroSuspendedByLandscape = when {
                    wasSuspended != GYRO_OFF -> wasSuspended
                    wasGyro != GYRO_OFF -> wasGyro
                    else -> gyroSuspendedByLandscape
                }
                gyroRestorePending = GYRO_OFF
            } else {
                // 转回竖屏：横屏暂存的模式优先，其次才是重建前那个
                val pending = if (wasSuspended != GYRO_OFF) wasSuspended else wasGyro
                if (wasSuspended != GYRO_OFF) gyroSuspendedByLandscape = GYRO_OFF
                if (pending != GYRO_OFF) gyroRestorePending = pending
            }
            if (savedInstanceState.getBoolean(KEY_UI_ENABLED, false)) {
                setEnabled(true, false)   // announce=false：重建不该再弹「已启用」
            }
        }
        // 横屏只有「关」：进横屏就把陀螺仪收掉（模式已记进 gyroSuspendedByLandscape）
        if (isLandscape()) {
            gyroRestorePending = GYRO_OFF
            if (gyroMode != GYRO_OFF) setGyroMode(GYRO_OFF)
            paintGyroMenu()
        }
    }

    /** 合成一段「向右匀速 200px」的位移喂给输入通道：不碰触摸屏也能量链路。 */
    private fun startSyntheticDrag(durationMs: Int) {
        Log.i("AirMouse", "autodrag start ${durationMs}ms expect=+200px sink=${sink.label} mode=$mode")
        Log.i("AirMouse", "hid before: ${hid.debugState()}")
        val t = Thread {
            val step = 10L
            val gain = 2 // 每 10ms 右移 2px → 共 100 步 200px
            var elapsed = 0L
            while (elapsed < durationMs) {
                sink.mouseMove(gain, 0)
                try {
                    Thread.sleep(step)
                } catch (e: InterruptedException) {
                    break
                }
                elapsed += step
            }
            Log.i("AirMouse", "autodrag done, hid: ${hid.debugState()}")
            hid.debugNudge()
        }
        t.isDaemon = true
        t.start()
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindUi() {
        btnModeMenu.setOnClickListener { showModeMenu() }
        btnGyroMenu.setOnClickListener { showGyroMenu() }

        statusBox.setOnClickListener {
            // 连接失败/异常时点这里就重连（省得进设置）；否则点了是改 IP / 换设备
            if (enabled && mode != Prefs.MODE_BT && (netState == NetInput.State.FAILED || netState == NetInput.State.ERROR)) {
                net.retryNow()
                toast("正在重新连接…")
                return@setOnClickListener
            }
            if (mode == Prefs.MODE_BT) pickDevice() else showNetSettings()
        }

        switchEnable.setOnCheckedChangeListener { _, checked ->
            if (checked != enabled) setEnabled(checked, true)
        }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener { showSettings() }

        touchpad.listener = object : TouchpadView.Listener {
            override fun onMove(dx: Int, dy: Int) {
                if (!enabled) { nudgeEnableHint(); return }
                sink.mouseMove(dx, dy)
            }

            override fun onButton(button: Int, down: Boolean) {
                if (!enabled) { nudgeEnableHint(); return }
                sink.mouseButton(button, down)
            }

            override fun onWheel(notches: Int) {
                if (!enabled) { nudgeEnableHint(); return }
                sink.wheel(notches)
            }

            override fun onBlockedTouch() = nudgeEnableHint()
        }

        scrollStrip.listener = object : ScrollStrip.Listener {
            override fun onWheel(notches: Int) {
                if (!enabled) { nudgeEnableHint(); return }
                sink.wheel(notches)
            }
        }

        keyboard.listener = object : KeyboardPanelView.Listener {
            override fun onKey(hidKey: Int, mods: Int) {
                if (!enabled) { nudgeEnableHint(); return }
                sink.keyPress(hidKey, mods)
            }

            override fun onImeRequest() = showImeDialog()
            override fun onTypingModeChanged(ime: Boolean) {
                prefs.typingMode = if (ime) Prefs.TYPE_IME else Prefs.TYPE_KEYS
                toast(if (ime) "手机输入法模式：打好再整段发" else "直通键码模式：一个键一个按键")
            }

            override fun onClose() {
                hideKeyboardPanel()
                updateGyroForKeyboard(false)
            }
        }
        // 2.11：默认改成手机输入法。老安装里存的还是「直通键码」，升级后纠正一次就够，之后尊重用户选择
        if (!prefs.imeDefaultApplied) {
            prefs.typingMode = Prefs.TYPE_IME
            prefs.imeDefaultApplied = true
        }
        keyboard.imeMode = prefs.typingMode == Prefs.TYPE_IME
        kbScrim = findViewById(R.id.kbScrim)
        // 点面板以外的暗色区域就收起键盘（直通键码模式尤其需要：那时没有抽屉的遮罩帮忙接这一下）
        kbScrim.setOnClickListener {
            hideKeyboardPanel()
            updateGyroForKeyboard(false)
        }
        findViewById<MaterialButton>(R.id.btnKeyboard).setOnClickListener {
            if (!enabled) { nudgeEnableHint(); return@setOnClickListener }
            if (keyboard.isShown) hideKeyboardPanel() else showKeyboardPanel()
            updateGyroForKeyboard(keyboard.isShown)
            // 默认就是手机输入法：面板一露头就把输入法叫起来，省得再点一次（切到直通键码后不会弹）
            if (keyboard.isShown && keyboard.imeMode) {
                keyboard.postDelayed({ if (keyboard.isShown) showImeDialog() }, 160)
            }
        }

        bindHoldButton(findViewById(R.id.btnLeft), InputSink.BTN_LEFT)
        bindHoldButton(findViewById(R.id.btnRight), InputSink.BTN_RIGHT)
        bindHoldButton(findViewById(R.id.btnMiddle), InputSink.BTN_MIDDLE)

        // 单击 = 立刻对准中心（最常用）；长按 = 打开校准菜单（含两点校准）
        btnCalib.setOnClickListener {
            if (gyroMode == GYRO_RAY) {
                ray.recenter()
                toast("已对准：手机现在指的方向就是屏幕中心（长按「校准」可做两点校准）")
            } else {
                gyro.recalibrate()
                toast("已重新校准，保持手机静止一下")
            }
        }
        btnCalib.setOnLongClickListener {
            if (gyroMode == GYRO_RAY) {
                showRayCalibration()
                true
            } else {
                false
            }
        }

        val p = ((prefs.sensitivity - 0.2f) / 2.3f * 200f).toInt().coerceIn(0, 200)
        seekSens.progress = p
        paintSensLabel()
        seekSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = sensValue()
                gyro.sensitivity = v
                ray.spanDeg = spanFromSlider()
                paintSensLabel()
                if (fromUser) prefs.sensitivity = v
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 点「灵敏度」三个字 = 回到默认（1.00× / ±33°），懒得拖动时用
        findViewById<TextView>(R.id.tvSensLabel).setOnClickListener {
            if (!enabled) { nudgeEnableHint(); return@setOnClickListener }
            seekSens.progress = 70
            prefs.sensitivity = sensValue()
            gyro.sensitivity = sensValue()
            ray.spanDeg = spanFromSlider()
            paintSensLabel()
            toast("灵敏度已回到默认")
        }
        // 停用时滑杆是 disabled，摸上去什么都不会发生；靠这一行接住点击给提示
        rowSens.isClickable = true
        rowSens.setOnClickListener { if (!enabled) nudgeEnableHint() }

        applyFlingSetting()
    }

    /** 滚轮惯性档位 → 触摸板的衰减系数（0.92 约等于「拨一下滑 200px」） */
    private fun applyFlingSetting() {
        val level = prefs.flingLevel
        touchpad.flingEnabled = level != Prefs.FLING_OFF
        touchpad.flingDecay = when (level) {
            Prefs.FLING_WEAK -> 0.86f
            Prefs.FLING_STRONG -> 0.95f
            else -> 0.92f
        }
    }

    private fun sensValue(): Float = 0.2f + seekSens.progress / 200f * 2.3f

    /** 射线模式下滑杆换成「半角」：滑得越多，覆盖同样屏幕需要转的角度越小（越灵敏）。 */
    private fun spanFromSlider(): Float = 45f - seekSens.progress / 200f * 35f

    /** 屏幕宽高比（长边/短边），射线模式里用来把水平角度换算成竖直方向的比例 */
    private fun screenAspect(): Float {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        return maxOf(w, h).toFloat() / minOf(w, h)
    }

    private fun paintSensLabel() {
        tvSens.text = if (gyroMode == GYRO_RAY) {
            if (ray.cornerCalibrated) "两点 ✓"
            else String.format(java.util.Locale.US, "±%.0f°", spanFromSlider())
        } else {
            String.format(java.util.Locale.US, "%.1fx", sensValue())
        }
    }

    /**
     * 通用底部抽屉弹窗：所有提示 / 表单都走这里，和「设置」「使用说明」同一套外观。
     * content 用来塞输入框、选项列表这类自定义内容；message 是纯文本说明，二选一即可。
     */
    private fun showSheet(
        title: String,
        message: String? = null,
        content: View? = null,
        ok: String = "确定",
        cancel: String? = "取消",
        extra: Pair<String, () -> Unit>? = null,
        onShown: ((BottomSheetDialog) -> Unit)? = null,
        onOk: (() -> Unit)? = null
    ): BottomSheetDialog {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_dialog, null)
        v.findViewById<TextView>(R.id.dlgTitle).text = title

        val msgView = v.findViewById<TextView>(R.id.dlgMessage)
        if (message != null) {
            msgView.text = message
            msgView.visibility = View.VISIBLE
        }
        if (content != null) {
            v.findViewById<FrameLayout>(R.id.dlgContent)
                .addView(content, FrameLayout.LayoutParams(-1, -2))
            v.findViewById<View>(R.id.dlgScroll).visibility = View.VISIBLE
        }

        val okBtn = v.findViewById<Button>(R.id.dlgOk)
        okBtn.text = ok
        okBtn.setOnClickListener {
            sheet.dismiss()
            onOk?.invoke()
        }
        val cancelBtn = v.findViewById<Button>(R.id.dlgCancel)
        if (cancel == null) {
            cancelBtn.visibility = View.GONE
        } else {
            cancelBtn.text = cancel
            cancelBtn.setOnClickListener { sheet.dismiss() }
        }
        val extraBtn = v.findViewById<Button>(R.id.dlgExtra)
        if (extra != null) {
            extraBtn.text = extra.first
            extraBtn.visibility = View.VISIBLE
            extraBtn.setOnClickListener {
                sheet.dismiss()
                extra.second()
            }
        }

        sheet.setContentView(v)
        sheet.setOnShowListener {
            val bottom: View? = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottom != null) {
                bottom.setBackgroundColor(Color.TRANSPARENT)
                val beh: BottomSheetBehavior<View> = BottomSheetBehavior.from(bottom)
                beh.state = BottomSheetBehavior.STATE_EXPANDED
                beh.skipCollapsed = true
            }
            onShown?.invoke(sheet)
        }
        sheet.show()
        return sheet
    }

    /** 抽屉里的可点选项行（射线校准、设备列表用它） */
    private fun optionRow(text: String, onClick: () -> Unit): TextView {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tv.setPadding(pad, pad, pad, pad)
        tv.background = ContextCompat.getDrawable(this, R.drawable.bg_key)
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = (6 * resources.displayMetrics.density).toInt()
        tv.layoutParams = lp
        return tv
    }

    /** 射线模式的校准入口：只对中心（快），或左上角+右下角两点校准（准） */
    private fun showRayCalibration() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        lateinit var sheet: BottomSheetDialog
        fun option(label: String, action: () -> Unit) {
            col.addView(optionRow(label) { sheet.dismiss(); action() })
        }
        option("对准屏幕中心（指到屏幕中间按一下）") {
            ray.recenter()
            paintSensLabel()
            toast("已对准：现在手机前端指的方向就是屏幕中心")
        }
        option(if (ray.cornerCalibrated) "重新两点校准（左上角 + 右下角）" else "两点校准（左上角 + 右下角）") {
            startCornerCalibration()
        }
        if (ray.cornerCalibrated) {
            option("取消两点校准（回到只对中心）") {
                ray.clearCorners()
                paintSensLabel()
                toast("已取消两点校准，回到只对中心")
            }
        }
        sheet = showSheet("射线校准", content = col, ok = "关掉", cancel = null)
    }

    private fun startCornerCalibration() {
        askCorner("第一步：手机顶端指向显示器【左上角】，对准后点「采下左上角」") { tl ->
            askCorner("第二步：手机顶端指向显示器【右下角】，对准后点「采下右下角」") { br ->
                if (ray.applyCorners(tl[0], tl[1], br[0], br[1])) {
                    paintSensLabel()
                    toast("两点校准完成：现在射线指哪，光标就在哪")
                } else {
                    toast("两次指向太接近（手机没真的转过去），再来一次")
                }
            }
        }
    }

    private fun askCorner(message: String, onDone: (FloatArray) -> Unit) {
        showSheet(
            title = "两点校准",
            message = message,
            ok = "采下",
            onOk = {
                val p = ray.peekPoint()
                if (p == null) toast("还没收到姿态数据，等一下再点") else onDone(p)
            }
        )
    }

    private fun bindHoldButton(v: View, button: Int) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    if (enabled) sink.mouseButton(button, true) else nudgeEnableHint()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (enabled) sink.mouseButton(button, false)
                    true
                }

                else -> false
            }
        }
    }

    // ---------------- 模式 ----------------

    private fun applyMode() {

        prefs.mode = mode
        paintModeButtons()
        if (!enabled) {
            // 停用：彻底从电脑那边消失 —— 蓝牙断开连接、网络停掉
            net.stop()
            hid.suspendHost()
            paintEnabledUi()
            return
        }
        if (mode == Prefs.MODE_BT) {
            net.stop()
            hid.start()
            statusTitle.text = "蓝牙 HID"
            statusSub.text = "点这里选择/配对电脑"
            setDot(COLOR_MID)
        } else if (mode == Prefs.MODE_USB) {
            hid.close()
            net.stop()
            net.usbMode = true
            net.host = "127.0.0.1"
            net.port = prefs.port
            net.start()
        } else {
            hid.close()
            net.stop()
            net.usbMode = false
            net.host = prefs.host
            net.port = prefs.port
            if (prefs.host.isBlank()) {
                statusTitle.text = "WiFi 模式"
                statusSub.text = "还没填电脑 IP，点这里设置"
                setDot(COLOR_WARN)
            } else {
                net.start()
            }
        }
        gyro.sensitivity = sensValue()
        paintEnabledUi()
    }

    /**
     * 总开关。关闭时会把蓝牙 HID 连接断开、网络停掉，
     * 电脑上就看不到这只手机鼠标了 —— 不会再和真鼠标抢指针。
     */
    /** 键盘面板是浮层：调用方只管说要开还是要关，遮罩和可见性一起管 */
    private fun showKeyboardPanel() {
        kbScrim.visibility = View.VISIBLE
        keyboard.show()
    }

    private fun hideKeyboardPanel() {
        kbScrim.visibility = View.GONE
        keyboard.hide()
    }

    private fun setEnabled(on: Boolean, announce: Boolean) {
        if (enabled == on) return
        enabled = on
        if (!on) {
            touchpad.stopFling()
            if (gyroMode != GYRO_OFF) {
                // 记住「停用前开着哪个」，重新启用并连上后自动恢复（用户抱怨过开关一关一开模式就回「关」）
                gyroRestorePending = gyroMode
                setGyroMode(GYRO_OFF)
            }
            if (keyboard.isShown) {
                hideKeyboardPanel()
                updateGyroForKeyboard(false)
            }
        }
        applyMode()
        if (announce) {
            toast(if (on) "已启用：手机开始控制电脑" else "已停用：电脑鼠标交还给真鼠标")
        }
        Log.i("AirMouse", "enabled -> $on mode=$mode")
        Diag.log("App", (if (on) "已启用" else "已停用") + " · 连接方式=" + modeName(mode))
    }

    private fun paintEnabledUi() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val accentSoft = ContextCompat.getColor(this, R.color.accent_soft)
        val soft = ContextCompat.getColor(this, R.color.stroke)

        if (switchEnable.isChecked != enabled) switchEnable.isChecked = enabled
        val on = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, ContextCompat.getColor(this, R.color.switch_off))
        )
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accentSoft, soft)
        )
        switchEnable.thumbTintList = on
        switchEnable.trackTintList = track

        // 停用时和「控鼠标」有关的东西一起暗下去：触摸板、左右中键+键盘、灵敏度
        val a = if (enabled) 1f else 0.35f
        touchpadFrame.alpha = a
        rowButtons.alpha = a
        rowSens.alpha = a
        touchpad.controlEnabled = enabled
        seekSens.isEnabled = enabled
        if (!enabled) {
            statusTitle.text = "已停用"
            statusSub.text = "打开右上角开关，手机才开始控电脑"
            setDot(COLOR_IDLE)
        }
    }

    /** 停用状态下用户去点触摸板/按键时，给一句提示（4 秒内只提示一次，免得刷屏） */
    private var lastEnableHintAt = 0L
    private fun nudgeEnableHint() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEnableHintAt < 4000) return
        lastEnableHintAt = now
        toast("先把右上角的开关打开，手机才会接管电脑鼠标")
    }

    /** 「已连接 192.168.1.51:47777」→「WiFi · 192.168.1.51:47777」，一眼看出走的哪条链路 */
    private fun decorateSub(prefix: String, message: String): String =
        if (message.startsWith("已连接 ")) "$prefix · " + message.removePrefix("已连接 ") else message

    /**
     * 调试用：`am start ... --ei mode 1 --es host 192.168.1.51 --ei port 47777`
     * 直接把模式/电脑地址写进偏好，省得在手机上点一遍（release 包 run-as 进不去，改不了 xml）。
     */
    private fun applyDebugConfig(i: Intent?) {
        if (i == null) return
        var changed = false
        val h = i.getStringExtra("host")
        if (!h.isNullOrEmpty() && h != prefs.host) {
            prefs.host = h
            changed = true
        }
        val p = i.getIntExtra("port", 0)
        if (p > 0 && p != prefs.port) {
            prefs.port = p
            changed = true
        }
        val m = i.getIntExtra("mode", -1)
        if (m >= 0 && m != mode) {
            mode = m
            changed = true
        }
        if (changed) {
            Log.i("AirMouse", "debug config host=${prefs.host} port=${prefs.port} mode=$mode")
            applyMode()
        }
    }

    private fun paintModeButtons() {
        btnModeMenu.text = modeName(mode) + " ▾"
    }

    private fun modeName(m: Int) = when (m) {
        Prefs.MODE_WIFI -> "WiFi"
        Prefs.MODE_USB -> "USB"
        else -> "蓝牙"
    }

    /** 连接方式下拉：贴着按钮弹出的列表（不是居中弹窗），选中的一行高亮 */
    private fun showModeMenu() {
        // 第 4 项不是「模式」，是配对新电脑的入口 —— 蓝牙那套低频操作从设置里搬过来
        val names = arrayOf("蓝牙 HID", "WiFi（填电脑 IP）", "USB（插线即用）", "配对新电脑…")
        val values = intArrayOf(Prefs.MODE_BT, Prefs.MODE_WIFI, Prefs.MODE_USB)
        val checked = values.indexOf(mode).coerceAtLeast(0)
        showDropDown(btnModeMenu, names, checked) { which ->
            if (which == 3) {
                pickDevice()
                return@showDropDown
            }
            if (mode != values[which]) {
                mode = values[which]
                // 换通道不带着上一次「恢复陀螺仪」的意图
                gyroRestorePending = GYRO_OFF
                applyMode()
            }
            if (values[which] == Prefs.MODE_WIFI) {
                // 选 WiFi 就当场把 IP 填了，省得再去设置里找
                if (prefs.host.isBlank()) {
                    main.postDelayed({ showNetSettings() }, 260)
                } else {
                    toast("WiFi：连 " + prefs.host + " · " + prefs.port + "，连不上就点状态栏改")
                }
            }
        }
    }

    /**
     * 贴着 anchor 弹出的下拉列表（ListPopupWindow，不是 AlertDialog）。
     * 宽度跟按钮一致，选中行用 accent 描字 + 淡底 + ✓。
     */
    private fun showDropDown(anchor: View, items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val soft = ContextCompat.getColor(this, R.color.accent_soft)
        val padH = (14 * resources.displayMetrics.density).toInt()
        val padV = (11 * resources.displayMetrics.density).toInt()

        val list = ListPopupWindow(this)
        list.anchorView = anchor
        list.isModal = true
        list.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_popup))
        // 至少和按钮一样宽；太长（比如「WiFi（填电脑 IP）」）就撑到 240dp，别把字截成省略号。
        // 但右边靠墙的按钮（陀螺仪那个）得往左挪，否则弹窗会顶出屏幕。
        val density = resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val desired = maxOf(anchor.width, (240 * density).toInt())
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = resources.displayMetrics.widthPixels
        var width = desired
        var offset = 0
        val rightSpace = screenW - loc[0] - margin
        if (desired > rightSpace) {
            // 往左挪，让弹窗右边缘和按钮右边缘对齐；最多挪到屏幕左边留 8dp
            width = minOf(desired, maxOf(anchor.width, loc[0] + anchor.width - margin))
            offset = anchor.width - width
        }
        list.width = width
        list.horizontalOffset = offset
        list.height = ListPopupWindow.WRAP_CONTENT
        list.verticalOffset = (4 * density).toInt()
        list.setAdapter(object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = TextView(this@MainActivity)
                tv.text = if (position == checked) "✓  " + items[position] else items[position]
                tv.textSize = 14f
                tv.maxLines = 1
                tv.ellipsize = android.text.TextUtils.TruncateAt.END
                tv.setTextColor(if (position == checked) accent else primary)
                tv.setBackgroundColor(if (position == checked) soft else android.graphics.Color.TRANSPARENT)
                tv.setPadding(padH, padV, padH, padV)
                return tv
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            list.dismiss()
            onPick(position)
        }
        list.show()
    }

    // ---------------- 状态回调 ----------------

    override fun onHidState(state: HidInput.State, message: String) {
        runOnUiThread {
            if (mode != Prefs.MODE_BT) return@runOnUiThread
            if (!enabled) return@runOnUiThread
            statusTitle.text = when (state) {
                HidInput.State.CONNECTED -> "已连接"
                HidInput.State.READY -> "蓝牙 HID"
                HidInput.State.REGISTERING -> "正在准备…"
                HidInput.State.UNSUPPORTED -> "不支持 HID"
                HidInput.State.NO_PERMISSION -> "缺少权限"
                HidInput.State.BLUETOOTH_OFF -> "蓝牙未开启"
                HidInput.State.NO_BLUETOOTH -> "没有蓝牙"
                HidInput.State.ERROR -> "出错了"
                else -> "未连接"
            }
            statusSub.text = decorateSub("蓝牙", message)
            setDot(
                when (state) {
                    HidInput.State.CONNECTED -> COLOR_OK
                    HidInput.State.READY, HidInput.State.REGISTERING -> COLOR_MID
                    HidInput.State.ERROR, HidInput.State.UNSUPPORTED,
                    HidInput.State.NO_PERMISSION, HidInput.State.BLUETOOTH_OFF -> COLOR_WARN
                    else -> COLOR_IDLE
                }
            )
            if (state == HidInput.State.READY && enabled) autoConnectHidHost()
            syncGyroWithLink()
        }
    }

    override fun onNetState(state: NetInput.State, message: String) {
        runOnUiThread {
            netState = state
            if (mode == Prefs.MODE_BT) return@runOnUiThread
            if (!enabled) return@runOnUiThread
            statusTitle.text = when (state) {
                NetInput.State.CONNECTED -> "已连接"
                NetInput.State.CONNECTING ->
                    if (net.failedAttempts > 0 && net.maxAttempts > 0)
                        "正在重连 第 ${net.failedAttempts + 1}/${net.maxAttempts} 次…"
                    else "正在连接…"
                NetInput.State.FAILED -> "连接失败 · 点这里重连"
                NetInput.State.ERROR -> "连接异常 · 点这里重连"
                else -> "网络模式"
            }
            statusSub.text = decorateSub(net.transport, message)
            setDot(
                when (state) {
                    NetInput.State.CONNECTED -> COLOR_OK
                    NetInput.State.CONNECTING -> COLOR_MID
                    NetInput.State.FAILED -> COLOR_WARN
                    NetInput.State.ERROR -> COLOR_WARN
                    else -> COLOR_IDLE
                }
            )
            // 失败/异常时整块状态栏都能点，点了就重新连（不用去翻设置）
            statusBox.isClickable = state == NetInput.State.FAILED || state == NetInput.State.ERROR
            syncGyroWithLink()
        }
    }

    private fun setDot(color: Int) {
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ---------------- 陀螺仪 / 射线指向 ----------------

    /**
     * 陀螺仪下拉：关 / 相对 / 射线。射线是绝对定位（手机当激光笔），相对是老的手腕转动。
     * 没连上电脑时不给开 —— 陀螺仪的唯一用途是动光标，连着才有意义，否则白耗电还像坏了。
     */
    private fun showGyroMenu() {
        if (!enabled) { nudgeEnableHint(); return }
        if (!isLinked) {
            toast("先连上电脑：陀螺仪是用来动光标的，没连接开着也没用")
            return
        }
        if (isLandscape()) {
            // 横屏下只允许「关」：横过来是双手捧着用触摸板的姿势，再叠加陀螺仪既难瞄也难放
            toast("横屏下不启用陀螺仪：左手按键、右手拖触摸板就够，转回竖屏再开")
            return
        }
        val names = arrayOf("关", "相对（转手腕）", "射线（指哪打哪）")
        val values = intArrayOf(GYRO_OFF, GYRO_RATE, GYRO_RAY)
        val checked = values.indexOf(gyroMode).coerceAtLeast(0)
        showDropDown(btnGyroMenu, names, checked) { which ->
            // 手动选过就以手选为准，别在下次连上时又被「恢复」抢走
            gyroRestorePending = GYRO_OFF
            if (gyroMode != values[which]) setGyroMode(values[which], announce = true)
        }
    }

    /** 横屏时陀螺仪一律关闭：布局是「左手按键、右手触摸板」，陀螺仪没有落脚点 */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 当前这条通道是否真的连上了电脑（蓝牙看 HID，WiFi/USB 看 socket） */
    private val isLinked: Boolean
        get() = if (mode == Prefs.MODE_BT) hid.isConnected else net.isConnected

    /** 连接状态变了：没连上就先把陀螺仪收起来，别让它空转；连上了把下拉恢复可点 */
    private fun syncGyroWithLink() {
        val linked = isLinked
        if (!linked && gyroMode != GYRO_OFF) {
            gyroBeforeKeyboard = GYRO_OFF
            setGyroMode(GYRO_OFF)
            toast("连接断了，陀螺仪已暂停")
        }
        // 开关重新打开、链路也连上了：把停用前那个模式接回去（只在「开着的时候被关掉」这一种情况下）
        if (linked && enabled && gyroMode == GYRO_OFF && gyroRestorePending != GYRO_OFF && !isLandscape()) {
            val want = gyroRestorePending
            gyroRestorePending = GYRO_OFF
            setGyroMode(want, announce = true)
        }
        paintGyroMenu()
    }

    private fun paintGyroMenu() {
        // 横屏只有「关」：按钮固定写关、压暗、校准胶囊也不出现
        if (isLandscape()) {
            btnGyroMenu.text = "陀螺仪 关 ▾"
            btnGyroMenu.alpha = 0.45f
            btnCalib.visibility = View.GONE
            return
        }
        btnGyroMenu.text = when (gyroMode) {
            GYRO_RATE -> "陀螺仪 相对 ▾"
            GYRO_RAY -> "陀螺仪 射线 ▾"
            else -> "陀螺仪 关 ▾"
        }
        // 没连上时把下拉压暗，暗示「现在点它没用」
        btnGyroMenu.alpha = if (enabled && isLinked) 1f else 0.45f
        // 校准只在陀螺仪开着时才有意义
        btnCalib.visibility = if (gyroMode == GYRO_OFF) View.GONE else View.VISIBLE
    }

    private fun setGyroMode(m: Int, announce: Boolean = false) {
        gyroMode = m
        if (m != GYRO_OFF) lastNonOffGyro = m
        // 只记住「被开启的模式」，关的时候不要覆盖，否则下次点开就不知道该开哪个了
        if (m != GYRO_OFF) prefs.gyroMode = m
        if (gyro.running) gyro.stop()
        if (ray.running) ray.stop()
        hid.setAbsolute(m == GYRO_RAY)
        applyPointerUi(m)

        when (m) {
            GYRO_RATE -> {
                if (!gyro.hasGyroscope) {
                    toast("这台手机没有陀螺仪")
                    gyroMode = GYRO_OFF
                    paintGyroMenu()
                    return
                }
                if (gyro.start()) {
                    if (announce) toast("相对模式：左右转手腕移动光标，静止会自动校准")
                }
            }
            GYRO_RAY -> {
                if (!ray.hasSensor) {
                    toast("这台手机没有姿态传感器")
                    gyroMode = GYRO_OFF
                    hid.setAbsolute(false)
                    applyPointerUi(GYRO_OFF)
                    paintGyroMenu()
                    return
                }
                ray.spanDeg = spanFromSlider()
                ray.aspect = screenAspect()
                if (ray.start()) {
                    if (announce) toast("射线模式：手机前端指向屏幕中心后点「校准」，然后转动它")
                } else {
                    toast("姿态传感器打不开")
                    gyroMode = GYRO_OFF
                    hid.setAbsolute(false)
                    applyPointerUi(GYRO_OFF)
                    paintGyroMenu()
                    return
                }
            }
            else -> {
                if (announce) toast("陀螺仪已关闭")
            }
        }
        paintGyroMenu()
        paintSensLabel()
    }

    /**
     * 按陀螺仪状态调整触摸板的行为：
     * - 相对模式：拖动 = 移动光标，滚轮只认双指 + 顶部竖条
     * - 射线模式：光标跟手机指向走，拖动腾出来做滚轮，竖条收起来（已经有整块触摸板能滚了）
     */
    private fun applyPointerUi(m: Int) {
        val rayMode = m == GYRO_RAY
        if (!rayMode) touchpad.stopFling()
        touchpad.moveEnabled = !rayMode
        touchpad.singleFingerScroll = rayMode
        scrollStrip.visibility = if (rayMode) View.GONE else View.VISIBLE
        hintText.text = if (rayMode) {
            "单指上下滑：滚轮 · 轻点：左键\n长按：按住左键拖动\n光标跟着手机指向走"
        } else {
            "拖动移动光标 · 轻点左键\n长按拖动 · 双指滚动\n上方滚轮条：一格一格细滚"
        }
    }

    /** 键盘弹出时先停陀螺仪/射线，免得打字时光标乱跑；收起后再恢复 */
    private var gyroBeforeKeyboard = GYRO_OFF
    private var autoConnectTried = false

    private fun updateGyroForKeyboard(showing: Boolean) {
        if (showing) {
            if (gyroMode != GYRO_OFF) {
                gyroBeforeKeyboard = gyroMode
                setGyroMode(GYRO_OFF)
            }
        } else if (gyroBeforeKeyboard != GYRO_OFF) {
            val restore = gyroBeforeKeyboard
            gyroBeforeKeyboard = GYRO_OFF
            setGyroMode(restore)
        }
    }

    // ---------------- 蓝牙选设备 ----------------

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        val adapter = hid.bluetoothAdapter
        if (adapter == null) {
            toast("本机没有蓝牙模块")
            return
        }
        if (!adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                toast("请手动打开蓝牙")
            }
            return
        }
        if (!hid.hasPermission()) {
            requestBluetoothPermissions()
            return
        }
        val bonded: List<BluetoothDevice> = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        lateinit var sheet: BottomSheetDialog
        for (d in bonded) {
            val name = hid.safeName(d)
            col.addView(optionRow("连接：$name") {
                sheet.dismiss()
                prefs.lastAddress = d.address
                hid.connect(d)
            })
        }
        col.addView(optionRow("让电脑发现本机（300 秒）") {
            sheet.dismiss()
            requestDiscoverable()
        })
        col.addView(optionRow("打开系统蓝牙设置") {
            sheet.dismiss()
            openBluetoothSettings()
        })
        sheet = showSheet("蓝牙 HID", content = col, ok = "关掉", cancel = null)
    }

    /**
     * HID 注册好之后，自动连回上次那台电脑：
     * 优先用记住的地址，其次找已配对的「电脑类」设备（major class = Computer）。
     */
    @SuppressLint("MissingPermission")
    private fun autoConnectHidHost() {
        if (autoConnectTried) return
        val adapter = hid.bluetoothAdapter ?: return
        if (!adapter.isEnabled || !hid.hasPermission()) return
        val bonded: List<BluetoothDevice> = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (bonded.isEmpty()) return
        autoConnectTried = true
        val target = bonded.firstOrNull { it.address == prefs.lastAddress }
            ?: bonded.firstOrNull { d ->
                try {
                    d.bluetoothClass?.majorDeviceClass == 0x0100
                } catch (e: SecurityException) {
                    false
                }
            }
            ?: return
        prefs.lastAddress = target.address
        hid.connect(target)
    }

    private fun requestDiscoverable() {
        try {
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivity(i)
            toast("在电脑上添加蓝牙设备，选 AirMouse")
        } catch (e: Exception) {
            toast("无法请求可发现状态，请到系统蓝牙设置里开启")
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            toast("打不开蓝牙设置")
        }
    }

    // ---------------- 对话框 ----------------

    private fun showTextDialog() = showImeDialog()

    /**
     * 手机输入法模式：先用系统输入法把字打出来（拼音/手写/表情都行），
     * 再一次性把成型的文字同步给电脑。
     */
    private fun showImeDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, 0, 0, 0)

        val tip = TextView(this)
        tip.text = if (mode == Prefs.MODE_BT) {
            "蓝牙 HID 只能送英文/数字/符号，中文要切到 WiFi 或 USB 模式。"
        } else {
            "网络模式：文字会直接注入电脑，中文、emoji 都行。"
        }
        tip.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        tip.textSize = 11f
        tip.setPadding(0, 0, 0, pad / 2)
        wrap.addView(tip)

        val input = EditText(this)
        input.hint = "在这里打字，打好后点「发送」"
        input.setSingleLine(false)
        input.minLines = 1
        input.maxLines = 6
        wrap.addView(input, LinearLayout.LayoutParams(-1, -2))

        showSheet(
            title = "手机输入法打字",
            content = wrap,
            ok = "发送",
            onOk = {
                sendComposedText(input.text.toString())
                // 发完就把键盘收起来：这时人要回去看光标，键盘面板继续杵着只会挡着触摸板
                hideKeyboardPanel()
                updateGyroForKeyboard(false)
            },
            onShown = { s ->
                // 抽屉贴到窗口上之后再要软键盘才生效；光设 softInputMode 在部分 ROM 上不弹，补一次 showSoftInput
                input.requestFocus()
                s.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                input.postDelayed({
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }, 260)
            }
        )
    }

    /** 把输入法打出来的一整段文字交给当前通道 */
    private fun sendComposedText(text: String) {
        android.util.Log.i("AirMouse", "sendComposedText mode=$mode len=${text.length}")
        val t = text.trimEnd('\n')
        if (t.isEmpty()) return
        if (mode == Prefs.MODE_BT) {
            if (!hid.isConnected) {
                toast("还没连上电脑，先点状态栏连一下")
                return
            }
            val bad = t.count { it.code > 126 }
            if (bad > 0) {
                toast("蓝牙只能送英文/数字，$bad 个字符会被跳过（切 WiFi 或 USB 模式可送中文）")
            }
        } else {
            if (!net.isConnected) {
                toast("还没连上电脑，点状态栏看看原因")
                return
            }
        }
        sink.typeText(t)
        val via = if (mode == Prefs.MODE_BT) "蓝牙" else "网络"
        Diag.log("App", "发送整段文字 " + t.length + " 字（" + via + "）")
    }

    private fun showNetSettings() {
        if (mode == Prefs.MODE_USB) {
            showUsbInfo()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, 0, 0, 0)

        val hostEdit = EditText(this)
        hostEdit.hint = "电脑 IP，例如 192.168.1.10"
        hostEdit.setText(prefs.host)
        wrap.addView(hostEdit, LinearLayout.LayoutParams(-1, -2))

        val portEdit = EditText(this)
        portEdit.hint = "端口"
        portEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        portEdit.setText(prefs.port.toString())
        wrap.addView(portEdit, LinearLayout.LayoutParams(-1, -2))

        val tip = TextView(this)
        tip.text = "电脑上双击 AirMouseSetup.exe 装好接收端就行\n本机 IP：" + localIp()
        tip.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        tip.textSize = 11f
        tip.setPadding(0, pad / 2, 0, 0)
        wrap.addView(tip)

        showSheet(
            title = "WiFi 模式",
            content = wrap,
            ok = "连接",
            onOk = {
                prefs.host = hostEdit.text.toString().trim()
                val p = portEdit.text.toString().toIntOrNull() ?: NetInput.DEFAULT_PORT
                prefs.port = p
                net.stop()
                net.usbMode = false
                net.host = prefs.host
                net.port = p
                if (prefs.host.isBlank()) {
                    toast("请填写电脑 IP")
                } else {
                    net.start()
                }
            }
        )
    }

    /** USB 模式的说明弹窗：这个模式不需要 IP，需要的是数据线 + adb 授权 */
    private fun showUsbInfo() {
        val msg = "USB 模式只走数据线，不经过路由器：\n\n" +
            "1. 手机用数据线连电脑，打开「USB 调试」并允许这台电脑。\n" +
            "2. 电脑上双击 AirMouseSetup.exe，装完它就在托盘区/任务栏跑着（无需 Python）。\n" +
            "3. 手机这边只要插着线就会连上，状态显示「USB · 127.0.0.1:47777」。\n\n" +
            "连不上时：看电脑端日志有没有「USB 通道已就绪」；没有的话手动执行\n" +
            "adb reverse tcp:" + prefs.port + " tcp:" + prefs.port
        showSheet(
            title = "USB 模式",
            message = msg,
            ok = "知道了",
            cancel = null,
            extra = "重连一次" to {
                net.stop()
                net.usbMode = true
                net.host = "127.0.0.1"
                net.port = prefs.port
                net.start()
            }
        )
    }

    /**
     * 设置：底部抽屉（2.6 起）。原来是一张系统 AlertDialog 长清单，
     * 七行灰底长句、操作提示全塞在标题里，看着像上世纪的东西。
     * 现在只留真正的全局偏好，其余全部搬到用得着的地方：
     *  · 电脑 IP   → 选 WiFi 时当场弹
     *  · 打字方式  → 键盘面板顶部两个 tab
     *  · 蓝牙配对  → 连接方式下拉里的「配对新电脑…」
     */
    private fun showSettings() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        sheet.setContentView(v)
        sheet.setOnShowListener {
            val bottom: View? = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottom != null) {
                bottom.setBackgroundColor(Color.TRANSPARENT)
                val beh: BottomSheetBehavior<View> = BottomSheetBehavior.from(bottom)
                beh.state = BottomSheetBehavior.STATE_EXPANDED
                beh.skipCollapsed = true
            }
        }

        v.findViewById<View>(R.id.sheetClose).setOnClickListener { sheet.dismiss() }

        // 滚轮惯性：四档分段，点到哪档就是哪档，不再「点一下换下一档」
        val chipIds = intArrayOf(R.id.chipFling0, R.id.chipFling1, R.id.chipFling2, R.id.chipFling3)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val dim = ContextCompat.getColor(this, R.color.text_dim)
        fun paintChips() {
            val lvl = prefs.flingLevel.coerceIn(0, 3)
            chipIds.forEachIndexed { i, id ->
                val c = v.findViewById<TextView>(id)
                val on = i == lvl
                c.setBackgroundResource(if (on) R.drawable.bg_key_on else R.drawable.bg_key)
                c.setTextColor(if (on) accent else dim)
            }
        }
        chipIds.forEachIndexed { i, id ->
            v.findViewById<TextView>(id).setOnClickListener {
                if (prefs.flingLevel != i) {
                    prefs.flingLevel = i
                    applyFlingSetting()
                    paintChips()
                    toast("滚轮惯性：" + Prefs.FLING_NAMES[i] + flingHint(i))
                }
            }
        }
        paintChips()

        v.findViewById<View>(R.id.rowHelp).setOnClickListener {
            // 不关设置抽屉：说明叠在它上面，关掉说明自然回到设置
            showHelp()
        }

        v.findViewById<View>(R.id.rowDiag).setOnClickListener {
            showDiag()
        }

        val tvUpdate = v.findViewById<TextView>(R.id.tvUpdateSub)
        tvUpdate.text = "当前 " + versionLabel()
        v.findViewById<View>(R.id.rowUpdate).setOnClickListener {
            tvUpdate.text = "正在检查…"
            Updater.checkNow(this) { r ->
                if (isFinishing) return@checkNow
                val info = r.info
                if (info == null) {
                    tvUpdate.text = "当前 " + versionLabel()
                    toast("检查失败：" + (r.error ?: "网络不通"))
                } else if (info.versionCode.toLong() == currentVersionCode()) {
                    tvUpdate.text = "当前 " + versionLabel() + "（已是最新）"
                    toast("已是最新版本 " + info.versionName)
                } else if (info.versionCode.toLong() < currentVersionCode()) {
                    // 线上还没有更新：本机这份比发布版新（自己编的测试版）
                    tvUpdate.text = "当前 " + versionLabel()
                    toast("线上最新是 " + info.versionName + "，你装的 " + versionLabel() + " 更新")
                } else {
                    tvUpdate.text = "当前 " + versionLabel()
                    showSheet(
                        "发现新版本 " + info.versionName,
                        buildString {
                            append("你现在是 ").append(versionLabel()).append("\n\n")
                            info.notes?.let { append(it) }
                        },
                        ok = "去下载",
                        cancel = "以后再说",
                        onOk = { startApkDownload(info) }
                    )
                }
            }
        }

        // 外观：跟随系统 / 浅色 / 深色。选了就立刻切（Activity 会重建，抽屉自然关掉）
        val tvAppearance = v.findViewById<TextView>(R.id.tvAppearance)
        fun paintAppearance() {
            tvAppearance.text = Prefs.APPEARANCE_NAMES[prefs.appearance.coerceIn(0, 2)]
        }
        paintAppearance()
        v.findViewById<View>(R.id.rowAppearance).setOnClickListener {
            // 点一下换下一档：跟随系统 -> 浅色 -> 深色 -> 跟随系统
            val next = (prefs.appearance + 1) % 3
            prefs.appearance = next
            applyAppearance()
            // 只调 setDefaultNightMode 有时不会重建当前 Activity（实测 ColorOS 就不重建），
            // 手动 recreate 一次，立刻见效
            recreate()
            toast("外观：" + Prefs.APPEARANCE_NAMES[next])
        }
        v.findViewById<View>(R.id.rowReset).setOnClickListener {
            sheet.dismiss()
            confirmReset()
        }

        // 版本号从 BuildConfig 拿不到（没开 buildConfig），直接读 PackageManager
        v.findViewById<TextView>(R.id.sheetVersion).text = "陀螺仪鼠标 " + versionLabel()

        sheet.show()
    }

    /** 界面上只显示版本名（如 1.0.4），版本码只留给诊断日志 */
    private fun versionLabel(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    /** 诊断日志用：版本名 + 版本码 */
    private fun versionLabelFull(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        pi.versionName + " · " + pi.longVersionCode
    } catch (e: Exception) {
        "1.0"
    }

    private fun currentVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    /** 诊断日志：给用户看的「一键交给作者」的出口 */
    private fun showDiag() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_diag, null)
        sheet.setContentView(v)
        sheet.setOnShowListener {
            val bottom: View? = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottom != null) {
                bottom.setBackgroundColor(Color.TRANSPARENT)
                val beh: BottomSheetBehavior<View> = BottomSheetBehavior.from(bottom)
                beh.state = BottomSheetBehavior.STATE_EXPANDED
                beh.skipCollapsed = true
            }
        }

        val tv = v.findViewById<TextView>(R.id.diagText)
        val scroll = v.findViewById<ScrollView>(R.id.diagScroll)
        fun refresh() {
            tv.text = Diag.dump()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        refresh()

        v.findViewById<View>(R.id.diagClose).setOnClickListener { sheet.dismiss() }
        v.findViewById<View>(R.id.diagCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AirMouse 诊断日志", Diag.dump()))
            toast("已复制，直接粘给对方就行")
        }
        v.findViewById<View>(R.id.diagSave).setOnClickListener {
            toast(Diag.saveToDownloads(this, Diag.dump()))
        }
        v.findViewById<View>(R.id.diagClear).setOnClickListener {
            Diag.clear()
            refresh()
            toast("已清空")
        }
        sheet.show()
    }

    /** 按偏好套用深/浅色；跟随系统就交还给系统（AppCompat 的默认行为） */
    private fun applyAppearance() {
        val a = Prefs(this).appearance
        AppCompatDelegate.setDefaultNightMode(
            when (a) {
                Prefs.APPEARANCE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.APPEARANCE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }


    /** 全部重置是不可撤销动作，先问一句 */
    private fun confirmReset() {
        showSheet(
            title = "全部重置",
            message = "会清掉电脑 IP、灵敏度、滚轮惯性、打字方式等全部偏好，应用本身不会被卸载。",
            ok = "重置",
            onOk = {
                prefs.host = ""
                prefs.port = NetInput.DEFAULT_PORT
                prefs.sensitivity = 1f
                prefs.flingLevel = Prefs.FLING_MID
                prefs.typingMode = Prefs.TYPE_KEYS
                seekSens.progress = 70
                keyboard.imeMode = false
                applyFlingSetting()
                paintSensLabel()
                toast("已重置")
            }
        )
    }

    private fun flingHint(level: Int): String = when (level) {
        Prefs.FLING_OFF -> "：甩了也不会继续滚"
        Prefs.FLING_WEAK -> "：甩一下滚一小段"
        Prefs.FLING_MID -> "：甩一下滚半屏左右"
        else -> "：甩一下滚很远"
    }

    /** 使用说明：把散在界面各处的操作集中一页，随时能查 */
    /**
     * 使用说明：底部抽屉 + 两列排版（左「手势/名词」、右「说明」）。
     * 之前是 AlertDialog.setMessage 的一大坨纯文本，扫起来累；现在按组切开，每行一眼看完。
     */
    private fun showHelp() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_help, null)
        sheet.setContentView(view)
        sheet.setOnShowListener {
            val dlg = it as BottomSheetDialog
            val bottom: View? = dlg.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottom != null) {
                bottom.setBackgroundColor(Color.TRANSPARENT)
                val beh: BottomSheetBehavior<View> = BottomSheetBehavior.from(bottom)
                beh.state = BottomSheetBehavior.STATE_EXPANDED
                beh.skipCollapsed = true
            }
        }
        view.findViewById<View>(R.id.helpClose).setOnClickListener { sheet.dismiss() }

        val body = view.findViewById<LinearLayout>(R.id.helpBody)
        val d = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun section(title: String) {
            val tv = TextView(this)
            tv.text = title
            tv.setTextColor(ContextCompat.getColor(this, R.color.accent))
            tv.textSize = 11.5f
            tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            tv.letterSpacing = 0.06f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(18)
            lp.bottomMargin = dp(2)
            tv.layoutParams = lp
            body.addView(tv)
        }

        fun row(left: String, right: String) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.setPadding(0, dp(7), 0, dp(7))
            val l = TextView(this)
            l.text = left
            l.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            l.textSize = 13f
            r.addView(l, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
            val t = TextView(this)
            t.text = right
            t.setTextColor(ContextCompat.getColor(this, R.color.dialog_text))
            t.textSize = 14f
            t.setLineSpacing(dp(3).toFloat(), 1f)
            r.addView(
                t,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.weight = 1f }
            )
            body.addView(r)
        }

        section("一、开机流程")
        row("①", "打开右上角开关，手机才开始接管电脑")
        row("②", "左边下拉选连接方式：蓝牙 / WiFi / USB")
        row("③", "用完关掉开关，鼠标立刻交还给真鼠标")

        section("二、触摸板")
        row("单指拖动", "移动光标")
        row("轻点", "左键单击")
        row("长按拖动", "按住左键拖选，松手松开")
        row("双指上下", "滚轮")
        row("双指轻点", "右键")
        row("上方竖条", "一格一格细滚")

        section("三、陀螺仪")
        row("射线", "手机顶端就是射线，指哪打哪；这时触摸板整块变成滚轮")
        row("相对", "转手腕累积移动，不用对着屏幕，躺着能用")
        row("校准", "单击对准屏幕中心；长按做两点校准（最准）")
        row("前提", "要连着电脑才能开，断了会自动暂停")

        section("四、键盘")
        row("直通键码", "一个键 = 一个真实按键")
        row("手机输入法", "用手机输入法打好，整段发给电脑")
        row("中文", "只有 WiFi / USB 能打，蓝牙协议只有键码")

        section("五、小技巧")
        row("复位", "点「灵敏度」三个字回到默认")
        row("躺着用", "选「相对」模式，射线需要手机大致朝着屏幕")

        sheet.show()
    }

    private fun localIp(): String {
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            for (nif in all) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
        }
        return "未知"
    }

    // ---------------- 权限 / 生命周期 ----------------

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val need = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_BT)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            if (mode == Prefs.MODE_BT) hid.start()
        }
    }

    override fun onPause() {
        super.onPause()
        // 快照要在「停掉陀螺仪」之前拍：onSaveInstanceState 在 onPause 之后跑，拿到的已是关
        gyroModeAtPause = gyroMode
        if (gyro.running || ray.running) {
            gyroBeforeKeyboard = GYRO_OFF   // 回前台不自动恢复，避免拿着手机乱切
            setGyroMode(GYRO_OFF)
        }
    }

    /**
     * 切主题会走 recreate()，这里把「总开关」和「陀螺仪模式」带过去：
     * 不带的话用户每换一次外观，手机就从「在控制电脑」变回「已停用」，陀螺仪也回到关。
     * 注意只对「Activity 重建」有效——冷启动 savedInstanceState 是 null，仍然默认停用。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UI_ENABLED, enabled)
        outState.putInt(KEY_UI_GYRO, if (gyroMode != GYRO_OFF) gyroMode else gyroModeAtPause)
        outState.putInt(KEY_UI_GYRO_SUSPENDED, gyroSuspendedByLandscape)
    }

    override fun onResume() {
        super.onResume()
        if (mode == Prefs.MODE_BT) hid.start()
    }

    /** 已经在跑的时候再用 am start 送调试参数（不用重启 App，避免反复重连把蓝牙 HID 搞乱）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugConfig(intent)
        val gm = intent.getIntExtra("gyromode", -1)
        if (gm in 0..2) setGyroMode(gm)
        // 调试用：--es abspoint "0.25,0.75" 直接把绝对指针摆到那个比例位置
        intent.getStringExtra("abspoint")?.let { s ->
            val p = s.split(",")
            if (p.size == 2) {
                val u = p[0].trim().toFloatOrNull()
                val v = p[1].trim().toFloatOrNull()
                if (u != null && v != null) {
                    hid.setAbsolute(true)
                    sink.pointerAbs(u, v)
                    Log.i("AirMouse", "abspoint -> $u,$v via ${sink.label}")
                }
            }
        }
        // 调试用：--ez autotext true --es imetext "文字"（onNewIntent 也要能用，不然得重启 App）
        val nlText = intent.getStringExtra("imetext")
        if (intent.getBooleanExtra("autotext", false) && !nlText.isNullOrEmpty()) {
            main.postDelayed({ sendComposedText(nlText) }, 900)
        }
        val f = intent.getIntExtra("flushms", 0)
        if (f > 0) {
            HidInput.flushMs = f.toLong()
            Log.i("AirMouse", "flushMs -> $f")
        }
        if (intent.getBooleanExtra("nudge", false) == true) {
            Log.i("AirMouse", "nudge begin: ${hid.debugState()}")
            main.postDelayed({
                for (i in 0 until 20) {
                    hid.mouseMove(5, 0)
                    Thread.sleep(30)
                }
                Log.i("AirMouse", "nudge end: ${hid.debugState()}")
            }, 300)
        }
        if (intent.getBooleanExtra("autodrag", false) == true) {
            val dragMs = intent.getIntExtra("dragms", 3000)
            val startDelay = intent.getIntExtra("dragdelay", 2000)
            main.postDelayed({ startSyntheticDrag(dragMs) }, startDelay.toLong())
        }
        applyCornerExtras(intent)
    }

    /** 调试用：--es corners "u1,v1,u2,v2" 直接写入两点校准；--ez clearcorners true 取消 */
    private fun applyCornerExtras(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra("clearcorners", false)) {
            ray.clearCorners()
            paintSensLabel()
            Log.i("AirMouse", "clearcorners")
        }
        i.getStringExtra("corners")?.let { s ->
            val p = s.split(",").map { it.trim().toFloatOrNull() }
            if (p.size == 4 && p.all { it != null }) {
                val ok = ray.applyCorners(p[0]!!, p[1]!!, p[2]!!, p[3]!!)
                paintSensLabel()
                Log.i("AirMouse", "corners -> $ok")
            }
        }
    }

    override fun onBackPressed() {
        if (keyboard.visibility == View.VISIBLE) {
            hideKeyboardPanel()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        gyro.stop()
        ray.stop()
        hid.close()
        net.stop()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }


    /** 应用内下载 APK（带进度），下完直接交给系统安装器 */
    private fun startApkDownload(info: Updater.Info) {
        val url = info.apkUrl
        if (url.isNullOrEmpty()) { toast("这个版本没有提供下载地址"); return }
        val name = "AirMouse-" + info.versionName + ".apk"

        Downloader.existing(this, name, 0L)?.let { f ->
            toast("已经下载好了：" + Downloader.sizeText(f.length()))
            askInstall(f)
            return
        }

        val d = resources.displayMetrics.density
        val px = { v: Int -> (v * d).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(2), 0, 0)
        }
        val tvLine = TextView(this).apply {
            textSize = 14f
            text = "正在连接…"
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(-1, px(10)).apply { topMargin = px(10) }
        }
        val tvHint = TextView(this).apply {
            textSize = 12f
            alpha = 0.65f
            setPadding(0, px(10), 0, 0)
            text = "4 MB 左右，按现在的网速大约半分钟。点「后台」可以边下边用手机，下完会提示安装。"
        }
        box.addView(tvLine)
        box.addView(bar)
        box.addView(tvHint)

        val sheet = showSheet(
            "下载 " + info.versionName,
            content = box,
            ok = "后台",
            cancel = "取消",
            onOk = { toast("继续在后台下载，完成会提示你安装") }
        )

        var task: Downloader.Task? = null
        task = Downloader.start(
            this, url, name, 0L,
            onProgress = { p ->
                if (sheet.isShowing) {
                    bar.progress = p.percent
                    tvLine.text = "已下载 " + Downloader.sizeText(p.bytes) + " / " + Downloader.sizeText(p.total) +
                        " · " + p.percent + "%" +
                        (if (p.bytesPerSec > 0) " · " + Downloader.speedText(p.bytesPerSec) else "")
                }
            },
            onDone = { f ->
                if (sheet.isShowing) sheet.dismiss()
                askInstall(f)
            },
            onError = { why ->
                if (sheet.isShowing) {
                    bar.progress = 0
                    tvLine.text = "下载失败：" + why
                } else {
                    toast("下载失败：" + why)
                }
            }
        )
        sheet.findViewById<Button>(R.id.dlgCancel)?.setOnClickListener {
            task?.cancel()
            sheet.dismiss()
        }
    }

    /** 问一句「装吗」，顺带处理「允许安装未知应用」 */
    private fun askInstall(file: java.io.File) {
        if (!Updater.canInstallApk(this)) {
            showSheet(
                "还要允许一次安装",
                "系统默认不许应用自己装 APK。点「去设置」找到「陀螺仪鼠标」→ 打开「允许安装未知应用」，回来再点一次安装就行（只需一次）。",
                ok = "去设置",
                cancel = "以后再说",
                onOk = { Updater.openInstallSettings(this) }
            )
            return
        }
        showSheet(
            "下载完成",
            file.name + " · " + Downloader.sizeText(file.length()),
            ok = "安装",
            cancel = "以后再说",
            onOk = {
                Updater.installApk(this, file)?.let { why -> toast("打不开安装器：" + why) }
            }
        )
    }
}

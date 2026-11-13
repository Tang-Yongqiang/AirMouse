package com.airmouse.gyro

import android.content.Context

class Prefs(context: Context) {

    companion object {
        /** 蓝牙 HID：手机直接变身鼠标键盘，电脑端零安装 */
        const val MODE_BT = 0

        /** WiFi：走局域网，手机和电脑同一个路由器，填电脑 IP */
        const val MODE_WIFI = 1

        /** USB：只插数据线，走 adb reverse 建的回环通道（127.0.0.1） */
        const val MODE_USB = 2

        /**
         * 手柄：手机上摊开一块虚拟手柄，走和 WiFi 一样的 TCP 链路。
         * 电脑端把它转成一只虚拟 Xbox 手柄（ViGEmBus），游戏里直接认。
         */
        const val MODE_PAD = 3

        /** 老名字，等同 WiFi（历史代码里还在用） */
        const val MODE_NET = MODE_WIFI

        /** 是不是网络类模式（WiFi / USB / 手柄都走 TCP） */
        fun isNetwork(mode: Int) = mode != MODE_BT

        /** 打字方式：直通键码（自绘键盘，一个键一个 HID 键码） */
        const val TYPE_KEYS = 0

        /** 打字方式：手机输入法（先打出成型的文字，再整段同步给电脑） */
        const val TYPE_IME = 1

        /** 滚轮惯性档位：关 / 弱 / 中 / 强 */
        const val FLING_OFF = 0
        const val FLING_WEAK = 1
        const val FLING_MID = 2
        const val FLING_STRONG = 3

        /** 档位名，settings 里的循环顺序就是这个 */
        val FLING_NAMES = arrayOf("关", "弱", "中", "强")

        /** 外观：跟随系统 / 浅色 / 深色 */
        const val APPEARANCE_SYSTEM = 0
        const val APPEARANCE_LIGHT = 1
        const val APPEARANCE_DARK = 2

        val APPEARANCE_NAMES = arrayOf("跟随系统", "浅色", "深色")
    }

    private val sp = context.getSharedPreferences("airmouse", Context.MODE_PRIVATE)

    var mode: Int
        get() = sp.getInt("mode", MODE_BT)
        set(v) = sp.edit().putInt("mode", v).apply()

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", NetInput.DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var sensitivity: Float
        get() = sp.getFloat("sens", 1f)
        set(v) = sp.edit().putFloat("sens", v).apply()

    var lastAddress: String
        get() = sp.getString("lastDev", "") ?: ""
        set(v) = sp.edit().putString("lastDev", v).apply()

    /** 已经「以后再说」过的新版本号：同一版不再每次启动都弹 */
    var updateSkipVersion: String
        get() = sp.getString("updateSkipVersion", "") ?: ""
        set(v) = sp.edit().putString("updateSkipVersion", v).apply()

    /**
     * 连接过的电脑地址，最近用的排最前，用逗号分隔。
     * 蓝牙设备列表靠它把「你的电脑」顶到最上面 —— 耳机、手表再也不会混在里面的。
     */
    var btHistory: String
        get() = sp.getString("btHistory", "") ?: ""
        set(v) = sp.edit().putString("btHistory", v).apply()

    /** 按「最近用过」的顺序，最多留 8 台 */
    fun btHistoryList(): List<String> =
        btHistory.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(8)

    fun rememberBt(address: String) {
        if (address.isBlank()) return
        val list = btHistoryList().toMutableList()
        list.remove(address)
        list.add(0, address)
        btHistory = list.take(8).joinToString(",")
        lastAddress = address
    }

    var typingMode: Int
        get() = sp.getInt("typingMode", TYPE_IME)
        set(v) = sp.edit().putInt("typingMode", v).apply()

    /** 2.11 起默认打字方式是手机输入法：老用户升上来时纠正一次，之后仍尊重用户自己切的 */
    var imeDefaultApplied: Boolean
        get() = sp.getBoolean("imeDefaultApplied", false)
        set(v) = sp.edit().putBoolean("imeDefaultApplied", v).apply()

    /** 陀螺仪按钮的三态：0 关 / 1 相对 / 2 射线（数值与 MainActivity.GYRO_* 一致） */
    var gyroMode: Int
        get() = sp.getInt("gyroMode", 0)
        set(v) = sp.edit().putInt("gyroMode", v).apply()

    /** 滚轮惯性强度：FLING_OFF..FLING_STRONG，默认「中」 */
    var flingLevel: Int
        get() = sp.getInt("flingLevel", FLING_MID)
        set(v) = sp.edit().putInt("flingLevel", v).apply()

    /** 外观：APPEARANCE_SYSTEM / LIGHT / DARK，默认跟随系统 */
    var appearance: Int
        get() = sp.getInt("appearance", APPEARANCE_SYSTEM)
        set(v) = sp.edit().putInt("appearance", v).apply()

    /**
     * 手柄模式的按键布局（用户在「布局」里拖出来的位置和大小）。
     * 格式见 GamepadView.exportLayout()；空串 = 全用默认布局。
     */
    var padLayout: String
        get() = sp.getString("padLayout", "") ?: ""
        set(v) = sp.edit().putString("padLayout", v).apply()

    /**
     * 手机当电脑麦克风（音频通道，默认 47778）。
     * 关掉就把这条连接彻底断掉，不留后台录音。
     */
    var audioMic: Boolean
        get() = sp.getBoolean("audioMic", false)
        set(v) = sp.edit().putBoolean("audioMic", v).apply()

    /** 电脑声音送到手机播放 */
    var audioMedia: Boolean
        get() = sp.getBoolean("audioMedia", false)
        set(v) = sp.edit().putBoolean("audioMedia", v).apply()

    /** 电脑声音的音量（0..100，占位给后面的音量条用） */
    var audioVolume: Int
        get() = sp.getInt("audioVolume", 100)
        set(v) = sp.edit().putInt("audioVolume", v).apply()

    /**
     * 麦克风优先：开麦的时候把手机外放静音。
     *
     * 为什么默认开：手机外放电脑声音，麦克风会把这声音一起收进去，
     * 对方就听到自己的回声。系统 AEC 对这种「非通话外放」不一定压得住，
     * 最稳的办法就是开麦时干脆不出声（要用外放又不回音，得戴耳机）。
     */
    var audioMicPriority: Boolean
        get() = sp.getBoolean("audioMicPriority", true)
        set(v) = sp.edit().putBoolean("audioMicPriority", v).apply()
}

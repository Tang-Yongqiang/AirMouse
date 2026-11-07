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

        /** 老名字，等同 WiFi（历史代码里还在用） */
        const val MODE_NET = MODE_WIFI

        /** 是不是网络类模式（WiFi / USB 都走 TCP） */
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
}

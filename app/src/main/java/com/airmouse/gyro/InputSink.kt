package com.airmouse.gyro

/**
 * 统一的输入出口。底层可以是蓝牙 HID，也可以是网络（PC 端 Python 程序）。
 * 所有方法都必须是非阻塞的，会被 UI 线程 / 传感器线程直接调用。
 */
interface InputSink {

    /** 界面上显示的通道名 */
    val label: String

    /** 相对移动，单位：像素（HID 里是鼠标计数） */
    fun mouseMove(dx: Int, dy: Int)

    /**
     * 绝对定位：把光标放到屏幕上的 (u, v)，两者都是 0..1 的归一化坐标（左上角为 0,0）。
     * 射线指向模式用这个；不支持的实现在这里退化成相对移动。
     */
    fun pointerAbs(u: Float, v: Float) {
        // 默认退化：什么都不做（子类按需覆盖）
    }

    /** 按键状态，button: 1=左 2=右 3=中 */
    fun mouseButton(button: Int, down: Boolean)

    /** 滚轮，正值向上 */
    fun wheel(notches: Int)

    /** 发送一次按键（按下+抬起），hidKey 是 HID Usage ID */
    fun keyPress(hidKey: Int, mods: Int)

    /** 整段文字（网络模式下支持中文） */
    fun typeText(text: String)

    fun close()

    companion object {
        const val BTN_LEFT = 1
        const val BTN_RIGHT = 2
        const val BTN_MIDDLE = 3
    }
}

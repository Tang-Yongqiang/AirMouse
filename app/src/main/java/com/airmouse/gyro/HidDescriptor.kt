package com.airmouse.gyro

/**
 * 复合 HID 报告描述符：一个设备同时暴露鼠标、键盘、绝对指针。
 *   Report ID 1: 相对鼠标   [按键(5bit)+填充(3bit), X, Y, Wheel]     = 4 字节
 *   Report ID 2: 键盘       [修饰键, 保留, 6 个键码]                = 8 字节
 *   Report ID 3: 绝对指针   [按键(3bit)+填充(5bit), X(u16), Y(u16), Wheel] = 6 字节
 *
 * 为什么要有 Report ID 3：射线指向模式算出来的是「屏幕上的绝对位置」，
 * 相对鼠标只能靠累加位移去逼近，既受指针加速影响、又会在屏幕边缘漂移，
 * 所以另开一个 absolute 指针集合，主机直接把光标放到指定坐标。
 *
 * 注意：sendReport() 的 data 里 **不含** report id，协议栈会自己补第 0 字节。
 */
object HidDescriptor {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    val COMBO: ByteArray = b(
        // ---------------- 鼠标 ----------------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1, 0x01,             // Collection (Application)
        0x85, 0x01,             //   Report ID (1)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1, 0x00,             //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Button)
        0x19, 0x01,             //     Usage Minimum (1)
        0x29, 0x05,             //     Usage Maximum (5)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x95, 0x05,             //     Report Count (5)
        0x75, 0x01,             //     Report Size (1)
        0x81, 0x02,             //     Input (Data,Var,Abs)
        0x95, 0x01,             //     Report Count (1)
        0x75, 0x03,             //     Report Size (3)
        0x81, 0x01,             //     Input (Const,Array,Abs)
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x03,             //     Report Count (3)
        0x81, 0x06,             //     Input (Data,Var,Rel)
        0xC0,                   //   End Collection (Physical)
        0xC0,                   // End Collection (Application)

        // ---------------- 键盘 ----------------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1, 0x01,             // Collection (Application)
        0x85, 0x02,             //   Report ID (2)
        0x05, 0x07,             //   Usage Page (Keyboard)
        0x19, 0xE0,             //   Usage Minimum (Left Ctrl 0xE0)
        0x29, 0xE7,             //   Usage Maximum (Right GUI 0xE7)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x08,             //   Report Count (8)
        0x81, 0x02,             //   Input (Data,Var,Abs)   修饰键位图
        0x95, 0x01,             //   Report Count (1)
        0x75, 0x08,             //   Report Size (8)
        0x81, 0x01,             //   Input (Const)          保留字节
        0x95, 0x05,             //   Report Count (5)
        0x75, 0x01,             //   Report Size (1)
        0x05, 0x08,             //   Usage Page (LEDs)
        0x19, 0x01,             //   Usage Minimum (Num Lock)
        0x29, 0x05,             //   Usage Maximum (Kana)
        0x91, 0x02,             //   Output (Data,Var,Abs)
        0x95, 0x01,             //   Report Count (1)
        0x75, 0x03,             //   Report Size (3)
        0x91, 0x01,             //   Output (Const)
        0x95, 0x06,             //   Report Count (6)
        0x75, 0x08,             //   Report Size (8)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x05, 0x07,             //   Usage Page (Keyboard)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x81, 0x00,             //   Input (Data,Array)     键码数组
        0xC0,                   // End Collection (Application)

        // ---------------- 绝对指针（射线指向模式） ----------------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1, 0x01,             // Collection (Application)
        0x85, 0x03,             //   Report ID (3)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1, 0x00,             //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Button)
        0x19, 0x01,             //     Usage Minimum (1)
        0x29, 0x03,             //     Usage Maximum (3)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x95, 0x03,             //     Report Count (3)
        0x75, 0x01,             //     Report Size (1)
        0x81, 0x02,             //     Input (Data,Var,Abs)
        0x95, 0x01,             //     Report Count (1)
        0x75, 0x05,             //     Report Size (5)
        0x81, 0x01,             //     Input (Const,Array,Abs)  填充到整字节
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x16, 0x00, 0x00,       //     Logical Minimum (0)
        0x26, 0xFF, 0x7F,       //     Logical Maximum (32767)
        0x75, 0x10,             //     Report Size (16)
        0x95, 0x02,             //     Report Count (2)
        0x81, 0x02,             //     Input (Data,Var,Abs)    绝对坐标
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x01,             //     Report Count (1)
        0x81, 0x06,             //     Input (Data,Var,Rel)    滚轮仍然是相对的
        0xC0,                   //   End Collection (Physical)
        0xC0                    // End Collection (Application)
    )
}

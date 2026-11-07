package com.airmouse.gyro

/**
 * HID Usage ID（Keyboard/Keypad Page, 0x07）与字符映射。
 * 参考：USB HID Usage Tables 1.12 第 10 章。
 */
object HidKeys {

    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08

    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val CAPS_LOCK = 0x39

    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val ARROW_RIGHT = 0x4F
    const val ARROW_LEFT = 0x50
    const val ARROW_DOWN = 0x51
    const val ARROW_UP = 0x52

    const val F1 = 0x3A
    const val F2 = 0x3B
    const val F5 = 0x3E
    const val F11 = 0x44
    const val F12 = 0x45

    private val plain: Map<Char, Int> = buildMap {
        "abcdefghijklmnopqrstuvwxyz".forEachIndexed { i, c -> put(c, 0x04 + i) }
        "1234567890".forEachIndexed { i, c -> put(c, 0x1E + i) }
        put(' ', SPACE)
        put('-', 0x2D)
        put('=', 0x2E)
        put('[', 0x2F)
        put(']', 0x30)
        put('\\', 0x31)
        put(';', 0x33)
        put('\'', 0x34)
        put('`', 0x35)
        put(',', 0x36)
        put('.', 0x37)
        put('/', 0x38)
    }

    private val shifted: Map<Char, Int> = buildMap {
        put('!', 0x1E)
        put('@', 0x1F)
        put('#', 0x20)
        put('$', 0x21)
        put('%', 0x22)
        put('^', 0x23)
        put('&', 0x24)
        put('*', 0x25)
        put('(', 0x26)
        put(')', 0x27)
        put('_', 0x2D)
        put('+', 0x2E)
        put('{', 0x2F)
        put('}', 0x30)
        put('|', 0x31)
        put(':', 0x33)
        put('"', 0x34)
        put('~', 0x35)
        put('<', 0x36)
        put('>', 0x37)
        put('?', 0x38)
    }

    /** 字符 → (HID usage, 修饰键)。无法用键盘发出的字符返回 null。 */
    fun charKey(c: Char): Pair<Int, Int>? {
        if (c in 'A'..'Z') {
            val u = plain[c.lowercaseChar()] ?: return null
            return u to MOD_SHIFT
        }
        plain[c]?.let { return it to 0 }
        shifted[c]?.let { return it to MOD_SHIFT }
        return null
    }
}

using System;
using System.Runtime.InteropServices;

/// <summary>
/// 运行在 powershell.exe（有签名）这个宿主进程里，真正执行键鼠注入。
/// 主程序未签名，在后台（最小化/被遮挡）时会被系统拒绝注入，所以命令都转发到这里。
/// 命令（stdin 一行一条）：
///   M dx dy      相对移动
///   A u v        绝对定位（0~1 的虚拟桌面比例）
///   B b d        按键 b(1左/2右/3中) d(1按下/0抬起)
///   W v          滚轮格数
///   V vk mods    按键（vk 虚拟键码，mods 位掩码 1=Ctrl 2=Shift 4=Alt 8=Win）
///   S text       逐字符 Unicode 文本
///   Q            退出
/// </summary>
public class AirMouseChild
{
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int i);
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inp, int size);

    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT { public uint type; public InputUnion U; }

    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr extra; }

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr extra; }

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint MOVE = 0x0001, LDOWN = 0x0002, LUP = 0x0004, RDOWN = 0x0008, RUP = 0x0010,
               MDOWN = 0x0020, MUP = 0x0040, WHEEL = 0x0800;
    const uint KEYUP = 0x0002, UNICODE = 0x0004;
    const int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;

    static void MouseEv(uint flags, int dx, int dy, int data)
    {
        var a = new INPUT[1];
        a[0].type = INPUT_MOUSE;
        a[0].U.mi.dx = dx; a[0].U.mi.dy = dy;
        a[0].U.mi.dwFlags = flags; a[0].U.mi.mouseData = (uint)data;
        SendInput(1, a, Marshal.SizeOf(typeof(INPUT)));
    }

    static void KeyVk(int vk, bool up)
    {
        var a = new INPUT[1];
        a[0].type = INPUT_KEYBOARD;
        a[0].U.ki.wVk = (ushort)vk;
        a[0].U.ki.dwFlags = up ? KEYUP : 0;
        SendInput(1, a, Marshal.SizeOf(typeof(INPUT)));
    }

    static void KeyUni(int unit, bool up)
    {
        var a = new INPUT[1];
        a[0].type = INPUT_KEYBOARD;
        a[0].U.ki.wVk = 0;
        a[0].U.ki.wScan = (ushort)unit;
        a[0].U.ki.dwFlags = UNICODE | (up ? KEYUP : 0);
        SendInput(1, a, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void Run()
    {
        var stdin = Console.In;
        string line;
        while ((line = stdin.ReadLine()) != null)
        {
            if (line.Length == 0) continue;
            char c = line[0];
            string rest = line.Length > 1 ? line.Substring(1).Trim() : string.Empty;
            try
            {
                if (c == 'Q') break;
                else if (c == 'M')
                {
                    var p = rest.Split(' ');
                    int dx = int.Parse(p[0]), dy = int.Parse(p[1]);
                    POINT cur; GetCursorPos(out cur);
                    if (!SetCursorPos(cur.X + dx, cur.Y + dy)) MouseEv(MOVE, dx, dy, 0);
                }
                else if (c == 'A')
                {
                    var p = rest.Split(' ');
                    double u = double.Parse(p[0], System.Globalization.CultureInfo.InvariantCulture);
                    double v = double.Parse(p[1], System.Globalization.CultureInfo.InvariantCulture);
                    int x0 = GetSystemMetrics(SM_XVIRTUALSCREEN), y0 = GetSystemMetrics(SM_YVIRTUALSCREEN);
                    int w = GetSystemMetrics(SM_CXVIRTUALSCREEN), h = GetSystemMetrics(SM_CYVIRTUALSCREEN);
                    int x = x0 + (int)Math.Round(u * (w - 1));
                    int y = y0 + (int)Math.Round(v * (h - 1));
                    if (!SetCursorPos(x, y)) MouseEv(MOVE | 0x8000 | 0x4000, (int)(u * 65535), (int)(v * 65535), 0);
                }
                else if (c == 'B')
                {
                    var p = rest.Split(' ');
                    int btn = int.Parse(p[0]), d = int.Parse(p[1]);
                    uint f;
                    if (btn == 2) f = d == 1 ? RDOWN : RUP;
                    else if (btn == 3) f = d == 1 ? MDOWN : MUP;
                    else f = d == 1 ? LDOWN : LUP;
                    MouseEv(f, 0, 0, 0);
                }
                else if (c == 'W') MouseEv(WHEEL, 0, 0, int.Parse(rest) * 120);
                else if (c == 'V')
                {
                    var p = rest.Split(' ');
                    int vk = int.Parse(p[0]), mods = int.Parse(p[1]);
                    int[] mvk = { 0xA2, 0xA0, 0xA4, 0x5B };
                    for (int i = 0; i < 4; i++) if ((mods & (1 << i)) != 0) KeyVk(mvk[i], false);
                    KeyVk(vk, false); KeyVk(vk, true);
                    for (int i = 3; i >= 0; i--) if ((mods & (1 << i)) != 0) KeyVk(mvk[i], true);
                }
                else if (c == 'S')
                {
                    foreach (char ch in rest)
                    {
                        if (ch == '\t') { KeyVk(0x09, false); KeyVk(0x09, true); }
                        else { KeyUni(ch, false); KeyUni(ch, true); }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("cmd failed: " + line + " :: " + ex.Message);
            }
        }
    }
}

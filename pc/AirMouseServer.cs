// AirMouse 接收端（Windows 单文件 exe）
// 编译：csc /target:winexe /codepage:65001 /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll AirMouseServer.cs
//
// 与 pc/airmouse_server.py 协议完全一致：一行一个 JSON
//   {"t":"m","dx":1,"dy":-2}        相对移动
//   {"t":"a","u":0.5,"v":0.5}       绝对定位（0..1，射线模式）
//   {"t":"b","b":1,"d":true}        鼠标键 1左 2右 3中
//   {"t":"w","v":1}                 滚轮
//   {"t":"k","code":40,"mods":0}    HID usage 按键
//   {"t":"s","text":"你好"}         整段文字（KEYEVENTF_UNICODE）

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

namespace AirMouse
{
    // ------------------------------------------------------------ Win32
    internal static class Native
    {
        public const int INPUT_MOUSE = 0;
        public const int INPUT_KEYBOARD = 1;

        public const uint MOUSEEVENTF_MOVE = 0x0001;
        public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        public const uint MOUSEEVENTF_LEFTUP = 0x0004;
        public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        public const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        public const uint MOUSEEVENTF_WHEEL = 0x0800;
        public const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
        public const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;

        public const uint KEYEVENTF_KEYUP = 0x0002;
        public const uint KEYEVENTF_UNICODE = 0x0004;

        public const int WHEEL_DELTA = 120;

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

        [DllImport("user32.dll")]
        public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        public static extern bool SetForegroundWindow(IntPtr hWnd);

        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

        /// <summary>按进程名找那个实例的顶层窗口（窗口被 Hide 过也能找到，FindWindow 按标题会漏）</summary>
        public static IntPtr FindWindowByProcess(string exeName)
        {
            var ids = new System.Collections.Generic.HashSet<uint>();
            foreach (var p in System.Diagnostics.Process.GetProcessesByName(exeName))
            {
                ids.Add((uint)p.Id);
            }
            if (ids.Count == 0) return IntPtr.Zero;
            IntPtr found = IntPtr.Zero;
            try
            {
                EnumWindows((h, l) =>
                {
                    uint pid;
                    GetWindowThreadProcessId(h, out pid);
                    if (ids.Contains(pid))
                    {
                        found = h;
                        return false;
                    }
                    return true;
                }, IntPtr.Zero);
            }
            catch { }
            return found;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct HARDWAREINPUT
        {
            public uint uMsg;
            public ushort wParamL;
            public ushort wParamH;
        }

        [StructLayout(LayoutKind.Explicit)]
        public struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct INPUT
        {
            public int type;
            public InputUnion U;
        }

        [DllImport("user32.dll", SetLastError = true)]
        public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [StructLayout(LayoutKind.Sequential)]
        public struct POINT { public int X; public int Y; }

        [DllImport("user32.dll")]
        public static extern bool GetCursorPos(out POINT p);

        [DllImport("user32.dll")]
        public static extern bool SetCursorPos(int x, int y);

        public const int SM_XVIRTUALSCREEN = 76;
        public const int SM_YVIRTUALSCREEN = 77;
        public const int SM_CXVIRTUALSCREEN = 78;
        public const int SM_CYVIRTUALSCREEN = 79;

        [DllImport("user32.dll")]
        public static extern int GetSystemMetrics(int index);

        [DllImport("user32.dll")]
        public static extern bool SetProcessDPIAware();
    }

    /// <summary>把手机发来的指令翻译成 SendInput。</summary>
    internal static class Injector
    {
        private static readonly Dictionary<int, int> HidToVk = BuildMap();
        private static readonly int[][] ModMap = new int[][]
        {
            new int[] { 0x01, 0xA2 }, // LCtrl
            new int[] { 0x02, 0xA0 }, // LShift
            new int[] { 0x04, 0xA4 }, // LAlt
            new int[] { 0x08, 0x5B }, // LWin
        };

        private static Dictionary<int, int> BuildMap()
        {
            var m = new Dictionary<int, int>();
            for (int i = 0; i < 26; i++) m[0x04 + i] = 0x41 + i;
            int[] digits = { 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x30 };
            for (int i = 0; i < 10; i++) m[0x1E + i] = digits[i];
            m[0x28] = 0x0D; m[0x29] = 0x1B; m[0x2A] = 0x08; m[0x2B] = 0x09; m[0x2C] = 0x20;
            m[0x2D] = 0xBD; m[0x2E] = 0xBB; m[0x2F] = 0xDB; m[0x30] = 0xDD; m[0x31] = 0xDC;
            m[0x33] = 0xBA; m[0x34] = 0xDE; m[0x35] = 0xC0; m[0x36] = 0xBC; m[0x37] = 0xBE;
            m[0x38] = 0xBF; m[0x39] = 0x14;
            m[0x4A] = 0x24; m[0x4B] = 0x21; m[0x4C] = 0x2E; m[0x4D] = 0x23; m[0x4E] = 0x22;
            m[0x4F] = 0x27; m[0x50] = 0x25; m[0x51] = 0x28; m[0x52] = 0x26;
            for (int i = 0; i < 12; i++) m[0x3A + i] = 0x70 + i;
            return m;
        }

        private static bool Send(Native.INPUT inp)
        {
            var arr = new Native.INPUT[] { inp };
            uint n = Native.SendInput(1, arr, Marshal.SizeOf(typeof(Native.INPUT)));
            if (n == 0)
            {
                Log.Write("  ! SendInput 失败，错误码 " + Marshal.GetLastWin32Error());
                return false;
            }
            return true;
        }

        private static bool Mouse(uint flags, int dx = 0, int dy = 0, int data = 0)
        {
            var inp = new Native.INPUT();
            inp.type = Native.INPUT_MOUSE;
            inp.U.mi = new Native.MOUSEINPUT();
            inp.U.mi.dx = dx;
            inp.U.mi.dy = dy;
            inp.U.mi.mouseData = (uint)data;
            inp.U.mi.dwFlags = flags;
            return Send(inp);
        }

        private static void KeyVk(int vk, bool up)
        {
            var inp = new Native.INPUT();
            inp.type = Native.INPUT_KEYBOARD;
            inp.U.ki = new Native.KEYBDINPUT();
            inp.U.ki.wVk = (ushort)vk;
            inp.U.ki.dwFlags = up ? Native.KEYEVENTF_KEYUP : 0;
            Send(inp);
        }

        private static void KeyUnicode(int unit, bool up)
        {
            var inp = new Native.INPUT();
            inp.type = Native.INPUT_KEYBOARD;
            inp.U.ki = new Native.KEYBDINPUT();
            inp.U.ki.wVk = 0;
            inp.U.ki.wScan = (ushort)unit;
            inp.U.ki.dwFlags = Native.KEYEVENTF_UNICODE | (up ? Native.KEYEVENTF_KEYUP : 0);
            Send(inp);
        }

        /// <summary>
        /// 相对移动。优先用 SetCursorPos：它没有指针加速，行为更可预测。
        /// 注意（实测）：火绒/360 这类安全软件会把「未签名程序在非前台时」的
        /// SetCursorPos 直接判失败、SendInput 静默丢弃 —— 两种情况都没法靠代码绕过，
        /// 只能让用户把本程序加入信任区，或让本窗口保持在前台。
        /// </summary>
        private static int _moveProbe;
        private static bool _blockedHintLogged;

        public static void Move(int dx, int dy)
        {
            // 有签名宿主在就直接交给它（未签名进程在后台会被系统拒绝注入）
            if (HostBridge.Alive && HostBridge.Send("M " + dx + " " + dy)) return;

            Native.POINT p;
            bool got = Native.GetCursorPos(out p);
            bool ok = got && Native.SetCursorPos(p.X + dx, p.Y + dy);
            if (++_moveProbe % 60 == 1)
                Log.Write(string.Format("  · move probe got={0} pos={1},{2} set={3} sm={4}x{5}",
                    got, p.X, p.Y, ok,
                    Native.GetSystemMetrics(Native.SM_CXVIRTUALSCREEN),
                    Native.GetSystemMetrics(Native.SM_CYVIRTUALSCREEN)));
            if (ok) return;
            if (!_blockedHintLogged)
            {
                _blockedHintLogged = true;
                Log.Write("[!] 系统拒绝了本程序移动光标（SetCursorPos 失败）。常见原因：火绒/360 只放行「前台」的未签名程序动鼠标。");
                Log.Write("    解决办法：把 AirMouseServer.exe 加进安全软件的信任区，或关掉它「系统加固 → 键鼠模拟/修改光标」这类拦截。");
                Log.Write("    临时办法：让本窗口保持前台（不要最小化、也不要把焦点切走）。");
            }
            Mouse(Native.MOUSEEVENTF_MOVE, dx, dy);
        }

        /// <summary>
        /// 试点移动一次并读回光标位置，判断 SendInput 是否真的生效。
        /// 安全软件（火绒/360）会「返回成功但吞掉输入」，只能靠实测判断。
        /// </summary>
        public static bool SelfTestMove()
        {
            try
            {
                Native.POINT before;
                if (!Native.GetCursorPos(out before)) return true;

                // 连试 3 次：刚启动时偶发的第一次 SendInput 可能被吞，单次判断会误报
                for (int i = 0; i < 3; i++)
                {
                    Mouse(Native.MOUSEEVENTF_MOVE, 12, 8);
                    System.Threading.Thread.Sleep(120);

                    Native.POINT after;
                    if (!Native.GetCursorPos(out after)) return true;
                    if (after.X != before.X || after.Y != before.Y)
                    {
                        // 挪回原位，别让用户的光标莫名跳一下
                        Mouse(Native.MOUSEEVENTF_MOVE, before.X - after.X, before.Y - after.Y);
                        return true;
                    }
                }
                return false;
            }
            catch
            {
                return true; // 测不出来就别吓用户
            }
        }

        public static void PointerAbs(double u, double v)
        {
            if (u < 0) u = 0; if (u > 1) u = 1;
            if (v < 0) v = 0; if (v > 1) v = 1;

            if (HostBridge.Alive && HostBridge.Send("A " +
                u.ToString("F4", System.Globalization.CultureInfo.InvariantCulture) + " " +
                v.ToString("F4", System.Globalization.CultureInfo.InvariantCulture))) return;

            // 同上：绝对定位也优先 SetCursorPos（按虚拟桌面像素算，避免 65535 取整误差）
            int w = Native.GetSystemMetrics(Native.SM_CXVIRTUALSCREEN);
            int h = Native.GetSystemMetrics(Native.SM_CYVIRTUALSCREEN);
            if (w > 1 && h > 1)
            {
                int x0 = Native.GetSystemMetrics(Native.SM_XVIRTUALSCREEN);
                int y0 = Native.GetSystemMetrics(Native.SM_YVIRTUALSCREEN);
                int x = x0 + (int)Math.Round(u * (w - 1));
                int y = y0 + (int)Math.Round(v * (h - 1));
                if (Native.SetCursorPos(x, y)) return;
                if (!_blockedHintLogged)
                {
                    _blockedHintLogged = true;
                    Log.Write("[!] 系统拒绝了本程序移动光标（SetCursorPos 失败）。常见原因：火绒/360 只放行「前台」的未签名程序动鼠标。");
                    Log.Write("    解决办法：把 AirMouseServer.exe 加进安全软件的信任区，或关掉它「系统加固 → 键鼠模拟/修改光标」这类拦截。");
                    Log.Write("    临时办法：让本窗口保持前台（不要最小化、也不要把焦点切走）。");
                }
            }

            int ax = (int)Math.Round(u * 65535.0);
            int ay = (int)Math.Round(v * 65535.0);
            Mouse(Native.MOUSEEVENTF_MOVE | Native.MOUSEEVENTF_ABSOLUTE | Native.MOUSEEVENTF_VIRTUALDESK, ax, ay);
        }

        public static bool Wheel(int notches)
        {
            if (HostBridge.Alive && HostBridge.Send("W " + notches)) return true;
            return Mouse(Native.MOUSEEVENTF_WHEEL, 0, 0, notches * Native.WHEEL_DELTA);
        }

        public static bool Button(int b, bool down)
        {
            if (HostBridge.Alive && HostBridge.Send("B " + b + " " + (down ? 1 : 0))) return true;
            uint f;
            if (b == 2) f = down ? Native.MOUSEEVENTF_RIGHTDOWN : Native.MOUSEEVENTF_RIGHTUP;
            else if (b == 3) f = down ? Native.MOUSEEVENTF_MIDDLEDOWN : Native.MOUSEEVENTF_MIDDLEUP;
            else f = down ? Native.MOUSEEVENTF_LEFTDOWN : Native.MOUSEEVENTF_LEFTUP;
            return Mouse(f);
        }

        public static bool Key(int usage, int mods)
        {
            int vk;
            if (!HidToVk.TryGetValue(usage, out vk)) return false;
            if (HostBridge.Alive && HostBridge.Send("V " + vk + " " + mods)) return true;
            var held = new List<int>();
            foreach (var pair in ModMap)
            {
                if ((mods & pair[0]) != 0) { held.Add(pair[1]); KeyVk(pair[1], false); }
            }
            KeyVk(vk, false);
            KeyVk(vk, true);
            for (int i = held.Count - 1; i >= 0; i--) KeyVk(held[i], true);
            return true;
        }

        public static void Text(string s)
        {
            if (s == null) return;
            if (HostBridge.Alive)
            {
                string norm = s.Replace("\r\n", "\n").Replace('\r', '\n');
                string[] parts = norm.Split('\n');
                for (int i = 0; i < parts.Length; i++)
                {
                    if (i > 0) HostBridge.Send("V 13 0");
                    if (parts[i].Length > 0) HostBridge.Send("S " + parts[i]);
                }
                return;
            }
            foreach (char ch in s)
            {
                if (ch == '\n' || ch == '\r') { KeyVk(0x0D, false); KeyVk(0x0D, true); continue; }
                if (ch == '\t') { KeyVk(0x09, false); KeyVk(0x09, true); continue; }
                KeyUnicode(ch, false);
                KeyUnicode(ch, true);
            }
        }
    }

    // ------------------------------------------------------------ 日志
    internal static class Log
    {
        private static readonly object Sync = new object();
        public static Action<string> Sink;
        public static readonly string LogPath = BuildPath();

        private static string BuildPath()
        {
            var candidates = new List<string>();
            // 优先写 exe 同目录：装到 Program Files 后本进程是管理员，写得进去；
            // 而 %LOCALAPPDATA% 在本机实测会被安全软件拒（未签名进程写用户目录）。
            try
            {
                string dir = Path.GetDirectoryName(Application.ExecutablePath);
                if (!string.IsNullOrEmpty(dir)) candidates.Add(Path.Combine(dir, "AirMouseServer.log"));
            }
            catch { }
            try
            {
                string la = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                if (!string.IsNullOrEmpty(la)) candidates.Add(Path.Combine(Path.Combine(la, "AirMouse"), "exe.log"));
            }
            catch { }
            try { candidates.Add(Path.Combine(Path.GetTempPath(), "AirMouseServer.log")); } catch { }

            foreach (var path in candidates)
            {
                try
                {
                    string dir = Path.GetDirectoryName(path);
                    if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                    File.AppendAllText(path, "", Encoding.UTF8);
                    return path;
                }
                catch { }
            }
            return null;
        }

        public static void Write(string line)
        {
            string stamped = DateTime.Now.ToString("HH:mm:ss ") + line;
            lock (Sync)
            {
                try { if (LogPath != null) File.AppendAllText(LogPath, stamped + Environment.NewLine, Encoding.UTF8); }
                catch { }
            }
            var sink = Sink;
            if (sink != null)
            {
                try { sink(stamped); } catch { }
            }
        }
    }

    // ------------------------------------------------------------ 主窗体
    internal sealed class MainForm : Form
    {
        private const int Port = 47777;

        private readonly Label _status = new Label();
        private readonly Label _ips = new Label();
        private readonly LinkLabel _updateLink = new LinkLabel();
        private string _updateUrl;

        /// <summary>
        /// 多个源按顺序试，第一个能通的就用（国内直连 raw.githubusercontent 常常不通）。
        /// 发布前把 CHANGE_ME 换成你自己的仓库，和 App 里 Updater.MANIFEST_URLS 保持一致。
        /// </summary>
        public const string TagsUrl = "https://api.github.com/repos/Tang-Yongqiang/AirMouse/tags?per_page=20";
        public static readonly string[] ManifestUrls =
        {
            "https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/update.json",
            "https://raw.githubusercontent.com/Tang-Yongqiang/AirMouse/main/update.json",
            "https://gitee.com/Tang-Yongqiang/AirMouse/raw/main/update.json"
        };

        /// <summary>
        /// GitHub 的 tag 列表（不走 CDN、无缓存），用来发现最新版本。
        /// jsDelivr 对分支（@main）有整仓快照缓存、最长 12 小时，光靠它判断新版本会滞后。
        /// </summary>

        /// <summary>取语义化版本最大的 tag（形如 v2.28），失败返回 null</summary>
        private static int ManifestCode(string json)
        {
            var ser = new JavaScriptSerializer();
            var map = ser.Deserialize<Dictionary<string, object>>(json);
            object v;
            if (map != null && map.TryGetValue("versionCode", out v) && v != null)
            {
                int code;
                if (int.TryParse(v.ToString(), out code)) return code;
            }
            return -1;
        }
        private void StartUpdateCheck()
        {
            var t = new Thread(() =>
            {
                try
                {
                    // .NET Framework 4.x 默认可能不启用 TLS 1.2，GitHub 现在只收 TLS 1.2+
                    ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11;
                    string json = null;
                    var errors = new StringBuilder();
                    // 第一步：直接用「本机版本号往后猜」去取清单（我们自己按 1.0.1、1.0.2 顺序发版，
                    // 猜 3 个就够）。这条路不碰接口、也不碰 CDN 的分支缓存，最稳。
                    try
                    {
                        var cur = typeof(MainForm).Assembly.GetName().Version;
                        foreach (int step in new int[] { 1, 2, 3, 0, -1, -2 })
                        {
                            string gt = "v" + cur.Major + "." + cur.Minor + "." + (cur.Build + step);
                            if (cur.Build + step < 0) continue;
                            string gu = "https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@" + gt + "/update.json";
                            try
                            {
                                string body = Download(gu);
                                Log.Write("[i] 猜测 @" + gt + " versionCode=" + ManifestCode(body));
                                if (string.IsNullOrEmpty(json) || ManifestCode(body) > ManifestCode(json)) json = body;
                            }
                            catch { }
                        }
                    }
                    catch { }

                    // 第二步（猜不到时）：问 tags 接口，再按 tag 逐个取清单。
                    // 为什么不是只读 @main：jsDelivr 的分支快照会卡住（实测一直停在旧版本），
                    // 而按 tag 取是不可变快照、发版后立刻就能拿到。先问 tags 接口，再按 tag 逐个取清单，
                    // 挑 versionCode 最大的那个（不依赖接口返回顺序）。
                    try
                    {
                        if (!string.IsNullOrEmpty(json)) throw new Exception("skip");
                        var tagsJson = Download(TagsUrl);
                        var ser0 = new JavaScriptSerializer();
                        var tags = ser0.Deserialize<List<Dictionary<string, object>>>(tagsJson);
                        int bestCode = -1;
                        int tried = 0;
                        foreach (var item in tags)
                        {
                            if (tried >= 5) break;
                            object n;
                            if (!item.TryGetValue("name", out n) || n == null) continue;
                            string tag = n.ToString();
                            if (!tag.StartsWith("v")) continue;
                            tried++;
                            string u = "https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@" + tag + "/update.json";
                            try
                            {
                                string body = Download(u);
                                int code = ManifestCode(body);
                                Log.Write("[i] @" + tag + " versionCode=" + code);
                                if (code > bestCode) { bestCode = code; json = body; }
                            }
                            catch (Exception ex)
                            {
                                Log.Write("[i] @" + tag + " 不可用 · " + ex.Message);
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Write("[i] tags 接口不可用 · " + ex.Message);
                    }
                    // 兜底：tags 接口不通时按固定清单试一遍（可能偏旧，但总比没有强）
                    if (string.IsNullOrEmpty(json))
                    {
                        foreach (var url in ManifestUrls)
                        {
                            try
                            {
                                json = Download(url);
                                Log.Write("[i] 更新源可用：" + HostOf(url));
                                break;
                            }
                            catch (Exception ex)
                            {
                                if (errors.Length > 0) errors.Append("；");
                                errors.Append(HostOf(url)).Append(" ").Append(ex.Message);
                                Log.Write("[i] 更新源不可用：" + HostOf(url) + " · " + ex.Message);
                            }
                        }
                    }
                    if (string.IsNullOrEmpty(json))
                    {
                        Log.Write("[i] 检查更新失败（不影响使用）：所有源都不可用 · " + errors);
                        return;
                    }
                    var ser = new JavaScriptSerializer();
                    var map = ser.Deserialize<Dictionary<string, object>>(json);
                    if (map == null) return;
                    object v;
                    string name = map.TryGetValue("versionName", out v) && v != null ? v.ToString() : "";
                    string exe = map.TryGetValue("exe", out v) && v != null ? v.ToString() : "";
                    string notes = map.TryGetValue("notes", out v) && v != null ? v.ToString() : "";
                    if (string.IsNullOrEmpty(name)) return;
                    if (!IsNewer(name)) return;

                    _updateUrl = exe;
                    BeginInvoke((Action)(() =>
                    {
                        _updateLink.Text = "发现新版本 " + name + " → 点这里下载（当前 " + CurrentVersion() + "）";
                        _updateLink.Visible = true;
                        Log.Write("[i] 发现新版本 " + name + "：" + (string.IsNullOrEmpty(exe) ? "（清单里没给下载地址）" : exe));
                        if (!string.IsNullOrEmpty(notes)) Log.Write("    " + notes.Replace("\n", "\n    "));
                    }));
                }
                catch (Exception ex)
                {
                    Log.Write("[i] 检查更新失败（不影响使用）：" + ex.Message);
                }
            });
            t.IsBackground = true;
            t.Start();
        }

        /// <summary>带超时的下载：WebClient 没有超时，网络卡住会一直挂着</summary>
        private static string Download(string url)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Timeout = 6000;
            req.ReadWriteTimeout = 6000;
            req.UserAgent = "AirMouseServer/" + CurrentVersion();
            req.CachePolicy = new System.Net.Cache.RequestCachePolicy(System.Net.Cache.RequestCacheLevel.NoCacheNoStore);
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var rs = resp.GetResponseStream())
            using (var sr = new StreamReader(rs, Encoding.UTF8))
            {
                return sr.ReadToEnd();
            }
        }

        private static string HostOf(string url)
        {
            try
            {
                return new Uri(url).Host;
            }
            catch
            {
                return url;
            }
        }

        private static string CurrentVersion()
        {
            var v = typeof(MainForm).Assembly.GetName().Version;
            return v.Major + "." + v.Minor + "." + v.Build;
        }

        /// <summary>比较 "1.0.0" 这种三段式版本号（两段也能用），比当前大就返回 true</summary>
        private static bool IsNewer(string remote)
        {
            // 支持 1.0.0 / 1.0 / 1 三种写法，按 主.次.修订 逐段比较
            var v = typeof(MainForm).Assembly.GetName().Version;
            var parts = remote.Split('.');
            int major = 0, minor = 0, patch = 0;
            if (parts.Length == 0 || !int.TryParse(parts[0], out major)) return false;
            if (parts.Length > 1) int.TryParse(parts[1], out minor);
            if (parts.Length > 2) int.TryParse(parts[2], out patch);
            if (major != v.Major) return major > v.Major;
            if (minor != v.Minor) return minor > v.Minor;
            return patch > v.Build;
        }
        private readonly TextBox _log = new TextBox();
        private readonly CheckBox _autostart = new CheckBox();
        private bool _autostartReverting;
        private FlowLayoutPanel _btnRow;
        private readonly List<string> _clients = new List<string>();

        private TcpListener _listener;
        private volatile bool _running = true;

        public MainForm()
        {
            Text = "AirMouse 接收端";
            ClientSize = new Size(700, 470);
            MinimumSize = new Size(480, 360);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9f);

            _status.Text = "正在启动…";
            _status.AutoSize = false;
            _status.Height = 26;
            _status.Dock = DockStyle.Top;
            _status.TextAlign = ContentAlignment.MiddleLeft;
            _status.Padding = new Padding(16, 0, 16, 0);

            _ips.AutoSize = false;
            _ips.Height = 24;
            _ips.Dock = DockStyle.Top;
            _ips.TextAlign = ContentAlignment.MiddleLeft;
            _ips.Padding = new Padding(16, 0, 16, 0);
            _ips.ForeColor = Color.FromArgb(90, 90, 90);

            _log.Multiline = true;
            _log.ReadOnly = true;
            _log.ScrollBars = ScrollBars.Vertical;
            _log.BackColor = Color.FromArgb(250, 250, 250);
            _log.Font = new Font("Consolas", 9f);
            _log.Dock = DockStyle.Fill;
            _log.Margin = new Padding(0);

            _autostart.Text = "开机自动启动";
            _autostart.AutoSize = true;
            _autostart.Dock = DockStyle.Left;
            _autostart.Padding = new Padding(6, 0, 6, 0);
            _autostart.TextAlign = ContentAlignment.MiddleLeft;
            _autostart.Checked = IsAutostartEnabled();
            _autostart.CheckedChanged += (s, e) =>
            {
                if (_autostartReverting) return;
                if (SetAutostart(_autostart.Checked)) return;
                if (!_autostart.Checked) return;
                // 三条路都走不通：把勾去掉，并告诉用户手动放快捷方式的办法。
                // 注意：这里绝对不要自己去拉 explorer.exe —— 受限令牌/安全软件下会弹
                // 「explorer.exe 应用程序无法正常启动(0xc0000142)」，比不打开更糟。
                _autostartReverting = true;
                _autostart.Checked = false;
                _autostartReverting = false;
                string dir = Environment.GetFolderPath(Environment.SpecialFolder.Startup);
                bool copied = false;
                try { Clipboard.SetText(dir); copied = true; } catch { }
                MessageBox.Show(
                    "系统不允许本程序自己写开机自启项（常见于装了火绒/360 等安全软件，或被公司策略限制）。\r\n\r\n" +
                    (copied ? "启动文件夹的路径已经复制到剪贴板：\r\n" : "启动文件夹路径：\r\n") + dir +
                    "\r\n\r\n手动开启的办法：按 Win+R → 粘贴 → 回车，打开启动文件夹，把 AirMouseServer.exe 的快捷方式拖进去。",
                    "AirMouse 接收端", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            };

            var copy = new Button();
            copy.Text = "复制 IP";
            copy.AutoSize = true;
            copy.Padding = new Padding(8, 2, 8, 2);
            copy.Click += (s, e) =>
            {
                try
                {
                    Clipboard.SetText(_ips.Text.Replace("手机里填这个 IP：", "").Trim());
                    Log.Write("[i] IP 已复制到剪贴板");
                }
                catch { }
            };

            var fw = new Button();
            fw.Text = "放行防火墙";
            fw.AutoSize = true;
            fw.Padding = new Padding(8, 2, 8, 2);
            fw.Click += (s, e) =>
            {
                try
                {
                    string exe = Application.ExecutablePath;
                    string args = "/c netsh advfirewall firewall add rule name=\"AirMouseServer\" dir=in action=allow program=\"" + exe +
                                  "\" enable=yes profile=any && echo. && echo 已放行，可以关掉这个窗口了 && pause";
                    var psi = new System.Diagnostics.ProcessStartInfo("cmd.exe", args);
                    psi.UseShellExecute = true;
                    psi.Verb = "runas";
                    System.Diagnostics.Process.Start(psi);
                    Log.Write("[i] 已请求管理员权限添加防火墙规则（在弹出窗口里确认）");
                }
                catch (Exception ex)
                {
                    Log.Write("[!] 放行失败：" + ex.Message);
                }
            };

            var openLog = new Button();
            openLog.Text = "查看日志";
            openLog.AutoSize = true;
            openLog.Padding = new Padding(8, 2, 8, 2);
            openLog.Click += (s, e) =>
            {
                try
                {
                    string p = Log.LogPath;
                    if (string.IsNullOrEmpty(p) || !System.IO.File.Exists(p))
                    {
                        Log.Write("[!] 还没有日志文件可打开");
                        return;
                    }
                    Clipboard.SetText(p);
                    bool ok = false;
                    try { Process.Start(new ProcessStartInfo(p) { UseShellExecute = true }); ok = true; } catch { }
                    // 不再退到「打开所在文件夹」：那会去拉 explorer.exe，受限环境下会弹 0xc0000142
                    Log.Write(ok
                        ? "[i] 已打开日志（路径也复制到剪贴板了）：" + p
                        : "[i] 打不开系统窗口，日志路径已复制到剪贴板，粘到资源管理器地址栏即可：" + p);
                }
                catch (Exception ex)
                {
                    Log.Write("[!] 打开日志失败：" + ex.Message);
                }
            };

            // 右下角按钮用 FlowLayoutPanel 自动排，避免不同 DPI/窗口宽度下互相压住
            var btnRow = new FlowLayoutPanel();
            btnRow.FlowDirection = FlowDirection.RightToLeft;
            btnRow.WrapContents = false;
            btnRow.AutoSize = true;
            btnRow.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            btnRow.Dock = DockStyle.Right;
            btnRow.Margin = new Padding(0);
            btnRow.Padding = new Padding(0, 6, 12, 6);
            var quit = new Button();
            quit.Text = "退出程序";
            quit.AutoSize = true;
            quit.Padding = new Padding(8, 2, 8, 2);
            quit.Click += (s, e) => { _running = false; Close(); };
            btnRow.Controls.Add(quit);
            btnRow.Controls.Add(copy);
            btnRow.Controls.Add(fw);
            btnRow.Controls.Add(openLog);

            // 底部一条：左边勾选框、右边按钮组。用 TableLayoutPanel 分两列，
            // 勾选框占 AutoSize 列，按钮占剩余宽度并右对齐 —— 任何 DPI/窗口宽度下都不会互相压住。
            var bottom = new TableLayoutPanel();
            bottom.Dock = DockStyle.Bottom;
            bottom.Height = 48;
            bottom.ColumnCount = 2;
            bottom.RowCount = 1;
            bottom.Padding = new Padding(10, 4, 8, 4);
            bottom.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            bottom.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            bottom.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

            _autostart.Dock = DockStyle.Fill;
            _autostart.Margin = new Padding(0, 0, 10, 0);

            btnRow.Dock = DockStyle.Fill;
            btnRow.Padding = new Padding(0);
            btnRow.Margin = new Padding(0);

            bottom.Controls.Add(_autostart, 0, 0);
            bottom.Controls.Add(btnRow, 1, 0);

            // 有新版本时才显示的一行链接（默认隐藏，不占版面）
            _updateLink.Text = "";
            _updateLink.AutoSize = false;
            _updateLink.Height = 24;
            _updateLink.Dock = DockStyle.Top;
            _updateLink.Padding = new Padding(16, 0, 16, 0);
            _updateLink.TextAlign = ContentAlignment.MiddleLeft;
            _updateLink.Visible = false;
            _updateLink.LinkClicked += (s, e) =>
            {
                try
                {
                    Process.Start(new ProcessStartInfo(_updateUrl) { UseShellExecute = true });
                }
                catch
                {
                    Clipboard.SetText(_updateUrl);
                    Log.Write("[i] 打不开浏览器，下载地址已复制到剪贴板");
                }
            };

            Controls.Add(_log);
            Controls.Add(bottom);
            Controls.Add(_ips);
            Controls.Add(_updateLink);
            Controls.Add(_status);
            _btnRow = btnRow;

            Load += (s, e) =>
            {
                HostBridge.Start();
                StartServer();
                StartUpdateCheck();
            };
            FormClosing += OnClosing;
        }

        private void OnClosing(object sender, FormClosingEventArgs e)
        {
            Log.Write("[i] 关闭请求：reason=" + e.CloseReason + " running=" + _running);
            if (_running && e.CloseReason == CloseReason.UserClosing)
            {
                // 点 × 只最小化到任务栏：程序要继续监听，但必须让用户看得见（托盘图标在部分
                // 机器上会被安全软件/受限权限挡掉，不能把「还活着」押在托盘上）。
                e.Cancel = true;
                WindowState = FormWindowState.Minimized;
                return;
            }
            try { _listener.Stop(); } catch { }
        }

        private void StartServer()
        {
            Log.Sink = line =>
            {
                if (IsDisposed) return;
                try
                {
                    BeginInvoke((Action)(() =>
                    {
                        _log.AppendText(line + Environment.NewLine);
                        if (_log.TextLength > 200000) _log.Text = _log.Text.Substring(_log.TextLength - 100000);
                    }));
                }
                catch { }
            };

            var ips = LocalIps();
            _ips.Text = "手机里填这个 IP：" + (ips.Count > 0 ? string.Join("  /  ", ips) : "（没找到网卡）") + "   端口 " + Port;

            try
            {
                _listener = new TcpListener(IPAddress.Any, Port);
                _listener.Start();
            }
            catch (Exception ex)
            {
                _status.Text = "端口 " + Port + " 被占用";
                Log.Write("[!] 绑不上端口 " + Port + "：" + ex.Message);
                MessageBox.Show("端口 " + Port + " 绑不上，可能已经有一个接收端在跑了。\r\n\r\n" + ex.Message,
                    "AirMouse", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            _status.Text = "监听中 · 端口 " + Port + " · 等待手机连接";
            _status.ForeColor = Color.FromArgb(20, 120, 60);
            Log.Write("AirMouse 接收端已启动，监听 0.0.0.0:" + Port);
            Log.Write("[i] 日志文件：" + (Log.LogPath ?? "（无）"));
            foreach (var ip in ips) Log.Write("  本机 IP：" + ip);
            Log.Write("  手机端：连接方式选 WiFi → 填上面的 IP（要和手机同一网段）→ 连接");
            if (HasFirewallRule())
                Log.Write("  [i] 防火墙：本程序的放行规则在，手机应该能连上");
            else if (TryAddFirewallRule())
                Log.Write("  [i] 防火墙：已自动放行本程序（管理员权限下无需手动操作）");
            else
                Log.Write("  [!] 防火墙：没找到本程序的放行规则，自动放行也失败。手机连不上的话，点右下角「放行防火墙」按钮，"
                          + "或手动到 控制面板 → Windows Defender 防火墙 → 允许应用通过防火墙 → 勾上 AirMouseServer");

            // 注意：这里不要加「模拟输入自检」。刚启动时第一次 SendInput 常被吞，
            // 自检会误报「被安全软件拦了」，但几秒后真正的指令其实都能生效（踩过）。

            var accept = new Thread(AcceptLoop);
            accept.IsBackground = true;
            accept.Start();

            StartAdbWatch();
        }

        private static bool HasFirewallRule()
        {
            try
            {
                var psi = new System.Diagnostics.ProcessStartInfo("netsh",
                    "advfirewall firewall show rule name=\"AirMouseServer\"")
                {
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };
                using (var p = System.Diagnostics.Process.Start(psi))
                {
                    string output = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(1500);
                    return output != null && output.IndexOf("AirMouseServer", StringComparison.OrdinalIgnoreCase) >= 0;
                }
            }
            catch { return false; }
        }

        /// <summary>管理员权限下静默加一条入站放行规则（免去用户手动点防火墙）。</summary>
        private static bool TryAddFirewallRule()
        {
            try
            {
                string exe = Application.ExecutablePath;
                var psi = new System.Diagnostics.ProcessStartInfo("netsh",
                    "advfirewall firewall add rule name=\"AirMouseServer\" dir=in action=allow program=\"" + exe +
                    "\" enable=yes profile=any")
                {
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };
                using (var p = System.Diagnostics.Process.Start(psi))
                {
                    p.StandardOutput.ReadToEnd();
                    p.StandardError.ReadToEnd();
                    p.WaitForExit(4000);
                    return p.ExitCode == 0;
                }
            }
            catch { return false; }
        }

        private void AcceptLoop()
        {
            while (_running)
            {
                try
                {
                    var client = _listener.AcceptTcpClient();
                    var t = new Thread(() => ServeClient(client));
                    t.IsBackground = true;
                    t.Start();
                }
                catch { if (!_running) return; Thread.Sleep(100); }
            }
        }

        private void ServeClient(TcpClient client)
        {
            string remote = "?";
            try
            {
                remote = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString();
                Log.Write("[+] 手机已连接 " + remote);
                UpdateClients(remote, true);

                using (client)
                using (var stream = client.GetStream())
                {
                    client.ReceiveTimeout = 60000;
                    var buf = new byte[8192];
                    var sb = new StringBuilder();
                    while (_running)
                    {
                        int n;
                        try { n = stream.Read(buf, 0, buf.Length); }
                        catch (IOException) { break; }
                        if (n <= 0) break;
                        sb.Append(Encoding.UTF8.GetString(buf, 0, n));
                        while (true)
                        {
                            string all = sb.ToString();
                            int idx = all.IndexOf('\n');
                            if (idx < 0) break;
                            string line = all.Substring(0, idx);
                            sb.Clear();
                            sb.Append(all.Substring(idx + 1));
                            HandleLine(line);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Write("[-] 连接异常：" + ex.Message);
            }
            finally
            {
                UpdateClients(remote, false);
                Log.Write("[-] 手机已断开 " + remote);
            }
        }

        private void UpdateClients(string remote, bool add)
        {
            lock (_clients)
            {
                if (add) { if (!_clients.Contains(remote)) _clients.Add(remote); }
                else _clients.Remove(remote);
                int count = _clients.Count;
                try
                {
                    BeginInvoke((Action)(() =>
                    {
                        if (count > 0)
                        {
                            _status.Text = "已连接 · " + string.Join(", ", _clients.ToArray()) + " · 手机现在可以操控这台电脑";
                            _status.ForeColor = Color.FromArgb(20, 120, 60);
                        }
                        else
                        {
                            _status.Text = "监听中 · 端口 " + Port + " · 等待手机连接";
                            _status.ForeColor = Color.FromArgb(20, 120, 60);
                        }
                    }));
                }
                catch { }
            }
        }

        private static void HandleLine(string line)
        {
            line = line.Trim();
            if (line.Length == 0) return;
            Dictionary<string, object> msg;
            try
            {
                var ser = new JavaScriptSerializer();
                msg = ser.Deserialize<Dictionary<string, object>>(line);
            }
            catch
            {
                Log.Write("  ? 不是 JSON：" + (line.Length > 80 ? line.Substring(0, 80) : line));
                return;
            }
            if (msg == null) return;

            object tv;
            string t = msg.TryGetValue("t", out tv) && tv != null ? tv.ToString() : null;
            if (t == "m")
            {
                int dx = GetInt(msg, "dx"), dy = GetInt(msg, "dy");
                MoveCount++;
                if (MoveCount % 200 == 0) Log.Write("  · 已累计 " + MoveCount + " 条移动指令");
                Injector.Move(dx, dy);
            }
            else if (t == "a")
            {
                double u = GetDouble(msg, "u", 0.5), v = GetDouble(msg, "v", 0.5);
                AbsCount++;
                if (AbsCount % 60 == 1) Log.Write(string.Format("  abs {0:F2},{1:F2}", u, v));
                Injector.PointerAbs(u, v);
            }
            else if (t == "b")
            {
                int btn = GetInt(msg, "b", 1);
                bool down = GetBool(msg, "d");
                bool ok = Injector.Button(btn, down);
                Log.Write(string.Format("  click b{0} {1} ok={2}", btn, down ? "down" : "up", ok));
            }
            else if (t == "w")
            {
                int dv = GetInt(msg, "v");
                bool ok = Injector.Wheel(dv);
                Log.Write(string.Format("  wheel {0} ok={1}", dv, ok));
            }
            else if (t == "k")
            {
                int code = GetInt(msg, "code"), mods = GetInt(msg, "mods");
                bool ok = Injector.Key(code, mods);
                Log.Write(string.Format("  key {0} mods={1} ok={2}", code, mods, ok));
            }
            else if (t == "s")
            {
                object s;
                string text = msg.TryGetValue("text", out s) && s != null ? s.ToString() : "";
                Log.Write("  text '" + text + "'");
                Injector.Text(text);
            }
            else Log.Write("  ? 未知指令：" + t);
        }

        private static int GetInt(Dictionary<string, object> m, string k, int def = 0)
        {
            object v;
            if (!m.TryGetValue(k, out v) || v == null) return def;
            try { return Convert.ToInt32(v); } catch { return def; }
        }

        private static double GetDouble(Dictionary<string, object> m, string k, double def = 0)
        {
            object v;
            if (!m.TryGetValue(k, out v) || v == null) return def;
            try { return Convert.ToDouble(v); } catch { return def; }
        }

        private static bool GetBool(Dictionary<string, object> m, string k)
        {
            object v;
            if (!m.TryGetValue(k, out v) || v == null) return false;
            try { return Convert.ToBoolean(v); } catch { return false; }
        }

        // -------------------------------------------------- USB 通道（adb reverse）
        private void StartAdbWatch()
        {
            string adb = FindAdb();
            if (adb == null)
            {
                Log.Write("[USB] 没找到 adb，USB 直连不可用（WiFi 不受影响）");
                return;
            }
            Log.Write("[USB] 已启动监控：" + adb);
            var t = new Thread(() => AdbWatchLoop(adb));
            t.IsBackground = true;
            t.Start();
        }

        private static string FindAdb()
        {
            var cands = new List<string>();
            string env = Environment.GetEnvironmentVariable("ADB");
            if (!string.IsNullOrEmpty(env)) cands.Add(env);
            env = Environment.GetEnvironmentVariable("AIRMOUSE_ADB");
            if (!string.IsNullOrEmpty(env)) cands.Add(env);
            cands.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe"));
            cands.Add(@"D:\Software\platform-tools-latest-windows\platform-tools\adb.exe");
            cands.Add(@"C:\platform-tools\adb.exe");
            foreach (var c in cands) if (File.Exists(c)) return c;
            string path = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (var dir in path.Split(';'))
            {
                try
                {
                    if (string.IsNullOrWhiteSpace(dir)) continue;
                    string p = Path.Combine(dir.Trim(), "adb.exe");
                    if (File.Exists(p)) return p;
                }
                catch { }
            }
            return null;
        }

        private void AdbWatchLoop(string adb)
        {
            var known = new HashSet<string>();
            while (_running)
            {
                try
                {
                    string devices = RunCapture(adb, "devices");
                    var serials = new List<string>();
                    if (devices != null)
                    {
                        var lines = devices.Replace("\r", "").Split('\n');
                        for (int i = 1; i < lines.Length; i++)
                        {
                            var parts = lines[i].Trim().Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                            if (parts.Length >= 2 && parts[1] == "device") serials.Add(parts[0]);
                        }
                    }
                    foreach (var s in serials)
                    {
                        if (known.Contains(s)) continue;
                        string r = RunCapture(adb, "-s " + s + " reverse tcp:" + Port + " tcp:" + Port);
                        if (!string.IsNullOrEmpty(r) && !r.StartsWith("error"))
                        {
                            Log.Write("[+] USB 通道已就绪 " + s + " -> 127.0.0.1:" + Port);
                            known.Add(s);
                        }
                    }
                    foreach (var s in new List<string>(known)) if (!serials.Contains(s)) known.Remove(s);
                }
                catch { }
                for (int i = 0; i < 30 && _running; i++) Thread.Sleep(100);
            }
        }

        private static string RunCapture(string exe, string args)
        {
            try
            {
                var psi = new System.Diagnostics.ProcessStartInfo(exe, args);
                psi.UseShellExecute = false;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.CreateNoWindow = true;
                using (var p = System.Diagnostics.Process.Start(psi))
                {
                    string outp = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(5000);
                    return outp;
                }
            }
            catch { return null; }
        }

        // -------------------------------------------------- 其它
        private static List<string> LocalIps()
        {
            var list = new List<string>();
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                        string s = ua.Address.ToString();
                        if (!list.Contains(s)) list.Add(s);
                    }
                }
            }
            catch { }
            return list;
        }

        private static int MoveCount = 0;
        private static int AbsCount = 0;

        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string AutostartName = "AirMouseServer";

        /// <summary>启动文件夹里的快捷方式（注册表被策略禁写时的退路）</summary>
        internal static string StartupLnk()
        {
            string dir = Environment.GetFolderPath(Environment.SpecialFolder.Startup);
            if (string.IsNullOrEmpty(dir)) return null;
            return Path.Combine(dir, "AirMouse 接收端.lnk");
        }

        internal static bool RegistryAutostartOn()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(RunKey))
                {
                    if (k == null) return false;
                    return k.GetValue(AutostartName) != null;
                }
            }
            catch { return false; }
        }

        internal static bool IsAutostartEnabledPublic() { return IsAutostartEnabled(); }
        internal static bool RegistryAutostartOnPublic() { return RegistryAutostartOn(); }
        internal static bool StartupLnkExistsPublic()
        {
            try { string s = StartupLnk(); return s != null && File.Exists(s); } catch { return false; }
        }
        internal static void SetAutostartPublic(bool on) { SetAutostart(on); }
        internal static bool IsAutostartEnabled()
        {
            if (RegistryAutostartOn()) return true;
            try
            {
                string lnk = StartupLnk();
                return lnk != null && File.Exists(lnk);
            }
            catch { return false; }
        }

        /// <summary>写注册表；返回 false 表示被策略拒绝，调用方应退回启动文件夹</summary>
        private static bool SetRegistryAutostart(bool on, out string error)
        {
            error = null;
            try
            {
                using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
                {
                    if (k == null) { error = "打不开 HKCU Run 项"; return false; }
                    if (on) k.SetValue(AutostartName, "\"" + Application.ExecutablePath + "\"");
                    else k.DeleteValue(AutostartName, false);
                }
                return true;
            }
            catch (Exception ex) { error = ex.Message; return false; }
        }

        /// <summary>在启动文件夹放/删快捷方式（不依赖注册表写权限）</summary>
        private static bool SetStartupLnk(bool on, out string error)
        {
            error = null;
            try
            {
                string lnk = StartupLnk();
                if (string.IsNullOrEmpty(lnk)) { error = "拿不到启动文件夹路径"; return false; }
                if (!on)
                {
                    if (File.Exists(lnk)) File.Delete(lnk);
                    return true;
                }
                // 用 WScript.Shell 建 .lnk（不引入 COM 互操作程序集，靠 dynamic）
                Type t = Type.GetTypeFromProgID("WScript.Shell");
                if (t == null) { error = "系统没有 WScript.Shell"; return false; }
                dynamic shell = Activator.CreateInstance(t);
                dynamic sc = shell.CreateShortcut(lnk);
                sc.TargetPath = Application.ExecutablePath;
                sc.WorkingDirectory = Path.GetDirectoryName(Application.ExecutablePath);
                sc.Description = "AirMouse 接收端";
                sc.WindowStyle = 7; // 最小化启动
                sc.Save();
                return File.Exists(lnk);
            }
            catch (Exception ex) { error = ex.Message; return false; }
        }

        /// <summary>第三种兜底：建一个登录时触发的计划任务（安全软件一般不拦这个）</summary>
        private static bool SetLogonTask(bool on, out string error)
        {
            error = null;
            try
            {
                var psi = new ProcessStartInfo("schtasks.exe");
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                psi.RedirectStandardError = true;
                psi.RedirectStandardOutput = true;
                if (on)
                {
                    psi.Arguments = "/create /tn \"AirMouseServer\" /tr \"" + Application.ExecutablePath + "\" /sc onlogon /rl highest /f";
                }
                else
                {
                    psi.Arguments = "/delete /tn \"AirMouseServer\" /f";
                }
                using (var p = Process.Start(psi))
                {
                    string outp = p.StandardOutput.ReadToEnd();
                    string err = p.StandardError.ReadToEnd();
                    p.WaitForExit(8000);
                    if (p.ExitCode == 0) return true;
                    error = (err + " " + outp).Trim();
                    if (error.Length == 0) error = "schtasks 退出码 " + p.ExitCode;
                    return false;
                }
            }
            catch (Exception ex) { error = ex.Message; return false; }
        }

        /// <summary>开/关开机自启：计划任务(最高权限) → 注册表 → 启动文件夹，逐级兜底。
        /// 本程序要求管理员权限，注册表 Run 项每次登录都会弹 UAC，所以优先用计划任务（/rl highest 不弹 UAC）。</summary>
        internal static bool SetAutostart(bool on)
        {
            string schErr;
            if (SetLogonTask(on, out schErr))
            {
                // 计划任务能建，顺手清掉另外两处可能残留，避免重复启动
                string ignored;
                SetStartupLnk(false, out ignored);
                if (on) SetRegistryAutostart(false, out ignored);
                Log.Write(on ? "[i] 已设置开机自启（计划任务 AirMouseServer，最高权限）" : "[i] 已取消开机自启");
                return true;
            }

            Log.Write("[i] 计划任务写不了（" + schErr + "），改用注册表");

            string regErr;
            bool regOk = SetRegistryAutostart(on, out regErr);
            if (regOk)
            {
                string ignored;
                SetStartupLnk(false, out ignored);
                Log.Write(on ? "[i] 已设置开机自启（注册表）" : "[i] 已取消开机自启");
                return true;
            }

            Log.Write("[i] 注册表也写不了（" + regErr + "），改用启动文件夹快捷方式");

            string lnkErr;
            bool lnkOk = SetStartupLnk(on, out lnkErr);
            if (lnkOk)
            {
                Log.Write(on
                    ? "[i] 已设置开机自启（启动文件夹快捷方式）：" + StartupLnk()
                    : "[i] 已取消开机自启（删掉了启动文件夹里的快捷方式）");
                return true;
            }

            Log.Write("[!] 设置自启失败：计划任务 / 注册表 / 启动文件夹都写不了。" +
                      "计划任务=" + schErr + "；注册表=" + regErr + "；快捷方式=" + lnkErr);
            return false;
        }
    }

    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            // 命令行维护入口（给脚本/排障用，不开窗口）：
            //   AirMouseServer.exe /autostart:on | /autostart:off | /autostart:check
            if (args != null && args.Length > 0 &&
                args[0].StartsWith("/autostart", StringComparison.OrdinalIgnoreCase))
            {
                string mode = args[0].Substring("/autostart".Length).TrimStart(':', '=').Trim().ToLowerInvariant();
                if (mode == "check")
                {
                    bool on = MainForm.IsAutostartEnabledPublic();
                    Log.Write("[i] 自启状态：" + (on ? "已开启" : "未开启") +
                              "（注册表=" + (MainForm.RegistryAutostartOnPublic() ? "有" : "无") +
                              "，启动文件夹=" + (MainForm.StartupLnkExistsPublic() ? "有" : "无") + "）");
                }
                else
                {
                    MainForm.SetAutostartPublic(mode != "off");
                }
                return;
            }

            bool created;
            using (var mutex = new Mutex(true, "AirMouseServer-SingleInstance", out created))
            {
                if (!created)
                {
                    // 已经在跑了：把那个窗口叫到前台，然后安静退出（不要弹框，会卡住进程看着像卡死）
                    try
                    {
                        IntPtr h = Native.FindWindow(null, "AirMouse 接收端");
                        if (h == IntPtr.Zero) h = Native.FindWindowByProcess("AirMouseServer");
                        if (h != IntPtr.Zero)
                        {
                            Native.ShowWindow(h, 5 /* SW_SHOW */);
                            Native.ShowWindow(h, 9 /* SW_RESTORE */);
                            Native.SetForegroundWindow(h);
                        }
                    }
                    catch { }
                    return;
                }
                try { Native.SetProcessDPIAware(); } catch { }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
        }
    }
}

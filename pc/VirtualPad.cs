using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace AirMouse
{
    /// <summary>
    /// 虚拟 Xbox 360 手柄（手柄模式）。
    ///
    /// 原理：电脑上装了 ViGEmBus 这个「虚拟手柄总线」驱动之后，任何一个程序都能往里插一只
    /// 假手柄，游戏看到的就是一只真正的 Xbox 360 手柄（XInput）——和你插实体 Xbox 手柄
    /// 完全一样，绝区零这类只吃 XInput 的游戏直接就能认。
    ///
    /// 依赖：ViGEmBus 驱动（安装时装上）+ ViGEmClient.dll（放在 AirMouseServer.exe 旁边）。
    /// 两者缺一不可；缺的时候这里只记一条日志，鼠标功能照旧可用，不影响其它模式。
    /// </summary>
    internal static class VirtualPad
    {
        #region 原生接口

        [StructLayout(LayoutKind.Sequential)]
        private struct XUSB_REPORT
        {
            public ushort wButtons;
            public byte bLeftTrigger;
            public byte bRightTrigger;
            public short sThumbLX;
            public short sThumbLY;
            public short sThumbRX;
            public short sThumbRY;
        }

        // 震动回调：游戏（或系统）要手柄震动时，ViGEmBus 会从驱动线程回调进来
        [UnmanagedFunctionPointer(CallingConvention.Winapi)]
        private delegate void X360Notification(IntPtr client, IntPtr target, byte largeMotor, byte smallMotor, IntPtr userData);

        private const string Dll = "ViGEmClient.dll";

        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern IntPtr vigem_alloc();
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern void vigem_free(IntPtr client);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern int vigem_connect(IntPtr client);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern void vigem_disconnect(IntPtr client);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern IntPtr vigem_target_x360_alloc();
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern void vigem_target_free(IntPtr target);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern int vigem_target_add(IntPtr client, IntPtr target);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern int vigem_target_remove(IntPtr client, IntPtr target);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern int vigem_target_x360_update(IntPtr client, IntPtr target, XUSB_REPORT report);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern int vigem_target_x360_register_notification(IntPtr client, IntPtr target, X360Notification callback, IntPtr userData);
        [DllImport(Dll, CallingConvention = CallingConvention.Winapi)] private static extern int vigem_target_x360_unregister_notification(IntPtr target);

        // 返回值：0x20000000 是「成功」，0xE0000xxx 才是错误码
        private const int VIGEM_ERROR_NONE = 0x20000000;

        #endregion

        private static readonly object Gate = new object();
        private static IntPtr _client = IntPtr.Zero;
        private static IntPtr _target = IntPtr.Zero;
        private static bool _unavailable;          // 驱动/DLL 缺失，别每条报文都重试
        private static string _unavailableReason;
        private static X360Notification _notify;   // 必须保住引用，否则被 GC 掉会崩
        private static ushort _lastButtons;
        private static long _updates;

        /// <summary>手柄状态变化（large, small），由驱动线程回调，UI 层自己切线程。</summary>
        public static event Action<byte, byte> Vibration;

        /// <summary>这条连接上收到手柄报文时调用；第一次调用会把虚拟手柄插进系统。</summary>
        public static void Update(ushort buttons, short lx, short ly, short rx, short ry, byte lt, byte rt)
        {
            lock (Gate)
            {
                if (_unavailable) return;
                if (!EnsureLocked()) return;

                var rep = new XUSB_REPORT
                {
                    wButtons = buttons,
                    bLeftTrigger = lt,
                    bRightTrigger = rt,
                    sThumbLX = lx,
                    sThumbLY = ly,
                    sThumbRX = rx,
                    sThumbRY = ry
                };
                try { vigem_target_x360_update(_client, _target, rep); }
                catch (Exception ex) { Disable("上报手柄状态失败：" + ex.Message); return; }

                _lastButtons = buttons;
                if (++_updates == 1) Log.Write("[i] 虚拟手柄已插入（Xbox 360），游戏里可以直接选它");
            }
        }

        /// <summary>手机端退出/换模式/断开时把手柄拔掉，免得系统里留一只没人操作的假手柄。</summary>
        public static void Shutdown(string reason)
        {
            lock (Gate)
            {
                if (_target == IntPtr.Zero && _client == IntPtr.Zero) return;
                try { if (_target != IntPtr.Zero && _client != IntPtr.Zero) vigem_target_x360_unregister_notification(_target); } catch { }
                try { if (_target != IntPtr.Zero && _client != IntPtr.Zero) vigem_target_remove(_client, _target); } catch { }
                try { if (_target != IntPtr.Zero) vigem_target_free(_target); } catch { }
                try { if (_client != IntPtr.Zero) vigem_disconnect(_client); } catch { }
                try { if (_client != IntPtr.Zero) vigem_free(_client); } catch { }
                bool wasActive = _target != IntPtr.Zero;
                _target = IntPtr.Zero;
                _client = IntPtr.Zero;
                _updates = 0;
                _lastButtons = 0;
                if (wasActive) Log.Write("[i] 虚拟手柄已拔出" + (string.IsNullOrEmpty(reason) ? "" : "（" + reason + "）"));
            }
        }

        private static bool EnsureLocked()
        {
            if (_client != IntPtr.Zero && _target != IntPtr.Zero) return true;
            try
            {
                _client = vigem_alloc();
                if (_client == IntPtr.Zero) { Disable("vigem_alloc 失败"); return false; }
                int r = vigem_connect(_client);
                if (r != VIGEM_ERROR_NONE) { Disable("连不上 ViGEmBus（" + ErrorText(r) + "）"); return false; }

                _target = vigem_target_x360_alloc();
                if (_target == IntPtr.Zero) { Disable("vigem_target_x360_alloc 失败"); return false; }
                r = vigem_target_add(_client, _target);
                if (r != VIGEM_ERROR_NONE) { Disable("插不进虚拟手柄（" + ErrorText(r) + "）"); return false; }

                // 刚插进去的这一刻，XInput 那边还没枚举完，紧跟着发的第一条报文会被丢掉。
                // 手柄模式的第一帧往往是「按下 A」这种真动作，丢了就是「按了没反应」——
                // 所以这里等一下，并把中立位置先报一次，让设备就位。
                //
                // 另外：ViGEmBus 1.22 刚添加的 x360 目标，XInput 读到的第一份状态是个
                // 非中立的默认值（实测 lx=-3356 ly=-1869 rx=-3255 ry=-848），而这时直接补
                // 一帧全 0 驱动是不认的（包号不涨、值也不变），游戏里就会表现为镜头自己缓慢漂移。
                // 先给一个真实的变化（lx=1）再回中立，包号才会推进，状态才真正归零。
                Thread.Sleep(60);
                try { vigem_target_x360_update(_client, _target, new XUSB_REPORT { sThumbLX = 1 }); } catch { }
                Thread.Sleep(40);
                try { vigem_target_x360_update(_client, _target, new XUSB_REPORT()); } catch { }
                Thread.Sleep(40);

                // 震动：游戏让它震，就转给手机。回调失败不影响手柄本身。
                try
                {
                    _notify = (c, t, large, small, ud) =>
                    {
                        var h = Vibration;
                        if (h != null) { try { h(large, small); } catch { } }
                    };
                    vigem_target_x360_register_notification(_client, _target, _notify, IntPtr.Zero);
                }
                catch { }
                return true;
            }
            catch (DllNotFoundException)
            {
                Disable("找不到 ViGEmClient.dll（要和 AirMouseServer.exe 放在一起）");
                return false;
            }
            catch (BadImageFormatException)
            {
                Disable("ViGEmClient.dll 位数不对（要用 64 位）");
                return false;
            }
            catch (EntryPointNotFoundException ex)
            {
                Disable("ViGEmClient.dll 版本太旧：" + ex.Message);
                return false;
            }
            catch (Exception ex)
            {
                Disable(ex.GetType().Name + "：" + ex.Message);
                return false;
            }
        }

        private static void Disable(string reason)
        {
            if (_unavailable) return;
            _unavailable = true;
            _unavailableReason = reason;
            Log.Write("[!] 手柄模式不可用：" + reason);
        }

        private static string ErrorText(int code)
        {
            switch (code)
            {
                case unchecked((int)0xE0000001): return "驱动没装（ViGEmBus）";
                case unchecked((int)0xE0000002): return "没有空的槽位";
                case unchecked((int)0xE0000003): return "参数不合法";
                case unchecked((int)0xE0000004): return "无效的目标";
                case unchecked((int)0xE0000005): return "找不到总线设备";
                case 0x20000001: return "总线已被占用";
                case 0x20000002: return "总线访问失败";
                default: return "0x" + code.ToString("X8");
            }
        }

        public static bool Unavailable { get { return _unavailable; } }
        public static string UnavailableReason { get { return _unavailableReason; } }
        public static ushort LastButtons { get { return _lastButtons; } }
    }
}

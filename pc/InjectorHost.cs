using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace AirMouse
{
    /// <summary>
    /// 把「键鼠注入」交给一个有数字签名的宿主（powershell.exe）执行。
    ///
    /// 起因（实测）：装了火绒之类安全软件后，**未签名**进程只有在它是前台窗口时才被
    /// 允许调 SetCursorPos / SendInput；一旦最小化或被别的窗口盖住，这些 API 直接
    /// 返回 FALSE 或静默丢弃。而 python.exe / powershell.exe（有官方签名）在后台注入
    /// 完全正常。所以主程序只负责收包与界面，真正的 Win32 调用放进 powershell 子进程。
    ///
    /// 注入代码编译在 AirMouseHook.dll 里，由 powershell 用 LoadFile 加载（不用 Add-Type，
    /// 避免运行时编译需要写临时目录、在受限环境下失败）。
    /// </summary>
    internal static class HostBridge
    {
        private static Process _proc;
        private static readonly object _lock = new object();
        private static bool _startFailed;

        private static string DllPath
        {
            get
            {
                try
                {
                    string dir = Path.GetDirectoryName(Application.ExecutablePath);
                    if (!string.IsNullOrEmpty(dir)) return Path.Combine(dir, "AirMouseHook.dll");
                }
                catch { }
                return "AirMouseHook.dll";
            }
        }

        public static bool Alive
        {
            get { lock (_lock) { try { return _proc != null && !_proc.HasExited; } catch { return false; } } }
        }

        public static bool Start()
        {
            lock (_lock)
            {
                if (Alive) return true;
                if (_startFailed) return false;
                Log.Write("[i] HOSTBRIDGE-INIT");
                try
                {
                    string dll = DllPath;
                    if (!File.Exists(dll))
                    {
                        Log.Write("[!] 缺少 AirMouseHook.dll（应与 exe 同目录），回退到直接注入。");
                        _startFailed = true;
                        return false;
                    }

                    string ps1 = WriteHostScript(dll);
                    if (ps1 == null) { _startFailed = true; return false; }

                    var psi = new ProcessStartInfo
                    {
                        FileName = "powershell.exe",
                        Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + ps1 + "\"",
                        UseShellExecute = false,
                        CreateNoWindow = true,
                        RedirectStandardInput = true,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true,
                        StandardOutputEncoding = Encoding.UTF8,
                        StandardErrorEncoding = Encoding.UTF8,
                    };
                    _proc = Process.Start(psi);
                    if (_proc == null) { _startFailed = true; return false; }

                    _proc.OutputDataReceived += (s, e) => { if (!string.IsNullOrEmpty(e.Data)) Log.Write("  [host] " + e.Data); };
                    _proc.ErrorDataReceived += (s, e) => { if (!string.IsNullOrEmpty(e.Data)) Log.Write("  [host!] " + e.Data); };
                    _proc.BeginOutputReadLine();
                    _proc.BeginErrorReadLine();

                    // 探一下：写一行注释命令，若宿主已挂会立刻退出
                    Thread.Sleep(200);
                    if (_proc.HasExited)
                    {
                        Log.Write("[!] 注入宿主启动失败（powershell 退出码 " + _proc.ExitCode + "），回退到直接注入。");
                        _startFailed = true;
                        return false;
                    }
                    Log.Write("[i] 注入宿主已启动：powershell.exe pid=" + _proc.Id + "（有签名，后台也能动鼠标）");
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Write("[!] 注入宿主启动失败：" + ex.Message);
                    _startFailed = true;
                    return false;
                }
            }
        }

        public static bool Send(string line)
        {
            lock (_lock)
            {
                try
                {
                    if (_proc == null || _proc.HasExited) return false;
                    _proc.StandardInput.WriteLine(line);
                    _proc.StandardInput.Flush();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Write("[!] 注入宿主写失败：" + ex.Message);
                    try { if (_proc != null) _proc.Kill(); } catch { }
                    return false;
                }
            }
        }

        public static void Stop()
        {
            lock (_lock)
            {
                try
                {
                    if (_proc != null && !_proc.HasExited)
                    {
                        try { _proc.StandardInput.WriteLine("Q"); _proc.StandardInput.Flush(); } catch { }
                        if (!_proc.WaitForExit(500)) _proc.Kill();
                    }
                }
                catch { }
                _proc = null;
            }
        }

        private static string WriteHostScript(string dll)
        {
            var dirs = new List<string>();
            try
            {
                string exeDir = Path.GetDirectoryName(Application.ExecutablePath);
                if (!string.IsNullOrEmpty(exeDir)) dirs.Add(Path.Combine(exeDir, "_host"));
            }
            catch { }
            try
            {
                string la = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                if (!string.IsNullOrEmpty(la)) dirs.Add(Path.Combine(Path.Combine(la, "AirMouse"), "host"));
            }
            catch { }
            try { dirs.Add(Path.Combine(Path.GetTempPath(), "AirMouseHost")); } catch { }

            // 注意两点（都踩过）：
            // 1) 用 Assembly.LoadFile 加载的类型不能写成 [AirMouseChild]::Run()——
            //    PowerShell 在解析脚本时就把 [类型] 解析掉了，那时 DLL 还没加载（TypeNotFound），
            //    必须走反射 GetMethod('Run').Invoke()。
            // 2) 某些盘/目录下 LoadFile 会报「尝试从一个网络位置加载程序集」，
            //    改用 Load(ReadAllBytes(...)) 从内存加载，绕开这个检查。
            string q = dll.Replace("'", "''");
            string body =
                "$ErrorActionPreference='Continue'\r\n" +
                "$asm=[Reflection.Assembly]::Load([IO.File]::ReadAllBytes('" + q + "'))\r\n" +
                "$t=$asm.GetType('AirMouseChild')\r\n" +
                "if ($t -eq $null) { Write-Error -Message ('AirMouseChild not found in " + q + "'); exit 2 }\r\n" +
                "$t.GetMethod('Run').Invoke($null, @())\r\n";

            foreach (var dir in dirs)
            {
                try
                {
                    Directory.CreateDirectory(dir);
                    string ps1 = Path.Combine(dir, "host.ps1");
                    File.WriteAllText(ps1, body, new UTF8Encoding(true));
                    return ps1;
                }
                catch { }
            }
            Log.Write("[!] 注入宿主：三个目录都写不进去（exe 目录 / LOCALAPPDATA / TEMP）");
            return null;
        }
    }
}

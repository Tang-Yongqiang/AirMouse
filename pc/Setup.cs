// AirMouse 安装程序（单文件）
// 编译：csc /target:winexe /codepage:65001 /win32icon:pc\airmouse.ico /resource:dist\AirMouseServer.exe,payload_server /resource:dist\AirMouseHook.dll,payload_hook pc\Setup.cs
// 说明：
//  - 安装目录 %LOCALAPPDATA%\AirMouse（不需要管理员也能写，避免每次启动弹 UAC）
//  - 开机自启写「计划任务 /rl highest」：以最高权限随登录启动，不弹 UAC
//  - 卸载信息写 HKCU\...\Uninstall\AirMouse，控制面板可卸载
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

namespace AirMouseSetup
{
    /// 同一份源码编出两个程序：安装器（默认）和卸载器（/define:UNINSTALL_ONLY）
    static class Build
    {
#if UNINSTALL_ONLY
        public const bool Uninstaller = true;
#else
        public const bool Uninstaller = false;
#endif
    }

    static class Program
    {
        public const string AppName = "AirMouse";
        public const string TaskName = "AirMouseServer";
        // 默认装到 Program Files：本机实测「未签名 + 非管理员」进程写 %LOCALAPPDATA% / %APPDATA% /
        // C:\ProgramData 都会被安全软件拒（UnauthorizedAccessException），只有提权后才写得进去。
        // 安装器本来就要求管理员，所以默认落在 Program Files；用户也可以在界面里改成别的盘。
        public static string InstallDir = DefaultDir();

        public static string DefaultDir()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), AppName);
        }

        /// 上次装到哪：从「应用和功能」里的卸载项读 InstallLocation。
        /// 更新时直接沿用它，用户不用再选一次目录（也不会出现 C 盘一份、D 盘一份）。
        public static string PreviousDir()
        {
            try
            {
                using (var k = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMouse"))
                {
                    if (k == null) return null;
                    string loc = k.GetValue("InstallLocation") as string;
                    if (string.IsNullOrEmpty(loc)) return null;
                    loc = loc.Trim().Trim('"').TrimEnd('\\');
                    if (loc.Length <= 3) return null;              // 盘符根不算有效安装目录
                    if (!Directory.Exists(loc)) return null;        // 目录被删了就回到默认
                    return loc;
                }
            }
            catch { return null; }
        }

        public static string ServerExe { get { return Path.Combine(InstallDir, "AirMouseServer.exe"); } }

        /// 支持 /dir:"D:\Apps\AirMouse"、/dir=D:\Apps\AirMouse、/d "D:\Apps\AirMouse"
        static string PickDirArg(string[] args)
        {
            for (int i = 0; i < args.Length; i++)
            {
                string a = args[i] ?? "";
                string v = null;
                if (a.StartsWith("/dir:", StringComparison.OrdinalIgnoreCase) || a.StartsWith("/d:", StringComparison.OrdinalIgnoreCase))
                    v = a.Substring(a.IndexOf(':') + 1);
                else if (a.Equals("/dir", StringComparison.OrdinalIgnoreCase) || a.Equals("/d", StringComparison.OrdinalIgnoreCase))
                    v = i + 1 < args.Length ? args[i + 1] : null;
                if (!string.IsNullOrEmpty(v))
                {
                    v = v.Trim().Trim('"');
                    if (v.Length > 0) return v;
                }
            }
            return null;
        }

        [STAThread]
        static void Main(string[] args)
        {
            Trace("Main args=[" + string.Join(",", args) + "] uninstaller=" + Build.Uninstaller);
            bool uninstall = Build.Uninstaller || (args.Length > 0 && args[0].Equals("/uninstall", StringComparison.OrdinalIgnoreCase));
            bool silent = false;
            foreach (var a in args) if (a.Equals("/S", StringComparison.OrdinalIgnoreCase)) silent = true;

            // 更新场景：沿用上次装的位置（注册表里的 InstallLocation），不用用户再选。
            // 命令行 /dir 优先级最高，仍然可以显式覆盖。
            if (!uninstall)
            {
                string prev = PreviousDir();
                if (!string.IsNullOrEmpty(prev))
                {
                    InstallDir = prev;
                    Trace("沿用上次安装位置：" + prev);
                }
            }

            string dirArg = PickDirArg(args);
            if (!string.IsNullOrEmpty(dirArg)) { try { InstallDir = InstallDirFrom(dirArg); } catch { } }

            // 调试用：--printdir 只把「最终会用的安装目录」写进 setup-trace.log 然后退出。
            // 不需要管理员，方便验证「更新时是否沿用了上次的位置」。
            foreach (var a in args)
            {
                if (a.Equals("--printdir", StringComparison.OrdinalIgnoreCase))
                {
                    Trace("--printdir InstallDir=" + InstallDir + " previous=" + (PreviousDir() ?? "(none)"));
                    return;
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            if (silent)
            {
                // 静默模式给脚本/无人值守用
                string err = uninstall ? UninstallCore() : InstallCore();
                Environment.Exit(err == null ? 0 : 1);
            }
            Application.Run(new SetupForm(uninstall));
        }

        /// 命令行/界面共用的路径归一（含盘符根 D: → D:\）
        public static string NormalizeInstallDir(string raw)
        {
            raw = (raw ?? "").Trim().Trim('"').TrimEnd('\\');
            if (raw.Length == 2 && char.IsLetter(raw[0]) && raw[1] == ':') raw += "\\";
            return Path.GetFullPath(raw);
        }

        /// 用户只给了盘符根（D:\）时自动加一层 AirMouse，免得把文件铺在根目录
        public static string InstallDirFrom(string raw)
        {
            string d = NormalizeInstallDir(raw);
            if (d.Length <= 3) d = d.TrimEnd('\\') + "\\" + AppName;
            return d.TrimEnd('\\');
        }

        // ---------- 安装 / 卸载的核心逻辑 ----------

        public static bool IsAdmin()
        {
            try
            {
                var id = System.Security.Principal.WindowsIdentity.GetCurrent();
                var p = new System.Security.Principal.WindowsPrincipal(id);
                return p.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
            }
            catch { return false; }
        }

        /// 以管理员重启自己（UAC 一次）；返回 false 表示用户取消了
        public static bool RelaunchElevated(string arguments)
        {
            try
            {
                var psi = new ProcessStartInfo(Application.ExecutablePath, arguments);
                psi.UseShellExecute = true;
                psi.Verb = "runas";
                Process.Start(psi);
                return true;
            }
            catch { return false; }
        }

        static void KillServer()
        {
            try
            {
                foreach (var p in Process.GetProcessesByName("AirMouseServer"))
                {
                    try { p.Kill(); p.WaitForExit(3000); } catch { }
                }
            }
            catch { }
        }

        static void Trace(string s)
        {
            try
            {
                var p = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "setup-trace.log");
                File.AppendAllText(p, DateTime.Now.ToString("HH:mm:ss.fff") + "  " + s + "\r\n", Encoding.UTF8);
            }
            catch { }
        }

        static string ExtractResource(string name, string dest)
        {
            try
            {
                var asm = Assembly.GetExecutingAssembly();
                using (var s = asm.GetManifestResourceStream(name))
                {
                    if (s == null) return "找不到内嵌文件：" + name;
                    using (var fs = new FileStream(dest, FileMode.Create, FileAccess.Write))
                        s.CopyTo(fs);
                }
                return null;
            }
            catch (Exception ex) { return ex.Message; }
        }

        static string MakeShortcut(string linkPath, string target)
        {
            try
            {
                Type t = Type.GetTypeFromProgID("WScript.Shell");
                dynamic sh = Activator.CreateInstance(t);
                dynamic lnk = sh.CreateShortcut(linkPath);
                lnk.TargetPath = target;
                lnk.WorkingDirectory = Path.GetDirectoryName(target);
                lnk.Description = "AirMouse 接收端（手机陀螺仪控制电脑）";
                lnk.IconLocation = target + ",0";
                lnk.WindowStyle = 1;
                lnk.Save();
                return null;
            }
            catch (Exception ex) { return ex.Message; }
        }

        public static string InstallCore()
        {
            try
            {
                Trace("InstallCore start, dir=" + InstallDir);
                Directory.CreateDirectory(InstallDir);
                KillServer();
                System.Threading.Thread.Sleep(400);

                string err = ExtractResource("payload_server", ServerExe);
                Trace("extract server -> " + (err ?? "ok"));
                if (err != null) return "写出 AirMouseServer.exe 失败：" + err;
                err = ExtractResource("payload_hook", Path.Combine(InstallDir, "AirMouseHook.dll"));
                Trace("extract hook -> " + (err ?? "ok"));
                if (err != null) return "写出 AirMouseHook.dll 失败：" + err;

                // 卸载器单独一份，装进安装目录（「应用和功能」和开始菜单都指向它）
                string uninstExe = Path.Combine(InstallDir, "uninstall.exe");
                err = ExtractResource("payload_uninstall", uninstExe);
                Trace("extract uninstaller -> " + (err ?? "ok"));
                // 老版本叫 AirMouseUninstall.exe，升级时顺手清掉，别在目录里留两个卸载器
                try
                {
                    string legacyUn = Path.Combine(InstallDir, "AirMouseUninstall.exe");
                    if (File.Exists(legacyUn)) { File.Delete(legacyUn); Trace("removed legacy AirMouseUninstall.exe"); }
                }
                catch { }

                // 快捷方式：开始菜单 + 桌面
                string startMenu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "AirMouse 接收端.lnk");
                string desktop = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "AirMouse 接收端.lnk");
                Trace("lnk startMenu -> " + (MakeShortcut(startMenu, ServerExe) ?? "ok"));
                Trace("lnk desktop -> " + (MakeShortcut(desktop, ServerExe) ?? "ok"));
                // 开始菜单里再放一个「卸载 AirMouse」，跟安装器分开
                string unLnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "卸载 AirMouse.lnk");
                if (File.Exists(uninstExe)) Trace("lnk uninstall -> " + (MakeShortcut(unLnk, uninstExe) ?? "ok"));

                // 开机自启：计划任务（/rl highest 不弹 UAC）
                RunCmd("schtasks.exe", string.Format("/create /tn \"{0}\" /tr \"\\\"{1}\\\"\" /sc onlogon /rl highest /f", TaskName, ServerExe));
                Trace("schtasks done");

                // 卸载信息（装到 Program Files 属于机器级安装，写 HKLM）
                using (var k = Microsoft.Win32.Registry.LocalMachine.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMouse"))
                {
                    k.SetValue("DisplayName", "AirMouse 接收端");
                    k.SetValue("DisplayVersion", "2.32");
                    k.SetValue("Publisher", "AirMouse");
                    k.SetValue("DisplayIcon", ServerExe);
                    k.SetValue("InstallLocation", InstallDir);
                    k.SetValue("UninstallString", "\"" + uninstExe + "\"");
                    k.SetValue("QuietUninstallString", "\"" + uninstExe + "\" /S");
                    k.SetValue("NoModify", 1, Microsoft.Win32.RegistryValueKind.DWord);
                    k.SetValue("NoRepair", 1, Microsoft.Win32.RegistryValueKind.DWord);
                }
                Trace("reg done");

                // 顺手放行防火墙（失败也不影响，运行期还会再试）
                RunCmd("netsh.exe", string.Format("advfirewall firewall add rule name=\"AirMouseServer\" dir=in action=allow program=\"{0}\" enable=yes profile=any", ServerExe));
                Trace("netsh done");

                // 装完不自动启动：由收尾窗口的「运行 AirMouse 接收端」复选框决定
                Trace("InstallCore ok");
                return null;
            }
            catch (Exception ex) { Trace("InstallCore EX " + ex); return ex.Message; }
        }

        public static string UninstallCore()
        {
            try
            {
                KillServer();
                System.Threading.Thread.Sleep(400);
                RunCmd("schtasks.exe", string.Format("/delete /tn \"{0}\" /f", TaskName));
                // 先按卸载项里记的真实路径删（用户可能装到了 D 盘）
                string dir = InstallDir;
                try
                {
                    using (var k = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMouse"))
                        if (k != null)
                        {
                            string loc = k.GetValue("InstallLocation") as string;
                            if (!string.IsNullOrEmpty(loc)) dir = loc.Trim().Trim('"');
                        }
                }
                catch { }
                try
                {
                    using (var k = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall", true))
                        if (k != null) k.DeleteSubKeyTree("AirMouse", false);
                }
                catch { }
                foreach (var f in new[]{
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "AirMouse 接收端.lnk"),
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "卸载 AirMouse.lnk"),
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "AirMouse 接收端.lnk")})
                {
                    try { if (File.Exists(f)) File.Delete(f); } catch { }
                }

                // 目录里有正在运行的卸载器自己，直接删会失败 → 先删能删的，再让 cmd 延时清空并自删
                try
                {
                    if (Directory.Exists(dir))
                    {
                        foreach (var f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                        {
                            try { File.SetAttributes(f, FileAttributes.Normal); } catch { }
                            try { if (!f.Equals(Application.ExecutablePath, StringComparison.OrdinalIgnoreCase)) File.Delete(f); } catch { }
                        }
                        foreach (var d in Directory.GetDirectories(dir, "*", SearchOption.AllDirectories))
                        {
                            try { if (Directory.GetFileSystemEntries(d).Length == 0) Directory.Delete(d, true); } catch { }
                        }
                        if (!Application.ExecutablePath.StartsWith(dir, StringComparison.OrdinalIgnoreCase))
                            Directory.Delete(dir, true);
                        else
                        {
                            // 卸载器自己还在跑，Windows 不允许删除正在执行的 exe
                            // → 生成一个小 .bat，由隐藏的 cmd 反复试删自己，删掉后再把整个目录删掉。
                            // 最多重试 150 次 × 2 秒 = 5 分钟，足够用户看完「✓ 已卸载」窗口再关掉。
                            // 用 .bat 而不是在命令行里拼 for /l，是为了绕开引号转义问题。
                            string self = Application.ExecutablePath;
                            string bat = Path.Combine(Path.GetTempPath(), "airmouse-uninstall-" + DateTime.Now.Ticks + ".bat");
                            string script =
                                "@echo off\r\n" +
                                "for /l %%i in (1,1,150) do (\r\n" +
                                "  del /f /q \"" + self + "\" >nul 2>&1\r\n" +
                                "  if not exist \"" + self + "\" (\r\n" +
                                "    rmdir /s /q \"" + dir + "\" >nul 2>&1\r\n" +
                                "    del /f /q \"%~f0\" >nul 2>&1\r\n" +
                                "    exit /b 0\r\n" +
                                "  )\r\n" +
                                "  ping 127.0.0.1 -n 2 >nul\r\n" +
                                ")\r\n" +
                                "rmdir /s /q \"" + dir + "\" >nul 2>&1\r\n";
                            File.WriteAllText(bat, script, Encoding.Default);
                            var cmd = new ProcessStartInfo("cmd.exe", "/c \"" + bat + "\"");
                            cmd.UseShellExecute = false;
                            cmd.CreateNoWindow = true;
                            cmd.WindowStyle = ProcessWindowStyle.Hidden;
                            Process.Start(cmd);
                            Trace("self-delete scheduled via " + bat);
                        }
                    }
                }
                catch { }
                return null;
            }
            catch (Exception ex) { return ex.Message; }
        }

        static void RunCmd(string exe, string args)
        {
            try
            {
                var psi = new ProcessStartInfo(exe, args);
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                using (var p = Process.Start(psi)) { p.WaitForExit(8000); }
            }
            catch { }
        }
    }

    class SetupForm : Form
    {
        readonly bool _uninstallMode;
        readonly TextBox _log = new TextBox();
        readonly TextBox _dirBox = new TextBox();
        readonly Button _browse = new Button();
        readonly Button _install = new Button();
        readonly Button _uninstall = new Button();
        readonly Button _close = new Button();

        public SetupForm(bool uninstallMode)
        {
            _uninstallMode = uninstallMode;
            Text = Build.Uninstaller ? "AirMouse 卸载程序" : "AirMouse 安装程序";
            ClientSize = new Size(560, Build.Uninstaller ? 300 : 380);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            BackColor = Color.FromArgb(246, 248, 250);

            var title = new Label
            {
                Text = Build.Uninstaller ? "卸载 AirMouse 接收端" : "AirMouse 接收端",
                Font = new Font("Microsoft YaHei UI", 15F, FontStyle.Bold),
                ForeColor = Color.FromArgb(20, 24, 32),
                AutoSize = true,
                Location = new Point(20, 16)
            };
            var sub = new Label
            {
                Text = Build.Uninstaller
                    ? "会删掉程序文件、开始菜单/桌面快捷方式、开机自启和「应用和功能」里的卸载项。"
                    : (Program.PreviousDir() != null
                        ? "检测到已安装过：更新会直接覆盖原来的位置，不用重新选目录。"
                        : "装一次就不用再右键「以管理员身份运行」了。安装位置可以改，不一定要在 C 盘。"),
                Font = new Font("Microsoft YaHei UI", 9F),
                ForeColor = Color.FromArgb(90, 100, 116),
                AutoSize = true,
                Location = new Point(22, 52)
            };

            var dirLabel = new Label
            {
                Text = "安装到",
                Font = new Font("Microsoft YaHei UI", 9F),
                ForeColor = Color.FromArgb(60, 70, 86),
                AutoSize = true,
                Location = new Point(22, 82)
            };

            _dirBox.Text = Program.InstallDir;
            _dirBox.Font = new Font("Microsoft YaHei UI", 9F);
            _dirBox.Location = new Point(80, 78);
            _dirBox.Size = new Size(376, 26);
            _dirBox.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;

            _browse.Text = "浏览…";
            _browse.Size = new Size(80, 26);
            _browse.Location = new Point(462, 78);
            _browse.Click += (s, e) => BrowseDir();

            int logY = Build.Uninstaller ? 86 : 130;
            _log.Multiline = true;
            _log.ReadOnly = true;
            _log.ScrollBars = ScrollBars.Vertical;
            _log.Font = new Font("Consolas", 9F);
            _log.BackColor = Color.White;
            _log.BorderStyle = BorderStyle.FixedSingle;
            _log.Location = new Point(22, logY);
            _log.Size = new Size(516, Build.Uninstaller ? 130 : 180);
            _log.Text = _uninstallMode ? "点「卸载」开始。\r\n" : "点「安装 / 更新」开始。\r\n";

            int btnY = Build.Uninstaller ? 236 : 324;
            _install.Text = "安装 / 更新";
            _install.Size = new Size(140, 38);
            _install.Location = new Point(22, btnY);
            _install.Visible = !Build.Uninstaller;
            _install.Click += (s, e) => DoInstall();

            _uninstall.Text = "卸载";
            _uninstall.Size = new Size(140, 38);
            _uninstall.Location = new Point(Build.Uninstaller ? 22 : 172, btnY);
            _uninstall.Visible = Build.Uninstaller;   // 安装器里不再放卸载按钮
            _uninstall.Click += (s, e) => DoUninstall();

            _close.Text = "关闭";
            _close.Size = new Size(100, 38);
            _close.Location = new Point(438, btnY);
            _close.Click += (s, e) => Close();

            Controls.Add(title);
            Controls.Add(sub);
            dirLabel.Visible = !Build.Uninstaller;
            _dirBox.Visible = !Build.Uninstaller;
            _browse.Visible = !Build.Uninstaller;
            Controls.Add(dirLabel);
            Controls.Add(_dirBox);
            Controls.Add(_browse);
            Controls.Add(_log);
            Controls.Add(_install);
            Controls.Add(_uninstall);
            Controls.Add(_close);
        }

        void BrowseDir()
        {
            using (var dlg = new FolderBrowserDialog())
            {
                dlg.Description = "选一个用来放 AirMouse 的文件夹（会自动在其下建 AirMouse 子目录）";
                dlg.ShowNewFolderButton = true;
                try
                {
                    string cur = _dirBox.Text.Trim();
                    if (cur.Length > 0) dlg.SelectedPath = Directory.Exists(cur) ? cur : Path.GetDirectoryName(cur);
                }
                catch { }
                if (dlg.ShowDialog(this) == DialogResult.OK && !string.IsNullOrEmpty(dlg.SelectedPath))
                {
                    // 「浏览」选的是父目录，安装时再拼一层 AirMouse，避免直接铺在目录/磁盘根上
                    string picked = NormalizeDir(dlg.SelectedPath);
                    _dirBox.Text = picked.EndsWith("\\" + Program.AppName, StringComparison.OrdinalIgnoreCase)
                        ? picked
                        : picked.TrimEnd('\\') + "\\" + Program.AppName;
                }
            }
        }

        /// 把用户输入的目录归一成绝对路径；"D:" / "D:\" 这类盘符根补回分隔符（否则 "D:AirMouse" 是盘符相对路径，
        /// 会被解析成「当前工作目录所在盘的那个相对路径」，正是之前装到 dist\AirMouse 的原因）。
        static string NormalizeDir(string raw)
        {
            raw = (raw ?? "").Trim().Trim('"');
            if (raw.Length == 0) return Program.DefaultDir();
            if (raw.Length == 2 && char.IsLetter(raw[0]) && raw[1] == ':') raw += "\\";
            else if (raw.Length == 3 && char.IsLetter(raw[0]) && raw[1] == ':' && raw[2] == '\\') { /* 已是 D:\ */ }
            return Path.GetFullPath(raw);
        }

        /// 把界面上的目录写回 Program.InstallDir；返回 false 表示路径非法
        bool ApplyDir()
        {
            try
            {
                string raw = NormalizeDir(_dirBox.Text);
                string final = (raw.Length <= 3) ? (raw.TrimEnd('\\') + "\\" + Program.AppName) : raw;
                Program.InstallDir = final.TrimEnd('\\');
                _dirBox.Text = Program.InstallDir;
                if (raw.Length <= 3) Log("安装到盘符根目录会自动加一层：" + Program.InstallDir);
                return true;
            }
            catch (Exception ex) { Log("✗ 安装路径不合法：" + ex.Message); return false; }
        }

        void Log(string s)
        {
            _log.AppendText(s + "\r\n");
        }

        void DoInstall()
        {
            if (!ApplyDir()) return;
            if (!Program.IsAdmin())
            {
                Log("需要管理员权限（写 Program Files / 建计划任务都要它），正在请求提权…");
                if (!Program.RelaunchElevated("/dir \"" + Program.InstallDir + "\""))
                {
                    Log("已取消提权，安装未执行。");
                    return;
                }
                Close();
                return;
            }
            Log("开始安装 → " + Program.InstallDir);
            string err = Program.InstallCore();
            if (err != null)
            {
                Log("✗ 安装失败：" + err);
                MessageBox.Show("安装失败：\r\n" + err, "AirMouse", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            Log("✓ 安装完成。");
            Log("以后开机登录会自动启动（计划任务，不弹 UAC）。");
            // 收尾窗口弹出时，安装窗口先消失（免得两个窗口叠着）
            Hide();
            using (var done = new DoneForm(false, Program.ServerExe))
            {
                done.ShowDialog(this);
                if (done.LaunchRequested)
                {
                    try
                    {
                        Process.Start(new ProcessStartInfo(Program.ServerExe) { WorkingDirectory = Program.InstallDir, UseShellExecute = true });
                        Log("已启动接收端。");
                    }
                    catch (Exception ex)
                    {
                        MessageBox.Show("启动失败：\r\n" + ex.Message, "AirMouse", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                }
            }
            Close();
        }

        void DoUninstall()
        {
            if (!Program.IsAdmin())
            {
                Log("卸载也需要管理员权限，正在请求提权…");
                if (!Program.RelaunchElevated("/uninstall"))
                {
                    Log("已取消提权，卸载未执行。");
                    return;
                }
                Close();
                return;
            }
            if (MessageBox.Show("确定卸载 AirMouse 接收端？", "确认", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
                return;
            Log("开始卸载…");
            string err = Program.UninstallCore();
            if (err != null)
            {
                Log("✗ 卸载出错：" + err);
                MessageBox.Show("卸载出错：\r\n" + err, "AirMouse", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            Log("✓ 已卸载。");
            Hide();
            using (var done = new DoneForm(true, null)) done.ShowDialog(this);
            Close();
        }
    }

    /// 收尾窗口：装完问「要不要现在就运行」，卸载完只报成功
    class DoneForm : Form
    {
        public bool LaunchRequested { get; private set; }
        readonly CheckBox _run = new CheckBox();

        public DoneForm(bool uninstall, string serverExe)
        {
            Text = uninstall ? "卸载完成" : "安装完成";
            ClientSize = new Size(430, uninstall ? 176 : 208);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false; MinimizeBox = false;
            ShowInTaskbar = false;
            BackColor = Color.FromArgb(246, 248, 250);

            var title = new Label
            {
                Text = uninstall ? "✓ 已卸载 AirMouse 接收端" : "✓ 安装完成",
                Font = new Font("Microsoft YaHei UI", 14F, FontStyle.Bold),
                ForeColor = Color.FromArgb(20, 100, 60),
                AutoSize = true,
                Location = new Point(20, 18)
            };
            var sub = new Label
            {
                Text = uninstall
                    ? "程序文件、快捷方式、开机自启和卸载项都已清掉。"
                    : "程序已装到：\n" + Program.InstallDir,
                Font = new Font("Microsoft YaHei UI", 9F),
                ForeColor = Color.FromArgb(90, 100, 116),
                AutoSize = true,
                Location = new Point(22, 52)
            };
            Controls.Add(title);
            Controls.Add(sub);

            if (!uninstall)
            {
                _run.Text = "运行 AirMouse 接收端";
                _run.Font = new Font("Microsoft YaHei UI", 9.5F);
                _run.AutoSize = true;
                _run.Location = new Point(24, 116);
                _run.Checked = false;
                Controls.Add(_run);
            }

            var close = new Button();
            close.Text = "关闭";
            close.Size = new Size(104, 36);
            close.Location = new Point(uninstall ? 158 : 302, uninstall ? 116 : 148);
            close.Click += (s, e) => { LaunchRequested = !uninstall && _run.Checked; Close(); };
            Controls.Add(close);
            AcceptButton = close;
            CancelButton = close;
        }
    }
}

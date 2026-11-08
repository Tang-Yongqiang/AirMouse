# 更新记录

> 技术记录，给开发/维护看。普通用户看到的说明在 `update.json` 的 `notes` 里（发版时用 `publish.ps1 -TechNotesFile` 分开写）。

## 1.1.17 · 2026-11-08（电脑端 1.1.9）

**用户可见**
- · 点状态栏那一行现在真的能点了：蓝牙弹设备面板，WiFi 弹 IP 设置，USB 给出接线提示
- · 连接方式下拉只留三个模式，配对入口挪到了状态栏点击里
- · 手机输入法打字的「取消」不再收起面板，还能接着按 Esc / Tab / ⌫ / ⏎；点「发送」才回触摸板
- · 多版本的更新说明改成越新越靠前，先看到这一版改了什么

**技术细节**
- - UI：`statusBox/statusTitle/statusSub/statusDot` 四个控件都挂 `onStatusTap`（ColorOS 下子 TextView 会吃掉点击，只挂外层表现为「点了没反应」）
- - UI：`showModeMenu()` 去掉第 4 项「配对新电脑…」，蓝牙动作统一走状态栏 → `pickDevice()`
- - UI：`pickDevice()` 三处静默返回补提示（无蓝牙 → toast；蓝牙没开 → toast + 系统对话框；无 BLUETOOTH_CONNECT → toast + 跳应用详情页）
- - 键盘：`showImeDialog()` 的 `onOk` 改为发送后 `hideKeyboardPanel()`；`setOnDismissListener` 未发送时保留面板并置 `keyboard.imeMode = true`
- - 更新：`updateNotesText()` 的 history 改为 `sortedByDescending { it.versionCode }`
- - 电脑端代码未变，版本号保持 1.1.9

## 1.1.16 · 2026-11-08（电脑端 1.1.9）

**用户可见**
- · 连上电脑后会互相确认版本：两端不配套时直接提示，不再出现「看着连上了、操作却没反应」
- · 检查更新改为直接读线上最新信息，电脑端单独更新也能马上知道
- · 更新说明继续显示「从你当前版本到最新版本」的全部改动

**技术细节**
- - 新增连接握手：手机端连上发 `{"t":"hi","app":…,"code":…,"proto":1}`，电脑端回 `{"t":"hi","server":…,"proto":1}`；两端比对 `NetInput.PROTO` / `MainForm.Proto`，不一致时手机弹一次「两端版本不配套」、电脑端写 `[!] 协议不一致…`
- - `NetInput` 的读线程从「丢弃字节只判 EOF」改为按行读，新增 `handleServerLine()` 解析 `hi`
- - 手机端 `Updater.check()` 新增 `fresher()`/`fetchMainInfo()`：把仓库根 `@main` 清单并入候选，同版本优先用 `@main`（电脑端单独发版时靠它传播）
- - 电脑端 `StartUpdateCheck` 新增「第零步」：先读 `ManifestUrls`（raw 优先）的 `@main` 清单，命中 `"server"` 就直接用，后面「猜 tag」「tags 接口」短路
- - `publish.ps1` 支持 `-ServerOnly`（只升电脑端、保留 apk 段与 history、tag 用 `s<serverVersion>`）；`-AppVersion` 改为可省略但非 `-ServerOnly` 时必填
- - 修 `publish.ps1` 丢 UTF-8 BOM 导致 PowerShell 5.1 按 ANSI 解析中文报错（改用 `UTF8Encoding($true,$false)` 写回）

## 1.1.15 · 2026-11-08（电脑端 1.1.8）

**用户可见**
- · 手机端这一版和 1.1.14 一样，没有功能变化
- · 本次发布主要是电脑端 1.1.8：修好了窗口底部按钮重叠、「查看日志」显示不全的问题

**技术细节**
- - 底部按钮行：TableLayoutPanel 行高改 AutoSize，`btnRow.WrapContents = true`（放不下时换行而不是互相压住）；窗口默认 `ClientSize 820×520`、`MinimumSize 520×380`
- - 修 `本机 IP` 日志：原来 `Log.Write("  本机 IP：" + ip)` 拼的是 `Nic` 对象，打出 `AirMouse.MainForm+Nic`；改成 `ip.Ip + "（" + ip.Name + "）"`
- - `pc/AssemblyInfo.cs` → 1.1.8.0

## 1.1.14 · 2026-11-08（电脑端 1.1.7）

**用户可见**
- · 更新说明现在会列出从你当前版本到最新版本的**每一版**改动
- · 隔了好几版没升级的，也能一次看清中间都改了什么

**技术细节**
- - update.json 新增 `history[]`（每版一条 versionCode/versionName/notes，保留最近 12 条），客户端据此把「当前版本 → 最新版本」之间的说明全部列出
- - `Updater.kt` 增 `data class VersionNote` 与 `Info.history`；`parse()` 解析 `history` 并按 versionCode 升序
- - `MainActivity.updateNotesText`：`history` 里 > 当前版本号的条目 ≥2 时按版本号逐段拼接（每段以版本号开头），否则退回单条 `notes`
- - `publish.ps1` 发版时读旧 `update.json` 的 `history`，追加本版、去重、按 versionCode 排序、截断到 12 条
- - 顺带把 1.1.11 / 1.1.12 / 1.1.13 的历史回填进 `update.json`

## 1.1.13 · 2026-11-08（电脑端 1.1.7）

**用户可见**
- · 修好了「发现新版本」时说明区空白的问题
- · 更新信息现在每次都能取到最新的，不再被缓存挡住
- · 同一个版本的更新提醒只弹一次，不会每次打开都打扰

**技术细节**
- - update.json 顶层补回 `notes`（publish.ps1 同时写顶层与 apk.notes），修复 ≤1.1.11 客户端只读顶层字段导致的「更新说明空白」
- - `MainActivity.updateNotesText` 说明为空时兜底「这一版没有写更新说明，直接装就行。」
- - `Updater.fetchByTag` / `AirMouseServer.DownloadTagged` 源顺序改为 raw → jsDelivr → gitee：jsDelivr 的 tag 快照实测 12 小时不刷新、`purge` 返回 200 但内容不变
- - 相关提交：`4f8aedd`（顶层 notes）、`7ad1406`（兜底文案）、`a2946b4`（raw 优先）

## 1.1.12 · 2026-11-08（电脑端 1.1.6）

**用户可见**
- 打开 App 会自动检查更新，发现新版本可以直接在 App 里下载安装
- 同一个新版本只提示一次，点过「以后再说」就不再打扰
- 蓝牙列表只显示电脑，最近连过的排最上面
- 键盘面板：取消或点空白处可收起；没输入内容会提示

**技术细节**
- 手机端 Updater.checkOnLaunch()：每次启动都查，仅挡 60 秒内重复（切主题/横竖屏会重建 Activity）；MainActivity.checkUpdateOnLaunch() 延迟 1.5s 执行，仅在 versionCode 更大时弹，弹过写 prefs.updateSkipVersion 防重复
- 清单按端拆分：apk.notes（手机端）/ server.notes（电脑端）/ server.minForApk（可选硬性电脑端版本要求，手机端自动补一行提示）；两端 parse 都保留旧的顶层 notes 兜底
- 电脑端底部新增常驻「检查更新」按钮；StartUpdateCheck(bool manual, Action onDone) 支持手动回显：ShowCheckResult() 复用顶部链接显示「已是最新版本 X」/「检查更新失败：…」，8 秒后自动收起；finally 里回调恢复按钮可用
- `publish.ps1` 新增 `-ServerNotesFile` / `-MinServer` / `-TechNotesFile`；notes 里字面 `\n` 归一为换行；`CHANGELOG.md` 由发版脚本自动追加（用户可见 + 技术细节两节）
- 修复 jsDelivr @<tag> 快照缓存导致 1.1.11 说明回退旧文案的问题：发新版本换 tag 即可，无需 purge

## 1.1.11 · 2026-11-08（电脑端 1.1.5）

**用户可见**

- 蓝牙列表只显示电脑，最近连过的排最上面；耳机、手表等不再混进来（需要时可点「显示全部蓝牙设备」）
- 蓝牙面板会提示「点这里选择电脑或配对新设备」，不再只显示「HID 就绪」
- 全部重置会清掉上次连接的电脑，重置后不会自动连回去
- 键盘面板：点取消或空白处可收起；没输入内容就点发送会提示；功能键顺序改为 Esc / Tab / ⌫ / ⏎
- 电脑端安装器升级时自动沿用上次的安装目录，不用重新选
- 电脑端卸载更干净，卸载后不会再残留卸载程序
- 两端每次打开都会自动检查更新；电脑端右下角新增「检查更新」按钮

**技术细节**

- 卸载器改名 `uninstall.exe`，升级时删除旧的 `AirMouseUninstall.exe`
- 卸载自删：改为生成临时 `.bat`，循环 `del` 自身（150 次 × 2 秒）后才 `rmdir` 目录并自删 bat。原实现 `cmd /c ping -n 3 & rmdir` 只等 2 秒，卸载器进程还没退出，删不掉自己 → 残留
- `Setup.PreviousDir()` 读 `HKLM\…\Uninstall\AirMouse` 的 `InstallLocation`；优先级 `/dir` > 上次位置 > `Program Files\AirMouse`；新增 `--printdir` 排障参数（不需要管理员）
- 蓝牙列表：`Prefs.btHistory`（最多 8 台）+ `rememberBt()`；`pickDevice()` 过滤 `majorDeviceClass == 0x0100` 并按最近使用排序；「显示全部蓝牙设备（还有 N 个）」；调试开关 `--ez pick`
- 状态文案：`applyMode()` 的蓝牙分支直接按 `hid.isConnected` 写，不再依赖 `onHidState(READY)` 回调（HID 早于开关注册，`publish()` 会去重导致文案不更新）
- 全部重置：`doReset()` 先 `setEnabled(false,false)` + `net.stop()` + `hid.close()`，再清 prefs 与内存态（含 `lastAddress`、`autoConnectTried`）
- `publish.ps1`：新增 `-NotesFile` / `-TechNotesFile`（按 UTF-8 读，规避命令行 UTF-8→GBK 乱码）；notes 里字面 `\n` 归一成换行；目录剪枝改按 `[version]` 排序 + `git rm --ignore-unmatch`（原来按字符串降序会把刚发的 `app-v1.1.11` 判成旧的删掉）

## 1.1.10 · 2026-11-08

- 「全部重置」不再残留 IP 并自动重连
- 新增 `_dev\testbuild.ps1`（`-PverSuffix=-test` → 装到手机 → 删除产物）

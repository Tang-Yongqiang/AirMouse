# 更新记录

> 技术记录，给开发/维护看。普通用户看到的说明在 `update.json` 的 `notes` 里（发版时用 `publish.ps1 -TechNotesFile` 分开写）。

## 1.1.21 · 2026-11-14（电脑端 1.1.11）

**用户可见**
- · 修好设置页底部几个选项被裁掉的问题：设置现在可以上下滑动，「检查更新」「全部重置」都能看到也能点到了

**技术细节**
- · 设置抽屉（sheet_settings.xml）根布局由 LinearLayout 改为 NestedScrollView（fillViewport=true、scrollbars=none），修复内容超出抽屉最大高度时底部「检查更新 / 全部重置」被裁掉且无法滚动的问题
- · 起因：新增「音频」三行后内容变高；BottomSheetDialog 高度上限为屏幕，wrap_content 的 LinearLayout 直接裁切

## 1.1.20 · 2026-11-14（电脑端 1.1.11）

**用户可见**
- · 手柄按钮能整体调大小了，十字键不再挤在一起
- · 布局编辑加了网格吸附；手柄铺满全屏，不再有白边
- · 左摇杆按下即定位、推过头会跟手，右半屏整块是视角区
- · 扳机改成按下即到底；调整大小的滑条挪到屏幕底部中央

**技术细节**
- · 手柄：ABXY/十字键按「组几何中心」缩放（键尺寸与相对组心偏移同倍放大），滑块 0.4×~2.5×，按组中心吸网格
- · 手柄：左右摇杆改成整半屏热区（x<w/2 左摇杆、x>w/2 触摸板），静止不绘制；左摇杆按下即把基准点设到手指、超出一个半径后基准点跟手平移、松手/取消回位
- · 手柄：右摇杆用浮动原点触摸板（PAD_DEADZONE 0.06 + smoothstep），整个右半屏可拖
- · 手柄：扳机由模拟量改按钮语义（pressed 即 value=1f），并整体内缩 edge=dp(24f)，配合 systemGestureExclusionRects 避开系统返回手势
- · 手柄：十字键改成单 Path（hor ∪ ver）一次绘制，消除四臂接缝；hitDpad 按相对中心的主方向选臂
- · 手柄：布局编辑加网格，gridStep=min(w,h)/20，原点取屏幕中线（与左右分界线重合），拖动/缩放/进编辑态统一 snapToGrid
- · 手柄：新增 DEFAULT_LAYOUT 常量（用户手摆版本，按比例存），rebuild 后先 applyDefaultLayout 再 applySaved；resetLayout 回出厂
- · 手柄：进编辑态先 snapAllToGrid（组按组中心、候选 ±2 格避让、容忍原生重叠）
- · 手柄：手柄模式真全屏 —— 隐藏 systemBars、cutout SHORT_EDGES、窗口底色 #FF12161C、insets 归零；退出恢复
- · 手柄：底部大小滑条改 380dp 固定宽 + 52dp 底距 + bg_pad_size_bar 圆角底衬（手柄模式因 configChanges 不重建，实际用的是 layout/ 那份）
- · 调试开关新增：--ez padsim / padsim2 / dpadsim / layoutedit / layoutreset / layoutpick / layoutgrow / gridsim / trigsim / dumplayout / micprio

## 1.1.19 · 2026-11-13（电脑端 1.1.11）

**用户可见**
- 新增：手柄模式 —— 手机横过来就是一块虚拟手柄，电脑上会多出一只 Xbox 360 手柄，绝区零这类游戏直接能在设置里选到
- 新增：手柄布局能自己调 —— 点右上角「布局」拖动摆放，点某个键再用下面的滑条调大小，随时能一键重置
- 新增：手机当电脑麦克风 —— 电脑里把输入设备选成「CABLE Output」，说话就进游戏/语音（安装包会自动装 VB-Cable）
- 新增：电脑的声音从手机放出来 —— 延迟一百毫秒左右；开麦时会自动暂停外放，免得对方听到回声（设置里可以关）
- 修复：手柄模式点了「退出」退不出来
- 修复：电脑声音越听越延迟（有人听到慢了 2 秒），现在最多慢 0.2 秒

**技术细节**
- 协议：主控链路新增 t:"g"（手柄帧 b/lx/ly/rx/ry/lt/rt）、t:"gp"（拔手柄）、t:"v"（电脑→手机震动）；扫描码仍在 t:"k"。"proto" 仍为 1，老客户端不受影响
- 音频走独立端口 47778（主控 47777 + 1）：握手 {"t":"au","dir":"mic|media"}，回 {"t":"a-ok",...} 或 {"t":"a-err","why":...}；帧 = 4 字节小端长度 + PCM16，媒体方向 20ms 一帧
- pc/VirtualPad.cs：P/Invoke ViGEmClient（vigem_alloc/connect/target_x360_alloc/target_add/update/get_user_index/register_notification），成功码是 0x20000000 而不是 0
- 手柄坑：新加的 x360 目标默认报告不是中立，而是 lx=-3356 ly=-1869 rx=-3255 ry=-848；插入后必须先报一帧 lx=1 再报中立帧，否则游戏里右摇杆自带约 10% 偏置、镜头会自己慢慢漂
- 手柄插入/拔出跟随主控连接：被另一台手机接管时不拔（避免游戏里手柄闪断），只有真正断开才 VirtualPad.Shutdown
- pc/AudioBridge.cs：麦克风 = BufferedWaveProvider → WaveOutEvent → VB-Cable 的「CABLE Input」；电脑声音 = WasapiLoopbackCapture（默认播放设备；默认是 VB-Cable 时改抓第一块真声卡并写日志，否则会把自己的声音送回去）
- 音频延迟坑：旧代码在 DataAvailable 回调里同步写 socket，链路抖一下就把积压留在发送缓冲里排不掉，表现为恒定慢 2 秒；改成有界队列（200ms）+ 独立发送线程 + 16KB SO_SNDBUF，超限丢最旧帧，手机端 16KB SO_RCVBUF + tcpNoDelay
- 回声三层：AudioRecord(VOICE_COMMUNICATION) + AcousticEchoCanceler/NoiseSuppressor、开麦时外放压到 35%、开麦时暂停外放（默认开，可在设置里关）
- Setup：内嵌 NAudio.dll / ViGEmClient.dll / VBCABLE_Setup_x64.exe / ViGEmBus 安装包，缺驱动时静默装（-i -h / /quiet /norestart），装不上只记 trace 不中断
- Setup：「AirMouse 接收端」快捷方式改成 schtasks /run /tn AirMouseServer，双击不再弹 UAC、也不留 PowerShell 窗口；卸载快捷方式仍指向 uninstall.exe
- publish.ps1：剪枝跳过 releases/stable；versionName 保留 $it 后缀；服务器 csc 行补 VirtualPad.cs / AudioBridge.cs / NAudio.dll；安装器资源改用 $setupRes 数组拼接

## 1.1.18 · 2026-11-08（电脑端 1.1.10）

**用户可见**
- · 两台手机连同一台电脑时，后连上的自动接管，先连的那台会提示「已被另一台手机接管」
- · 被接管时点一下状态栏那一行就能马上抢回来，不用重开 App
- · 被接管的那台不会再偷偷重连，两台不会你顶我、我顶你地来回抢
- · WiFi、USB 都能互踢；蓝牙是两台各连一路，互不影响，两台都能用

**技术细节**
- - 电脑端：`AcceptLoop()` 增加单槽 `_activeClient` + `SafeRemote()`；新连接先发 `{"t":"bye","reason":"replaced","server":<ver>,"proto":1}` 再 `Close()` 旧的，然后 `_activeClient = client`
- - 电脑端：新增 `_activeRemote` 缓存地址；只有 bye 真的写成功（`notified = true`）才记「新手机接入，… 接管，已让上一条（…）让位」，避免残留 socket 让每条新连接都误报「接管（?）让位」
- - 电脑端：`ServeClient` 的 finally 里 `if (ReferenceEquals(_activeClient, client)) { _activeClient = null; _activeRemote = null; }`，槽位随主控断开自动清空
- - 手机端：`NetInput` 新增 `State.KICKED`、`@Volatile kickedOut`（private set）、`@Volatile onKicked`；`handleServerLine` 改成按 `t` 分派（`bye` → `onServerBye`，`hi` → `onServerHi`）
- - 手机端：`onServerBye()` 顺序是 `kickedOut = true; running = false; queue.clear(); closeSocket();` 再 publish/回调 —— 顺序反了会被读线程抢先改成「正在重连…」，1.5 秒后又连回去，形成无限互踢
- - 手机端：`loop()` 顶部、`registerFailure()` 开头、`startReader` 的 finally 三处按 `kickedOut` 提前退出；`start()` / `retryNow()` 清标志（手动「点回接管」是唯一的翻盘入口）
- - 手机端 UI：状态栏 KICKED 文案「已被另一台手机接管 · 点这里重新接管」+ 红点 `COLOR_WARN` + `statusBox.isClickable`；`onStatusTap` 把 KICKED 并进「点了就重连」分支；新增调试开关 `--ez retake true`（HyperOS 拦 adb 注入点击，界面交互只能靠开关或手点）
- - 构建：修 `app/build.gradle.kts` 的 verSuffix bug（原来 `versionName = "1.1.17"`，后缀根本没拼进去，测试包版本名不带 -test）
- - 兼容性：`bye` 复用 proto 1（老客户端遇到不认识的 `t` 会忽略）。但 1.1.18 以前的手机被接管只表现为连接被断开，会自己重连 —— 新老混用时仍可能互顶，两端都升到 1.1.18 才干净
- - 实测（红米 K70 + OPPO Find X8s）：USB+WiFi、WiFi+WiFi 双向互踢；6 次交替 retake 每次只产生一条接管记录，空闲 20 秒零重连；蓝牙为两路独立 HID（`Get-PnpDevice` 可见两组 BTHID），不经服务端、无接管语义

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

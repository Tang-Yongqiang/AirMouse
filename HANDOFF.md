# AirMouse 交接说明

> **给接手的 DSH / AI 助手**：这份文档假设你要在这个仓库里继续改代码或把它部署到新机器上。
> 读法：先看 §0 和 §14（验证清单），要改哪块再跳到对应章节。
> 下面的路径都以仓库根目录为基准（原作者机器上是 `D:\Workspaces\deepseek_harness\Something\gyro-mouse`），换机请自行替换。

---

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 是什么 | 一个 Android App，把手机变成电脑的无线鼠标 + 键盘（陀螺仪控光标、触摸板点击/滚轮、软键盘打字） |
| 语言/形态 | Kotlin，单 module（`:app`），除 AndroidX 外**无第三方依赖**，无 gRPC/协程/Compose |
| 包名 | `com.airmouse.gyro` |
| 版本 | versionCode 34 / versionName "2.23"（v1.3：总开关、射线指向、布局改版；v1.4：射线模式单指滚轮；v1.5：滚轮方向翻转；v1.6：惯性；v1.7：惯性设置、中键收窄；v1.8：射线两点校准；v1.9：陀螺仪按钮两态化；v2.0：交互与视觉打磨；v2.1：USB 直连自动化；v2.2：三种连接方式拆成三个按钮；v2.3：顶部收成两个下拉 + 开关；v2.4：下拉改成真·下拉列表；v2.5：停用时整块禁用 + 提示；v2.6：设置改底部抽屉；v2.7：使用说明抽屉 + 陀螺仪受连接约束；v2.8：说明叠在设置上；v2.9：所有对话框统一抽屉；v2.10：点输入法 tab 直接弹键盘；v2.11：默认手机输入法；v2.12–2.14：去掉重复的「整段输入」；v2.13：键盘面板浮层化；v2.15：开关重开恢复陀螺仪；v2.16：校准挪右下角；v2.17：深浅色主题；v2.18：重建后状态恢复；v2.19：横屏布局；v2.20：横屏禁用陀螺仪；v2.21：横竖屏往返恢复陀螺仪 —— 见 §16） |
| minSdk / targetSdk / compileSdk | 28 / 36 / 36 |
| 两条链路 | ① **蓝牙 HID**（手机自己注册成 HID 复合设备，电脑零安装）② **WiFi/USB TCP**（电脑跑 `pc/airmouse_server.py`，用 SendInput 注入；**唯一能打中文的链路**） |
| 实测通过 | 红米 23113RKC6C（HyperOS 3 / Android 16）、OPPO PKT110（ColorOS / Android 17，SDK 37）、Windows 11 + Intel 蓝牙 |
| 现在的默认节奏 | 蓝牙移动报告 32ms 一帧（≈31Hz），**这是实测出来的硬结论，别改小**，见 §4.3 |

---

## 1. 功能清单

- **光标**：陀螺仪（世界坐标系解算，姿态无关）或触摸板单指拖动
- **点击**：轻点=左键、长按拖动=按住左键拖选、双指轻点=右键、底部「左键/右键/中键」按住式按钮
- **滚轮**：触摸板双指上下；右侧竖条精调滚轮
- **打字**：两种方式随时切 —— ① 直通键码（自绘 QWERTY，一个键=一个 HID 键码）② 手机输入法（系统输入法先把字打成型，再整段注入电脑）
- **多设备**：蓝牙 HID 名字带机型（`AirMouse-PKT110`），两台手机可共存；自动重连上次那台电脑
- **配置**：模式/电脑 IP/灵敏度/打字方式落 SharedPreferences

---

## 2. 目录与文件职责

```
gyro-mouse/
├── settings.gradle.kts        # local-repo + 阿里云镜像 + google/mavenCentral
├── build.gradle.kts           # AGP 8.7.3 / Kotlin 2.0.21（apply false）
├── gradle.properties          # 内存、UTF-8、useAndroidX、suppressUnsupportedCompileSdk=36
├── local.properties           # ← 机器相关，不进包：sdk.dir=<你的 Android SDK>
├── local-repo/                # 离线补件仓库（只有 kotlin-reflect 2.0.21，见 §9.2）
├── README.md                  # 面向使用者：怎么配对、怎么用
├── HANDOFF.md                 # 本文（面向开发者/DSH）
├── dist/AirMouse-2.17.apk     # 已签名 release 包
├── app/
│   ├── build.gradle.kts       # 签名、版本、依赖、版本强制对齐
│   ├── airmouse.keystore      # 开发用签名（别名/密码全 airmouse），更新已装 App 必须用同一个
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/airmouse/gyro/
│       │   ├── MainActivity.kt        (768)  UI 编排、模式切换、权限、提示对话框、调试 extra
│       │   ├── HidInput.kt            (501)  蓝牙 HID：注册、描述符、发送线程
│       │   ├── HidDescriptor.kt        (77)  复合 HID 描述符字节（鼠标 Report 1 + 键盘 Report 2）
│       │   ├── HidKeys.kt              (86)  ASCII → HID usage 映射
│       │   ├── NetInput.kt            (214)  TCP 行分隔 JSON：写线程 + 读线程 + 重连
│       │   ├── InputSink.kt            (32)  ★两种链路的统一抽象
│       │   ├── GyroEngine.kt          (222)  陀螺仪解算 + 定时积分
│       │   ├── TouchpadView.kt        (184)  手势识别 + 亚像素累加
│       │   ├── ScrollStrip.kt         (128)  右侧精调滚轮条
│       │   ├── KeyboardPanelView.kt   (294)  自绘键盘 + 两种打字方式的分页
│       │   └── Prefs.kt                (43)  SharedPreferences 封装
│       └── res/                        # 布局 activity_main.xml、颜色、主题、矢量图标
└── pc/
    ├── airmouse_server.py             # Windows 接收端（ctypes SendInput）
    └── 启动接收端.bat                  # 双击即跑（chcp 65001 + python -u）
```

> 括号里是行数，方便你判断改动面的比例。`MainActivity.kt` 偏大是刻意的：所有 UI 交互集中在一处，没有引入 ViewModel/DI。

---

## 3. 架构

核心抽象是 `InputSink`（`InputSink.kt`）：

```kotlin
interface InputSink {
    val label: String
    fun mouseMove(dx: Int, dy: Int)
    fun mouseButton(button: Int, down: Boolean)   // BTN_LEFT=1 / BTN_RIGHT=2 / BTN_MIDDLE=3
    fun wheel(notches: Int)
    fun keyPress(hidKey: Int, mods: Int)
    fun typeText(text: String)
    fun close()
}
```

两个实现：`HidInput`（蓝牙）和 `NetInput`（TCP）。`MainActivity` 里持有 `private var sink: InputSink`，按 `prefs.mode`（`Prefs.MODE_BT=0` / `Prefs.MODE_NET=1`）切换。

数据流：

```
GyroEngine ─┐
TouchpadView ┼─→ MainActivity（转成 InputSink 调用）─→ HidInput / NetInput ─→ 电脑
ScrollStrip ─┤
KeyboardPanel┘
```

**为什么中间还有一层 MainActivity**：手势/键码的产生与链路无关，而状态栏文案、未连接时的 toast 提示也在这里，集中一处比每层各自处理更好改。新增链路 = 实现 `InputSink` + 在 `applyMode()` 里加一个分支。

---

## 4. 蓝牙 HID 模式

### 4.1 注册

`HidInput.start()` → `adapter.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)` → 拿到 `BluetoothHidDevice` 后 `registerApp(sdp, null, null, executor, callback)`：

- `BluetoothHidDeviceAppSdpSettings(DEVICE_NAME, "Gyro mouse & keyboard", "AirMouse", BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.COMBO)`
- `DEVICE_NAME = "AirMouse-${Build.MODEL}"`（`by lazy`，两台手机才分得清）
- QoS 两个参数直接传 `null`（默认值就够用）
- **第 4 个参数是 `Executor`，第 5 个才是 `Callback`** —— 这个顺序踩过坑，别写反
- `registerApp` 返回 `false` 通常不是设备不支持，而是**上一个进程还没释放注册**：`adb shell am force-stop com.airmouse.gyro`，等 3 秒再启动即可

### 4.2 描述符与报告格式

`HidDescriptor.COMBO`（`HidDescriptor.kt`）是复合描述符：

| Report ID | 用途 | 载荷 |
|---|---|---|
| `1` | 鼠标 | 4 字节 `[buttons, dx, dy, wheel]`，dx/dy/wheel 是 int8（-127..127） |
| `2` | 键盘 | 8 字节 `[modifiers, 0, k1..k6]` |

关键点：`sendReport(device, id, data)` 的 `data` **不含 report id**，协议栈自己补。所以代码里 `send(REPORT_MOUSE, byteArrayOf(btn, sx, sy, 0))`。

- 按钮位：bit0 左 / bit1 右 / bit2 中
- 修饰键位：bit0 Ctrl / bit1 Shift / bit2 Alt / bit3 GUI（见 `HidKeys.MOD_*`）
- 单次移动超过 127 要分片（`HidInput.mouseMove` 里已经做了）

### 4.3 为什么移动报告默认 32ms 一帧（**最重要的性能结论**）

现象：红米上很顺，OPPO 上「不跟手 + 不连贯」。原因是 OPPO 触控采样率 120Hz+，而**蓝牙 HID 的 interrupt 通道大约 30ms 才真正被主机取走一次**；发得比这快，报告在队列里互相覆盖，位移直接丢（表现就是光标追不上手指、快慢不均）。

实测（合成 +200px 右移，用 `tools/cursor_sampler.ps1` 量电脑端起止坐标差）：

| flushMs | 实际到位 |
|---|---|
| 8（125Hz） | 36px（18%） |
| 16 | 76px（38%） |
| 20 | 161px（80%） |
| 24 | 173px（86%） |
| **32** | **196px（98%）** |

nudge 交叉验证（20 次 `mouseMove(5,0)`，共 100px）：24→83、28→85、32→90、36→92、40→97，同一趋势。

所以 `HidInput.kt` 里：

```kotlin
private const val MOVE_FLUSH_MS = 32L   // 实测：32ms≈31Hz 是能把位移几乎完整送达的节奏
var flushMs: Long = MOVE_FLUSH_MS       // 可运行时改，见 §11 的 --ei flushms
```

发送端结构（**别退回主线程合并**）：`mouseMove()` 只累加到 `pendX/pendY`，独立守护线程 `airmouse-hid-send` 每 `flushMs` 取一次快照发送；按钮/滚轮置脏位，随下一帧一起发。早期版本用 `Handler.postDelayed(8)` 在主线程合并，实测只跑到 ~16ms 节奏且 `sendReport` 的 1.7ms 卡 UI，已废弃。

> 红米不卡只是因为它触控本来就是 60Hz，天然没超发；**别拿红米的表现当作 8ms 可用的证据**。

### 4.4 假连接（排查时必看）

反复 force-stop / 重装后会出现：手机端 `state=CONNECTED`、`sendReport` 返回 true、`adb logcat` 一切正常，但**电脑一个像素都不动** —— 主机侧的 HID 句柄已经是陈旧的。判断方式：`HidInput.debugState()` 打印的 `state/dev/proxy/sender` 都正常却没反应。恢复方式：重启 App 后等 `onConnectionStateChanged -> 2`。

所以：**调试尽量用 `onNewIntent` 触发（`am start` 打同一个 Activity），不要每次都 force-stop**。

---

## 5. 网络（WiFi / USB）模式

### 5.1 协议

TCP，UTF-8，**一行一条 JSON**（`\n` 分隔）。手机 → 电脑单向，服务端不回包（只靠 TCP FIN 感知断开）。

| `t` | 字段 | 含义 |
|---|---|---|
| `m` | `dx`, `dy` | 相对移动（像素） |
| `b` | `b`(1/2/3), `d`(bool) | 按键按下/抬起 |
| `w` | `v` | 滚轮档数（正=上滚） |
| `k` | `code`(HID usage), `mods` | 单键（含修饰位） |
| `s` | `text` | 整段文字（服务端用 `KEYEVENTF_UNICODE` 逐 UTF-16 code unit 注入，**中文/emoji 都行**） |

示例：`{"t":"m","dx":4,"dy":-2}` / `{"t":"s","text":"你好 hello"}`

### 5.2 服务端（`pc/airmouse_server.py`）

- 纯标准库 + ctypes，`MOUSEINPUT/KEYBDINPUT/INPUT` 结构与 `user32.SendInput` 手写；`HID_TO_VK` 表覆盖字母/数字/标点/方向键/Home/End/PgUp/PgDn/Del/F1-F12
- 每连接一个线程；`--dry-run` 只打印不注入（调试必用）；`--host/--port` 可改
- pythonw 无控制台时自动把 stdout/stderr 重定向到 `%LOCALAPPDATA%\AirMouse\server.log`
- 端口被占会打印 `[!] 端口 N 绑不上…`（**注意：代码里有 SO_REUSEADDR，两个实例可能都 bind 成功、新连接全被旧进程吃掉**；排查时先 `Get-NetTCPConnection -LocalPort 47777 | select OwningProcess`）
- 它**不是服务**：进程没了就断，手机每 1.5 秒重试，服务端回来自动重连

### 5.3 客户端要点

- `NetInput` 有**独立读线程**（`airmouse-net-read`），`read()` 返回 -1 才算断开。没有这个线程时，服务端被 kill 手机还显示「已连接」，是修过的 bug
- 重连成功时先 `queue.clear()`，避免把断线期间积压的旧指令补发出去让光标乱跳
- `adb reverse tcp:47777 tcp:47777` + 手机填 `127.0.0.1` 就是 USB 免 WiFi 模式
- 实测：网络模式 median 10ms/p90 15ms、**不丢位移**；蓝牙是 31Hz 且最多丢 2%。所以要「跟手」就用网络模式，要「零安装」就用蓝牙

---

## 6. 陀螺仪解算（`GyroEngine.kt`）

```
up        = normalize(-gravity)        // 设备坐标系里的「上」，来自 TYPE_GRAVITY（没有就用加速度计低通）
yawRate   = ω · up                     // 绕竖直轴转（左右摆手腕，像打方向盘）
pitchRate = ω.x                        // 手机面上沿前后俯仰

rateX = +curve(yawRate)   * 480 * sens // px/s
rateY = -curve(pitchRate) * 480 * sens
curve(v) = sign(v) * max(0, |v| - 0.022) ^ 1.35
```

- 60Hz `Handler` tick 把 `rateX/rateY` 积分进 `accX/accY`，取整数像素发出去（余数留着，避免慢速丢步）
- 静止（|ω| < 0.05 rad/s）满 600ms 后学零偏，之后用 `0.03` 的 lerp 持续跟；「校准」按钮强制重学
- 常量：`DEAD_ZONE=0.022`、`GAIN_PX_PER_RAD=480`、`CURVE_EXP=1.35`、灵敏度 0.15..2.5（滑杆 0.2×~2.5×）
- **符号方向是用户一条条反馈调出来的**：`rateY` 取负（否则上下反）、`rateX` 取正（否则左右反）。改姿态基准（比如换成 `R0ᵀ·R` 参考帧方案）会重新引入这两个符号问题，改前先想在哪种握持姿势下测
- 打开键盘面板时**暂停陀螺仪**（`updateGyroForKeyboard`），否则打字时光标乱跑

---

## 7. 触摸板（`TouchpadView.kt`）

| 手势 | 行为 |
|---|---|
| 单指拖动 | 移动（gain 1.7） |
| 轻点（<400ms） | 左键 按+抬 |
| 长按 420ms 后拖 | 按住左键拖动 |
| 双指上下 | 滚轮（52px 一档，手指下拖=负） |
| 双指轻点 | 右键 |

实现要点：

- **亚像素累加**（`accX/accY`）：`ACTION_MOVE` 里 `accX += (x-lastX)*gain`，取 `toInt()` 发出去再扣掉余数。早期每个事件直接 `toInt()` 截断，高采样率下慢拖会整段丢
- 双指抬起一指时（`ACTION_POINTER_UP`）要把 `lastX/lastY` 换成剩下那根手指的坐标，否则光标会跳
- 每个动作都 `performHapticFeedback`

---

## 8. 键盘：两种打字方式

| | 直通键码（`Prefs.TYPE_KEYS=0`） | 手机输入法（`Prefs.TYPE_IME=1`） |
|---|---|---|
| UI | `KeyboardPanelView` 字母页/符号页 | 同一个面板的输入法页 → 弹对话框 |
| 发送 | `keyPress(usage, mods)` 逐键 | `sendComposedText()` → `typeText()` 整段 |
| 中文 | 不行 | 仅网络模式可以 |
| 修饰键 | ⇧ Ctrl Alt 是一次性（下一个键生效） | — |

`HidKeys.charKey(c)` 返回 `(usage, mods)`，覆盖 a-z / 0-9 / 常见标点 / 空格回车退格 Tab Esc。

**为什么中文只能走网络模式**：蓝牙 HID 键盘协议只有键码，没有承载任意文字的通道。网络模式在电脑侧用 `SendInput` + `KEYEVENTF_UNICODE` 才能注入中文。蓝牙模式下 `sendComposedText` 会统计 `it.code > 126` 的字符数并提示「N 个字符会被跳过」。

---

## 9. 构建

### 9.1 本机（离线）构建命令

```powershell
& 'C:\Users\Tangyq\.gradle\wrapper\dists\gradle-8.14.3-all\cbf6zifq8xavouihta8md72jo\gradle-8.14.3\bin\gradle.bat' `
  -p 'D:\Workspaces\deepseek_harness\Something\gyro-mouse' --offline --console=plain assembleRelease
```

- 这台机器**没有 `gradlew`**，也没有 cmdline-tools / Android Studio，只能走上面这个绝对路径
- 产物：`app\build\outputs\apk\release\app-release.apk`（约 10.0MB）、`...\debug\app-debug.apk`

### 9.2 这些配置为什么长这样（换机器前先读）

| 配置 | 原因 |
|---|---|
| `settings.gradle.kts` 先声明 `local-repo` | 离线缓存里缺 `kotlin-reflect:2.0.21`（只有 2.0.20），仓库里放了一份复制品 + 手写 pom |
| 仓库里写阿里云镜像 | 本机 Gradle 缓存是**从阿里云拉的**，离线命中要求仓库 URL 一致，否则永远 `No cached version ... available for offline mode` |
| `buildToolsVersion = "35.0.0"` | AGP 8.7.3 默认要 34.0.0，本机只有 35.0.0 / 36.0.0 |
| `force(recyclerview 1.3.2, viewpager2 1.1.0-beta02)` | material 1.12.0 的默认依赖版本不在缓存里 |
| `lint { checkReleaseBuilds = false }` | 离线跑 `lintVitalAnalyzeRelease` 会去拉检查项 → 失败 |
| `suppressUnsupportedCompileSdk=36` | AGP 8.7.3 只认证到 35，只是压警告 |
| `compileSdk 36` + 手动 `WindowInsets` | targetSdk 36 强制全面屏（edge-to-edge），内边距在 `MainActivity.applyWindowInsets()` 里自己加 |

### 9.3 换一台**有网**的机器

最省事的做法是用 Android Studio 直接打开 `gyro-mouse/`：删掉 `local-repo` 那两段仓库声明也能跑（有网时从阿里云/google 拉即可）。要保留也行，`local-repo` 里只有一个替身 jar，不影响。

- `local.properties` 不要从旧机器拷贝，写自己机器的 `sdk.dir`
- 想升级依赖/AGP 就整组一起升（AGP 8.7.3 ↔ Kotlin 2.0.21 ↔ compileSdk 36 是一套验证过的组合）

### 9.4 签名

`app/airmouse.keystore`：alias `airmouse`，storepass/keypass 都是 `airmouse`，RSA 2048。

**更新已安装的 App 必须用同一个 keystore**，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。这也是为什么 OPPO 上装 debug 包会失败（debug 用调试签名）——装 `app-release.apk`。

---

## 10. 装到手机

```powershell
adb install -r -t app\build\outputs\apk\release\app-release.apk
```

| 现象 | 处理 |
|---|---|
| HyperOS 报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` | 开发者选项里开「USB 安装」（部分机型还要「USB 安装管理」），或直接把 APK 拷进手机用文件管理器点安装 |
| ColorOS 侧载弹「未经安全检测」 | 选「继续安装」；被手机管家拦选「仍要安装」 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致：改装 release 包，或 `adb uninstall com.airmouse.gyro` 后重装 |
| 覆盖安装后 `registerApp -> false` | force-stop 等 3 秒再启动 |

首次使用：手机选「蓝牙 HID」→ 状态显示 `HID 就绪` → 点状态栏「让电脑发现本机（300 秒）」→ 电脑「设置 → 蓝牙和设备 → 添加设备 → 蓝牙」里选 `AirMouse-<机型>` 配对 → 手机变绿色 `已连接`。之后打开 App 会自动连回上次那台电脑（找 `bluetoothClass.majorDeviceClass == 0x0100` 的已配对设备，优先 `prefs.lastAddress`）。

---

## 11. 调试开关总表

### 11.1 Intent extras（`am start` 直接触发，不用手点）

| extra | 作用 |
|---|---|
| `--ez gyro true [--ez gyrolog true]` | 0.4s 后自动开陀螺仪；`gyrolog` 打开每 0.5s 的原始数据日志 |
| `--ez autotext true --es imetext '文字'` | 1.2s 后走一遍 `sendComposedText`（测网络通道/中文注入） |
| `--ez keyboard true [--ez imemode true]` | 0.5s 后展开键盘面板，可选切到输入法页 |
| `--ez imedlg true` | 0.9s 后弹「手机输入法」对话框 |
| `--ei mode 1 --es host 192.168.1.51 --ei port 47777` | 直接写偏好并 `applyMode()`（release 包 `run-as` 进不去，改不了 xml） |
| `--ei flushms 24` | 改蓝牙移动报告间隔（§4.3 的实验旋钮） |
| `--ez autodrag true --ei dragms 1000 --ei dragdelay 1500` | 后台合成「每 10ms 右移 2px，共 N 步」，量链路用，不用碰屏幕 |
| `--ez nudge true` | 直接发一帧 `(5,0)` 位移，验证连接是否真的活着 |

> `onCreate` 和 `onNewIntent` 都会处理这些 extra —— **测试优先用 `am start` 打到已运行实例**，避免反复重启（见 §4.4 假连接）。

### 11.2 日志

```powershell
adb logcat -s HidInput AirMouse   # HID 注册/连接/发送统计、陀螺仪原始值
```

`HidInput` 在拖动的每 2 秒打印：

```
move stats: ev=120/s rep=31/s sendAvg=1700us sendMax=5000us evGapMax=16ms
```

`ev`=触摸事件率、`rep`=实际发出的报告率（**32ms 档应该看到 rep≈31/s**）、`sendAvg/sendMax`=`sendReport` 单次耗时、`evGapMax`=事件最大间隔。

### 11.3 光标采样工具

`tools/cursor_sampler.ps1`（C# 内联编译 + `GetCursorPos` 紧循环，只记坐标变化）：

```powershell
powershell -File tools\cursor_sampler.ps1 -DurationMs 6000 -OutFile .\_moves.csv
# 输出 moves= / span= / rate= / avg= / median= / p90= / max=
```

用来客观判断「链路丢没丢位移」，比肉眼靠谱：发 N 像素，看电脑端到位多少。

---

## 12. 已知限制

1. **蓝牙模式打不了中文**（协议限制，不是 bug）
2. **蓝牙移动只有 ~31Hz**：这是 HID interrupt 通道的特性，32ms 是实测最优。想要 100Hz 跟手只能走网络模式
3. **HyperOS 拦 adb 安装**；**`adb shell input` 需要「USB 调试（安全设置）」**，所以自动化测试点击基本不可行，得靠 intent extra 或人工
4. **假连接**：主机侧句柄陈旧时不报错但没反应（§4.4）
5. 服务端只有 Windows 实现（macOS/Linux 需要自己写注入，`InputSink` 协议不变）
6. `MainActivity.kt` 已经 768 行，继续加功能前建议先拆出「模式管理」和「调试入口」两块

---

## 13. 建议的下一步（按性价比排序）

1. **把 `flushMs` 暴露到设置里**（现在是调试 extra）：不同电脑/适配器的最佳值可能不同，用户可自调
2. **拼音中文键盘**：不依赖电脑端也能打中文（输入法面板已经有一半了，缺的是一个精简拼音表 + 候选）
3. **滚轮横向**（`wheel` 的第二个轴）和 **触摸板惯性**
4. **网络模式走 UDP/多路复用**降低延迟（现在是 TCP 单连接，够用但不极致）
5. **多电脑切换 UI**（现在靠状态栏列表）
6. 把 `Tools`/调试 extra 收进一个 `DebugOptions` 类，别留在 `MainActivity`

---

## 14. 五分钟验证清单

代码没坏的话，按顺序应该全部通过：

1. `adb install -r -t dist/AirMouse-2.17.apk` 成功（同签名）
2. 启动，状态栏显示 `蓝牙 HID / HID 就绪`，`adb logcat -s HidInput` 有 `registerApp -> true`
3. 手机点「让电脑发现本机」→ 电脑蓝牙里能看到/连到 `AirMouse-<机型>`；连上后状态变绿
4. 触摸板拖几下：电脑光标跟着走；`logcat -s HidInput` 的 `move stats` 里 `rep≈31/s`、`sendAvg < 5ms`
5. `--ez autodrag true --ei dragms 1000` 期间用 `tools/cursor_sampler.ps1` 量，位移应接近期望值（蓝牙 ≥95%，网络模式 ≈100%）
6. 点「左键」→ 电脑上能选中图标；「键盘」→ 敲 `abc`，电脑记事本能出 `abc`
7. 切「WiFi / USB」，`python pc/airmouse_server.py --dry-run` 起服务端，手机填电脑 IP → 绿点；拖触摸板，服务端终端应刷 `move +N -M`
8. `--ez autotext true --es imetext '你好 hello'` → 服务端打印 `text '你好 hello'`（**验证中文链路**）

---

## 15. 交接物清单

| 需要带走 | 说明 |
|---|---|
| `gyro-mouse/`（除 `build/`、`.gradle/`、`.kotlin/`、`local.properties`） | 源码 + 配置 + keystore |
| `dist/AirMouse-2.17.apk` | 可直接安装的包 |
| `pc/airmouse_server.py` + `启动接收端.bat` | 电脑端（Windows） |
| `tools/cursor_sampler.ps1` | 链路质量量测 |
| `README.md` / `HANDOFF.md` | 使用者文档 / 本文 |

**这台机器特有的、换机必须改的**：`local.properties` 的 `sdk.dir`、Gradle 发行版的绝对路径（§9.1）、启动文件夹里的 `.lnk`（§README 第三节）。签名和包名别动，否则老用户升级会失败。

---

## 16. v1.3 变更（相对 v1.2）

### 16.1 总开关（`enabled`）
- 每次 `onCreate` 都是 **`enabled = false`**，不持久化：开机就「已停用」，什么指令都不发。
- `MainActivity.setEnabled(on, announce)`：关的时候 `setGyroMode(GYRO_OFF)`、收键盘、`applyMode()`；
  `applyMode()` 在 `!enabled` 时走 `net.stop()` + `hid.suspendHost()` 后直接 return。
- `HidInput.suspendHost()`：`hid.disconnect(device)` 但**不** `unregisterApp()` —— 电脑侧立刻看不到这只鼠标，
  重新启用只走一次 `connect()`，不用等 4 秒重新注册。
- 所有输入回调（触摸板/滚轮/键盘/左右中键按钮）都加了 `if (enabled)` 闸门；`onHidState`/`onNetState`
  在 `!enabled` 时直接 return，免得状态文案把「已停用」盖掉。
- `onHidState` 里自动重连的条件从 `state == READY` 改成 `state == READY && enabled`（否则 suspendHost
  触发的 READY 会立刻把自己连回去）。

### 16.2 射线指向（RayEngine + HID Report 3 + 协议 `t:"a"`）
- `RayEngine.kt`：`d = R·(0,1,0)`（`getRotationMatrixFromVector` 的 `rot[1]/rot[4]/rot[7]` = 设备 +Y 轴的世界方向），
  开/校准那一刻抓参考系 `fwd0/right0/up0`；`yaw = atan2(d·right0, d·fwd0)`、`pitch = atan2(d·up0, hypot(right,f))`；
  `u = 0.5 + tan(yaw)·k`、`v = 0.5 - tan(pitch)·k·aspect`，`k = 0.5/tan(spanDeg)`（tan 才是真实透视投影）。
  `|dz| > 0.94`（指天花板/地面）时不抓参考系。
- 滤波：**1€ filter**（`OneEuroFilter`，`minCutoff=0.9Hz`、`beta=14`、`dCutoff=1Hz`），按传感器时间戳算 dt。
  固定系数低通会「要么抖要么粘」，1€ 静止时滤得狠、动起来截止频率随速度抬高。
- 三态：`GYRO_OFF=0 / GYRO_RATE=1 / GYRO_RAY=2`，`cycleGyro()` 循环；`setGyroMode()` 统一停旧/启新/
  `hid.setAbsolute(m == GYRO_RAY)`/`touchpad.moveEnabled = (m != GYRO_RAY)`；射线模式滑杆变视场角
  `spanFromSlider() = 45 - progress/200*35`（±45°…±10°）。
- HID：`HidDescriptor` 增加第三个 top-level collection，**Report ID 3** 绝对指针
  `[按键3bit+填充5bit, X u16, Y u16, Wheel i8]` = 6 字节，X/Y 逻辑 0..32767；
  `HidInput.sendAbs()` 负责编码，`sendReport(device, 3, data)`（data 不含 report id）。
- 网络：JSON `{"t":"a","u":..,"v":..}`；服务端 `Injector.pointer_abs()` 用
  `MOUSEEVENTF_MOVE|ABSOLUTE|VIRTUALDESK`，坐标 `round(u*65535)`。
- **Windows 只在第一次配对时读 HID 描述符并缓存**：v1.2 配过对的电脑升级到 v1.3 后仍然只有两个集合
  （`Get-PnpDevice -Class Mouse` 里看不到 `&COL03`），必须**在 Windows 里删掉设备重新配对**，才会出现
  `HID-compliant mouse ... &COL03`，绝对坐标才开始生效。已在实机验证：重新配对后
  `abspoint 0.75,0.25 → (1919,360)`、`0.25,0.75 → (640,1079)`、`0.5,0.5 → (1280,720)`（2560×1440）。

### 16.3 布局改版（照真实鼠标摆）
- 顶栏下面是一整条**总开关按钮**（`btnEnable`，`layout_weight` 必须显式写 `0` + 固定高度，
  否则 `AirAction` 样式里的 `layout_weight=1` 会让它把整屏撑开——踩过这个坑）。
- 滚轮从右侧竖条移到**触摸板顶部居中**（`ScrollStrip` 48×112dp，`layout_gravity="center_horizontal|top"`）。
- 左/中/右键移到**触摸板正下方**；陀螺仪/校准/键盘挪到它们下面。
- 触摸板停用态整体 `alpha=0.4`，提示文字移到触摸板底部（避免和滚轮条重叠）。

### 16.4 新增调试开关
| 参数 | 作用 |
|---|---|
| `--ei gyromode 0/1/2` | 直接切陀螺仪三态（0 关 / 1 相对 / 2 射线） |
| `--es abspoint "0.25,0.75"` | 直接把光标按比例摆到屏幕某点，验证绝对定位链路 |
| `--ez raylog true` | 打射线解算日志（yaw/pitch/u/v/span） |

注意 `am start` 打到已在前台的 singleTop Activity 只走 `onNewIntent`，所以这些 extra 在
`onCreate` 和 `onNewIntent` 里都要解析。

### 16.5 v1.4：射线模式下把触摸板当滚轮
- 需求原话：「射线模式的时候滚轮就可以不要了，触摸板上滑下滑直接可以模拟滚轮」。
- `MainActivity.applyPointerUi(m)`（新函数，`setGyroMode` 里统一调用，失败回滚分支也调）：
  `touchpad.moveEnabled = !rayMode`、`touchpad.singleFingerScroll = rayMode`、
  `scrollStrip.visibility = GONE/VISIBLE`、`hintText` 换两套文案。
- `TouchpadView.singleFingerScroll`：单指 `ACTION_MOVE` 走 `wheelAcc += dy`，每 40px（`wheelStepSingle`）
  出一格；方向与双指一致（手指上滑 = 向上滚）。长按时不滚（长按仍是按住左键），
  `longPressed` 分支只做相对模式的移动。
- 验证：WiFi 模式 + 射线，`adb shell input swipe 600 1600 600 1200 300` 后自动滚动窗口的
  AutoScroll 位置按手指方向变化（0 → 480 → 1080），确认滚轮真的送到 Windows。

### 16.6 v1.5：射线滚轮方向翻转
- 需求原话：「射线模式的滑动方向反过来吧」。
- `TouchpadView` 单指滚轮分支 `val dir = if (wheelAcc > 0) 1 else -1`（原来是 `-1/1`）：
  手指往上推 = 页面往上走 = 滚轮向下（内容跟随手指拖动，和触屏刷手机的直觉一致）。
  双指滚轮的方向**没有**改，仍是手指下拖 = 向下滚。
- 验证：AutoScroll 探测窗，swipe 向上 → 位置 0→1080（向下滚）；swipe 向下 → 1080→240（向上滚）。

### 16.7 v1.6：射线滚轮的惯性
- 需求原话：「现在滑动距离是严格对齐触摸板滑动距离的，能模拟快速滚动滚轮的惯性作用吗」。
- `TouchpadView` 新增惯性：单指滚轮分支用 `dy/dt`（4~60ms 夹紧）算出瞬时速度，指数平滑
  `flingVel = flingVel*0.6 + inst*0.4`；`ACTION_UP` 时若 `moved && !longPressed && |flingVel| ≥ 0.35 px/ms`
  就 `startFling()`，用 `Handler` 每 16ms 把 `flingVel*16` px 灌进 `wheelAcc` 并按 `flingDecay=0.92` 衰减，
  低于 0.10 px/ms 停止。停止点：`ACTION_DOWN`（新手势）、切出射线模式、总开关关闭、`onDetachedFromWindow`。
- 参数都在 `TouchpadView` 顶部：`flingDecay`（0.92 → 总滑行≈初速×200px）、`flingStartVel`（0.35）、
  `flingMaxVel`（9 px/ms 封顶）。想更「窜」就调大 decay，想更「黏」就调小。
- 验证：20k 高的 AutoScroll 探测窗，250px/100ms 的快甩后滚轮继续走，位置 360→1200→1800→2040→2160→2280
  （约 700ms 内衰减），慢拖则抬手即停。

### 16.8 v1.7：惯性做成设置项 + 中键收窄
- 需求原话：「改成设置吧并且把中键缩小点吧」。
- **惯性档位**（`Prefs.kt`）：`FLING_OFF=0 / FLING_WEAK=1 / FLING_MID=2 / FLING_STRONG=3`、
  `FLING_NAMES = ["关","弱","中","强"]`、`var flingLevel`（key `flingLevel`，默认 `FLING_MID`）。
- **`TouchpadView`**：`flingDecay` 从 `val` 改成 `var`，新增 `var flingEnabled = true`；
  `startFling()` 首行改成 `if (!flingEnabled || flinging) return`。
- **`MainActivity.applyFlingSetting()`**：`flingEnabled = level != FLING_OFF`，
  `flingDecay = when(level) { FLING_WEAK -> 0.86f; FLING_STRONG -> 0.95f; else -> 0.92f }`；
  在 `onCreate` 末尾调一次（seekSens 监听之后）。
- **设置菜单**：第 3 项（index 2）变成「滚轮惯性：<档名> → 点一下换下一档」，点击 `(flingLevel + 1) % 4`
  并 toast 档位说明（`flingHint()`）；后面三项顺延为 index 3/4/5。
- **中键**：`activity_main.xml` 里 `btnMiddle` 的 `layout_weight` 0.62 → **0.40**、`textSize` 13sp，
  左键/右键仍各 1，视觉上「左宽、中窄、右宽」更接近真鼠标（中键本来只用来开新标签/自动滚动）。
- 验证：装 v1.7 后截图中键明显变窄；设置里点一下「滚轮惯性：中」→ 重新打开变「强」并持久化
  （SharedPreferences），档位对应 `flingDecay=0.95`（总滑行距离约为「中」的 1.6 倍）。

### 16.9 v1.8：射线模式的「两点校准」
- 需求原话：「现在校准是什么逻辑，不论手机真实角度，把当前手机角度指向屏幕中心吗」→ 我解释了
  「recenter = 当前朝向变中心」后问「要不要加两点校准（更贴近真实几何）」，用户：「加上吧」。
- **为什么两点就够（数学依据）**：射线打在**垂直于中心方向**的平面上时，平面坐标正比于 `tan(偏角)`；
  而 `RayEngine` 的原始输出 `u = 0.5 + tan(yaw)·k`、`v = 0.5 - tan(pitch)·k·aspect` 本身就是 tan 的
  线性函数。所以只要拿到「左上角」和「右下角」两次采样，`u' = (u-u1)/(u2-u1)`、`v' = (v-v1)/(v2-v1)`
  就是精确解，并且**顺带把视场角设置错误、人站偏了造成的缩放/偏移一起校正掉**（k 在分子分母里抵消，
  因此两点校准后滑杆的视场角不再影响结果）。平面不严格垂直时退化成近似，但两个角点严格对上。
- **`RayEngine.kt` 新增**：
  - 字段 `cornerCalibrated`（`private set`）、`calU1/calV1/calU2/calV2`、`rawU/rawV/rawValid`；
  - `fun peekPoint(): FloatArray?` 返回最近一帧**滤波后、未做两点映射**的 `[u,v]`（没数据时为 null）；
  - `fun applyCorners(u1,v1,u2,v2): Boolean`：任一边跨度 `< 0.02` 判为「两次指向太近」返回 false，
    否则存四个角值、`cornerCalibrated = true` 并打日志 `corner cal (u1,v1) -> (u2,v2)`；
  - `fun clearCorners()`；
  - `handle()` 里先算 `uRaw/vRaw`（存进 `rawU/rawV`），再按需做线性映射，最后才 `coerceIn(0f,1f)`；
    调试日志多打 `raw=(..) cal=<bool>`；
  - `recenter()` 会 `clearCorners()`（参考系换了，旧角点无意义）；`start()` 改成
    `if (!cornerCalibrated) hasRef = false` —— 做了两点校准就不再重新对中心，避免开合键盘把校准弄丢。
- **`MainActivity.kt`**：
  - 「校准」按钮在射线模式调 `showRayCalibration()`：列表是「对准屏幕中心 / 两点校准（或重新两点校准）
    / 取消两点校准（仅已校准时出现）」；
  - `startCornerCalibration()` 用两次 `askCorner()` 弹窗（「第一步…左上角」「第二步…右下角」，
    按钮「采下」），第二次采完调 `ray.applyCorners(...)`，失败 toast「两次指向太接近…」；
  - `paintSensLabel()` 在射线模式且已两点校准时显示「两点 ✓」，否则仍显示 `±NN°`；
  - `applyCornerExtras(intent)` 调试用：`--es corners "u1,v1,u2,v2"` 直接写入、
    `--ez clearcorners true` 取消（onCreate 与 onNewIntent 都调）。
- 验证（真机 OPPO，WiFi 模式）：
  1. 点「校准」→ 两个选项正常显示；选「两点校准」→ 第一步弹窗文案正确；
  2. 不转动手机连按两次「采下」→ toast「两次指向太接近（手机没真的转过去），再来一次」（守卫生效）；
  3. `--es corners "0.60,0.60,0.70,0.70"` → 日志 `corner cal (0.600,0.600) -> (0.700,0.700)`，
     光标立刻被钉到屏幕**左下角边缘**（raw 落在角框外被 clamp），证明映射真的在跑；
  4. `--ez clearcorners true` → 日志 `corner cal cleared`。
- **踩坑**：`am start --ez key true` 写成 `--es`（String）时，`getBooleanExtra` 返回默认 false 并在
  logcat 打 `W Bundle: Key xxx expected Boolean but value was a java.lang.String` —— 排查开关没生效先看这句。
- 已知限制：两点校准只在本次运行有效（`hasRef` 与传感器世界系绑定），重启 App 需重新对准；
  这次没做持久化，因为 GAME_ROTATION_VECTOR 的世界系在重启后会重新建立，存下来的角点会失效。

### 16.10 v1.9：陀螺仪按钮改成「开 / 关」两态（模式进设置）
- 需求来源：用户问「相对模式是不是没啥用」。我的回答是「对桌面平放正对屏幕的场景射线更好，
  相对只是备用，真正的痛点是三态循环难按（关→相对→射线→关 要点两下）」，给了三个改法（A：按钮只
  切「关 ⇄ 上次用的模式」，模式进设置；B：长按切模式；C：删掉相对）。用户回「a」。
- `MainActivity.cycleGyro()` 改成：
  ```kotlin
  if (gyroMode != GYRO_OFF) setGyroMode(GYRO_OFF, announce = true)
  else setGyroMode(if (prefs.gyroMode == GYRO_RATE) GYRO_RATE else GYRO_RAY, announce = true)
  ```
  即点一下 → 进记住的模式；再点一下 → 关。`GYRO_RAY` 作默认（prefs 里是 0 或其他值时都落回射线）。
- `setGyroMode()` 里持久化改成 `if (m != GYRO_OFF) prefs.gyroMode = m`，并删掉失败回滚分支里的
  `prefs.gyroMode = GYRO_OFF` —— 关掉时不覆盖记忆，否则下次开就退回默认。
- `showSettings()` 第 4 项（index 3）「陀螺仪模式：射线 ⇄（点「陀螺仪」按钮时用）」：翻转
  `prefs.gyroMode`；如果此刻陀螺仪正开着，立刻 `setGyroMode(target, announce = true)` 切过去，
  否则只提示「下次开陀螺仪用「射线」/「相对」」。
- 验证（OPPO，WiFi）：关态点一次 → `RayEngine: ray start true sensor=game_rotvec`，按钮变「射线 指向」、
  hint 与竖条跟着切；再点一次回「陀螺仪 关」；设置里切到「相对」后点开 → 按钮「陀螺仪 相对」并把
  toast 换成相对模式的说明，logcat 出现 `SensorEventConnection ... GyroEngine enable`。
- 注意：`--ei gyromode N` 调试开关仍然可用，且会同时更新记忆（`m != 0` 时写 prefs），
  所以用它测完射线模式后，用户下次点按钮也会进射线。

### 16.11 v2.0：交互与视觉打磨

需求原话：「看看怎么能让app更好用，界面更美观，操作更人性化」。架构没动，补的是五处「用户会卡住 / 会怀疑」的地方。

**1. 触摸板有反馈了**（`TouchpadView.kt`）

新增 `onDraw` 与触点状态 `touchX/touchY/touchAlpha`：

- 手指按下画四层：46dp 光晕（alpha≈30）、22dp 内圈（≈58）、13dp 描边环（≈150）、5.5dp 实心点（≈210），全用 accent `#22D3EE`。
- 抬手用 `ValueAnimator` 220ms 把 `touchAlpha` 淡到 0（`endTouch()` 里 `cancel()` 旧动画再启动）。
- 四角常驻 20dp 圆弧描边（alpha 55），按下时升到 `40 + 150 * touchAlpha`，等于「板子在待命」的指示灯。
- 事件挂载：`ACTION_DOWN` / `ACTION_POINTER_DOWN` → `startTouch()`；`ACTION_MOVE` → `moveTouch()`（多点取 `averageX()`）；`ACTION_UP`/`CANCEL` → `endTouch()`；`onDetachedFromWindow()` 里 `fadeAnim?.cancel()`。

> **坑**：最初写成 `canvas.drawArc(rect, 180f, 90f, false, cornerPaint)` 直接传整块 rect，画出来是「整个触摸板那么大的四分之一椭圆」。角标必须给每个角单独建小矩形：`RectF(p, p, p + d, p + d)`（`d = 2r`，`r = 20f * density`），四个角分别 180/270/0/90 起画 90°。

**2. 主操作做成 CTA**（`MainActivity.paintEnabledUi()`）

- 停用：按钮实心 `accent_soft` + accent 描边 + `#9FEAF6` 文字 —— 全屏唯一「实心」的东西，一眼看到。
- 启用：换成 `surface` 底 + 灰描边 + accent 文字（安静，避免误碰）。
- 用 `MaterialButton.backgroundTintList / strokeColor` 运行时切，不维护两套 layout。

**3. 状态副标题带链路**：`decorateSub("蓝牙"/"WiFi", message)` 把「已连接 192.168.1.51:47777」显示成「WiFi · 192.168.1.51:47777」，一眼看出走的哪条路。

**4. 停用不再静默**：触摸板 / 左右中键 / 滚轮条 / 键盘的 listener 里，`if (!enabled)` 时调 `nudgeEnableHint()`（`SystemClock.uptimeMillis()` 节流 4 秒），toast「先点上方「已停用 · 点此启用」…」。

**5. 校准与灵敏度的快捷键**

- 射线模式下「校准」**单击 = 立刻 recenter**（原来要点开菜单再选），**长按 = 打开校准菜单**（两点校准入口）。相对模式单击仍是 `gyro.recalibrate()`，长按 `return false` 不拦截。
- 灵敏度滑杆两端加「慢 / 快」标签；**点「灵敏度」三个字 = 回到默认**（`seekSens.progress = 70` ≈ 1.0× / ±33°；「全部重置」同样用 70）。
- 显示格式 `%.2fx` → `%.1fx`，避免滑块停在 0.99x / 1.00x 之间来回抖。
- 默认值修正的原因：早期复位写的是 progress=100，实际是 1.35×（射线模式显示 ±28°），偏快。

**6. 使用说明进设置**：`showHelp()` 一页 AlertDialog，五节（开机流程 / 触摸板 / 陀螺仪 / 键盘 / 小技巧），文本是 Kotlin 原始字符串 `.trimIndent()`。设置项顺延为：网络 / 打字方式 / 滚轮惯性 / 陀螺仪模式 / 使用说明 / 让电脑发现本机 / 打开系统蓝牙设置 / 全部重置。

**7. 顺带**：hint 文案色 `#46586C`、12sp（原来 #3A4A5C 13sp 太暗，截图里几乎看不见）。

**真机验证（OPPO，WiFi）**

1. 停用态 CTA 实心高亮 → 点启用变安静样式，状态显示「WiFi · 192.168.1.51:47777」。
2. `input swipe` 期间截图能看到光晕 + 四角变亮；抬手后 220ms 淡出。
3. 长按「校准」弹出「射线校准」菜单；单击直接 recenter 并 toast。
4. 点「灵敏度」三个字回到 1.0x / ±33°。
5. 设置第 5 项打开使用说明（内容完整、可滚动）。
6. 用 `uiautomator dump` 核对 8 个设置项 bounds：网络 [87,677][1128,845]、打字 [845,1013]、惯性 [1013,1181]、陀螺仪模式 [1181,1349]、使用说明 **[1349,1517]**、让电脑发现 [1517,1685]、系统蓝牙 [1685,1853]、全部重置 [1853,2021]。
   > **坑**：点使用说明要取 y=1433；按到 y=1517 正好落在「让电脑发现本机」，会弹 ColorOS 的 `com.oplus.wirelesssettings/...RequestPermissionActivity`（白屏），要 `input keyevent KEYCODE_BACK` 才回得来。

**构建坑**：`mergeDexRelease` 报 `...\intermediates\dex\release\mergeDexRelease\classes.dex: 另一个程序正在使用此文件，进程无法访问。`，连续失败两次；`gradle --stop` 停掉 daemon、删 `app/build/intermediates/dex` 后重建一次通过（BUILD SUCCESSFUL in 16s）。

**写文档的坑（重要）**：用 PowerShell here-string + `AppendAllText` 往 HANDOFF 追加中文，会把内容按 ANSI 解析成 GBK 乱码（形如 `鏂囧瓧`）。HANDOFF / README 这类中文文档**只用 write / edit 工具**写，不要用 pwsh 拼字符串。

### 16.12 v2.1：让「USB」这个模式真的能用

**问题**：只有数据线、没开 WiFi 时选「WiFi / USB」，显示连不上、也控不了。根因两条：

1. App 里存的 host 是 WiFi 网段的 IP（`192.168.1.51`），没有 WiFi 时这个地址根本不可达；
2. 标着「USB」但其实 USB 从来不是独立通路 —— 它依赖 `adb reverse` 把手机的 `127.0.0.1:47777` 转到电脑上，而这条命令**从来没被自动执行过**，只写在文档里让人手动敲。

**修法 A：App 端自动退回回环通道**（`NetInput.kt`）

- 新增常量 `LOOPBACK = "127.0.0.1"`、`LOOPBACK_AFTER_FAILS = 2`。
- `tryConnect()` 先按 `host` 连；`failStreak >= 2`（约 3 秒）后改连 `127.0.0.1`；连上就记 `usedLoopback = true`、`failStreak = 0`。
- 新增 `var usedLoopback`（private set）与 `val transport: String get() = if (usedLoopback) "USB" else "WiFi"`；`start()` 里重置这几个状态。
- 失败文案分两种：还在连原 IP 时提示「连不上 X，正在试 USB 通道…」；改试回环也失败时提示「USB 也连不上：电脑端要在插入数据线后执行 adb reverse tcp:47777 tcp:47777」。
- `MainActivity.onNetState` 里把写死的 `decorateSub("WiFi", …)` 改成 `decorateSub(net.transport, …)`，于是副标题显示 `USB · 127.0.0.1:47777` 或 `WiFi · 192.168.1.51:47777`，一眼看出走的哪条线。

> **坑**：一开始成功文案写成 `"已连接 USB · $target:$port"`，和 `decorateSub("USB", …)` 的前缀叠成了「USB · USB · 127.0.0.1:47777」。prefix 由 UI 层统一加，NetInput 只负责 `已连接 host:port`。

**修法 B：电脑端自动维护 adb reverse**（`pc/airmouse_server.py`）

- `find_adb()`：环境变量 `AIRMOUSE_ADB` → 常见安装路径（`D:\Software\platform-tools-latest-windows\platform-tools\adb.exe`、`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`、`C:\platform-tools\adb.exe`）→ PATH。
- `adb_reverse_watch(adb_path, port, stop)`：每 3 秒 `adb devices`，对状态为 `device` 的序列号执行 `adb -s <serial> reverse tcp:PORT tcp:PORT`；成功才记入 `known` 并打印 `[+] USB 通道已就绪 <serial> -> 127.0.0.1:PORT`；设备掉线从 `known` 移除，重插会重新建隧道。整体包在 try/except 里，adb 缺失或未授权都不影响 WiFi。
- `main()` 在 banner 之后启动该守护线程；新增 `--no-adb` 关闭它；banner 改成「插上数据线即可（自动执行 adb reverse）」。

**验证（OPPO，故意把 host 设成不可达的 192.168.1.99 来模拟没有 WiFi）**

1. 服务端日志出现 `[USB] 已启动监控` + `[+] USB 通道已就绪 DQGQEYNFC6W4SKPN -> 127.0.0.1:47777`；`adb reverse --list` 有 `UsbFfs tcp:47777 tcp:47777`。
2. 点启用 → 约 3 秒后 logcat `I NetInput: connected to 127.0.0.1:47777 usb=true`；服务端 `[+] 手机已连接 127.0.0.1:63294`。
3. 界面副标题 `USB · 127.0.0.1:47777`（压掉重复前缀之后）。
4. 真控制：两次 `input swipe` 后 PC 光标 (1645,721) → (1681,782)（+36 / +61），链路通。

**行为小结**：同一份设置，有 WiFi 走 WiFi（host 填 LAN IP），没 WiFi 自动走 USB；两端都不用手动敲命令，前提只是「USB 调试已授权」。

---

### 16.13 v2.2：三个模式按钮分开（蓝牙 / WiFi / USB）

**动机**：2.1 只有一个「WiFi / USB」按钮，实际走哪条线靠「先连 host，两次失败后自动退回 127.0.0.1」——用户插着线却显示 WiFi、或者不知道现在走的哪条路，属于「智能但不可见」。2.2 拆成三个按钮，用户点哪条就是哪条。

**改动**：

1. `res/layout/activity_main.xml`：模式行从 2 个 `AirChip` 按钮改成 3 个，`@+id/btnModeBt`「蓝牙」、`@+id/btnModeWifi`「WiFi」、`@+id/btnModeUsb`「USB」，`layout_marginStart/End 6dp`，`btnModeNet` 删除。
2. `Prefs.kt`：`MODE_BT = 0`、`MODE_WIFI = 1`、`MODE_USB = 2`，保留 `const val MODE_NET = MODE_WIFI`（旧代码/旧偏好兼容），新增 `fun isNetwork(mode: Int) = mode != MODE_BT`。
3. `NetInput.kt`：
   - 删掉 `failStreak` / `usedLoopback` / `LOOPBACK_AFTER_FAILS` 与自动回退逻辑（2.1 的行为）；
   - 改成 `@Volatile var usbMode: Boolean` + `val transport get() = if (usbMode) "USB" else "WiFi"`；
   - `tryConnect()` 里 `val target = if (usbMode) LOOPBACK else host`；
   - `start()` 的空地址判断同理（USB 不需要 host）；
   - USB 连续失败时给的是「USB 连不上：确认数据线已插好、手机允许了「USB 调试」，电脑端接收端已启动」。
4. `MainActivity.kt`：
   - `bindUi()` 三个按钮各自 `if (mode == Prefs.MODE_X) return` 再 `mode = X; applyMode()`；
   - `applyMode()` 分三支：BT 走 `hid.start()`；USB 走 `net.stop(); net.usbMode = true; net.host = "127.0.0.1"; net.start()`；WiFi 走 `net.stop(); net.usbMode = false; net.host = prefs.host; net.start()`（host 空则状态栏提示去填）；
   - **关键**：切模式前必须 `net.stop()`，否则 `NetInput.start()` 里 `if (running) return` 会让旧连接一直留着、状态栏也不刷新（本轮就踩了这个：点 WiFi 后按钮变亮但状态还是 `USB · 127.0.0.1:47777`）。
   - `paintModeButtons()` 三色；`onNetState` 闸门从 `mode != MODE_NET` 改成 `mode == MODE_BT`；
   - `showNetSettings()`：USB 模式不弹 IP 框，改弹 `showUsbInfo()`（三步说明 + 「重连一次」按钮）；WiFi 弹窗标题「WiFi 模式」；
   - `showSettings()` 第一项随模式变文案（USB / WiFi / 蓝牙三种），点击只 `showNetSettings()`，不再强制切模式。
5. 文案：`toast("还没连上电脑，点状态栏看看原因")`、「切 WiFi 或 USB 模式可送中文」。

**真机验证（OPPO DQGQEYNFC6W4SKPN，服务端 22:33 重启为含 adb 监控的版本）**：

1. USB：启动 `--ei mode 2` → 启用 → `adb reverse --list` 有 `UsbFfs tcp:47777 tcp:47777`，界面 `已连接 / USB · 127.0.0.1:47777`，服务端 `[+] 手机已连接 127.0.0.1:54361`。
2. 切 WiFi（`--ei mode 1 --es host 192.168.1.51`）：服务端 `[-] 手机已断开 127.0.0.1` → `[+] 手机已连接 192.168.1.120:53864`，界面 `WiFi · 192.168.1.51:47777`。
3. 点「蓝牙」：logcat `registerApp -> true` / `connect(QQDESKTOP) -> true` / `-> 1` / 2.4 秒后 `-> 2`，界面 `蓝牙 · QQDESKTOP`。

**版本**：versionCode 13 / versionName 2.2，产物 `dist/AirMouse-2.2.apk`。

---

### 16.14 v2.3：顶部收成「两个下拉 + 一个开关」

**动机**：2.2 的顶部是「一个大 CTA 按钮 + 一排三个模式按钮 + 一排陀螺仪/校准/键盘」，占掉三行。用户要求精简：连接方式、陀螺仪都改成下拉菜单，总开关做成一枚 switch。

**布局（`res/layout/activity_main.xml`）**：

```
顶栏：● 状态标题 / 副标题 ................ 设置
控制行：[连接方式 ▾] [陀螺仪 ▾] ●—   ← 一行
触摸板：TouchpadView + ScrollStrip（中上）+ 校准小胶囊（右上，陀螺仪开着才显示）+ hint
按键行：[左键][中键][右键][键盘]      ← 键盘并进这一行，省掉一整行
灵敏度：慢 ———●——— 快   1.0x
```

- `btnModeMenu` / `btnGyroMenu` 都是 `style="@style/AirChip"`（`layout_width=0dp` + `weight=1`），文字带 `▾`。
- `switchEnable` 用 `androidx.appcompat.widget.SwitchCompat`，`thumbTint` / `trackTint` 在 `paintEnabledUi()` 里用 `ColorStateList` 设成 accent / 0xFF3E4C5C、accent_soft / stroke（主题默认色和青色不搭）。
- `btnCalib` 从按钮行搬到触摸板里（`layout_gravity="top|end"`、`bg_key` 底、30dp 高），`paintGyroMenu()` 里按 `gyroMode == GYRO_OFF` 切 `GONE/VISIBLE`。

**代码**：

1. `showModeMenu()`：`AlertDialog.setSingleChoiceItems(arrayOf("蓝牙 HID","WiFi（填电脑 IP）","USB（插线即用）"), checked)`，选中后 `mode = values[which]; applyMode()`。
2. `showGyroMenu()`：同样写法，`arrayOf("关","相对（转手腕）","射线（指哪打哪）")` → `setGyroMode(values[which], announce = true)`。
3. `paintModeButtons()` 现在只干一件事：`btnModeMenu.text = modeName(mode) + " ▾"`（`modeName` 返回 蓝牙/WiFi/USB）。
4. `paintGyroMenu()`：`btnGyroMenu.text = "陀螺仪 相对 ▾" / "陀螺仪 射线 ▾" / "陀螺仪 关 ▾"`，并切校准胶囊可见性；`setGyroMode()` 每个分支结束都调它（包括三个失败回滚），启动时 `bindUi()` 之后也调一次。
5. `paintEnabledUi()`：不再改按钮文案/底色，只 `switchEnable.isChecked = enabled` + 设 tint + 触摸板 alpha 0.4 + 停用时状态栏「已停用 / 打开右上角开关，手机才开始控电脑」。
6. `switchEnable.setOnCheckedChangeListener { _, c -> if (c != enabled) setEnabled(c, true) }`（注意 `paintEnabledUi` 里也会写 `isChecked`，用 `if (c != enabled)` 防止自触发循环）。
7. 删除 `cycleGyro()` 与设置里的「陀螺仪模式」项（改由下拉承担），设置项索引顺延为 0 电脑地址 / 1 打字方式 / 2 滚轮惯性 / 3 使用说明 / 4 让电脑发现 / 5 系统蓝牙 / 6 全部重置。
8. `nudgeEnableHint()` 文案改「先把右上角的开关打开…」；`showHelp()` 第一节同步改成「右上角开关 + 左边下拉选连接方式」。

**踩坑**：

- `AirChip` 自带 `layout_weight=1`，把它直接放进横向 `LinearLayout` 时，两个 chip 会吃掉全部宽度、把后面的 `SwitchCompat` 挤到边缘；因为 switch 是 `wrap_content`，其实没被裁掉，但视觉上贴边。现在顺序是「两个 weight=1 的 chip + 一个 wrap 的 switch」，正常。
- 点击 chip 的坐标要先 `uiautomator dump` 确认：本轮第一次按 preview 比例换算的 y 偏了 40px，点空了一次，误以为监听没绑上。

**真机验证（OPPO DQGQEYNFC6W4SKPN，WiFi 服务端常驻）**：

1. 控制行渲染正常：`[WiFi ▾][陀螺仪 关 ▾][开关]`，开关关闭时状态「已停用」。
2. 打开开关 → logcat `enabled -> true mode=0`（mode 已先在下拉里选成「蓝牙 HID」）→ `registerApp -> true` → `connect(QQDESKTOP) -> true` → `-> 2`，状态「已连接 / 蓝牙 · QQDESKTOP」。
3. 陀螺仪下拉选「射线」→ `RayEngine: ray start true sensor=game_rotvec`、`ray ref captured`，chip 变「陀螺仪 射线 ▾」，触摸板右上角出现「校准」，滚轮条收起，hint 切到射线文案。
4. 关开关 → `HidInput: suspendHost: disconnect(QQDESKTOP)` + `enabled -> false`，状态回「已停用」，校准胶囊随陀螺仪关闭隐藏。

**版本**：versionCode 14 / versionName 2.3，产物 `dist/AirMouse-2.3.apk`。

---

### 16.15 v2.4：下拉改成真·下拉列表（ListPopupWindow）

**动机**：v2.3 的两个「下拉」其实是 `AlertDialog` 居中弹窗——点完要往屏幕中间看，跟按钮没有视觉关联。用户要求「不是点击下拉弹窗，而是直接显示下拉列表」。

**做法**（`MainActivity.kt`）：

```kotlin
private fun showDropDown(anchor: View, items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
    val list = ListPopupWindow(this)
    list.anchorView = anchor          // 贴着按钮弹出，不再是居中弹窗
    list.isModal = true
    list.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_popup))
    list.width = anchor.width         // 和按钮一样宽，看起来就是按钮展开
    list.height = ListPopupWindow.WRAP_CONTENT
    list.verticalOffset = dp(4)
    list.setAdapter(object : BaseAdapter() { ... })  // 每行是程序化 TextView，选中行 "✓  " + accent 字 + accent_soft 底
    list.setOnItemClickListener { _, _, position, _ -> list.dismiss(); onPick(position) }
    list.show()
}
```

- `showModeMenu()` / `showGyroMenu()` 都改成调 `showDropDown(...)`，值数组与回调照旧。
- 新增 `res/drawable/bg_popup.xml`：`surface_2` 底 + 12dp 圆角 + 1dp `stroke` 描边，暗色下和面板是一套。
- 行高靠 padding（横 14dp / 竖 11dp），`textSize 14sp`、`maxLines 1` + `ellipsize END`（"WiFi（填电脑 IP）" 在按钮宽度内放得下）。
- import 增加 `android.view.ViewGroup`、`android.widget.BaseAdapter`、`androidx.appcompat.widget.ListPopupWindow`；`TextUtils.TruncateAt`、`Color.TRANSPARENT` 用全限定名省得再加 import。

**效果 & 验证（OPPO DQGQEYNFC6W4SKPN）**：

1. 点「蓝牙 ▾」→ 列表从按钮正下方展开：`✓ 蓝牙 HID`（accent 字 + 淡青底）/ `WiFi（填电脑 IP）` / `USB（插线即用）`，宽度与按钮一致。
2. 点「WiFi（填电脑 IP）」→ 列表收起，chip 变「WiFi ▾」，模式真的切了（状态栏随后按 WiFi 连接）。
3. 点「陀螺仪 关 ▾」→ 同样从按钮下展开 `✓ 关` / `相对（转手腕）` / `射线（指哪打哪）`；选「射线」后 chip 变「陀螺仪 射线 ▾」、右上角出现「校准」。

**踩坑**：`ListPopupWindow` 不设 `width` 时默认 `WRAP_CONTENT`，长文案会把列表撑得比按钮宽、看着不像下拉；设成 `anchor.width` 后才是「按钮展开」的感觉。

**版本**：versionCode 15 / versionName 2.4，产物 `dist/AirMouse-2.4.apk`。

---

### 16.16 v2.5：总开关关着时，控制相关的全部禁掉

**动机**：之前只有「真正发指令」那几处做了 `if (!enabled)` 判断，界面照样能点 —— 触摸板还会画触点光晕、键盘面板还能弹出来、灵敏度还能拖。用户明确要求「开关关时，鼠标控制相关的操作应该禁用，操作时提示需要连接」。

**改动**

1. `TouchpadView.kt`
   - `Listener` 加 `fun onBlockedTouch() {}`（带空默认实现，MainActivity 里覆写成 `nudgeEnableHint()`）。
   - 新增 `var controlEnabled = true`；`onTouchEvent()` 在 `if (!isEnabled) return false` 之后加：
     ```kotlin
     if (!controlEnabled) {
         if (event.actionMasked == MotionEvent.ACTION_DOWN) l.onBlockedTouch()
         return true
     }
     ```
     不放行 ACTION_MOVE/UP，就不会留下半截手势；也不画触点光晕 —— 停用时的视觉反馈应该是「这块失效了」，不是「它响应了你但没有用」。
2. `activity_main.xml`：给按键行加 `@+id/rowButtons`、灵敏度行加 `@+id/rowSens`（为了整体调暗 + 接住点击）。
3. `MainActivity.kt`
   - `paintEnabledUi()`：`touchpad.controlEnabled = enabled`、`seekSens.isEnabled = enabled`、`rowButtons/rowSens/touchpadFrame` 三个 alpha 设 0.35/1.0。
   - 「键盘」按钮：`if (!enabled) { nudgeEnableHint(); return@setOnClickListener }`，不再弹出面板。
   - `showGyroMenu()`：开头 `if (!enabled) { nudgeEnableHint(); return }`（陀螺仪的唯一用途是控光标）。
   - 灵敏度：「灵敏度」标题的复位点击加闸门；`rowSens.isClickable = true` + `setOnClickListener { if (!enabled) nudgeEnableHint() }` —— `SeekBar` 被 disable 后不吃触摸，事件冒到这一行才接得住。
   - 左右中键本来就有闸门（`bindHoldButton` 里 `if (enabled) sink.mouseButton(...) else nudgeEnableHint()`）。
   - `nudgeEnableHint()` 自带 4 秒节流，连点不会刷屏。

**为什么用 alpha 0.35 而不是直接把 View 设成 disabled**：`View.setEnabled(false)` 会吞掉触摸，连提示都给不出来；「变暗 + 能接住点击 + 弹提示」才符合「操作更人性化」。

**验证（OPPO DQGQEYNFC6W4SKPN）**

1. 开关关着启动 → 触摸板 / 左右中键+键盘 / 灵敏度三块明显变暗，滑杆 knob 变灰。
2. 点触摸板任意位置 → toast「先把右上角的开关打开，手机才会接管电脑鼠标」，且**没有**触点光晕。
3. 点「键盘」→ 同样 toast，面板不弹。
4. 点「陀螺仪 关 ▾」→ 同样 toast，列表不展开。
5. 点灵敏度区域 → 同样 toast，滑杆不动。

**版本**：versionCode 16 / versionName 2.5，产物 `dist/AirMouse-2.5.apk`。

---

### 16.17 v2.6：设置改成底部抽屉，清单砍到三项

**动机**：用户说「设置弹出的 android 原生窗口太丑了，看看怎么优化，有没有必要全列出来」。原来的
`AlertDialog.setItems` 是一张七行灰底长清单，每行都是一句完整句子（「…→ 点这里改」），
没有分组、没有图标、没有层级；而且里面有重复项和低频项。

**先做的减法**（用户确认三项都可以拿掉）

| 原设置项 | 去处 |
|---|---|
| 打字方式 | 键盘面板顶部本来就有「直通键码 / 手机输入法」两个 tab，设置里是重复入口 |
| 让电脑发现本机 / 打开系统蓝牙设置 | 塞进「连接方式」下拉的最后一行「配对新电脑…」，走的还是原来的 `pickDevice()` |
| 电脑 IP | 选「WiFi」时当场弹 `showNetSettings()`；已填过则只 toast 当前地址 |

**再做的加法**：`res/layout/sheet_settings.xml` + `BottomSheetDialog`

- 抽屉结构：拖柄 → 标题「设置」+ ✕ → 分组「输入」（滚轮惯性）→ 分组「其它」（使用说明、全部重置）→ 版本号。
- 滚轮惯性从「点一下换下一档」改成 **四档分段控件**（`chipFling0..3`），点哪档是哪档，
  选中态 `bg_key_on` + accent 字，改完不关抽屉、只 toast 一句该档位的效果。
- 行样式：图标（24dp）+ 标题（15sp bold）+ 副标题（12sp）三件套，卡片用 `bg_key` 圆角。
- 背景：`sheet.setOnShowListener` 里把 `design_bottom_sheet` 的背景设成透明，
  让 `bg_panel`（surface + 顶部 18dp 圆角）露出来；`STATE_EXPANDED` + `skipCollapsed = true`，
  抽屉不会被停在半高。
- 图标是三个手写 vector：`ic_sheet_inertia`（三条递减横线）、`ic_sheet_info`（圆圈+感叹号）、
  `ic_sheet_reset`（回头箭头，红）、`ic_close`（✕）。

**两个小坑**

1. `BottomSheetBehavior.from(bottom)` 传 `View?` 会让 Kotlin 报「Cannot infer type / Not enough
   information to infer T」。先 `val bottom: View? = sheet.findViewById(...)` 显式给类型，
   再 `if (bottom != null)`，并写 `val beh: BottomSheetBehavior<View> = BottomSheetBehavior.from(bottom)`。
2. `ListPopupWindow` 宽度设成 `anchor.width` 后，「WiFi（填电脑 IP）」被截成「WiFi（填电脑 …」。
   改成 `maxOf(anchor.width, 240.dp)`，四个选项就都完整了。

**验证（OPPO DQGQEYNFC6W4SKPN）**

1. 点「设置」→ 抽屉从底部滑出：拖柄、标题、两组、三行卡片、底部 `陀螺仪鼠标 2.6 · 17`。
2. 点「弱」→ 高亮跳到弱、toast「滚轮惯性：弱：甩一下滚一小段」，抽屉不关。
3. 点「使用说明」→ 说明抽屉叠在设置上面打开（设置不关）；关掉说明或按返回键回到设置。
4. 点「全部重置」→ 先弹确认（说明会清哪些东西），取消后什么都不变。
5. 连接方式下拉 → 四项齐全、无省略号；点最后一项「配对新电脑…」→ 弹出已配对设备列表。

**版本**：versionCode 17 / versionName 2.6，产物 `dist/AirMouse-2.6.apk`。

---

### 16.18 v2.7：使用说明也抽屉化 + 陀螺仪受连接状态约束

**动机**：用户说「使用说明的弹窗还是 android 原生的，文字排版也还有优化空间 … 陀螺仪的模式需要
受连接状态控制吗」。答案是要——陀螺仪只输出「光标往哪走」，没连接时开着毫无意义，
还会在断线瞬间继续吃电量、留下一个「看着是开着的」假状态。

**A. 使用说明 → 底部抽屉（`res/layout/sheet_help.xml`）**

- 结构：拖柄 → 标题「使用说明」+ ✕ → `ScrollView`（`height=0dp` + `weight=1`）→ 18dp 渐隐条
  （`fade_bottom.xml`）→ 底注「设置里还有：滚轮惯性、全部重置」。
- 一开始想「抽屉最高占 60% 屏」——结果刚好把一行字切在中间，很难看；改成**整屏可滚**，
  再用底部 18dp 渐变把边缘软化，视觉上就顺了。
- 正文不用字符串里堆空格，而是两个局部函数程序化生成：
  - `section(title)`：11.5sp / accent `#22D3EE` / bold / letterSpacing 0.06 / topMargin 18dp；
  - `row(left, right)`：横向两列，左列固定 80dp、`#7D8B9C` 13sp（手势名），右列 `weight=1`、
    `#D8E3EE` 14sp、`lineSpacingExtra 3dp`（效果说明）。两列对齐后扫读比原来一段段大字快得多。
- 内容重写成五节：开机流程（①②③）/ 触摸板（6 条）/ 陀螺仪（射线、相对、校准、**前提**）/
  键盘（直通键码、手机输入法、中文）/ 小技巧（复位、躺着用）。陀螺仪那节特意写了
  「要连着电脑才能开，断了会自动暂停」，跟下面的行为改动对齐。

**B. 陀螺仪受连接状态约束**

- 新增 `private val isLinked: Boolean get() = if (mode == Prefs.MODE_BT) hid.isConnected else net.isConnected`。
- `showGyroMenu()` 两道闸门：没开总开关 → `nudgeEnableHint()`；开了但没连上 → toast
  「先连上电脑：陀螺仪是用来动光标的，没连接开着也没用」，不展开菜单。
- `onHidState` / `onNetState` 末尾调 `syncGyroWithLink()`：一旦 `!isLinked` 且 `gyroMode != GYRO_OFF`，
  就 `gyroBeforeKeyboard = GYRO_OFF`、`setGyroMode(GYRO_OFF)`、toast「连接断了，陀螺仪已暂停」。
- `paintGyroMenu()` 里 `btnGyroMenu.alpha = if (enabled && isLinked) 1f else 0.45f`：没连上时这个
  下拉是暗的，和「点了没反应」相比更能提前说明原因。
- **不做自动恢复**：重新连上后仍停在「关」，要用户自己再选一次。理由是断开往往意味着人离开了，
  自动恢复会出现「手机在桌上转手腕、电脑光标乱跑」。

**C. 顺手修的下拉越界**

- 陀螺仪那个按钮靠右，`width = maxOf(anchor.width, 240dp)` 会让弹窗顶出屏幕右边（截图可见被裁）。
- `showDropDown()` 改成：`anchor.getLocationInWindow(loc)` 算出 `rightSpace = screenW - loc[0] - margin`，
  不够就把宽度压到 `maxOf(anchor.width, loc[0] + anchor.width - margin)`，并
  `horizontalOffset = anchor.width - width`，让弹窗右缘对齐按钮右缘、整体往左挪。
  连接方式那个按钮靠左，走不到这个分支，行为不变。

**验证（OPPO DQGQEYNFC6W4SKPN）**

1. 设置 → 使用说明：底部抽屉，五节标题青色、左灰右亮两列，能滚到「五、小技巧」，底部渐隐不硬切。
2. 关着总开关点陀螺仪下拉 → 提示先开开关；开着开关但没连上 → 提示先连电脑，菜单不展开。
3. 连上 WiFi 后点陀螺仪下拉 → 菜单完整落在屏幕内（不再越界），选「射线」→
   `I RayEngine: ray start true sensor=game_rotvec`。
4. `Stop-Process pythonw` 杀掉接收端 → 约 1 秒内 toast「连接断了，陀螺仪已暂停」，
   chip 回到「陀螺仪 关」且变暗，状态栏转「正在连接… / 连接异常」；重启接收端后自动重连，
   陀螺仪仍是关（需手动再开）。

**版本**：versionCode 18 / versionName 2.7，产物 `dist/AirMouse-2.7.apk`。

---

### 16.19 v2.8：使用说明改为「叠在设置上」，关掉回设置

**反馈**：用户说「使用说明关掉后，界面应该回到设置」。v2.7 里 `rowHelp` 的写法是
`sheet.dismiss(); showHelp()`——先把设置抽屉关掉再开说明，于是关掉说明就直接回到主界面，
想看下一项还得重新点「设置」。

**改法**（一行）：`rowHelp` 里**不再 dismiss**，直接 `showHelp()`。两个 `BottomSheetDialog`
是可以叠加的：说明盖在设置上面，它自带一层遮罩，背后的设置正好暗下去，层级一眼看得出来。

```kotlin
v.findViewById<View>(R.id.rowHelp).setOnClickListener {
    // 不关设置抽屉：说明叠在它上面，关掉说明自然回到设置
    showHelp()
}
```

**为什么不用「关掉后重新 open」**：那样会看到设置抽屉先掉下去、再升上来，中间闪一下主界面；
叠加是零成本且视觉连贯的。唯一要注意的是返回键顺序——系统本来就是后开的先关，所以
返回键一次关说明、再一次关设置，和预期一致，不需要额外处理。

**验证（OPPO DQGQEYNFC6W4SKPN）**

1. 设置 → 使用说明：说明抽屉滑出，设置仍在下面（可见其遮罩变暗）。
2. 点说明右上角 ✕ → 说明收起，设置抽屉原样还在（滚轮惯性仍是刚才选的「弱」）。
3. 再进说明，按返回键 → 同样回到设置；再按一次返回才退出到主界面。

**版本**：versionCode 19 / versionName 2.8，产物 `dist/AirMouse-2.8.apk`（已被 2.9 取代）。

---

### 16.20 v2.9：所有窗口统一成底部抽屉

**起因**：用户反馈「全部重置的提示框还是旧风格」+「窗口最上方的白条是锥形的」。

**拖柄锥形**：两个抽屉里 44×4dp 的 `<View>` 原来用 `@drawable/dot`（oval）靠 `backgroundTint`
上色，拉成 44×4 后成了两头尖的梭形。改为 `res/drawable/bg_handle.xml`（矩形 + `corners 2dp` +
solid `#46586C`），XML 里换成 `android:background="@drawable/bg_handle"`，不再 tint。

**放弃 AlertDialog（走过的弯路）**：先试 `MaterialAlertDialogBuilder` +
`ThemeOverlay.Material3.Dialog.Alert` 皮肤：`elevationOverlayEnabled=false` 能修掉「深色面板
被 accent tint 成偏青」，但四角仍是直角；再加 `android:background=@drawable/bg_dialog` 后，
Material3 内部把标题 / 内容 / 按钮画成**三块各自带圆角的面板**，外层再套一层圆角底 → 重影
「一层套一层」。结论：不要跟 M3 内部绘制较劲。

**做法**：所有对话框改用自己的抽屉壳。

- 布局 `res/layout/sheet_dialog.xml`：圆角矩形拖柄 → 标题 → 可选 `dlgMessage`（纯文本）→ 可选
  `dlgScroll` / `dlgContent`（塞输入框、选项列表）→ 按钮行（`dlgExtra` 可选 + 右对齐
  `dlgCancel` / `dlgOk`，均为 `AirText` 窄文字按钮）。
- `MainActivity.showSheet(title, message?, content?, ok="确定", cancel="取消", extra?, onShown?, onOk?)`
  统一走 `BottomSheetDialog`：`design_bottom_sheet` 背景透明、`STATE_EXPANDED`、`skipCollapsed=true`；
  `onShown: ((BottomSheetDialog) -> Unit)?` 在抽屉贴到窗口后回调（要 dialog 实例就用它，别指望
  `showSheet()` 返回值——那时还没赋值）。
- `MainActivity.optionRow(text, onClick)`：生成抽屉里的可点卡片行（`bg_key` 底、15sp、行距 6dp），
  设备列表 / 校准菜单都用它。

**迁移清单（原 7 处 AlertDialog）**：

1. 射线校准 —— `optionRow` 两项 / 三项；
2. 两点校准「采下」 —— `message` + ok=采下；
3. 蓝牙设备列表 —— `optionRow` 列已配对设备 + 让电脑发现 + 系统蓝牙设置；
4. 手机输入法打字 —— `content` = 提示 + `EditText`，ok=发送；
5. WiFi 填 IP —— `content` = 两个 `EditText` + 本机 IP 提示，ok=连接；
6. USB 说明 —— `message` + `extra="重连一次"`；
7. 全部重置 —— `message` + ok=重置。

**软键盘坑（重要）**：抽屉里弹输入法，`show()` 之后再 `setSoftInputMode()` 没用。正确顺序是在
`onShown` 里：`input.requestFocus()` → `window.setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_VISIBLE
or SOFT_INPUT_ADJUST_RESIZE)` → `input.postDelayed({ imm.showSoftInput(input, SHOW_IMPLICIT) }, 260)`。
只设 softInputMode 时 ColorOS 上不弹，补的那次 `showSoftInput` 才生效。

**顺手清理**：删掉 `themes.xml` 的 `Theme.AirMouse.Dialog` / `AirDialogShape` /
`materialAlertDialogTheme` 与 `res/drawable/bg_dialog.xml`，`MainActivity` 去掉
`MaterialAlertDialogBuilder` 的 import —— 项目里已没有系统对话框。

**验证（OPPO DQGQEYNFC6W4SKPN）**：

1. 设置 → 全部重置：圆角抽屉、拖柄是圆角矩形条（不再两头尖）、取消 / 重置在右下。
2. 连接方式 → 配对新电脑…：设备列表是卡片行，点一行即连。
3. 键盘 → 整段输入：抽屉弹出后软键盘自动升起，输入框有光标，抽屉被顶到键盘上方。
4. 射线模式长按「校准」：校准菜单是抽屉；点「两点校准」后同样是抽屉（第一步文案 + 采下）。
5. 连接 WiFi 时点连接方式 → WiFi：抽屉里两个输入框 + 本机 IP 提示 + 取消 / 连接。
6. 所有抽屉下滑 / 点遮罩 / 返回键都能关；返回键不会误退主界面。

**版本**：versionCode 20 / versionName 2.9，产物 `dist/AirMouse-2.9.apk`（已被 2.10 取代）。

---

### 16.21 v2.10：点「手机输入法」tab 直接弹输入法；发送后收起键盘

**反馈**：用户说「点击手机输入法不能直接点出系统输入法吗，然后点击发送过后收起键盘模式」。

**改动 1：tab 即入口**（`KeyboardPanelView.kt`）

```kotlin
private fun switchMode(ime: Boolean) {
    if (imeMode != ime) {
        imeMode = ime
        listener?.onTypingModeChanged(ime)
    }
    // 点「手机输入法」这个 tab，意图就是要打字：直接把输入法调起来，省掉再点一次中间的按钮
    if (ime) listener?.onImeRequest()
}
```

注意 `imeMode` 的 **setter** 里不能放这段逻辑——启动时
`keyboard.imeMode = prefs.typingMode == Prefs.TYPE_IME` 也走 setter，若那里弹窗，App 一开就会
自己冒出输入框。只有「用户点了 tab」（`switchMode`）才触发 `onImeRequest()`。

`buildImePage()` 里的「调出手机输入法 ✎」按钮保留：用户本来就在输入法页、想再打一段时，
它是比 `switchMode` 更直接的入口（`switchMode` 只在切换 tab 时被调）。

**改动 2：发完收起**（`MainActivity.showImeDialog()`）

```kotlin
onOk = {
    sendComposedText(input.text.toString())
    // 发完就把键盘收起来：这时人要回去看光标，键盘面板继续杵着只会挤掉触摸板
    keyboard.hide()
    updateGyroForKeyboard(false)
},
```

`showSheet()` 的 ok 回调本身就是「先 dismiss 抽屉，再调 onOk」，所以抽屉、软键盘、键盘面板
会一起收掉；`updateGyroForKeyboard(false)` 负责把「开键盘时暂停的陀螺仪」按原状态恢复。

**验证（OPPO DQGQEYNFC6W4SKPN，WiFi）**

1. 键盘面板点「直通键码」→ 切到字母页；再点「手机输入法」→ 抽屉直接弹出、系统输入法自动升起、
   输入框有光标（不用再点中间按钮）。
2. 输入 `hello-airmouse` → 点「发送」→ 抽屉收起、软键盘收起、键盘面板收起，触摸板恢复整块高度。
3. 总开关关着时点这两个 tab 仍会被拦住（沿用 v2.5 的闸门）。

**版本**：versionCode 21 / versionName 2.10，产物 `dist/AirMouse-2.10.apk`。

### 16.22 v2.11：默认手机输入法 + 去掉多余按钮

**用户要什么**：「点击键盘后默认使用手机输入法，并且调出手机输入法这个按钮也不需要了」。

**改动**

1. `Prefs.kt`：`typingMode` 的默认值从 `TYPE_KEYS` 改成 `TYPE_IME`；新增一次性迁移标记
   `var imeDefaultApplied: Boolean`（key `"imeDefaultApplied"`）。
2. `MainActivity.onCreate`（bind 之前）：
   ```kotlin
   // 2.11：默认改成手机输入法。老安装里存的还是「直通键码」，升级后纠正一次就够，之后尊重用户选择
   if (!prefs.imeDefaultApplied) {
       prefs.typingMode = Prefs.TYPE_IME
       prefs.imeDefaultApplied = true
   }
   keyboard.imeMode = prefs.typingMode == Prefs.TYPE_IME
   ```
   为什么要标记：`typingMode` 是持久化的，老用户的手机里已经存了 `TYPE_KEYS`，只改默认值等于没用；
   而每次都强刷成 IME 又会剥夺用户「我就想用直通键码」的选择，所以只纠正一次。
3. `btnKeyboard` 的点击回调：
   ```kotlin
   keyboard.toggle()
   updateGyroForKeyboard(keyboard.isShown)
   if (keyboard.isShown && keyboard.imeMode) {
       keyboard.postDelayed({ if (keyboard.isShown) showImeDialog() }, 160)
   }
   ```
   160ms 是留给面板展开动画的；回调里再判一次 `isShown`，避免用户手快点了「收起」又弹出来。
4. `KeyboardPanelView.buildImePage()`：删掉 52dp 的「调出手机输入法 ✎」大按钮，只留两行提示 +
   回车/⌫/Tab/Esc 快捷行。提示文案改成「手机输入法会自己弹出来，打好字点「发送」就整段送到电脑。」

**为什么不需要那个按钮了**：v2.10 起点「手机输入法」tab 就会弹抽屉，v2.11 又把「点键盘按钮」
这条主路径也接上了，按钮成了第三条重复入口。

**验证**

1. 装 2.11（老数据里 `typingMode` 还是直通键码）→ 点「键盘」→ 面板展开后 160ms 内弹出
   「手机输入法打字」抽屉 + 系统输入法，输入框有光标。
2. 点面板上的「直通键码」tab → 变成字母页、不再弹抽屉；关掉面板再点「键盘」→ 直接是字母页
   （说明用户的选择被记住，不会每次强制弹）。
3. 切回「手机输入法」tab → 抽屉立刻弹出。

**版本**：versionCode 22 / versionName 2.11，产物 `dist/AirMouse-2.11.apk`。

### 16.23 v2.12：「整段输入」改名「打一段字」+ 只留在直通键码页

**背景**：用户问「整段输入是什么功能，两种输入方式都有这个按钮」。

**它的功能**：就是调用 `showImeDialog()` —— 和「手机输入法」tab 完全同一个抽屉。
这个按钮在 v1.x 时代就有了，那时面板只有 QWERTY、没有输入法 tab，它是打中文的唯一入口；
v2.10 起 tab 自己会弹抽屉，v2.11 起点「键盘」也会弹，于是它变成了第三个（在输入法页里是第四个）重复入口。

**改动**

1. `KeyboardPanelView.kt`：把顶栏按钮提成字段 `private lateinit var textBtn: TextView`，文案
   「整段输入」→「打一段字」（4 个字，76dp 宽 12sp 放得下）。
2. `tintTabs()` 末尾：
   ```kotlin
   // 「打一段字」只在直通键码页有意义（用系统输入法打一段中文再整段发）；输入法页本身就在打字，留着是重复入口
   textBtn.visibility = if (imeMode) View.GONE else View.VISIBLE
   ```
   （用 `android.view.View.GONE` 全限定名，文件里没 import View。）
   注意 `tintTabs()` 在 init 里、`textBtn` 创建之后调用；`imeMode` 的 setter 也会调它，所以切换 tab 立即生效。

**为什么留在直通键码页**：那一页是「一个个敲键」的场景，忽然要打一句中文（蓝牙打不了中文，
只能 WiFi/USB 整段注入）时，就近有个入口比切 tab 更快。

**验证**

1. 手机输入法页：顶栏只剩「收起」+ 标题，无「打一段字」。
2. 直通键码页：顶栏出现「打一段字」，点它弹出「手机输入法打字」抽屉。
3. 来回切 tab，按钮显隐即时跟随。

**版本**：versionCode 23 / versionName 2.12，产物 `dist/AirMouse-2.12.apk`。

### 16.24 v2.13：键盘面板改成浮层（不再挤压触摸板）+ 点空白收起

**用户报的两个问题**

1. 直通键码模式会挤压上方按钮和触摸板，手机输入法模式不会；
2. 直通键码模式点击键盘以外的地方，键盘不会收起。

**根因**：`activity_main.xml` 里 `KeyboardPanelView` 是 `content`（垂直 LinearLayout）的最后一个子 View，
面板一展开就占掉真实高度，上面的触摸板/按键/灵敏度全被压扁；而输入法模式是靠
`BottomSheetDialog` 浮在上面的，所以那一边看起来「不挤压」。
「点外面收起」同理：输入法有抽屉的遮罩接住点击，直通键码没有任何遮罩，点哪儿都只是点到底下的控件。

**改法**

1. `activity_main.xml`：把 `KeyboardPanelView` 从 `content` 里搬到根 `FrameLayout`，加 `layout_gravity="bottom"`；
   在它之前插入一层遮罩：
   ```xml
   <View android:id="@+id/kbScrim" android:layout_width="match_parent"
       android:layout_height="match_parent" android:background="#99000000"
       android:clickable="true" android:focusable="true" android:visibility="gone" />
   ```
   遮罩在前、面板在后 → 面板浮在遮罩之上；`root` 本身已经有系统栏 padding，所以面板底部不会被手势条压住。
2. `MainActivity`：新增字段 `private lateinit var kbScrim: View` 与两个 helper：
   ```kotlin
   private fun showKeyboardPanel() { kbScrim.visibility = View.VISIBLE; keyboard.show() }
   private fun hideKeyboardPanel() { kbScrim.visibility = View.GONE; keyboard.hide() }
   ```
   `kbScrim.setOnClickListener { hideKeyboardPanel(); updateGyroForKeyboard(false) }`
3. 把所有直接调 `keyboard.show()/hide()/toggle()` 的地方换成这两个 helper（共 6 处：
   调试 extra `--ez keyboard`、`onClose`、`btnKeyboard`、`setEnabled(false)`、`showImeDialog` 的 onOk、`onBackPressed`）。
   `btnKeyboard` 的 toggle 改成 `if (keyboard.isShown) hideKeyboardPanel() else showKeyboardPanel()`。

**注意**：面板现在会盖住触摸板下半部分（这是浮层的代价），但尺寸不再变；输入法模式下
抽屉还会叠在面板之上，看起来是「面板 + 再一层抽屉」，视觉上可接受。

**验证**

1. 直通键码页：顶栏/触摸板/按键位置与收起时完全一致（不再被压扁）。
2. 面板展开时点上方暗色区域 → 面板和遮罩一起收起。
3. 点「收起」按钮、按返回键同样收起。
4. 手机输入法：点键盘 → 面板 + 抽屉 + 系统输入法；发送后全部收起。

**版本**：versionCode 24 / versionName 2.13，产物 `dist/AirMouse-2.13.apk`。

### 16.25 v2.14：「打一段字」按钮删掉（保留 16.23 的结论：它就是输入法抽屉）

**用户反馈**：「直通键码里的打一段字点击怎么是唤起手机输入法，这合理吗，这跟手机输入法重复了吧」。

**判断**：确实重复。16.23 里我把它降级成「只在直通键码页显示」，理由是同页有个就近入口更方便；
但既然点下去弹的就是同一个抽屉，同页多一个按钮只会让人以为它是另一种功能。同一功能不该有两个名字。

**改动**（纯删除，没有新增逻辑）

1. `KeyboardPanelView.kt`：删掉字段 `private lateinit var textBtn: TextView`、顶栏里它的创建与 `bar.addView(...)`、
   `tintTabs()` 末尾那两行显隐控制；接口 `Listener` 里删掉 `fun onTextRequest()`（连带清掉一处残留空行）。
2. `MainActivity.kt`：删掉 `override fun onTextRequest() = showImeDialog()`。
3. 顶栏现在只剩「键盘」标题 + 右上「收起」。

**现在打中文的两条路**（都通向同一个抽屉，只是入口不同场景）

- 点「键盘」→ 默认就是手机输入法，抽屉和系统输入法一起出来；
- 面板里点「手机输入法」tab → 同样弹抽屉（在直通键码页想改打一整段时用）。

**版本**：versionCode 25 / versionName 2.14，产物 `dist/AirMouse-2.14.apk`。

### 16.26 v2.15：开关关掉再打开，陀螺仪接回上次的模式

**用户反馈**：「陀螺仪模式，会在关闭开关再打开后重置，没有记忆上次的模式」。

**为什么原样如此**：v1.9 起 `prefs.gyroMode` 只在「开启某个模式」时写（`setGyroMode` 里
`if (m != GYRO_OFF) prefs.gyroMode = m`），关的时候故意不覆盖，所以**记忆是有的**；
问题在于没有任何代码在重新启用后去读它——`setEnabled(true)` 只调 `applyMode()` 建连接，
`gyroMode` 从内存里的 `GYRO_OFF` 开始，于是看起来「重置了」。

**改法**（只加状态，不动持久化）

1. 字段 `private var gyroRestorePending = GYRO_OFF`（注释：关总开关时记下当时开着的模式，连上后恢复；
   用户手动改过就作废）。
2. `setEnabled(false)` 里在调 `setGyroMode(GYRO_OFF)` **之前**：
   ```kotlin
   gyroRestorePending = gyroMode
   setGyroMode(GYRO_OFF)
   ```
3. `syncGyroWithLink()` 末尾（它本来就在 `onHidState` / `onNetState` 里被调用）：
   ```kotlin
   if (linked && enabled && gyroMode == GYRO_OFF && gyroRestorePending != GYRO_OFF) {
       val want = gyroRestorePending
       gyroRestorePending = GYRO_OFF
       setGyroMode(want, announce = true)
   }
   ```
   —— 必须等 `linked` 才恢复：陀螺仪没连接本来就不许开（v2.7 的规矩），提前开会白耗电。
4. 「作废」的两处：`showGyroMenu()` 的 onPick 里第一行 `gyroRestorePending = GYRO_OFF`；
   `showModeMenu()` 切换连接通道时也清一次（换通道等于重新开始）。

**没做的**：没有改成「每次启用都无条件按 `prefs.gyroMode` 开陀螺仪」。那样会把你手动关掉的陀螺仪
在下一次启用时又打开；这里只恢复「主动关总开关时还开着」的那一次。

**验证**

1. 开开关 → 连上 → 下拉选「射线」→ 关开关：下拉变「陀螺仪 关」并压暗。
2. 再开开关 → 连上瞬间（WiFi 下约 40ms）下拉自动回到「陀螺仪 射线」，logcat
   `enabled -> true` 后紧跟 `RayEngine: ray start true sensor=game_rotvec`。
3. 手动在下拉里选「关」→ 关开关再开 → 保持「关」（手选优先）。

**版本**：versionCode 26 / versionName 2.15，产物 `dist/AirMouse-2.15.apk`。

### 16.27 v2.16：校准胶囊从触摸板右上角挪到右下角

**用户反馈**：「校准按钮在触摸板右上方不太好按」，给了四个方案（A 挪到按键行、B 双指轻点手势、C 挪右下角、D 并进下拉），用户选 **C**。

**改动**（只有布局）

`activity_main.xml` 里 `btnCalib`：

| | 改前 | 改后 |
|---|---|---|
| layout_gravity | `top|end` | `bottom|end` |
| 外边距 | top 10dp, end 10dp | bottom 10dp, end 10dp |
| 高度 | 30dp | **28dp**（缩小一点少挡触摸板） |
| minWidth / padding | 52dp / 12dp | 48dp / 10dp |

**为什么这样**：手机常用姿势是单手托着、拇指在屏幕下半区活动，右下角正好在拇指画弧的范围内；
缩小到 48×28dp 依然有 ~168×98px 的触摸区（560dpi），够 ISO 的最小触控尺寸，同时尽量少占拖光标的地盘。

**验证**

1. 开陀螺仪 → 胶囊出现在触摸板右下角、紧贴右键按钮上方。
2. 点它 → logcat `RayEngine: ray recenter` + `ray ref captured fwd=(...)`，光标回到屏幕中心。
3. 长按 → 弹「射线校准」抽屉（含两点校准）。
4. 关陀螺仪 → 胶囊消失。

> 测试提醒：`uiautomator dump` 里它的 bounds 是 `[964,2087][1132,2185]`，点 `(1048,2136)`；
> 我第一次按截图比例估的 `(1037,2189)` 差了 4px 落在触摸板上，白测一轮。

**版本**：versionCode 27 / versionName 2.16，产物 `dist/AirMouse-2.16.apk`。







---

### 16.28 v2.17：深色 / 浅色主题

**需求**：用户要能切深浅色（原来整套 UI 写死深色，连 `colors.xml` 都只有一套值）。

**1. 颜色拆成两套资源**
- `res/values/colors.xml` → 浅色；`res/values-night/colors.xml` → 深色（原来那套值原样搬过去）。
- 除了原有的 `bg / surface / surface_2 / stroke / accent / accent_soft / text_primary / text_dim / ok / warn / danger / icon_bg`，**新增一组语义色**，专门给 drawable 和自绘 View 用，避免代码里再出现 `0xFF22D3EE` 这种字面量：
  `on_accent`、`on_primary_container`、`switch_off`、`key_bg`、`key_stroke`、`key_ripple`、`panel_bg`、`panel_stroke`、`pad_top`、`pad_bottom`、`pad_stroke`、`handle`、`hint`、`section`、`dialog_text`、`scrim`、`dot`、`fade_from`、`fade_to`。
- 浅色调色不是简单反色：强调色用 `#0E7490`（原来的 `#22D3EE` 在白底上看不清），卡片纯白 + `#DCE3EA` 描边，触摸板 `#FFFFFF → #F1F4F8` 渐变，键盘面板 `#F1F4F8`（比键帽白底深一档，键才浮得出来）。

**2. 主题**
- `Theme.AirMouse` 的 parent 从 `Theme.Material3.Dark.NoActionBar` 换成 `Theme.Material3.DayNight.NoActionBar`。
- 状态栏/导航栏图标明暗：`android:windowLightStatusBar` / `android:windowLightNavigationBar` 指向 `@bool/light_system_bars`（`values/bools.xml` = true，`values-night/bools.xml` = false）。
- `colorOnPrimary` / `colorOnPrimaryContainer` / `colorOnSecondary` 也改成 `@color/on_accent` / `@color/on_primary_container`（原先写死 `#FF04222B`、`#FF9FEAF6`，浅色下会是深底亮字）。

**3. drawable 与自绘 View 全部走资源**
- `bg_key` / `bg_panel` / `bg_touchpad` / `fade_bottom` / `dot` / `bg_handle` 的 `solid`、`stroke`、`gradient` 全改成 `@color/...`；`ic_sheet_*`、`ic_close` 的 `strokeColor` / `fillColor` 同样。
- `MainActivity`：`COLOR_OK/WARN/MID/IDLE` 从 `const val ARGB` 改成 `private val ... get() = ContextCompat.getColor(this, R.color.x)`（**注意它们原来在 `companion object` 里，`this` 是 Companion 会编译报错，必须挪到类体**）；`Color.parseColor("#22D3EE")` 之类全换成 `ContextCompat.getColor`。
- `TouchpadView` / `ScrollStrip` / `KeyboardPanelView` 里的 ARGB 同样换成 `ContextCompat.getColor` + `ColorUtils.setAlphaComponent`（原来用 `0x8822D3EE` 这种自带 alpha 的写法）。四个文件都要补 `import androidx.core.content.ContextCompat`。

**4. 切换开关**
- `Prefs` 加 `APPEARANCE_SYSTEM=0 / APPEARANCE_LIGHT=1 / APPEARANCE_DARK=2`、`APPEARANCE_NAMES`、`var appearance`（key `"appearance"`）。
- `MainActivity.applyAppearance()`：`AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO / MODE_NIGHT_YES / MODE_NIGHT_FOLLOW_SYSTEM)`；在 `onCreate` 里 **`super.onCreate` 之前**调用，重建时才会用新配置 inflate。
- 设置抽屉加「外观」行（`rowAppearance` + `tvAppearance` 显示当前值），点一下按「跟随系统 → 浅色 → 深色」轮换。
- **关键坑**：只调 `setDefaultNightMode` 在 ColorOS 上**不会重建当前 Activity**（prefs 改了、主题不变，要等下次启动）。所以在行点击里再显式 `recreate()` 一次，立刻生效。
- 另一个坑：BottomSheetDialog 里做锚点的 `ListPopupWindow` 收不到点击（弹窗挂在对话框窗口上），试过一版「外观用小抽屉选」，点击也不触发；最后用「点一下换下一档」的循环，最稳。

**5. 踩坑（工程流程）**
- 用 PowerShell 批量替换颜色时，如果写成 `foreach ($pair in @(@('a','b'))) { $c.Replace($pair[0],$pair[1]) }`，单元素数组会被展平成字符串，`$pair[0]` 变成**第一个字符**、`$pair[1]` 变成第二个字符 —— 于是每个 `<` 被替换成 `s`、每个 `t` 被替换成 `i`，文件被写烂（本次把 `MainActivity.kt`、`TouchpadView.kt`、`bg_handle.xml`、`dot.xml`、`ic_close.xml`、`ic_sheet_inertia.xml` 都毁过一遍）。
- 恢复办法：`AirMouse-2.16-full.zip` 里有上一版源码，用 `[System.IO.Compression.ZipFile]` 取出同名文件覆盖，再重新做替换。**建议：批量改颜色一律用 edit/write 工具，或至少一次只 `.Replace($a, $b)` 两个显式字符串。**

**验证**
1. 设置 → 外观，连续点三次：跟随系统（跟随手机）→ 浅色（白底深青）→ 深色（近黑底亮青），每次**立刻**变。
2. 浅色下检查：设置抽屉卡片、使用说明两列、键盘面板键帽、射线校准胶囊、全部重置抽屉、状态条副标题都清晰可读。
3. 深色下与 v2.16 观感一致（颜色值没动，只是挪了位置）。
4. 杀进程重开：主题与上次选择一致。

**版本**：versionCode 28 / versionName 2.17，产物 `dist/AirMouse-2.17.apk`。

---

## §16.29 v2.18：切主题不再重置总开关与陀螺仪

**现象**（用户报的）：每切一次深/浅色，App 从「在控制电脑」变回「已停用」，陀螺仪回到「关」，
并且弹一次「已启用：手机开始控制电脑」。

**两个独立原因**

1. **开关被系统回填**：`SwitchCompat` 默认参与 `onSaveInstanceState`，Activity 重建后它被恢复成 checked，
   触发 `setOnCheckedChangeListener` → `setEnabled(true, announce = true)` → 既有副作用又弹 toast。
   修法：`activity_main.xml` 里 `switchEnable` 加 `android:saveEnabled="false"`。
2. **`enabled` 与 `gyroMode` 只是内存字段**，`recreate()` 一过就没了。修法：`onSaveInstanceState`
   写 `KEY_UI_ENABLED` / `KEY_UI_GYRO`，`onCreate` 末尾读回来，用 `setEnabled(true, false)` 恢复
   （`announce = false`，重建不该再弹提示），并预置 `gyroRestorePending`，等 `syncGyroWithLink()`
   在连接建立后把陀螺仪接回上次的模式。

**最隐蔽的坑：`onPause()` 先跑、`onSaveInstanceState()` 后跑**

`recreate()` 的顺序是 `onPause()` → `onSaveInstanceState()`。而 `onPause()` 里有：

```kotlin
if (gyro.running || ray.running) { gyroBeforeKeyboard = GYRO_OFF; setGyroMode(GYRO_OFF) }
```

`setGyroMode(GYRO_OFF)` 会把字段 `gyroMode` 置 0 —— 于是保存进 bundle 的永远是 0，
恢复逻辑拿不到「射线」。第一次修完的表现正是「开关恢复了、陀螺仪还是关」。

修法：加一个只记非关模式的字段

```kotlin
private var lastNonOffGyro = GYRO_OFF   // onPause 清零 gyroMode 时的兜底
// setGyroMode() 里
if (m != GYRO_OFF) lastNonOffGyro = m
// onSaveInstanceState() 里
outState.putInt(KEY_UI_GYRO, if (gyroMode != GYRO_OFF) gyroMode else lastNonOffGyro)
```

**验证**（OPPO DQGQEYNFC6W4SKPN，WiFi + 射线）
1. 开总开关、选「射线」，确认 `RayEngine: ray start true sensor=game_rotvec`。
2. 设置 → 外观 点一下切主题：界面重建后仍是「已连接 / 已启用」，**没有**「已启用」toast。
3. logcat 顺序为 `enabled -> true mode=1` 紧跟 `RayEngine: ray start true sensor=game_rotvec`（间隔约 50ms），
   即总开关恢复后由 `syncGyroWithLink()` 接回射线。
4. 冷启动（杀进程）仍然是「已停用 / 陀螺仪 关」——只有 Activity 重建才恢复，这是刻意的。

**版本**：versionCode 29 / versionName 2.18，产物 `dist/AirMouse-2.18.apk`。

---

## §16.30 v2.19：横屏布局

**改了什么**

1. `AndroidManifest.xml`：去掉 `android:screenOrientation="portrait"`；`android:configChanges` 从
   `orientation|screenSize|keyboardHidden|screenLayout|uiMode|smallestScreenSize` 收成 `keyboardHidden|uiMode`。
   去掉 `orientation|screenSize` 是**必须**的——留着的话旋转不会重建 Activity，`layout-land` 永远不会被加载。
   重建带来的状态丢失由 v2.18 的 `onSaveInstanceState` 恢复逻辑兜住（总开关 + 陀螺仪模式自动接回）。
2. 新建 `res/layout-land/activity_main.xml`：横向两栏。
   - 左栏（weight 1）：状态行 → `[连接方式 ▾][陀螺仪 ▾][开关]` → `[左/中/右/键盘]` 一行四格 → 灵敏度 → 底部一行说明。
   - 右栏（weight 1.35）：整块触摸板，滚轮条贴右边缘（`44dp × match_parent`），校准胶囊放`bottom|start`（横屏时左手拇指在左下），
     hint 放 `bottom|center_horizontal`。
   - 键盘面板宽 `620dp`、`layout_gravity="bottom|center_horizontal"`（竖屏是全宽，横屏 754dp 宽会把键帽拉成一排大方块）。
3. `res/values/dimens.xml` 新增 `key_row_h = 46dp`，`res/values-land/dimens.xml` 覆盖为 `37dp`；
   `KeyboardPanelView.rowLp()` 改成 `resources.getDimensionPixelSize(R.dimen.key_row_h)`。
   横屏可用高度只有 ~347dp 减去系统栏，五排 46dp 会溢出，37dp 正好（含 header/tabs 约 277dp）。

**坑**

- `layout-land` 里 `FrameLayout` 的子 View 如果写 `layout_height="0dp"` 而没有 weight，就是**零高**（滚轮条第一次就是这么消失的），
  要么 `match_parent`，要么给 weight。
- 横屏下系统输入法（ColorOS）是浮在右侧的小窗，我们的抽屉停在左下，两者不打架；但 `adjustResize` 在横屏时
  基本不会触发了，抽屉自己贴底就够。
- 测试旋转：`adb shell wm user-rotation lock 1`（`settings put system user_rotation` 在 ColorOS 上会被系统改回 0，
  必须用 `wm`）；**每次 force-stop/重装 App 之后要重新 lock 一次**，否则又会回到竖屏。
- 射线模式的解算基于世界系 yaw/pitch + 启动时抓的参考系，横屏时手势方向仍然成立，但参考系是当时姿态，
  横竖切换会重建 Activity，`ray.recenter()` 的参考系也会重抓，属于预期行为。

**验证**（OPPO DQGQEYNFC6W4SKPN）
1. `wm user-rotation lock 1` → 界面自动变横屏：左控制栏 + 右触摸板，滚轮条在触摸板右边缘。
2. 打开总开关 → 连上 WiFi，右侧触摸板滑动光标能动。
3. 点键盘 → 面板居中 620dp、五排键帽 37dp 全在屏内，无溢出。
4. 转回竖屏 → 恢复原布局，总开关保持开启（v2.18 恢复逻辑生效）。

**版本**：versionCode 30 / versionName 2.19，产物 `dist/AirMouse-2.19.apk`。
---

## 16.31 v2.20：横屏下只允许「陀螺仪 关」

**为什么不做横屏陀螺仪**：横过来是「左手按键、右手拖触摸板」的双手捧机姿势，
陀螺仪要么得单手举着瞄、要么和右手抢方向，实际用起来比触摸板差；而且窄的左栏放不下
「陀螺仪 射线 ▾」这种长文本，会被挤成怪样子（用户原话「文本显示有异」）。
结论：横屏就把陀螺仪这条路收掉，只留触摸板，转回竖屏再恢复。

**改动**（`app/src/main/java/com/airmouse/gyro/MainActivity.kt`）

```kotlin
private fun isLandscape(): Boolean =
    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
```

1. `showGyroMenu()`：`!enabled` → `!isLinked` 之后再加一道
   `if (isLandscape()) { toast("横屏下不启用陀螺仪：左手按键、右手拖触摸板就够，转回竖屏再开"); return }`。
2. `paintGyroMenu()` 开头：横屏时直接写死 `btnGyroMenu.text = "陀螺仪 关 ▾"`、`alpha = 0.45f`、
   `btnCalib.visibility = View.GONE` 然后 `return`，不走后面的三态文本。
3. `syncGyroWithLink()` 的自动恢复条件追加 `&& !isLandscape()`。
4. `onCreate` 末尾（v2.18 的状态恢复块之后）：
   ```kotlin
   if (isLandscape()) {
       gyroRestorePending = GYRO_OFF
       if (gyroMode != GYRO_OFF) setGyroMode(GYRO_OFF)
       paintGyroMenu()
   }
   ```
   这一步同时清掉「待恢复的模式」，否则旋转后一连上电脑又会被 `syncGyroWithLink` 打开。

**验证**（OPPO DQGQEYNFC6W4SKPN）
1. 竖屏开总开关 + 选「射线」→ logcat `RayEngine: ray start`。
2. `wm user-rotation lock 1` → `RayEngine: ray stop`，chip 变暗显示「陀螺仪 关 ▾」，校准胶囊消失，开关仍是开着的。
3. 点 gyro chip → toast「横屏下不启用陀螺仪…」，不展开下拉。
4. `wm user-rotation lock 0` 回竖屏 → 仍是「已连接 / 陀螺仪 关」，chip 恢复正常亮度、可正常点开。

**版本**：versionCode 31 / versionName 2.20，产物 `dist/AirMouse-2.20.apk`。
---

## 16.32 v2.21：横屏回竖屏，陀螺仪自动接回

**问题**：v2.20 把横屏的陀螺仪收掉，但收掉后没有「记住」，转回竖屏只能显示「陀螺仪 关」，
用户得手动再点一次。

**两个真问题**

1. 进横屏时 `setGyroMode(GYRO_OFF)` 会刷新 `lastNonOffGyro` 之外的字段，但**横屏这个新实例里
   `lastNonOffGyro` 从没被赋过值**（它只在 `setGyroMode(m != OFF)` 时写），于是转回竖屏时
   `onSaveInstanceState` 存出来的是 0。
2. `onSaveInstanceState` 比 `onPause` 晚跑，而 `onPause` 会把陀螺仪停掉（`setGyroMode(OFF)`），
   所以快照必须**在停之前**拍。

**改法**（`app/src/main/java/com/airmouse/gyro/MainActivity.kt`）

- 新字段：
  ```kotlin
  private var gyroSuspendedByLandscape = GYRO_OFF  // 横屏期间暂存的模式
  private var gyroModeAtPause = GYRO_OFF           // onPause 前的快照
  ```
- `onPause()` 第一行无条件 `gyroModeAtPause = gyroMode`，然后再判断要不要 `setGyroMode(GYRO_OFF)`。
- `onSaveInstanceState` 存两项：
  ```kotlin
  outState.putInt(KEY_UI_GYRO, if (gyroMode != GYRO_OFF) gyroMode else gyroModeAtPause)
  outState.putInt(KEY_UI_GYRO_SUSPENDED, gyroSuspendedByLandscape)
  ```
- `onCreate` 恢复块按方向分流：
  - 横屏：`gyroSuspendedByLandscape = wasSuspended ?: wasGyro`，`gyroRestorePending = OFF`（横屏不开）。
  - 竖屏：`pending = wasSuspended ?: wasGyro` → `gyroRestorePending = pending`，
    并把 `gyroSuspendedByLandscape` 清零；连上电脑后由 `syncGyroWithLink()` 接回。
- 关键点：**用「横屏暂存」而不是「最后一次非关模式」**。后者在「竖屏本来就把陀螺仪关掉」的情况下
  是过期的（仍是上次的射线），会导致转一圈回来莫名其妙自己动起来。

**验证**（OPPO DQGQEYNFC6W4SKPN）
1. 竖屏开开关、选「射线」→ `RayEngine: ray start`；`wm user-rotation lock 1` → `ray stop`（横屏关）；
   `lock 0` → `ray start` 自动回来，chip 显示「陀螺仪 射线 ▾」，校准胶囊回来。
2. 竖屏把陀螺仪关掉 → 转横屏 → 转回竖屏：仍是「陀螺仪 关」，日志里没有 `ray start`。
3. 两种情况下总开关都保持开启，状态仍是「已连接」。

**版本**：versionCode 32 / versionName 2.21，产物 `dist/AirMouse-2.21.apk`。

---

## 16.33 v2.22：红米（HyperOS）兼容性验证 + 调试开关

**版本**：versionCode 33 / versionName 2.22，产物 `dist/AirMouse-2.22.apk`。

### 16.33.1 为什么要升版本

只是加了一个调试开关 `--ez enable true`（`onCreate` 里 300ms 后 `setEnabled(true, false)`），
因为红米上 `adb shell input tap` 被系统静默丢弃，点不了总开关就没法做端到端验证。

### 16.33.2 红米实测清单（Redmi 23113RKC6C / HyperOS V816 / Android 16 / 1440×3200）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 安装 | 需先 `adb uninstall` | 旧包是 11-05 的 debug 签名，`INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| 启动 / 布局 | 正常 | 无 FATAL，浅色主题渲染正常 |
| HID 注册 | 通过 | `HidInput: registerApp -> true` / `onAppStatusChanged registered=true` |
| HID 连接 | 通过 | `connect(QQDESKTOP) -> true` → `-> 1` → `-> 2`（约 0.9s） |
| 相对位移 | 通过 | `autodrag` 后光标 (1861,1297) → (2055,1297) |
| 陀螺仪传感器 | 通过 | `RayEngine: ray start true sensor=Game Rotation Vector  Non-wakeup` |
| WiFi 模式 | 通过 | `wlan0 192.168.1.254` → 服务端 `[+] 手机已连接 192.168.1.254:47218` |
| 横屏布局 | 通过 | 3200×1440，左栏 + 右触摸板、滚轮条贴右缘、无校准胶囊 |
| 键盘面板 | 通过 | 620dp 居中、默认「手机输入法」页 |
| 中文整段发送 | 通过 | 记事本窗口标题变成 `*NOTE123中文 - Notepad` |
| 绝对指针（蓝牙） | **不生效** | `abs send … ok=true` 但光标不动 —— Windows 缓存了旧 HID 描述符，需删配对重连 |
| 绝对指针（WiFi） | 通过 | `abspoint 0.75,0.25` → (1919,360) |

### 16.33.3 调试链路里踩的坑（都值得记住）

1. **`adb shell input tap` 在 HyperOS 上「成功但无效」**：不抛 `SecurityException`，`mCurrentFocus` 也是本 App，但界面完全没反应。别把它当「代码没接住」，先换 `--ez` 开关验证。
2. **服务端 `Injector.text()` 以前只在 dry-run 打印**：真模式静默 `_key_unicode`，导致「日志里没有 text 行」被误判成链路断了。现在改成两种模式都 `print(f"  text {s!r}")`。
3. **用记事本验证输入最省事**：`Start-Process notepad` → 手机发字 → `Get-Process notepad` 看 `MainWindowTitle` 有没有 `*` 前缀。WinForms 探针窗口不可靠（`Start-Process -WindowStyle Minimized` 起的窗体抢不到焦点，连 `SendKeys` 都进不去）。
4. **PowerShell 写 Kotlin 会把 `\n` 写坏**：`.Replace()` 里带 `\n` 的 here-string 会把字面反斜杠+n 落进源码，Kotlin 直接语法错误。改 `.kt` 一律用 write/edit。

### 16.33.4 复验命令

```powershell
adb -s e3d85ef4 install -r -t dist\AirMouse-2.22.apk
adb -s e3d85ef4 shell "am start -n com.airmouse.gyro/.MainActivity --ez enable true --ei mode 0"
adb -s e3d85ef4 logcat -s HidInput
```

### 16.33.5 蓝牙绝对指针：重配对后通过（2026-11-07 12:20 补验）

- 之前判定的「红米蓝牙绝对指针不可用」**只因为 Windows 缓存了旧描述符**。把电脑上的设备删掉、在 App 处于「蓝牙 HID / HID 就绪」时重新配对之后，`Get-PnpDevice -Class Mouse` 里出现了
  `HID\{00001124-…}_VID&0001079A_PID&0000&COL03`（第二个 collection 是键盘 COL02，第一个是相对鼠标 COL01）。
- `--es abspoint` 三点实测（PC 2560×1440）：
  | 参数 | 实测光标 | 理论值 |
  | --- | --- | --- |
  | `0.75,0.25` | (1919,360) | (1920,360) |
  | `0.25,0.75` | (640,1079) | (640,1080) |
  | `0.05,0.5` | (127,720) | (128,720) |
  误差 ≤1px，来自 HID 16 位逻辑坐标的取整。
- 配对注意：Windows 扫描列表显示的是**手机蓝牙名**（`Redmi K70`，经典+LE 各一条），`AirMouse-23113RKC6C` 是 HID 服务名，配对成功后才出现在「设备」里。配错会变成「普通手机」（`QQtang的Redmi K70`），此时删掉重配。
- 结论更新：红米上蓝牙绝对指针**可用**，红米唯一限制只剩「HyperOS 丢弃 `adb shell input tap`」。

## 16.34 v2.22 附带：PC 端单文件 exe（AirMouseServer.exe）

**为什么做**：用户要求「手机上装 apk、电脑上装 exe，达到现在调试的效果」。原来 WiFi/USB 要装 Python 跑 `pc/airmouse_server.py`，对普通用户门槛高。

**产物**：`dist\AirMouseServer.exe`（单文件 WinForms，约 25 KB，依赖系统自带 .NET Framework 4），源码 `pc\AirMouseServer.cs`。

**编译**（机器上没装 .NET SDK 也能编，用系统自带的 csc）：

```
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /codepage:65001 /optimize+ /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll /out:gyro-mouse\dist\AirMouseServer.exe gyro-mouse\pc\AirMouseServer.cs
```

**功能对齐 python 版**：`m/a/b/w/k/s` 全部六种指令；`SendInput` 绝对定位用 `MOUSEEVENTF_ABSOLUTE|VIRTUALDESK` + `u*65535`；HID usage 到 VK 的映射表；文本逐 UTF-16 单元 `KEYEVENTF_UNICODE`；`adb reverse` 每 3 秒扫一次 `adb devices`（`AIRMOUSE_ADB` 环境变量优先，其次 `D:\Software\platform-tools-latest-windows\platform-tools\adb.exe`、`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`、`C:\platform-tools\adb.exe`、PATH）；托盘图标 + 窗口（状态 / IP / 日志 / 开机自启 / 复制 IP / 放行防火墙）。

**实测（红米 e3d85ef4 → USB 模式 → exe）**：

- `abspoint 0.25,0.75` → 光标 (640,1079)，即 2560×1440 的 25%/75%，误差 0px；
- `--ez autotext --es imetext` 中文 → exe 日志有 `text` 行，底层键盘钩子捕获 `vk=231 (VK_PACKET) injected=True`；
- 左/右键与滚轮用 `WH_MOUSE_LL` 钩子证实：`0x201/0x202`、`0x204/0x205`、`0x20A`（`mouseData=23592960 = 3×120<<16`）；
- 相对位移 `m dx=100` 实测 +152（Windows 指针加速，属正常）。

**五个坑**（都踩过）：

1. `Start-Process <exe>` 会让调用方一直挂着（等到进程退出）；`Invoke-CimMethod Win32_Process Create` 虽然立刻返回，但进程在**非交互窗口站**上，`SendInput` 全失效（日志 ok=True、光标不动）——真机双击不受影响，自动化测试必须从交互会话启动。
2. 单实例 Mutex `AirMouseServer-SingleInstance`：第二个实例静默 exit 0，看起来像「启动即崩溃」。
3. `Environment.GetFolderPath(LocalApplicationData)` 在 WMI/服务上下文返回空 → 日志路径三级回退（`%LOCALAPPDATA%\AirMouse\exe.log` → exe 同目录 → `%TEMP%`），并把实际路径打进第一行日志。
4. SmartScreen 弹窗跑在**安全桌面**：`EnumWindows`/UIAutomation 都看不到，但吞掉所有注入输入；只能人工点「不运行 / 仍要运行」。
5. 探测注入是否生效别用 WinForms 窗口（抢不到焦点就全是 0），用 `SetWindowsHookEx(WH_KEYBOARD_LL / WH_MOUSE_LL)`；PowerShell 跑 .ps1 要先过 ExecutionPolicy：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File x.ps1`。
## 16.35 v2.23：两端都能取日志（用户可把日志发回来定位）

**动机**：用户问「apk 和 exe 是不是都有 log 系统，别人出问题能直接给我 log」。之前手机端只有 logcat（普通用户拿不到），电脑端有文件但不好找。

**手机端**：新增 `Diag.kt`（内存环形缓冲 1200 行，同时打 logcat）+ `sheet_diag.xml` 抽屉，入口「设置 → 诊断日志」，三个按钮：复制 / 保存到文件（API 29+ 走 MediaStore 存「下载」，低版本退 App 私有目录） / 清空。埋点位置：`MainActivity.onCreate`（版本+机型+系统）、`setEnabled`、`sendComposedText`、`HidInput`（注册、发起连接、连接状态变化）、`NetInput`（连接成功、失败原因去重、发送文字）、`GyroEngine`/`RayEngine`（开关与传感器名）。

**电脑端**：接收端窗口右下角新增「查看日志」按钮（记事本打开 + 路径进剪贴板）；单实例第二个进程不再弹 MessageBox（会卡住看着像假死），改成 `FindWindow` + `ShowWindow` + `SetForegroundWindow` 把已有窗口叫到前台后静默退出；按钮行改用 `FlowLayoutPanel(RightToLeft)` + `Load` 里定位，避免 DPI 缩放/窗口变窄时按钮互相压住。

**实测（红米 e3d85ef4）**：`--ez diag true` 打开抽屉，内容为
`13:03:34.900 [App] start v2.23 · 34 · Xiaomi 23113RKC6C · Android 16 (SDK 36)` / `13:03:35.211 [App] 已启用 · 连接方式=USB` / `13:03:35.213 [NET] 已连接 127.0.0.1:47777 (USB)`；
`--ez diagsave true` → `/sdcard/Download/airmouse-log-20261107-130421.txt` 内容一致，说明 MediaStore 写入正常（注意 HyperOS 仍丢 `input tap`，所以这两个调试开关很有用）。

**坑**：`Diag.log` 的字符串模板里用 `${text.length}` 这种是安全的，但拼接 `"（" + if (...) "a" else "b" + "）"` 会把 `+ "）"` 归到 else 分支 —— 先算 `val via = if (...) ...` 再拼。
## 16.36 开机自启写注册表被拒 → 自动退回启动文件夹

**现象**：用户勾「开机自动启动」，日志报 `设置自启失败：对注册表项"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run"的访问被拒绝`，复选框却停在勾上状态（状态和真实情况不一致）。

**改法**（`pc/AirMouseServer.cs`）：
- `SetAutostart(bool)` 先试写 `HKCU\...\Run`；成功就把启动文件夹里可能残留的 `.lnk` 删掉（避免两处同时启动）。
- 写注册表失败 → 用 `WScript.Shell` 的 `CreateShortcut` 在 `Environment.SpecialFolder.Startup` 下建 `AirMouse 接收端.lnk`（`TargetPath` = exe、`WindowStyle = 7` 最小化）。
- 两条都失败才报错，并把两条路径的错误原因都写进日志。
- `IsAutostartEnabled()` 改为「注册表有值 **或** 启动文件夹有 lnk」。
- 新增命令行维护入口（不开窗口）：`AirMouseServer.exe /autostart:on | /autostart:off | /autostart:check`，状态打印「注册表=有/无，启动文件夹=有/无」。
- 编译需加 `/r:Microsoft.CSharp.dll`（用了 `dynamic` 调 WScript.Shell）。

**重要发现（影响以后所有 PC 端测试）**：从 DSH 工具 shell 里启动的原生 exe **继承沙箱令牌**，写 `%APPDATA%`、`%LOCALAPPDATA%`、`HKCU` 全部 "访问被拒绝"；而同一条命令用 PowerShell cmdlet 写（`Set-Content`、`Set-ItemProperty`）却是成功的。写了最小探针 `_probe.cs` 复现：`startup write FAIL / registry FAIL / wsh shortcut FAIL`。
→ 所以「设置自启失败」这条日志是**测试环境造成的假阳性**，用户自己双击运行时通常正常。这类需要真实用户令牌的验证（自启、防火墙加规则、SmartScreen）只能让用户手动点，或让用户跑 `/autostart:on` 再发日志。
## 16.37 exe 外观信息 + 「解除锁定」说明（SmartScreen 只能这样绕过）

- 给 `AirMouseServer.exe` 加了图标（`pc\airmouse.ico`，256/64/48/32/16 五尺寸，深底+青光标）和版本资源（`pc\AssemblyInfo.cs`：`AssemblyTitle=AirMouse 接收端`、`Product=AirMouse`、`FileVersion=2.23.0.0`）。编译命令相应加 `/win32icon:pc\airmouse.ico` 和多传一个源文件，产物 38400 B。
- 结论：**未签名 exe 的 SmartScreen 弹窗没法在代码里去掉**。它是 Windows 对「来自 Internet 且未签名」文件的检查（MOTW，Alternate Data Stream `Zone.Identifier`）。
  - 本机编译的 exe 没有 MOTW，直接 `& exe` 不弹；但用 `explorer.exe`/双击（ShellExecute）时会被查一次应用信誉 → 弹「Windows 已保护你的电脑」。
  - 真正去掉只有两条路：① 买代码签名证书（EV 立刻无提示，OV 需攒声誉）；② 让用户先「解除锁定」（右键 属性 → 解除锁定，或 `Unblock-File`），去掉 MOTW 后就不弹。
- 已把「第一次运行前先解除锁定」写进 `使用说明.txt` 第七节和 `快速上手.md`，含命令行写法 `powershell -Command "Unblock-File .\AirMouseServer.exe"`。
- 顺带确认：本机 SmartScreen 没有组策略（`HKLM\...\Policies\System\EnableSmartScreen` 不存在），所以不是策略强制。
# 先看这里（给接手的人 / 他的 DSH）

这是一个能用的 Android App：**手机当电脑的无线鼠标 + 键盘**（陀螺仪控光标、触摸板点击滚动、软键盘打字）。
两条链路：蓝牙 HID（电脑零安装）和 WiFi/USB（电脑跑一个 Python 脚本，能打中文）。

## 30 秒上手

1. 装 `dist/AirMouse-1.2.apk`（Android 9 以上）
2. 打开 App，默认是「蓝牙 HID」：让电脑能发现本机 → Windows「蓝牙和设备 → 添加设备」里选 `AirMouse-<机型>`
3. 连上后：转手腕=动光标，触摸板=点击/滚动，「键盘」=打字

细节用法看 `README.md`；**要改代码或在新机器上构建，看 `HANDOFF.md`**（架构、构建环境、调试开关、踩过的坑都在里面）。

## 目录

| 路径 | 是什么 |
|---|---|
| `README.md` | 使用者文档：配对、手势、两种打字方式、开机自启 |
| `HANDOFF.md` | **开发者/DSH 交接文档**：架构、HID 性能结论（32ms）、网络协议、构建环境、验证清单 |
| `dist/AirMouse-1.2.apk` | 已签名可安装包（versionCode 3） |
| `app/` | Android 源码（Kotlin，单 module） |
| `pc/` | Windows 接收端 `airmouse_server.py` + 双击启动的 .bat |
| `tools/cursor_sampler.ps1` | 量「手机→电脑」链路丢不丢位移的小工具 |

## 你可能第一时间会问

- **为什么蓝牙模式光标没网络模式顺？** 蓝牙 HID 中断通道约 30ms 才被主机取一次，发快了会互相覆盖丢位移。默认 32ms 是实测最优（见 `HANDOFF.md` §4.3）。要 100Hz 跟手就用 WiFi 模式。
- **为什么中文只能走 WiFi？** 蓝牙键盘协议只送键码，没有任意文字通道。WiFi 模式电脑侧用 `SendInput + KEYEVENTF_UNICODE` 注入，中文/emoji 都行。
- **本地构建要联网吗？** 不一定。原机器是离线构建的，`local-repo/` + 阿里云镜像就是为这个准备的；换有网机器用 Android Studio 直接打开也能跑（见 `HANDOFF.md` §9）。

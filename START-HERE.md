# 从这里开始

这个仓库是**陀螺仪鼠标 AirMouse**：手机 APK + 电脑端安装程序，用手机陀螺仪/触摸板控制电脑鼠标键盘。

## 我是来用的（不想看代码）

1. 电脑装 `AirMouseSetup.exe`（双击 → UAC 点「是」→ 选安装位置 → 安装）
2. 手机装 `AirMouse-<版本>.apk`
3. 手机打开 App → 右上角开关打开 → 想走蓝牙就在电脑「蓝牙和其他设备」里配对一次；想打中文就切到 WiFi 模式，填电脑 IP（接收端窗口上有）

详细图文步骤见 [快速上手.md](快速上手.md)。

## 我是来改代码的

- 架构、构建环境、调试开关、踩过的坑：[HANDOFF.md](HANDOFF.md)
- 怎么发版（改版本号 → 编 APK/EXE/安装器 → 推 GitHub → 刷 jsDelivr）：[PUBLISH.md](PUBLISH.md)
- 一眼看全：`publish.ps1`（一键发版）、`update.json`（线上清单，`apk` / `server` 两段）、`releases/`（已发布版本）

## 常用命令

```powershell
# 只升手机端（电脑端沿用现值）
.\publish.ps1 -AppVersion 1.1.3

# 手机端和电脑端一起升
.\publish.ps1 -AppVersion 1.2.0 -ServerVersion 1.1.1

# 只在本机编 APK
& 'C:\Users\Tangyq\.gradle\wrapper\dists\gradle-8.14.3-all\cbf6zifq8xavouihta8md72jo\gradle-8.14.3\bin\gradle.bat' -p . --offline assembleRelease
```

## 目录

`app/` 安卓端 · `pc/` 电脑端 C# · `dist/` 构建产物（不进仓库）· `releases/` 已发布版本（app-v*/server-v*）· `_archive/` `_shots/` `_probes/` 本地留档与调试产物（被 `.gitignore` 忽略）
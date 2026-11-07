# 发布与自动更新（GitHub + jsDelivr）

两个程序都不需要服务器：仓库里放一份 `update.json`，手机和电脑启动时去读它，比自己新就提示下载。

## 一、现在已经托管好了

| 东西 | 地址 |
| --- | --- |
| 仓库 | https://github.com/Tang-Yongqiang/AirMouse （public） |
| 版本清单 | https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/update.json |
| APK 下载 | https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/releases/v2.25/AirMouse-2.25.apk |
| EXE 下载 | https://cdn.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/releases/v2.25/AirMouseServer-v2.25.zip |

代码里已经写死这三条清单地址（⚠️ 多源回退，按顺序试）：

- `app/src/main/java/com/airmouse/gyro/Updater.kt` 的 `MANIFEST_URLS`
- `pc/AirMouseServer.cs` 的 `ManifestUrls`

## 二、仓库结构

```
AirMouse/
├─ app/                         Android 工程
├─ pc/
│   ├─ AirMouseServer.cs        电脑端源码
│   ├─ AssemblyInfo.cs          版本资源（要和 versionName 一致）
│   ├─ airmouse.ico             图标
│   └─ 使用说明.txt
├─ releases/
│   └─ v2.25/
│       ├─ AirMouse-2.25.apk            ← 安装包（10 MB）
│       ├─ AirMouseServer.exe           ← 电脑端（44 KB）
│       └─ AirMouseServer-v2.25.zip     ← ★ jsDelivr 只能发这个
├─ update.json                  ★ 版本清单
├─ publish.ps1                  ★ 一条命令发版
├─ README.md / 快速上手.md / PUBLISH.md
└─ .gitignore                   排除 build/、.gradle/、keystore、dist/、tools/
```

> `app/airmouse.keystore` 和 `tools/` **没有**提交到公开仓库（签名密钥不该公开）。别人 clone 下来能看代码、能编 debug 版，但打不出和你一样的 release 签名——这不影响他们用你发的 APK。

## 三、为什么这么放（jsDelivr 的三条硬限制）

1. **`.exe` 被禁止**：`cdn.jsdelivr.net/.../AirMouseServer.exe` 返回 `403 已禁止`。所以 exe 要先压成 `AirMouseServer-v2.25.zip`（zip 正常返回 200）。
2. **单文件 20 MB 上限**：APK 10 MB，没问题。
3. **CDN 会缓存**：`@main` 的清单改了不会立刻生效。每次发版必须刷新一次：

```
https://purge.jsdelivr.net/gh/Tang-Yongqiang/AirMouse@main/update.json
```

（浏览器打开就行，返回一段 JSON 表示刷新成功；`publish.ps1` 会自动做。）

**为什么不直接把下载地址写 GitHub Release？** 这台机器上 `raw.githubusercontent.com` 经常超时、`github.com/.../releases/download/...` 会 302 到更慢的域名。实测 jsDelivr 拉 10 MB 的 APK 只用 2.3 秒，所以能走 jsDelivr 就走 jsDelivr。

## 四、发一个新版本：一条命令

```powershell
cd gyro-mouse
.\publish.ps1 -Version 2.26 -Notes "· 新增 XXX`n· 修复 YYY"
```

它按顺序做 7 件事：

1. `versionCode` 自动 +1、`versionName` 改成 2.26（`app/build.gradle.kts`），`AssemblyInfo.cs` 同步成 2.26.0.0
2. 编译 APK（Gradle offline）和 EXE（csc），产物落到 `dist/`
3. 拷进 `releases/v2.26/`，并把 exe 压成 `AirMouseServer-v2.26.zip`
4. 生成 `update.json`（`apk`/`exe` 都指向 `@main/releases/v2.26/...`）
5. `git commit`
6. `git tag v2.26` + 推 main 和 tag
7. 调 `purge.jsdelivr.net` 刷新缓存

跑完会打印这次的三个下载地址。

**手动等价步骤**（脚本坏了的时候用）：

```powershell
# 1. 改版本号：app/build.gradle.kts 的 versionCode/+1、versionName；pc/AssemblyInfo.cs 的 2.26.0.0
# 2. 编译
& 'C:\Users\Tangyq\.gradle\wrapper\dists\gradle-8.14.3-all\cbf6zifq8xavouihta8md72jo\gradle-8.14.3\bin\gradle.bat' -p . --offline assembleRelease
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /target:winexe /codepage:65001 /optimize+ `
    /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:Microsoft.CSharp.dll `
    /win32icon:pc\airmouse.ico /out:dist\AirMouseServer.exe pc\AirMouseServer.cs pc\AssemblyInfo.cs
# 3. 拷到 releases\v2.26\，并把 exe 压成 zip
# 4. 改 update.json，git commit / tag / push，最后打开 purge 链接
```

## 五、换成你自己的仓库

改三处，然后**重新构建 APK + 重新编译 EXE**（地址是编进去的）：

| 位置 | 改什么 |
| --- | --- |
| `Updater.kt` 的 `MANIFEST_URLS` | `Tang-Yongqiang/AirMouse` → 你的账号/仓库 |
| `AirMouseServer.cs` 的 `ManifestUrls` | 同上 |
| `publish.ps1` 的 `$Repo` 参数默认值 | 同上 |

第一次上传：

```bash
git init -b main
git add -A && git commit -m "AirMouse 2.25"
git remote add origin git@github.com:<账号>/AirMouse.git
git push -u origin main && git push origin v2.25
```

## 六、`update.json` 字段

| 字段 | 说明 |
| --- | --- |
| `versionCode` | 整数，**跟手机端比这个大**才提示；必须和 APK 里的 versionCode 一致 |
| `versionName` | 给人看的版本号，exe 也用它比较 |
| `apk` | APK 直链（手机点「去下载」会打开它） |
| `exe` | 电脑端链接；因为 jsDelivr 禁 .exe，这里是 **zip** |
| `notes` | 更新说明，`\n` 分隔，会显示在弹窗里 |
| `publishedAt` | 发布日期，仅供人看 |

## 七、两个有意的取舍

1. **只提示、不自动安装**。APK 静默装要「未知来源」权限，exe 自动替换要提权，都容易出事；现在点一下跳下载，用户自己装。
2. **清单是静态 JSON，不调 GitHub API**。API 有 60 次/小时限流，CDN 没有；而且改清单不用发版。

## 八、检查更新失败怎么办

失败**不影响使用**，只是不提示新版本：

- 手机：设置 → 检查更新 会 Toast「检查失败：…」，详细原因进「诊断日志」的 `[UPD]` 行
- 电脑：日志里一行 `[i] 检查更新失败（不影响使用）：所有源都不可用 · …`

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 三条源都 `HTTP 404` | 仓库路径写错（还是 CHANGE_ME）或清单不在 main 根目录 | 核对 `MANIFEST_URLS` |
| `操作超时` | 那个源被墙 | 正常，会自动换下一个 |
| 改了清单但客户端没反应 | jsDelivr 缓存 | 打开 purge 链接刷一次 |
| `HTTP 403` | 直接把 .exe 当下载地址 | 换成 `.zip` |

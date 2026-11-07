# 发布到 GitHub（托管 + 自动检查更新）

这套代码本身就是「可发布」的：仓库里放一份 `update.json`，两个程序启动时去读它，比自己版本新就提示下载。

## 一、仓库长这样

```
AirMouse/                 ← 仓库根目录，就是本目录的内容
├─ app/ …                 ← Android 工程
├─ pc/
│   ├─ AirMouseServer.cs  ← 电脑端源码
│   ├─ AssemblyInfo.cs
│   └─ 使用说明.txt
├─ dist/                  ← 构建产物（可以提交，也可以只放在 Release 里）
│   ├─ AirMouse-2.25.apk
│   └─ AirMouseServer.exe
├─ update.json            ← ★ 版本清单，两个程序都读它
├─ README.md
└─ 快速上手.md
```

`.gitignore` 建议：

```gitignore
build/
.gradle/
.kotlin/
local.properties
.idea/
dist/*.log
*.iml
```

## 二、第一次上传

```bash
cd gyro-mouse
git init
git add .
git commit -m "AirMouse 2.25"
git branch -M main
git remote add origin https://github.com/<你的账号>/AirMouse.git
git push -u origin main
```

## 三、把清单地址改成你的仓库（两处，一次改完）

两端都改成了**多源回退**：按顺序试，第一个能通的就用，所以三处地址都要换成你的仓库。

| 文件 | 常量 |
| --- | --- |
| `app/src/main/java/com/airmouse/gyro/Updater.kt` | `MANIFEST_URLS`（List，三条） |
| `pc/AirMouseServer.cs` | `ManifestUrls`（string[]，三条） |

```kotlin
val MANIFEST_URLS = listOf(
    "https://cdn.jsdelivr.net/gh/<账号>/AirMouse@main/update.json",
    "https://raw.githubusercontent.com/<账号>/AirMouse/main/update.json",
    "https://gitee.com/<账号>/AirMouse/raw/main/update.json"
)
```

```csharp
public static readonly string[] ManifestUrls =
{
    "https://cdn.jsdelivr.net/gh/<账号>/AirMouse@main/update.json",
    "https://raw.githubusercontent.com/<账号>/AirMouse/main/update.json",
    "https://gitee.com/<账号>/AirMouse/raw/main/update.json"
};
```

改完 **APK 要重新构建、EXE 要重新编译**（因为地址是编进去的）。

> 三条的顺序可以按自己网络调整；用不到的那条留着也无害（失败会在日志里留一行 `更新源不可用：host 原因`）。
> 实测（2026-11-07 这台机器）：`raw.githubusercontent.com` **超时**、`cdn.jsdelivr.net` 和 `gitee.com` 都能连上 —— 所以**不要只写 GitHub raw 一条**。
> 每次下载都有 6 秒超时（Kotlin 用 `connectTimeout/readTimeout`，C# 用 `HttpWebRequest.Timeout/ReadWriteTimeout`），某个源卡住不会拖死整个检查。


## 四、发一个新版本（每次就这四步）

1. 改版本号：
   - `app/build.gradle.kts`：`versionCode += 1`、`versionName = "2.26"`
   - `pc/AssemblyInfo.cs`：`AssemblyVersion/AssemblyFileVersion = 2.26.0.0`
2. 构建：
   ```bash
   gradle -p . --offline assembleRelease      # → app/build/outputs/apk/release/app-release.apk
   csc /target:winexe /codepage:65001 /optimize+ ^
       /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:Microsoft.CSharp.dll ^
       /win32icon:pc\airmouse.ico /out:dist\AirMouseServer.exe pc\AirMouseServer.cs pc\AssemblyInfo.cs
   ```
3. 建 Release：tag 用 `v2.26`，上传两个附件，**文件名固定**（清单里就是按这个名字写死 URL 的）：
   - `AirMouse-2.26.apk`
   - `AirMouseServer.exe`
4. 改 `update.json` 并提交到 main：

```json
{
  "versionCode": 37,
  "versionName": "2.26",
  "apk": "https://github.com/<账号>/AirMouse/releases/download/v2.26/AirMouse-2.26.apk",
  "exe": "https://github.com/<账号>/AirMouse/releases/download/v2.26/AirMouseServer.exe",
  "notes": "· 这次改了什么，一行一条",
  "publishedAt": "2026-11-08"
}
```

提交推送后，**已经装过旧版的手机和电脑会自动发现新版本**：

- 手机：设置 → 检查更新（启动时也会静默查一次，24 小时最多一次）
- 电脑：启动时在窗口顶部出现「发现新版本 2.26 → 点这里下载」

## 五、两个设计取舍（都是有意的）

1. **只提示、不自动安装**。APK 自动装需要「未知来源」权限、exe 自动替换要提权，都容易出事；现在是点一下跳到下载页，用户自己装。
2. **清单是静态 JSON，不用 GitHub API**。API 有 60 次/小时的限流，raw/CDN 没有；而且改清单不用发版，改完推上去就生效。

## 六、检查更新失败不影响使用

- 手机：只在设置页里显示「检查失败：…」，日志进诊断日志（`[UPD]` 前缀）
- 电脑：日志里一行 `[i] 检查更新失败（不影响使用）：…`

常见原因：没网、DNS 污染（换 jsDelivr）、公司代理拦 HTTPS。**程序本身照常工作。**

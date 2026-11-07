# 一键发版：改版本号 → 编译 APK/EXE → 放进 releases/vX.Y → 改 update.json → 提交 → 打 tag → 推送 → 刷新 jsDelivr
#
# 用法（在仓库根目录）：
#   .\publish.ps1 -Version 2.26 -Notes "新增：…`n修复：…"
#
# 前置条件：
#   * 已配好 SSH key（ssh -T git@github.com 能看到 Hi <用户名>!）
#   * Gradle / Android SDK 路径与本文一致（见 $Gradle、$Aapt2）
#   * 首次发版前把 $Repo 改成自己的 GitHub 账号/仓库

param(
    [Parameter(Mandatory = $true)][string]$Version,      # 例如 2.26
    [string]$Notes = "",                                  # 更新说明，\n 分隔
    [string]$Repo = "Tang-Yongqiang/AirMouse"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$Gradle = 'C:\Users\Tangyq\.gradle\wrapper\dists\gradle-8.14.3-all\cbf6zifq8xavouihta8md72jo\gradle-8.14.3\bin\gradle.bat'
$Csc    = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$Aapt2  = 'D:\Software\Android_SDK\build-tools\35.0.0\aapt2.exe'

# 1) 版本号：versionCode 自动 +1
$gradleFile = Join-Path $root 'app\build.gradle.kts'
$txt = [IO.File]::ReadAllText($gradleFile, [Text.Encoding]::UTF8)
$m = [regex]::Match($txt, 'versionCode\s*=\s*(\d+)')
if (-not $m.Success) { throw "在 app/build.gradle.kts 里找不到 versionCode" }
$code = [int]$m.Groups[1].Value + 1
$txt = [regex]::Replace($txt, 'versionCode\s*=\s*\d+', "versionCode = $code")
$txt = [regex]::Replace($txt, 'versionName\s*=\s*"[^"]*"', "versionName = `"$Version`"")
[IO.File]::WriteAllText($gradleFile, $txt, (New-Object Text.UTF8Encoding($false)))

$asm = Join-Path $root 'pc\AssemblyInfo.cs'
$a = [IO.File]::ReadAllText($asm, [Text.Encoding]::UTF8)
$a = [regex]::Replace($a, 'AssemblyVersion\("[^"]*"\)', "AssemblyVersion(`"$Version.0.0`")")
$a = [regex]::Replace($a, 'AssemblyFileVersion\("[^"]*"\)', "AssemblyFileVersion(`"$Version.0.0`")")
[IO.File]::WriteAllText($asm, $a, (New-Object Text.UTF8Encoding($false)))

Write-Host "[1/7] 版本号 → $Version (versionCode $code)" -ForegroundColor Cyan

# 2) 编译
& $Gradle -p $root --offline --console=plain assembleRelease | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败" }
$apkSrc = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$apkOut = Join-Path $root "dist\AirMouse-$Version.apk"
Copy-Item $apkSrc $apkOut -Force

$exeOut = Join-Path $root 'dist\AirMouseServer.exe'
Get-Process AirMouseServer -ErrorAction SilentlyContinue | Stop-Process -Force
& $Csc /nologo /target:winexe /codepage:65001 /optimize+ /r:System.Web.Extensions.dll `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:Microsoft.CSharp.dll `
    /win32icon:"$root\pc\airmouse.ico" /out:"$exeOut" `
    "$root\pc\AirMouseServer.cs" "$root\pc\AssemblyInfo.cs"
if ($LASTEXITCODE -ne 0) { throw "csc 编译失败" }
Write-Host "[2/7] 编译完成：APK $([math]::Round((Get-Item $apkOut).Length/1MB,2)) MB / EXE $((Get-Item $exeOut).Length) B" -ForegroundColor Cyan

# 3) 放进 releases/vX.Y，并给 exe 打一个 zip（jsDelivr 不允许 .exe 直链）
$rel = Join-Path $root "releases\v$Version"
New-Item -ItemType Directory -Path $rel -Force | Out-Null
Copy-Item $apkOut (Join-Path $rel "AirMouse-$Version.apk") -Force
Copy-Item $exeOut (Join-Path $rel 'AirMouseServer.exe') -Force
$exeZip = Join-Path $rel "AirMouseServer-v$Version.zip"
Remove-Item $exeZip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $rel 'AirMouseServer.exe') -DestinationPath $exeZip -CompressionLevel Optimal
Write-Host "[3/7] 已放入 releases\v$Version" -ForegroundColor Cyan

# 4) update.json
$manifest = [ordered]@{
    versionCode = $code
    versionName = $Version
    apk = "https://cdn.jsdelivr.net/gh/$Repo@main/releases/v$Version/AirMouse-$Version.apk"
    exe = "https://cdn.jsdelivr.net/gh/$Repo@main/releases/v$Version/AirMouseServer-v$Version.zip"
    notes = $Notes
    publishedAt = (Get-Date -Format 'yyyy-MM-dd')
}
$json = $manifest | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $root 'update.json'), $json + "`n", (New-Object Text.UTF8Encoding($false)))
Write-Host "[4/7] update.json 已更新" -ForegroundColor Cyan

# 5) 提交
& git add -A
& git -c core.quotepath=false commit -q -m "AirMouse $Version" 
Write-Host "[5/7] 已提交" -ForegroundColor Cyan

# 6) 打 tag + 推送
& git tag -f "v$Version"
$env:GIT_SSH_COMMAND = 'ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new'
& git push origin main
& git push origin "v$Version"
if ($LASTEXITCODE -ne 0) { throw "推送失败（检查 SSH key）" }
Write-Host "[6/7] 已推送到 GitHub" -ForegroundColor Cyan

# 7) 刷新 jsDelivr 缓存（清单是 @main，必须刷新）
$purge = "https://purge.jsdelivr.net/gh/$Repo@main/update.json"
try { $r = Invoke-WebRequest -Uri $purge -TimeoutSec 40 -UseBasicParsing; Write-Host "[7/7] jsDelivr 已刷新（$($r.StatusCode)）" -ForegroundColor Cyan }
catch { Write-Warning "jsDelivr 刷新失败，稍后手动打开：$purge" }

Write-Host ""
Write-Host "完成。下载地址：" -ForegroundColor Green
Write-Host "  APK : https://cdn.jsdelivr.net/gh/$Repo@main/releases/v$Version/AirMouse-$Version.apk"
Write-Host "  EXE : https://cdn.jsdelivr.net/gh/$Repo@main/releases/v$Version/AirMouseServer-v$Version.zip"
Write-Host "  清单: https://cdn.jsdelivr.net/gh/$Repo@main/update.json"
Write-Host "  仓库: https://github.com/$Repo"

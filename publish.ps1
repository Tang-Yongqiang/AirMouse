# 一键发版：改版本号 → 编译 APK/EXE → 放进 releases/vX.Y → 改 update.json → 提交 → 打 tag → 推送 → 刷新 jsDelivr
#
# 用法（在仓库根目录）：
#   .\publish.ps1 -AppVersion 1.1.0 -Notes "新增：…`n修复：…"
#   .\publish.ps1 -AppVersion 1.1.0 -ServerVersion 1.1.0 -Notes "..."   # 同时升电脑端
#   .\publish.ps1 -AppVersion 1.1.1                                      # 只升手机端，电脑端沿用现值
#
# 说明：手机端与电脑端版本号各自独立；update.json 里 apk / server 两段分开写。
#       协议版本 proto 只在「手机端与电脑端的指令集不兼容」时才加一。

param(
    [Parameter(Mandatory = $true)][string]$AppVersion,   # 手机端版本，例如 1.1.0
    [string]$ServerVersion = "",                          # 电脑端版本；留空=沿用 pc\AssemblyInfo.cs 里的现值
    [string]$Notes = "",                                  # 更新说明，\n 分隔
    [string]$NotesFile = "",                              # 更新说明写在这个 UTF-8 文件里（中文更保险，避免命令行编码问题）
    [string]$Repo = "Tang-Yongqiang/AirMouse",
    [int]$Proto = 1                                       # 协议版本，指令集变了才改
)

# 中文更新说明走命令行时，某些 shell 会把 UTF-8 当成 GBK 传进来（写进 update.json 就成了「鎵嬫満绔?」）。
# 所以支持 -NotesFile：直接读一个 UTF-8 文本文件，绕开命令行编码。
if ($NotesFile) {
    $nf = (Resolve-Path $NotesFile).Path
    $Notes = ([IO.File]::ReadAllText($nf, [Text.Encoding]::UTF8)).Trim()
}

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$Gradle = 'C:\Users\Tangyq\.gradle\wrapper\dists\gradle-8.14.3-all\cbf6zifq8xavouihta8md72jo\gradle-8.14.3\bin\gradle.bat'
$Csc    = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$Aapt2  = 'D:\Software\Android_SDK\build-tools\35.0.0\aapt2.exe'

function Norm3([string]$v) {
    # 把 1.1.0.0 / 1.1 统一成 1.1.0（三段）
    $n = @($v.Split('.') | Where-Object { $_ -ne '' })
    while ($n.Count -gt 3) { $n = $n[0..($n.Count - 2)] }
    while ($n.Count -gt 2 -and $n[$n.Count - 1] -eq '0') { $n = $n[0..($n.Count - 2)] }
    while ($n.Count -lt 3) { $n += '0' }
    return ($n -join '.')
}

function Pad4([string]$v) {
    if (-not $v) { return "" }
    $n = $v.Split('.')
    if ($n.Count -lt 2 -or $n.Count -gt 4) { throw "版本号要写成 1.0.0 这种 2~4 段：$v" }
    $x = $v
    while (($x.Split('.')).Count -lt 4) { $x = $x + ".0" }
    return $x
}

# 1) 版本号：手机端 versionCode 自动 +1；电脑端版本号按需更新
$gradleFile = Join-Path $root 'app\build.gradle.kts'
$txt = [IO.File]::ReadAllText($gradleFile, [Text.Encoding]::UTF8)
$m = [regex]::Match($txt, 'versionCode\s*=\s*(\d+)')
if (-not $m.Success) { throw "在 app/build.gradle.kts 里找不到 versionCode" }
$code = [int]$m.Groups[1].Value + 1
$txt = [regex]::Replace($txt, 'versionCode\s*=\s*\d+', "versionCode = $code")
$txt = [regex]::Replace($txt, 'versionName\s*=\s*"[^"]*"', "versionName = `"$AppVersion`"")
[IO.File]::WriteAllText($gradleFile, $txt, (New-Object Text.UTF8Encoding($false)))

$asm = Join-Path $root 'pc\AssemblyInfo.cs'
$a = [IO.File]::ReadAllText($asm, [Text.Encoding]::UTF8)
if ([string]::IsNullOrWhiteSpace($ServerVersion)) {
    $mm = [regex]::Match($a, 'AssemblyFileVersion\("([^"]*)"\)')
    if (-not $mm.Success) { throw "读不到 pc\AssemblyInfo.cs 里的 AssemblyFileVersion" }
    $ServerVersion = $mm.Groups[1].Value
}
$ServerVersion = Norm3 $ServerVersion
$asmVer = Pad4 $ServerVersion
$a = [regex]::Replace($a, 'AssemblyVersion\("[^"]*"\)', "AssemblyVersion(`"$asmVer`")")
$a = [regex]::Replace($a, 'AssemblyFileVersion\("[^"]*"\)', "AssemblyFileVersion(`"$asmVer`")")
[IO.File]::WriteAllText($asm, $a, (New-Object Text.UTF8Encoding($false)))

Write-Host "[1/7] 手机端 $AppVersion (versionCode $code) / 电脑端 $ServerVersion (proto $Proto)" -ForegroundColor Cyan

# 2) 编译
& $Gradle -p $root --offline --console=plain assembleRelease | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败" }
$apkSrc = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$apkOut = Join-Path $root "dist\AirMouse-$AppVersion.apk"
Copy-Item $apkSrc $apkOut -Force

$exeOut = Join-Path $root 'dist\AirMouseServer.exe'
$hookOut = Join-Path $root 'dist\AirMouseHook.dll'
Get-Process AirMouseServer -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Start-Sleep -Milliseconds 400
& $Csc /nologo /target:library /optimize+ /out:"$hookOut" "$root\pc\InjectorChild.cs"
if ($LASTEXITCODE -ne 0) { throw "csc 编译 AirMouseHook.dll 失败" }
$exeTmp = Join-Path $root 'dist\_server_new.exe'
Remove-Item $exeTmp -Force -ErrorAction SilentlyContinue
& $Csc /nologo /target:winexe /codepage:65001 /optimize+ /r:System.Web.Extensions.dll `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:Microsoft.CSharp.dll /r:System.IO.Compression.FileSystem.dll `
    /win32icon:"$root\pc\airmouse.ico" /win32manifest:"$root\pc\app.manifest" /out:"$exeTmp" `
    "$root\pc\AirMouseServer.cs" "$root\pc\InjectorHost.cs" "$root\pc\AssemblyInfo.cs"
if ($LASTEXITCODE -ne 0) { throw "csc 编译失败" }
$copied = $false
for ($i = 1; $i -le 15; $i++) {
    try { Copy-Item $exeTmp $exeOut -Force -ErrorAction Stop; $copied = $true; break } catch { Start-Sleep -Milliseconds 300 }
}
if (-not $copied) { Write-Host "[!] 接收端正在运行，沿用现有的 dist\AirMouseServer.exe（本次 exe 代码没变，可以继续发版）" -ForegroundColor Yellow }
Remove-Item $exeTmp -Force -ErrorAction SilentlyContinue

# 卸载器 + 安装器（安装器把 server / hook / 卸载器 三个都内嵌进去）
$uninOut = Join-Path $root 'dist\uninstall.exe'
$setupOut = Join-Path $root 'dist\AirMouseSetup.exe'
& $Csc /nologo /target:winexe /codepage:65001 /optimize+ /define:UNINSTALL_ONLY `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll `
    /win32icon:"$root\pc\airmouse.ico" /win32manifest:"$root\pc\app.manifest" `
    /out:"$uninOut" "$root\pc\Setup.cs"
if ($LASTEXITCODE -ne 0) { throw "csc 编译卸载器失败" }
& $Csc /nologo /target:winexe /codepage:65001 /optimize+ `
    /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll `
    /win32icon:"$root\pc\airmouse.ico" /win32manifest:"$root\pc\app.manifest" `
    /resource:"$exeOut",payload_server /resource:"$hookOut",payload_hook /resource:"$uninOut",payload_uninstall `
    /out:"$setupOut" "$root\pc\Setup.cs"
if ($LASTEXITCODE -ne 0) { throw "csc 编译安装器失败" }
Write-Host "[2/7] 编译完成：APK $([math]::Round((Get-Item $apkOut).Length/1MB,2)) MB / EXE $((Get-Item $exeOut).Length) B / Setup $((Get-Item $setupOut).Length) B" -ForegroundColor Cyan

# 3) 两端各自一个目录：releases\app-v<手机端> / releases\server-v<电脑端>
$appRel = Join-Path $root "releases\app-v$AppVersion"
$srvRel = Join-Path $root "releases\server-v$ServerVersion"
New-Item -ItemType Directory -Path $appRel -Force | Out-Null
New-Item -ItemType Directory -Path $srvRel -Force | Out-Null
Copy-Item $apkOut (Join-Path $appRel "AirMouse-$AppVersion.apk") -Force
Copy-Item $exeOut (Join-Path $srvRel 'AirMouseServer.exe') -Force
Copy-Item $hookOut (Join-Path $srvRel 'AirMouseHook.dll') -Force
Copy-Item $setupOut (Join-Path $srvRel 'AirMouseSetup.exe') -Force
Copy-Item $uninOut (Join-Path $srvRel 'uninstall.exe') -Force
$exeZip = Join-Path $srvRel "AirMouseServer-v$ServerVersion.zip"
Remove-Item $exeZip -Force -ErrorAction SilentlyContinue
$readmeSrc = Join-Path $root 'pc\使用说明（先看这个）.txt'
Compress-Archive -Path $setupOut, $readmeSrc -DestinationPath $exeZip -CompressionLevel Optimal
Copy-Item $readmeSrc (Join-Path $srvRel '使用说明（先看这个）.txt') -Force

# 给人发的一整包（放在 dist\，不进仓库，避免撑爆 jsDelivr 的 50 MB 上限）
$sendZip = Join-Path $root "dist\AirMouse-$AppVersion-发送包.zip"
Remove-Item $sendZip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path $apkOut, $setupOut, $readmeSrc -DestinationPath $sendZip -CompressionLevel Optimal
Write-Host "[3/7] APK → releases\app-v$AppVersion；电脑端 → releases\server-v$ServerVersion" -ForegroundColor Cyan
Write-Host "      要发给别人的一个包：dist\AirMouse-$AppVersion-发送包.zip（$([math]::Round((Get-Item $sendZip).Length/1MB,2)) MB）" -ForegroundColor Cyan

# 4) update.json：apk / server 两段分开写
$manifest = [ordered]@{
    proto = $Proto
    apk = [ordered]@{
        versionCode = $code
        versionName = $AppVersion
        url = "https://cdn.jsdelivr.net/gh/$Repo@main/releases/app-v$AppVersion/AirMouse-$AppVersion.apk"
    }
    server = [ordered]@{
        version = $ServerVersion
        url = "https://cdn.jsdelivr.net/gh/$Repo@main/releases/server-v$ServerVersion/AirMouseServer-v$ServerVersion.zip"
    }
    notes = $Notes
    publishedAt = (Get-Date -Format 'yyyy-MM-dd')
}
$json = $manifest | ConvertTo-Json -Depth 6
# 剪枝：手机端/电脑端各只留最近 3 个目录（jsDelivr 对超过 50 MB 的仓库直接拒绝服务）
# 注意：不能按字符串降序排 —— "app-v1.1.9" 会排在 "app-v1.1.11" 前面，把刚发的那版删掉。
$relRoot = Join-Path $root 'releases'
$keep = @()
foreach ($prefix in @('app-v', 'server-v')) {
    $keep += @(Get-ChildItem $relRoot -Directory | Where-Object { $_.Name -like "$prefix*" } |
        Sort-Object { [version]($_.Name.Substring($prefix.Length)) } -Descending |
        Select-Object -First 3 | ForEach-Object { $_.Name })
}
Get-ChildItem $relRoot -Directory | Where-Object { $keep -notcontains $_.Name } | ForEach-Object {
    Write-Host "[3b] 清理旧版本目录 $($_.Name)" -ForegroundColor DarkGray
    # --ignore-unmatch：还没被 git 跟踪的目录直接删就行，不然 git rm 会 fatal
    & git -C $root rm -r --quiet --ignore-unmatch "releases/$($_.Name)"
    Remove-Item (Join-Path $relRoot $_.Name) -Recurse -Force -ErrorAction SilentlyContinue
}
[IO.File]::WriteAllText((Join-Path $root 'update.json'), $json + "`n", (New-Object Text.UTF8Encoding($false)))
Write-Host "[4/7] update.json 已更新" -ForegroundColor Cyan

# 5) 提交
& git add -A
& git -c core.quotepath=false commit -q -m "AirMouse app $AppVersion / server $ServerVersion"
Write-Host "[5/7] 已提交" -ForegroundColor Cyan

# 6) 打 tag + 推送
& git tag -f "v$AppVersion"
$env:GIT_SSH_COMMAND = 'ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new'
& git push origin main
& git push origin "v$AppVersion"
if ($LASTEXITCODE -ne 0) { throw "推送失败（检查 SSH key）" }
Write-Host "[6/7] 已推送到 GitHub" -ForegroundColor Cyan

# 7) 刷新 jsDelivr 缓存
$purge = "https://purge.jsdelivr.net/gh/$Repo@main/update.json"
try { $r = Invoke-WebRequest -Uri $purge -TimeoutSec 40 -UseBasicParsing; Write-Host "[7/7] jsDelivr 已刷新（$($r.StatusCode)）" -ForegroundColor Cyan }
catch { Write-Warning "jsDelivr 刷新失败，稍后手动打开：$purge" }

Write-Host ""
Write-Host "完成。下载地址：" -ForegroundColor Green
Write-Host "  APK : https://cdn.jsdelivr.net/gh/$Repo@main/releases/app-v$AppVersion/AirMouse-$AppVersion.apk"
Write-Host "  EXE : https://cdn.jsdelivr.net/gh/$Repo@main/releases/server-v$ServerVersion/AirMouseServer-v$ServerVersion.zip"
Write-Host "  发人: dist\AirMouse-$AppVersion-发送包.zip"
Write-Host "  清单: https://cdn.jsdelivr.net/gh/$Repo@main/update.json"
Write-Host "  仓库: https://github.com/$Repo"
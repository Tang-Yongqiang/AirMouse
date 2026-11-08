# 更新记录

> 技术记录，给开发/维护看。普通用户看到的说明在 `update.json` 的 `notes` 里（发版时用 `publish.ps1 -TechNotesFile` 分开写）。

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

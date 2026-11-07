#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AirMouse PC 端接收程序（Windows）

手机 APK 的「网络模式」会通过 TCP 发送「一行一个 JSON」的指令，
本程序把它们翻译成 SendInput 调用，所以手机就变成了电脑的鼠标 + 键盘，
并且中文也能打出来（KEYEVENTF_UNICODE 直接送字符，绕开输入法）。

用法：
    python airmouse_server.py                # 监听 0.0.0.0:47777
    python airmouse_server.py --port 47777
    python airmouse_server.py --dry-run      # 只打印，不真的操作鼠标（调试用）

协议：
    {"t":"m","dx":1,"dy":-2}          相对移动
    {"t":"b","b":1,"d":true}          鼠标键 1左 2右 3中
    {"t":"w","v":1}                   滚轮，正数向上
    {"t":"k","code":40,"mods":0}      HID usage 按键
    {"t":"s","text":"你好"}           整段文字
"""

import argparse
import ctypes
import json
import os
import socket
import sys
import threading
from ctypes import wintypes


def _ensure_streams():
    """控制台里正常输出；被 pythonw / 计划任务拉起时（没有控制台）改成写日志文件。"""
    log_path = None
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        log_dir = os.path.join(base, "AirMouse")
        try:
            os.makedirs(log_dir, exist_ok=True)
            log_path = os.path.join(log_dir, "server.log")
        except Exception:
            log_path = None

    def _fix(stream, name):
        if stream is None:
            if not log_path:
                return open(os.devnull, "w", encoding="utf-8")
            return open(log_path, "a", encoding="utf-8", buffering=1)
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
        return stream

    sys.stdout = _fix(sys.stdout, "stdout")
    sys.stderr = _fix(sys.stderr, "stderr")
    return log_path


LOG_PATH = _ensure_streams()


# ---------------------------------------------------------------- Win32 结构

ULONG_PTR = ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_ulong

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
# 绝对定位（射线指向模式）：坐标要按 0..65535 归一化到整个虚拟桌面
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

WHEEL_DELTA = 120


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


user32 = ctypes.WinDLL("user32", use_last_error=True)
user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT

# ---------------------------------------------------------------- HID -> VK

VK_LCONTROL = 0xA2
VK_LSHIFT = 0xA0
VK_LMENU = 0xA4
VK_LWIN = 0x5B

MOD_MAP = [(0x01, VK_LCONTROL), (0x02, VK_LSHIFT), (0x04, VK_LMENU), (0x08, VK_LWIN)]

HID_TO_VK = {}

for i in range(26):
    HID_TO_VK[0x04 + i] = 0x41 + i           # a-z
for i, vk in enumerate([0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x30]):
    HID_TO_VK[0x1E + i] = vk                 # 1-9,0

HID_TO_VK.update({
    0x28: 0x0D,   # Enter
    0x29: 0x1B,   # Esc
    0x2A: 0x08,   # Backspace
    0x2B: 0x09,   # Tab
    0x2C: 0x20,   # Space
    0x2D: 0xBD,   # -
    0x2E: 0xBB,   # =
    0x2F: 0xDB,   # [
    0x30: 0xDD,   # ]
    0x31: 0xDC,   # backslash
    0x33: 0xBA,   # ;
    0x34: 0xDE,   # '
    0x35: 0xC0,   # `
    0x36: 0xBC,   # ,
    0x37: 0xBE,   # .
    0x38: 0xBF,   # /
    0x39: 0x14,   # CapsLock
    0x4A: 0x24,   # Home
    0x4B: 0x21,   # PageUp
    0x4C: 0x2E,   # Delete
    0x4D: 0x23,   # End
    0x4E: 0x22,   # PageDown
    0x4F: 0x27,   # Right
    0x50: 0x25,   # Left
    0x51: 0x28,   # Down
    0x52: 0x26,   # Up
})
for i in range(12):
    HID_TO_VK[0x3A + i] = 0x70 + i           # F1-F12


def _send(inp: INPUT):
    n = user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))
    if n == 0:
        err = ctypes.get_last_error()
        print(f"  ! SendInput 失败，错误码 {err}", file=sys.stderr)


def _mouse(flags: int, dx: int = 0, dy: int = 0, data: int = 0):
    inp = INPUT(type=INPUT_MOUSE)
    inp.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    _send(inp)


def _key_vk(vk: int, up: bool):
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.ki = KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, 0)
    _send(inp)


def _key_unicode(unit: int, up: bool):
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.ki = KEYBDINPUT(0, unit, KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0), 0, 0)
    _send(inp)


# ---------------------------------------------------------------- 执行指令

class Injector:
    def __init__(self, dry_run=False):
        self.dry_run = dry_run
        self.left_down = False
        self.right_down = False
        self.middle_down = False

    def move(self, dx, dy):
        if self.dry_run:
            print(f"  move {dx:+d} {dy:+d}")
        else:
            _mouse(MOUSEEVENTF_MOVE, dx, dy)

    def pointer_abs(self, u, v):
        """u/v 是 0..1 的归一化屏幕坐标（射线指向模式）。"""
        u = min(max(float(u), 0.0), 1.0)
        v = min(max(float(v), 0.0), 1.0)
        if self.dry_run:
            print(f"  abs {u:.3f} {v:.3f}")
        else:
            # 用虚拟桌面范围，多显示器时也能指到副屏
            x = int(round(u * 65535))
            y = int(round(v * 65535))
            _mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, x, y)

    def wheel(self, notches):
        if self.dry_run:
            print(f"  wheel {notches:+d}")
        else:
            _mouse(MOUSEEVENTF_WHEEL, 0, 0, notches * WHEEL_DELTA)

    def button(self, b, down):
        if self.dry_run:
            print(f"  button {b} {'down' if down else 'up'}")
            return
        if b == 2:
            _mouse(MOUSEEVENTF_RIGHTDOWN if down else MOUSEEVENTF_RIGHTUP)
        elif b == 3:
            _mouse(MOUSEEVENTF_MIDDLEDOWN if down else MOUSEEVENTF_MIDDLEUP)
        else:
            _mouse(MOUSEEVENTF_LEFTDOWN if down else MOUSEEVENTF_LEFTUP)

    def key(self, usage, mods):
        vk = HID_TO_VK.get(usage)
        if self.dry_run:
            print(f"  key usage=0x{usage:02X} mods=0x{mods:02X} vk={vk}")
            return
        if vk is None:
            return
        down_mods = [vk_mod for bit, vk_mod in MOD_MAP if mods & bit]
        for m in down_mods:
            _key_vk(m, False)
        _key_vk(vk, False)
        _key_vk(vk, True)
        for m in reversed(down_mods):
            _key_vk(m, True)

    def text(self, s):
        # 无论 dry-run 还是真发，都在日志里留一行，方便排查「到底有没有收到」
        print(f"  text {s!r}")
        if self.dry_run:
            return
        for ch in s:
            if ch == "\n" or ch == "\r":
                _key_vk(0x0D, False)
                _key_vk(0x0D, True)
                continue
            if ch == "\t":
                _key_vk(0x09, False)
                _key_vk(0x09, True)
                continue
            # UTF-16 编码单元逐个发送，中文/emoji 都能出去
            data = ch.encode("utf-16-le")
            for i in range(0, len(data), 2):
                unit = int.from_bytes(data[i:i + 2], "little")
                _key_unicode(unit, False)
                _key_unicode(unit, True)


def handle_line(line: str, inj: Injector):
    line = line.strip()
    if not line:
        return
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        print(f"  ? 不是 JSON: {line[:80]}")
        return
    t = msg.get("t")
    if t == "m":
        inj.move(int(msg.get("dx", 0)), int(msg.get("dy", 0)))
    elif t == "a":
        # 射线指向：绝对坐标
        inj.pointer_abs(float(msg.get("u", 0.5)), float(msg.get("v", 0.5)))
    elif t == "b":
        inj.button(int(msg.get("b", 1)), bool(msg.get("d", False)))
    elif t == "w":
        inj.wheel(int(msg.get("v", 0)))
    elif t == "k":
        inj.key(int(msg.get("code", 0)), int(msg.get("mods", 0)))
    elif t == "s":
        inj.text(str(msg.get("text", "")))
    else:
        print(f"  ? 未知指令: {t}")


def serve_client(conn, addr, inj: Injector):
    print(f"[+] 手机已连接 {addr[0]}:{addr[1]}")
    buf = b""
    try:
        conn.settimeout(60)
        while True:
            data = conn.recv(8192)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                handle_line(line.decode("utf-8", "replace"), inj)
    except socket.timeout:
        print("[-] 超时（手机可能断开了）")
    except ConnectionResetError:
        pass
    except Exception as e:
        print(f"[-] 连接异常: {e}")
    finally:
        try:
            conn.close()
        except Exception:
            pass
        print(f"[-] 手机已断开 {addr[0]}")


def local_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    if not ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
            s.close()
        except Exception:
            pass
    return ips


def find_adb():
    """找 adb：环境变量 > 常见安装位置 > PATH。找不到就返回 None（USB 模式不可用，WiFi 不受影响）。"""
    cands = []
    env = os.environ.get("AIRMOUSE_ADB")
    if env:
        cands.append(env)
    cands += [
        r"D:\Software\platform-tools-latest-windows\platform-tools\adb.exe",
        os.path.expanduser(r"~\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\platform-tools\adb.exe",
    ]
    for c in cands:
        if c and os.path.isfile(c):
            return c
    from shutil import which
    return which("adb")


def adb_reverse_watch(adb_path: str, port: int, stop: threading.Event):
    """
    插上数据线后自动执行 adb reverse tcp:PORT tcp:PORT。
    这样手机在「没有 WiFi、只有数据线」时也能连 127.0.0.1:PORT 控制电脑，
    不需要用户自己敲 adb 命令。每 3 秒扫一次，新增设备才补一条。
    """
    import subprocess

    known = set()
    while not stop.is_set():
        try:
            out = subprocess.run(
                [adb_path, "devices"], capture_output=True, text=True, timeout=5,
                creationflags=subprocess.CREATE_NO_WINDOW,
            ).stdout
            serials = []
            for line in out.splitlines()[1:]:
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    serials.append(parts[0])
            for s in serials:
                if s in known:
                    continue
                r = subprocess.run(
                    [adb_path, "-s", s, "reverse", f"tcp:{port}", f"tcp:{port}"],
                    capture_output=True, text=True, timeout=5,
                    creationflags=subprocess.CREATE_NO_WINDOW,
                )
                if r.returncode == 0 and r.stdout.strip():
                    print(f"[+] USB 通道已就绪 {s} -> 127.0.0.1:{port}")
                    known.add(s)
                else:
                    # 没授权 / 还没连好，下轮再试
                    pass
            # 掉线的设备清掉，重插时能重新建
            for s in list(known):
                if s not in serials:
                    known.discard(s)
        except Exception:
            pass
        stop.wait(3)


def main():
    ap = argparse.ArgumentParser(description="AirMouse PC 接收端")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=47777)
    ap.add_argument("--dry-run", action="store_true", help="只打印指令，不真的注入输入")
    ap.add_argument(
        "--no-adb",
        action="store_true",
        help="不自动配置 USB 通道（默认会在插入数据线后自动执行 adb reverse）",
    )
    args = ap.parse_args()

    inj = Injector(args.dry_run)

    ips = local_ips()
    print("=" * 58)
    print(" AirMouse PC 接收端")
    print("=" * 58)
    print(f" 监听端口 : {args.port}")
    print(" 在手机上填写这个 IP：")
    for ip in ips:
        print(f"    {ip}")
    print(" 手机端：设置 → 网络模式 → 填 IP → 连接")
    print(f" USB 直连：插上数据线即可（自动执行 adb reverse tcp:{args.port} tcp:{args.port}）")
    print(f"           手机端连不上原 IP 时会自动退回 127.0.0.1:{args.port}")
    if args.dry_run:
        print(" [dry-run] 不会真的操作鼠标键盘")
    print(" Ctrl+C 退出")
    if LOG_PATH:
        print(f" 日志文件 : {LOG_PATH}")
    print("=" * 58)

    # 自动维护 USB 通道（插线即用）
    if not args.no_adb:
        adb_path = find_adb()
        if adb_path:
            stop_evt = threading.Event()
            threading.Thread(
                target=adb_reverse_watch, args=(adb_path, args.port, stop_evt), daemon=True
            ).start()
            print(f" [USB] 已启动监控：{adb_path}")
        else:
            print(" [USB] 没找到 adb，USB 直连不可用（WiFi 不受影响）")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((args.host, args.port))
        srv.listen(4)
    except OSError as e:
        print(f" [!] 端口 {args.port} 绑不上：{e}")
        print("     可能已经有一个接收端在跑了；换端口用 --port 47778")
        srv.close()
        return
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve_client, args=(conn, addr, inj), daemon=True).start()
    except KeyboardInterrupt:
        print("\n退出")
    finally:
        srv.close()


if __name__ == "__main__":
    main()

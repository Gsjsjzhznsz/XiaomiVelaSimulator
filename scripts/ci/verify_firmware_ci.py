#!/usr/bin/env python3
"""
v0.3.1 固件 CI 端到端验证（GitHub Actions runner 上运行）。

复刻 App QemuArgsBuilder 的参数形态启动 QEMU，验证三件事：
  1) 分辨率注入：-device virtio-gpu-device,xres=,yres= → guest virtio-gpu
     驱动经 GET_DISPLAY_INFO 跟随宿主 → VNC 初始帧缓冲必须等于注入值
     （v0.3.0 圆表变椭圆的根因即未注入 → 落回 QEMU 默认 1024x768）；
     注入宽 = 模板宽向上对齐到 32 倍数（QEMU VNC 服务器按脏矩形粒度
     VNC_DIRTY_PIXELS_PER_BIT=32 上报宽度，高度原样上报，不填充）；
  2) 画面渲染：LVGL widgets demo 输出非黑帧；
  3) 触摸链路：裸 RFB 点击（按下态抓帧）→ 帧差或串口 indev 日志任一命中
     即 PASS（v0.3.1 固件触摸缩放改为动态读 fb0 分辨率）。

用法: python3 verify_firmware_ci.py --kernel nuttx.elf --xres 466 --yres 466
退出码: 0 = 全部 PASS；非 0 = 失败（原因见日志）。
"""
import argparse, socket, struct, subprocess, sys, time, os

def log(m): print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)

ap = argparse.ArgumentParser()
ap.add_argument("--kernel", required=True)
ap.add_argument("--xres", type=int, default=466)
ap.add_argument("--yres", type=int, default=466)
ap.add_argument("--serial", type=int, default=4444)
ap.add_argument("--vncport", type=int, default=5905)
args = ap.parse_args()

SER, VNCPORT = args.serial, args.vncport
LOG = "qemu-ci.log"

# ---------- 1) 启动 QEMU（与 App QemuArgsBuilder 同参形态） ----------
cmd = ["qemu-system-arm", "-M", "virt", "-cpu", "cortex-a7", "-smp", "1",
       "-m", "512M", "-kernel", os.path.abspath(args.kernel),
       "-device", f"virtio-gpu-device,xres={args.xres},yres={args.yres}",
       "-device", "virtio-tablet-device", "-nic", "none",
       "-display", "none", "-monitor", "none",
       "-serial", f"tcp:127.0.0.1:{SER},server",
       "-vnc", f"127.0.0.1:{VNCPORT - 5900}"]
log("QEMU 启动: " + " ".join(cmd))
with open(LOG, "w") as lf:
    proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT)
time.sleep(1.0)
if proc.poll() is not None:
    log("QEMU 启动即退出!"); print(open(LOG).read()[-2000:]); sys.exit(2)

import atexit
def cleanup(*_):
    try: proc.terminate(); proc.wait(5)
    except Exception:
        try: proc.kill()
        except Exception: pass
atexit.register(cleanup)

# ---------- 2) 串口：连接解除 wait 阻塞 → nsh> → lvgldemo ----------
ser = None
for _ in range(40):
    try:
        ser = socket.create_connection(("127.0.0.1", SER), timeout=5); break
    except OSError:
        time.sleep(0.5)
if ser is None:
    log("串口连接失败"); print(open(LOG).read()[-2000:]); sys.exit(3)
ser.settimeout(1)
boot = b""; end = time.time() + 40
while time.time() < end:
    try:
        boot += ser.recv(4096)
    except socket.timeout:
        if b"nsh>" in boot: break
    except Exception: break
log(f"NSH 就绪: {b'nsh>' in boot}, 引导输出 {len(boot)}B")
if b"nsh>" not in boot:
    print(open(LOG).read()[-1500:]); sys.exit(6)
ser.sendall(b"lvgldemo\r\n")
time.sleep(3)

# ---------- 3) 裸 RFB 客户端 ----------
class VncFail(Exception): pass

class Vnc:
    def __init__(self):
        self.s = socket.create_connection(("127.0.0.1", VNCPORT), timeout=15)
        self.s.settimeout(15)
        self.w = self.h = 0
        self._handshake()
    def _rd(self, n):
        out = b""
        while len(out) < n:
            c = self.s.recv(n - len(out))
            if not c: raise EOFError("closed")
            out += c
        return out
    def _handshake(self):
        self._rd(12); self.s.sendall(b"RFB 003.008\n")
        n = self._rd(1)[0]; self._rd(n)               # security types
        self.s.sendall(b"\x01"); self._rd(4)          # None + ok
        self.s.sendall(b"\x01")                        # share
        si = self._rd(24)
        self.w, self.h = struct.unpack(">HH", si[:4])
        nl = struct.unpack(">I", si[20:24])[0]; self._rd(nl)
        pf = bytearray(16)
        pf[0] = 32; pf[1] = 32; pf[3] = 1
        pf[4:6] = struct.pack(">H", 255); pf[6:8] = struct.pack(">H", 255); pf[8:10] = struct.pack(">H", 255)
        pf[10] = 16; pf[11] = 8; pf[12] = 0
        self.s.sendall(b"\x00" + b"\x00\x00\x00" + bytes(pf))  # 20B: type+3pad+pf
        self.s.sendall(b"\x02\x00" + struct.pack(">H", 1) + struct.pack(">i", 0))
    def pointer(self, x, y, pressed):
        self.s.sendall(b"\x05" + (b"\x01" if pressed else b"\x00") + struct.pack(">HH", x, y))
    def frame(self, max_wait=15):
        """请求整帧并重组; 返回 (rgba_bytes, w, h); 自动处理 resize/LED 消息"""
        self.s.sendall(b"\x03\x00" + struct.pack(">HHHH", 0, 0, self.w, self.h))
        px = bytearray(self.w * self.h * 4)
        deadline = time.time() + max_wait
        rects = 0
        while time.time() < deadline:
            try:
                mt = self._rd(1)[0]
            except (socket.timeout, EOFError):
                break
            if mt == 0x51: self._rd(2); continue        # QEMU LED 扩展
            if mt == 2: continue                        # bell
            if mt != 0: continue
            self._rd(1); nr = struct.unpack(">H", self._rd(2))[0]
            for _ in range(nr):
                rx, ry, rw, rh = struct.unpack(">HHHH", self._rd(8))
                enc = struct.unpack(">i", self._rd(4))[0]
                if enc == -223:                          # desktop resize
                    self.w, self.h = rw, rh
                    px = bytearray(self.w * self.h * 4); continue
                data = self._rd(rw * rh * 4)
                rects += 1
                for yy in range(rh):
                    dy = ry + yy
                    if dy >= self.h: continue
                    off = (dy * self.w + rx) * 4
                    seg = data[yy * rw * 4:(yy + 1) * rw * 4]
                    o = bytearray(len(seg))
                    o[0::4] = seg[2::4]; o[1::4] = seg[1::4]
                    o[2::4] = seg[0::4]; o[3::4] = b"\xff" * len(o[3::4])
                    px[off:off + len(seg)] = o
            if rects > 0: break
        if rects == 0: raise VncFail("未收到帧")
        return px, self.w, self.h
    def close(self): self.s.close()

def nonblack(px):
    return sum(1 for i in range(0, len(px), 400) if px[i] or px[i + 1] or px[i + 2])

def save_png(px, w, h, name):
    try:
        from PIL import Image
        Image.frombytes("RGBA", (w, h), bytes(px), "raw", "BGRA").convert("RGB").save(name)
        log(f"截图: {name} ({w}x{h})")
    except Exception as e:
        log(f"截图失败(忽略): {e}")

# ---------- 4) 显示验证：初始尺寸 == 注入分辨率 + 非黑帧 ----------
frameA = None; fb_w = fb_h = 0
for attempt in range(6):
    try:
        v = Vnc()
        log(f"VNC 握手初始帧缓冲: {v.w}x{v.h} (期望 {(args.xres + 31) // 32 * 32}x{args.yres})")
        px, fb_w, fb_h = v.frame()
        v.close()
        nb = nonblack(px)
        log(f"帧A #{attempt}: {fb_w}x{fb_h} 非黑采样 {nb}/{len(px)//400}")
        if nb > 60:
            frameA = px
            save_png(px, fb_w, fb_h, "e2e-frameA.png")
            break
    except (VncFail, EOFError, OSError, socket.timeout) as e:
        log(f"  断连({type(e).__name__}), 2s 重试"); time.sleep(2)
ok_size = (fb_w == (args.xres + 31) // 32 * 32 and fb_h == args.yres)
if frameA is None:
    log("显示验证失败: 无非黑帧"); print(open(LOG).read()[-1500:]); sys.exit(4)
if not ok_size:
    log(f"FAIL: 帧缓冲 {fb_w}x{fb_h} != 注入 {(args.xres + 31) // 32 * 32}x{args.yres} —— 分辨率注入未生效(椭圆根因)")
    sys.exit(5)
log(">>> 分辨率注入 PASS")
log(">>> 画面渲染 PASS")

# ---------- 5) 触摸验证：按下态抓帧 + 帧差 / 串口 indev 日志 ----------
def diffcount(a, b):
    return sum(1 for i in range(0, min(len(a), len(b)), 4) if a[i:i+3] != b[i:i+3])

cx, cy = fb_w // 2, fb_h // 2
probes = [(cx, cy), (cx, fb_h // 4), (cx, fb_h * 3 // 4),
          (fb_w // 4, 30), (fb_w * 3 // 4, 30)]
touch_diff = 0
for (x, y) in probes:
    try:
        v = Vnc()
        v.pointer(x, y, True)
        time.sleep(0.45)                       # LVGL 渲染按下态
        pxb, _, _ = v.frame(max_wait=8)
        v.pointer(x, y, False)
        v.close()
        d = diffcount(frameA, pxb)
        log(f"按下({x},{y}) → 帧差 {d} 像素")
        if d > 800:
            save_png(pxb, fb_w, fb_h, "e2e-frameB.png")
            touch_diff = d
            break
    except (VncFail, EOFError, OSError, socket.timeout) as e:
        log(f"  点击轮断连({type(e).__name__}), 重试"); time.sleep(1)

# 串口尾部: LVGL indev 处理日志(固件已开调试输出, 任何触摸都会出现)
ser_mark = False
try:
    ser.settimeout(2)
    tail = b""
    while True: tail += ser.recv(4096)
except Exception: pass
stail = tail.decode("utf-8", "ignore")
print("SERIAL_TAIL:", stail[-400:])
if "indev" in stail or "press" in stail.lower():
    ser_mark = True

if touch_diff > 0:
    log(f">>> 触摸 PASS (按下态帧差 {touch_diff})")
elif ser_mark:
    log(">>> 触摸 PASS (串口 indev 日志命中)")
else:
    log("触摸 INCONCLUSIVE: 帧差与串口日志均未命中")
    sys.exit(7)
log(">>> E2E 全部 PASS")

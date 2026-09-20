package com.vela.simulator.vnc

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简 VNC (RFB 3.8) 客户端 —— 仅实现本模拟器需要的路径：
 *  - 握手 / 认证（None）
 *  - 设置像素格式 ARGB8888（小端 BG32）
 *  - 请求 Raw 编码整帧增量更新
 *  - 输入事件上行: PointerEvent(触摸/点击) + KeyEvent(按键)
 *  - 输出到 Bitmap 供 Compose 显示
 *
 * NuttX/openvela 的带帧缓冲镜像（如 lvgl demo + virtio-gpu）可通过该视图观察画面；
 * 无显示设备的镜像（纯 nsh）会显示空帧，属正常现象。
 */
class RfbClient {

    private var socket: Socket? = null
    private var din: DataInputStream? = null
    private var dout: DataOutputStream? = null
    private val running = AtomicBoolean(false)

    /** 写锁: 帧循环请求与输入事件共用一条输出流，必须串行化 */
    private val writeLock = Any()

    var framebuffer: Bitmap? = null
        private set

    @Volatile
    var frameReady: Boolean = false
        private set

    /** 帧版本号：每收到一次完整帧缓冲更新 +1，UI 层以此感知重绘（同一 Bitmap 原地写像素不会触发重组） */
    val frameVersion = AtomicLong(0)

    /** 上一帧是否含非黑像素（无显示设备的镜像发来的帧全黑，UI 用此自动回退控制台画面） */
    @Volatile
    var frameHasContent: Boolean = false
        private set

    private var width = 0
    private var height = 0

    /** 连接并完成初始化；成功返回 true。onFrame 可选回调（UI 线程外） */
    fun connect(port: Int, expectW: Int, expectH: Int): Boolean {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        s.tcpNoDelay = true
        socket = s
        din = DataInputStream(s.getInputStream().buffered())
        dout = DataOutputStream(s.getOutputStream().buffered())
        handshake()
        // 尺寸不一致时以服务器为准
        if (width != expectW || height != expectH) { /* 使用服务器尺寸 */ }
        running.set(true)
        return true
    }

    private fun handshake() {
        val di = din ?: error("no input")
        val doo = dout ?: error("no output")
        // ProtocolVersion
        val ver = ByteArray(12)
        di.readFully(ver)
        doo.write("RFB 003.008\n".toByteArray()); doo.flush()
        // SecTypes (u8 count + types)
        val n = di.read()
        if (n > 0) {
            val types = ByteArray(n); di.readFully(types)
            doo.write(1) // None
        } else {
            doo.write(1) // legacy
        }
        doo.flush()
        // SecurityResult
        val result = di.readInt()
        check(result == 0) { "VNC 认证失败 result=$result" }
        // ClientInit
        doo.write(1) // share
        doo.flush()
        // ServerInit
        val w = di.readUnsignedShort()
        val h = di.readUnsignedShort()
        width = w; height = h
        di.skipBytes(16) // pixelformat
        val nameLen = di.readInt()
        val name = ByteArray(nameLen); di.readFully(name)
        framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 设置像素格式 + 请求整帧更新，循环读取帧缓冲直到连接关闭。
     *
     * v0.2.5 修复（画面全黑第二根因）：
     *  - SetPixelFormat 16 字节布局此前整体错位（bits-per-pixel 位置写成了 0，
     *    QEMU 无法按 32bpp 编码），现按 RFB 规范填入：bpp/depth=32、
     *    little-endian、true-color、max=255×3、shift=16/8/0（对应内存序 B,G,R,X，
     *    与下方解码循环一致）；
     *  - FramebufferUpdate 矩形头此前按 5 个 u16 错序读取，实为 x,y,w,h,enc，
     *    且此前丢弃 numRects 只处理第一个矩形，现按 numRects 逐个解码。
     *
     * 必须在 [connect] 成功后调用；阻塞至连接关闭，请在协程中调用。
     */
    suspend fun frameLoop(onFrame: ((Bitmap) -> Unit)? = null) {
        val di = din ?: error("not connected")
        val doo = dout ?: error("not connected")
        val w = width; val h = height
        check(w > 0 && h > 0) { "服务器尺寸非法: ${w}x${h}" }

        // SetPixelFormat (msg 0): 16 字节像素格式，多字节字段按协议大端序发送
        val pf = ByteArray(16)
        pf[0] = 32  // bits-per-pixel
        pf[1] = 32  // depth
        pf[2] = 0   // big-endian-flag = false（像素数据按小端读取）
        pf[3] = 1   // true-colour-flag = true
        putU16(pf, 4, 255)   // red-max
        putU16(pf, 6, 255)   // green-max
        putU16(pf, 8, 255)   // blue-max
        pf[10] = 16 // red-shift
        pf[11] = 8  // green-shift
        pf[12] = 0  // blue-shift
        doo.write(0)
        doo.write(pf)
        doo.flush()

        // SetEncodings: Raw only
        doo.write(2) // SetEncodings
        doo.write(0) // pad
        doo.writeShort(1)
        doo.writeInt(0) // encoding raw
        doo.flush()

        var bitmap = framebuffer ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { framebuffer = it }
        val pixels = IntArray(w * h)

        try {
            while (running.get()) {
                // FramebufferUpdateRequest: incremental=0 请求全量（低频轮询，足够演示）
                synchronized(writeLock) {
                    doo.write(3); doo.write(0)
                    doo.writeShort(0); doo.writeShort(0); doo.writeShort(w); doo.writeShort(h)
                    doo.flush()
                }

                val msgType = di.readUnsignedByte()
                when (msgType) {
                    0 -> { // FramebufferUpdate
                        di.readUnsignedByte() // pad
                        val numRects = di.readUnsignedShort()
                        var content = false
                        repeat(numRects.coerceAtMost(64)) {
                            // 矩形头: x(u16) y(u16) w(u16) h(u16) encoding(s32)
                            val rx = di.readUnsignedShort()
                            val ry = di.readUnsignedShort()
                            val rw = di.readUnsignedShort()
                            val rh = di.readUnsignedShort()
                            val enc = di.readInt()
                            if (enc != 0) {
                                // 只请求了 Raw，理论不应出现其它编码；防御性断开
                                throw java.io.IOException("非 Raw 编码 rect: enc=$enc")
                            }
                            if (rw == 0 || rh == 0) return@repeat
                            if (rx >= w || ry >= h) {
                                // 越界矩形：仍需吞掉数据保持流同步
                                val skip = ByteArray(rw * 4)
                                for (yy in 0 until rh) di.readFully(skip)
                                return@repeat
                            }
                            val bpp = 4
                            val lineBytes = rw * bpp
                            val raw = ByteArray(lineBytes)
                            val xEnd = minOf(rx + rw, w).coerceAtLeast(rx)
                            for (yy in 0 until rh) {
                                di.readFully(raw)
                                val destY = ry + yy
                                if (destY >= h) continue
                                // BG32(小端) -> ARGB int；同时检测是否有非黑像素
                                for (xx in rx until xEnd) {
                                    val b = raw[(xx - rx) * 4].toInt() and 0xFF
                                    val g = raw[(xx - rx) * 4 + 1].toInt() and 0xFF
                                    val r = raw[(xx - rx) * 4 + 2].toInt() and 0xFF
                                    pixels[destY * w + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                    if (!content && (r or g or b) > 8) content = true
                                }
                            }
                        }
                        if (numRects > 0) {
                            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                            frameHasContent = content
                            frameReady = true
                            frameVersion.incrementAndGet()
                            onFrame?.invoke(bitmap)
                        }
                    }
                    1 -> { // SetColourMapEntries
                        di.readUnsignedByte()
                        di.readUnsignedShort()
                        val n = di.readUnsignedShort()
                        repeat(n) { di.readUnsignedShort(); di.readUnsignedShort(); di.readUnsignedShort() }
                    }
                    2 -> { // Bell
                    }
                    3 -> { // ServerCutText
                        di.skipBytes(3)
                        val len = di.readInt()
                        if (len > 0 && len < 1 shl 20) {
                            var left = len
                            val buf = ByteArray(64 * 1024)
                            while (left > 0) {
                                val n = di.read(buf, 0, minOf(left, buf.size))
                                if (n < 0) throw java.io.IOException("流中断")
                                left -= n
                            }
                        }
                    }
                    else -> throw java.io.IOException("未知消息类型: $msgType")
                }
            }
        } catch (_: Exception) {
            // 连接关闭/对端断开：静默退出帧循环（调用方负责清理）
        } finally {
            running.set(false)
        }
    }

    private fun putU16(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v shr 8).toByte(); arr[off + 1] = v.toByte()
    }

    /**
     * 发送指针事件（RFB PointerEvent, msg-type=4）—— 触摸/点击的上行通道。
     * QEMU 侧由 virtio-tablet 等绝对指针设备接收为绝对坐标。
     * buttonMask: bit0=左键(按下触摸)。
     */
    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val d = dout ?: return
        synchronized(writeLock) {
            runCatching {
                d.write(4)
                d.write(buttonMask and 0xFF)
                d.writeShort(x.coerceIn(0, 65535))
                d.writeShort(y.coerceIn(0, 65535))
                d.flush()
            }
        }
    }

    /** 触摸按下/拖动/抬起 一行式调用: pressed=false 时发送 buttonMask=0 */
    fun sendTouch(x: Int, y: Int, pressed: Boolean) = sendPointer(x, y, if (pressed) 1 else 0)

    /**
     * 发送按键事件（RFB KeyEvent, msg-type=3），keysym 为 X11 keysym 编码。
     * down=false 表示松开。
     */
    fun sendKey(keysym: Int, down: Boolean) {
        val d = dout ?: return
        synchronized(writeLock) {
            runCatching {
                d.write(3)
                d.write(if (down) 1 else 0)
                d.writeShort(0)
                d.writeInt(keysym)
                d.flush()
            }
        }
    }

    companion object {
        // 常用 X11 keysym（供后续屏幕按键使用）
        const val KEY_ENTER = 0xFF0D
        const val KEY_BACKSPACE = 0xFF08
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_TAB = 0xFF09
        const val KEY_SPACE = 0x0020
        const val KEY_UP = 0xFF52
        const val KEY_DOWN = 0xFF54
        const val KEY_LEFT = 0xFF51
        const val KEY_RIGHT = 0xFF53
    }

    fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }
}
